package ch.lkmc.blipbird.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** User-authored intent; file included in Auto Backup (see data_extraction_rules.xml). */
@Database(
    entities = [
        TrackedFlightEntity::class,
        ItineraryEntity::class,
        ItineraryLegEntity::class,
        ItineraryTransitionEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun trackedFlightDao(): TrackedFlightDao
    abstract fun itineraryDao(): ItineraryDao

    companion object {
        const val NAME = "blipbird-user.db"
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `tracked_flight` ADD COLUMN `dateIntentSource` " +
                        "TEXT NOT NULL DEFAULT 'NEXT_OCCURRENCE'"
                )
                db.execSQL(
                    "UPDATE `tracked_flight` SET `dateIntentSource` = 'LEGACY_UNKNOWN' " +
                        "WHERE `dateLocal` IS NOT NULL"
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `itinerary` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `creationRequestId` TEXT NOT NULL,
                        `name` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_itinerary_creationRequestId` " +
                        "ON `itinerary` (`creationRequestId`)"
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `itinerary_leg` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `itineraryId` INTEGER NOT NULL,
                        `trackedFlightId` INTEGER NOT NULL,
                        `ordinal` INTEGER NOT NULL,
                        FOREIGN KEY(`itineraryId`) REFERENCES `itinerary`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`trackedFlightId`) REFERENCES `tracked_flight`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )""".trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_itinerary_leg_itineraryId_ordinal` " +
                        "ON `itinerary_leg` (`itineraryId`, `ordinal`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_itinerary_leg_itineraryId_id` " +
                        "ON `itinerary_leg` (`itineraryId`, `id`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_itinerary_leg_trackedFlightId` " +
                        "ON `itinerary_leg` (`trackedFlightId`)"
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `itinerary_transition` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `itineraryId` INTEGER NOT NULL,
                        `inboundLegId` INTEGER NOT NULL,
                        `outboundLegId` INTEGER NOT NULL,
                        `intent` TEXT NOT NULL,
                        `bookingArrangement` TEXT NOT NULL,
                        `baggagePlan` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`itineraryId`, `inboundLegId`) REFERENCES `itinerary_leg`(`itineraryId`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`itineraryId`, `outboundLegId`) REFERENCES `itinerary_leg`(`itineraryId`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_itinerary_transition_itineraryId_inboundLegId` " +
                        "ON `itinerary_transition` (`itineraryId`, `inboundLegId`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_itinerary_transition_itineraryId_outboundLegId` " +
                        "ON `itinerary_transition` (`itineraryId`, `outboundLegId`)"
                )
            }
        }

        fun build(context: Context): UserDatabase =
            Room.databaseBuilder(context, UserDatabase::class.java, NAME)
                // The include-only backup rules name the main DB file. TRUNCATE
                // keeps committed user intent there instead of a WAL sidecar.
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

/**
 * Provider-derived + reference data; excluded from backup. Rebuildable by design
 * with two exceptions: `quota_ledger` holds real per-cycle spend accounting and
 * `platform_cleanup_task` carries lifecycle work across process death. Neither
 * can be reconstructed safely, so schema changes must migrate this DB and never
 * fall back to a destructive recreate.
 */
@Database(
    entities = [
        StatusSnapshotEntity::class,
        PositionFixEntity::class,
        EmittedEventEntity::class,
        QuotaLedgerEntity::class,
        StatusLookupAttemptEntity::class,
        PlatformCleanupTaskEntity::class,
        AirportEntity::class,
        AirlineEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class OpsDatabase : RoomDatabase() {
    abstract fun statusSnapshotDao(): StatusSnapshotDao
    abstract fun positionFixDao(): PositionFixDao
    abstract fun emittedEventDao(): EmittedEventDao
    abstract fun quotaLedgerDao(): QuotaLedgerDao
    abstract fun statusLookupAttemptDao(): StatusLookupAttemptDao
    abstract fun platformCleanupTaskDao(): PlatformCleanupTaskDao
    abstract fun referenceDao(): ReferenceDao

    companion object {
        const val NAME = "blipbird-ops.db"
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `status_lookup_attempt` (
                        `trackedFlightId` INTEGER NOT NULL,
                        `attemptedAt` INTEGER NOT NULL,
                        `outcome` TEXT NOT NULL,
                        `consecutiveFailures` INTEGER NOT NULL,
                        `nextEligibleAt` INTEGER NOT NULL,
                        PRIMARY KEY(`trackedFlightId`)
                    )""".trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `platform_cleanup_task` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `mutationId` TEXT NOT NULL,
                        `targetKind` TEXT NOT NULL,
                        `targetId` INTEGER NOT NULL,
                        `expectedTargetGeneration` TEXT NOT NULL,
                        `desiredUserState` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_platform_cleanup_task_mutationId_targetKind_targetId` " +
                        "ON `platform_cleanup_task` (`mutationId`, `targetKind`, `targetId`)"
                )
            }
        }

        fun build(context: Context): OpsDatabase =
            Room.databaseBuilder(context, OpsDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
