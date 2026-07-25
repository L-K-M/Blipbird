package ch.lkmc.blipbird

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.blipbird.core.database.OpsDatabase
import ch.lkmc.blipbird.core.database.UserDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        UserDatabase::class.java,
    )

    @Test
    fun userMigrationPreservesFlightsAndAddsEmptyItineraryGraph() {
        helper.createDatabase(USER_DB, 1).apply {
            execSQL(
                """INSERT INTO tracked_flight
                    (id, designatorIata, designatorIcao, flightNumber, suffix, dateLocal, alias, createdAt, archived)
                    VALUES (1, 'LX', 'SWR', '2801', NULL, NULL, 'Outbound', 100, 0)
                """.trimIndent()
            )
            execSQL(
                """INSERT INTO tracked_flight
                    (id, designatorIata, designatorIcao, flightNumber, suffix, dateLocal, alias, createdAt, archived)
                    VALUES (2, 'NH', 'ANA', '204', NULL, '2026-09-18', NULL, 200, 1)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(USER_DB, 2, true, UserDatabase.MIGRATION_1_2)
        db.query("SELECT id, alias, archived, dateIntentSource FROM tracked_flight ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
            assertEquals("Outbound", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
            assertEquals("NEXT_OCCURRENCE", cursor.getString(3))
            assertTrue(cursor.moveToNext())
            assertEquals(2L, cursor.getLong(0))
            assertEquals(1, cursor.getInt(2))
            assertEquals("LEGACY_UNKNOWN", cursor.getString(3))
        }
        listOf("itinerary", "itinerary_leg", "itinerary_transition").forEach { table ->
            db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
        db.close()
    }

    @Test
    fun opsMigrationAddsDurableCleanupOutbox() {
        val opsHelper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            OpsDatabase::class.java,
        )
        opsHelper.createDatabase(OPS_DB, 2).close()

        val db = opsHelper.runMigrationsAndValidate(OPS_DB, 3, true, OpsDatabase.MIGRATION_2_3)
        db.execSQL(
            """INSERT INTO platform_cleanup_task
                (mutationId, targetKind, targetId, expectedTargetGeneration, desiredUserState, state, createdAt)
                VALUES ('m1', 'FLIGHT', 42, '100', 'ABSENT', 'READY', 200)
            """.trimIndent()
        )
        db.query("SELECT COUNT(*) FROM platform_cleanup_task").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        db.close()
    }

    private companion object {
        const val USER_DB = "migration-user-test"
        const val OPS_DB = "migration-ops-test"
    }
}
