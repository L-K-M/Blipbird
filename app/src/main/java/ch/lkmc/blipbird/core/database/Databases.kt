package ch.lkmc.blipbird.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
        AirportEntity::class,
        AirlineEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class OpsDatabase : RoomDatabase() {
    abstract fun statusSnapshotDao(): StatusSnapshotDao
    abstract fun positionFixDao(): PositionFixDao
    abstract fun emittedEventDao(): EmittedEventDao
    abstract fun quotaLedgerDao(): QuotaLedgerDao
    abstract fun referenceDao(): ReferenceDao

    companion object {
        const val NAME = "blipbird-ops.db"
        fun build(context: Context): OpsDatabase =
            Room.databaseBuilder(context, OpsDatabase::class.java, NAME)
                .fallbackToDestructiveMigration(dropAllTables = true) // ops data is rebuildable by design
                .build()
    }
}
