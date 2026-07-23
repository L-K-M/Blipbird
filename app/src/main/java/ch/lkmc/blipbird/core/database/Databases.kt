package ch.lkmc.blipbird.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** User-authored intent; file included in Auto Backup (see data_extraction_rules.xml). */
@Database(entities = [TrackedFlightEntity::class], version = 1, exportSchema = true)
abstract class UserDatabase : RoomDatabase() {
    abstract fun trackedFlightDao(): TrackedFlightDao

    companion object {
        const val NAME = "blipbird-user.db"
        fun build(context: Context): UserDatabase =
            Room.databaseBuilder(context, UserDatabase::class.java, NAME).build()
    }
}

/** Provider-derived + reference data; excluded from backup, fully rebuildable. */
@Database(
    entities = [
        StatusSnapshotEntity::class,
        PositionFixEntity::class,
        EmittedEventEntity::class,
        QuotaLedgerEntity::class,
        StatusLookupAttemptEntity::class,
        AirportEntity::class,
        AirlineEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class OpsDatabase : RoomDatabase() {
    abstract fun statusSnapshotDao(): StatusSnapshotDao
    abstract fun positionFixDao(): PositionFixDao
    abstract fun emittedEventDao(): EmittedEventDao
    abstract fun quotaLedgerDao(): QuotaLedgerDao
    abstract fun statusLookupAttemptDao(): StatusLookupAttemptDao
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

        fun build(context: Context): OpsDatabase =
            Room.databaseBuilder(context, OpsDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
