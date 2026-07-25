package ch.lkmc.blipbird.core.database

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

// ---------------------------------------------------------------------------
// USER DB (blipbird-user.db) — user-authored intent only; included in backup.
// ---------------------------------------------------------------------------

@Entity(tableName = "tracked_flight")
data class TrackedFlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val designatorIata: String?,
    val designatorIcao: String?,
    val flightNumber: String,
    val suffix: String?,
    /** Departure-airport local date, ISO yyyy-MM-dd; null = next occurrence. */
    val dateLocal: String?,
    /** Whether [dateLocal] is portable user intent or an operational legacy pin. */
    @ColumnInfo(defaultValue = "NEXT_OCCURRENCE")
    val dateIntentSource: String = "NEXT_OCCURRENCE",
    val alias: String?,
    val createdAt: Long,
    val archived: Boolean = false,
)

// ---------------------------------------------------------------------------
// OPS DB (blipbird-ops.db) — provider-derived + reference data; excluded from
// backup. Most rows are rebuildable and expire; quota accounting and durable
// platform-cleanup tasks are intentionally non-rebuildable operational state.
// ---------------------------------------------------------------------------

@Entity(
    tableName = "status_snapshot",
    indices = [Index("trackedFlightId", "fetchedAt")],
)
data class StatusSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackedFlightId: Long,
    val provider: String,
    val fetchedAt: Long,
    val status: String,
    val depIcao: String?, val depIata: String?,
    val arrIcao: String?, val arrIata: String?,
    val schedDep: Long?, val estDep: Long?, val actDep: Long?,
    val runwayEstDep: Long?, val runwayActDep: Long?,
    val schedArr: Long?, val estArr: Long?, val actArr: Long?,
    val runwayEstArr: Long?, val runwayActArr: Long?,
    val depTerminal: String?, val depGate: String?, val depCheckInDesk: String?,
    val arrTerminal: String?, val arrGate: String?, val baggageBelt: String?,
    val aircraftModel: String?, val registration: String?, val icao24: String?,
    val operatingDesignator: String?, val codeshareOf: String?,
    val greatCircleKm: Double?,
    val expiresAt: Long,
)

@Entity(
    tableName = "position_fix",
    indices = [Index("trackedFlightId", "at")],
)
data class PositionFixEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackedFlightId: Long,
    val at: Long,
    val lat: Double,
    val lon: Double,
    val baroAltitudeFt: Double?,
    val onGround: Boolean,
    val groundSpeedKt: Double?,
    val trackDeg: Double?,
    val verticalRateFpm: Double?,
    val seenPosAgeSec: Double,
    val icao24: String?,
    val source: String,
    val expiresAt: Long,
)

@Entity(
    tableName = "emitted_event",
    indices = [Index(value = ["trackedFlightId", "fingerprint"], unique = true)],
)
data class EmittedEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackedFlightId: Long,
    val eventType: String,
    val fingerprint: String,
    val emittedAt: Long,
    val expiresAt: Long,
)

@Entity(tableName = "quota_ledger", primaryKeys = ["provider", "periodKey"])
data class QuotaLedgerEntity(
    val provider: String,
    /** Provider billing period, e.g. "2026-07" or a subscription-anchored key. */
    val periodKey: String,
    val unitsUsed: Long,
)

@Entity(tableName = "status_lookup_attempt")
data class StatusLookupAttemptEntity(
    @PrimaryKey val trackedFlightId: Long,
    val attemptedAt: Long,
    val outcome: String,
    val consecutiveFailures: Int,
    val nextEligibleAt: Long,
)

/** Crash-safe tombstone for cross-database/platform lifecycle cleanup. */
@Entity(
    tableName = "platform_cleanup_task",
    indices = [Index(value = ["mutationId", "targetKind", "targetId"], unique = true)],
)
data class PlatformCleanupTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mutationId: String,
    val targetKind: String,
    val targetId: Long,
    val expectedTargetGeneration: String,
    val desiredUserState: String,
    val state: String,
    val createdAt: Long,
)

// Reference tables (imported from bundled assets; rebuildable, never backed up).

@Entity(
    tableName = "airport",
    indices = [Index(value = ["iata"]), Index(value = ["icao"])],
)
data class AirportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val icao: String?,
    val iata: String?,
    val name: String,
    val city: String?,
    val country: String?,
    val lat: Double?,
    val lon: Double?,
    val tz: String?,
)

@Entity(
    tableName = "airline",
    indices = [Index(value = ["iata"]), Index(value = ["icao"])],
)
data class AirlineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val icao: String?,
    val iata: String?,
    val name: String,
)
