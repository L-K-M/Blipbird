package ch.lkmc.blipbird.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackedFlightDao {
    @Insert
    suspend fun insert(flight: TrackedFlightEntity): Long

    @Query("SELECT * FROM tracked_flight WHERE archived = 0 ORDER BY createdAt DESC")
    fun observeActive(): Flow<List<TrackedFlightEntity>>

    @Query("SELECT * FROM tracked_flight WHERE archived = 0")
    suspend fun activeList(): List<TrackedFlightEntity>

    @Query("SELECT * FROM tracked_flight WHERE archived = 1 ORDER BY createdAt DESC")
    fun observeArchived(): Flow<List<TrackedFlightEntity>>

    @Query("SELECT * FROM tracked_flight WHERE id = :id")
    suspend fun byId(id: Long): TrackedFlightEntity?

    @Query("SELECT * FROM tracked_flight WHERE id = :id")
    fun observeById(id: Long): Flow<TrackedFlightEntity?>

    @Query("UPDATE tracked_flight SET archived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    @Query("UPDATE tracked_flight SET archived = 0 WHERE id = :id")
    suspend fun unarchive(id: Long)

    @Query("DELETE FROM tracked_flight WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE tracked_flight SET alias = :alias WHERE id = :id")
    suspend fun setAlias(id: Long, alias: String?)

    @Query("UPDATE tracked_flight SET dateLocal = :date WHERE id = :id")
    suspend fun pinDate(id: Long, date: String)

}

@Dao
interface StatusSnapshotDao {
    @Insert
    suspend fun insert(snapshot: StatusSnapshotEntity): Long

    @Query("SELECT * FROM status_snapshot WHERE trackedFlightId = :flightId ORDER BY fetchedAt DESC LIMIT 1")
    suspend fun latest(flightId: Long): StatusSnapshotEntity?

    @Query("SELECT * FROM status_snapshot WHERE trackedFlightId = :flightId ORDER BY fetchedAt DESC LIMIT 1")
    fun observeLatest(flightId: Long): Flow<StatusSnapshotEntity?>

    @Query("DELETE FROM status_snapshot WHERE expiresAt < :now")
    suspend fun pruneExpired(now: Long)

    @Query("DELETE FROM status_snapshot WHERE trackedFlightId = :flightId")
    suspend fun deleteForFlight(flightId: Long)
}

@Dao
interface PositionFixDao {
    @Insert
    suspend fun insert(fix: PositionFixEntity)

    @Insert
    suspend fun insertAll(fixes: List<PositionFixEntity>)

    @Query("DELETE FROM position_fix WHERE trackedFlightId = :flightId AND source = :source")
    suspend fun deleteBySource(flightId: Long, source: String)

    /** Atomic swap of one source's trail — a backfill always replaces wholesale. */
    @Transaction
    suspend fun replaceBySource(flightId: Long, source: String, fixes: List<PositionFixEntity>) {
        deleteBySource(flightId, source)
        insertAll(fixes)
    }

    @Query("SELECT * FROM position_fix WHERE trackedFlightId = :flightId ORDER BY at DESC LIMIT 1")
    suspend fun latest(flightId: Long): PositionFixEntity?

    @Query("SELECT * FROM position_fix WHERE trackedFlightId = :flightId ORDER BY at DESC LIMIT 1")
    fun observeLatest(flightId: Long): Flow<PositionFixEntity?>

    @Query("SELECT * FROM position_fix WHERE trackedFlightId = :flightId ORDER BY at ASC")
    suspend fun track(flightId: Long): List<PositionFixEntity>

    @Query("SELECT * FROM position_fix WHERE trackedFlightId = :flightId ORDER BY at ASC")
    fun observeTrack(flightId: Long): Flow<List<PositionFixEntity>>

    @Query("DELETE FROM position_fix WHERE expiresAt < :now")
    suspend fun pruneExpired(now: Long)

    @Query("DELETE FROM position_fix WHERE trackedFlightId = :flightId")
    suspend fun deleteForFlight(flightId: Long)
}

@Dao
interface EmittedEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(event: EmittedEventEntity): Long   // -1 when already emitted

    @Query("DELETE FROM emitted_event WHERE expiresAt < :now")
    suspend fun pruneExpired(now: Long)

    @Query("DELETE FROM emitted_event WHERE trackedFlightId = :flightId")
    suspend fun deleteForFlight(flightId: Long)
}

@Dao
interface QuotaLedgerDao {
    @Query("SELECT unitsUsed FROM quota_ledger WHERE provider = :provider AND periodKey = :periodKey")
    suspend fun used(provider: String, periodKey: String): Long?

    @Query(
        "INSERT INTO quota_ledger (provider, periodKey, unitsUsed) VALUES (:provider, :periodKey, :units) " +
            "ON CONFLICT(provider, periodKey) DO UPDATE SET unitsUsed = unitsUsed + :units"
    )
    suspend fun add(provider: String, periodKey: String, units: Long)

    @Query("SELECT * FROM quota_ledger")
    fun observeAll(): Flow<List<QuotaLedgerEntity>>
}

@Dao
interface StatusLookupAttemptDao {
    @Query("SELECT * FROM status_lookup_attempt WHERE trackedFlightId = :flightId")
    suspend fun byFlightId(flightId: Long): StatusLookupAttemptEntity?

    @Query("SELECT * FROM status_lookup_attempt WHERE trackedFlightId = :flightId")
    fun observeByFlightId(flightId: Long): Flow<StatusLookupAttemptEntity?>

    @Upsert
    suspend fun upsert(attempt: StatusLookupAttemptEntity)

    @Query("DELETE FROM status_lookup_attempt WHERE trackedFlightId = :flightId")
    suspend fun deleteForFlight(flightId: Long)
}

@Dao
interface ReferenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAirports(airports: List<AirportEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAirlines(airlines: List<AirlineEntity>)

    @Query("SELECT COUNT(*) FROM airport")
    suspend fun airportCount(): Int

    @Query("SELECT * FROM airport WHERE iata = :iata LIMIT 1")
    suspend fun airportByIata(iata: String): AirportEntity?

    @Query("SELECT * FROM airport WHERE icao = :icao LIMIT 1")
    suspend fun airportByIcao(icao: String): AirportEntity?

    @Query("SELECT * FROM airline WHERE iata = :iata")
    suspend fun airlinesByIata(iata: String): List<AirlineEntity>

    @Query("SELECT * FROM airline WHERE icao = :icao")
    suspend fun airlinesByIcao(icao: String): List<AirlineEntity>

    @Query("DELETE FROM airport")
    suspend fun clearAirports()

    @Query("DELETE FROM airline")
    suspend fun clearAirlines()
}
