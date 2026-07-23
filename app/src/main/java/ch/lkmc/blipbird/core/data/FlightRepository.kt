package ch.lkmc.blipbird.core.data

import ch.lkmc.blipbird.core.database.EmittedEventEntity
import ch.lkmc.blipbird.core.database.OpsDatabase
import ch.lkmc.blipbird.core.database.PositionFixEntity
import ch.lkmc.blipbird.core.database.StatusSnapshotEntity
import ch.lkmc.blipbird.core.database.StatusLookupAttemptEntity
import ch.lkmc.blipbird.core.database.TrackedFlightEntity
import ch.lkmc.blipbird.core.database.UserDatabase
import ch.lkmc.blipbird.core.model.AirportRef
import ch.lkmc.blipbird.core.model.Designator
import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.core.model.MovementTimes
import ch.lkmc.blipbird.core.model.PositionFix
import ch.lkmc.blipbird.core.model.StatusSnapshot
import ch.lkmc.blipbird.core.model.TrackRequest
import ch.lkmc.blipbird.core.database.ReferenceDao
import ch.lkmc.blipbird.domain.FlightDates
import ch.lkmc.blipbird.domain.InstanceSelector
import ch.lkmc.blipbird.domain.LookupBackoffPolicy
import ch.lkmc.blipbird.domain.LookupOutcome
import ch.lkmc.blipbird.domain.NotificationPlanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Posted by the repository after ledger dedup; implemented by the platform layer. */
interface NotificationSink {
    suspend fun post(flightId: Long, designator: String, event: NotificationPlanner.Event)
}

/**
 * Lets the data layer (re)start background refreshes without depending on
 * WorkManager directly; implemented by the platform layer. The worker cancels
 * itself when no active flights remain, so tracking a flight must re-arm it.
 */
interface BackgroundRefreshController {
    fun ensureScheduled()
}

@Singleton
class FlightRepository @Inject constructor(
    private val userDb: UserDatabase,
    private val opsDb: OpsDatabase,
    private val statusProviders: StatusProviderChain,
    private val positionProvider: PositionProvider,
    private val openSkyTracks: OpenSkyTrackProvider,
    private val quota: QuotaLedger,
    private val identity: IdentityResolver,
    private val referenceDao: ReferenceDao,
    private val notificationSink: NotificationSink,
    private val backgroundRefresh: BackgroundRefreshController,
) {
    private val trackedDao get() = userDb.trackedFlightDao()
    private val snapshotDao get() = opsDb.statusSnapshotDao()
    private val fixDao get() = opsDb.positionFixDao()
    private val emittedDao get() = opsDb.emittedEventDao()
    private val lookupAttemptDao get() = opsDb.statusLookupAttemptDao()

    companion object {
        /** Snapshots/fixes kept for landing + 3 days, then pruned. */
        val RETENTION: Duration = Duration.ofDays(3)
        /** Force-refresh debounce per flight. */
        val REFRESH_DEBOUNCE: Duration = Duration.ofSeconds(30)
        /** Minimum spacing between OpenSky trajectory backfills per flight. */
        val TRACK_BACKFILL_INTERVAL: Duration = Duration.ofMinutes(10)
        /** Notification-dedup ledger outlives the data prune (glm 1.10). */
        val EVENT_LEDGER_RETENTION: Duration = Duration.ofDays(30)
    }

    // ------------------------------------------------------------------ tracking

    suspend fun track(request: TrackRequest): Long {
        val d = identity.complete(request.designator)
        val id = trackedDao.insert(
            TrackedFlightEntity(
                designatorIata = d.airlineIata,
                designatorIcao = d.airlineIcao,
                flightNumber = d.number,
                suffix = d.suffix,
                dateLocal = request.date?.toString(),
                alias = request.alias,
                createdAt = Instant.now().toEpochMilli(),
            )
        )
        // The periodic worker cancels itself when the list empties; re-arm it.
        backgroundRefresh.ensureScheduled()
        return id
    }

    fun observeFlights(): Flow<List<TrackedFlightEntity>> = trackedDao.observeActive()
    fun observeFlight(id: Long): Flow<TrackedFlightEntity?> = trackedDao.observeById(id)
    suspend fun flight(id: Long): TrackedFlightEntity? = trackedDao.byId(id)
    suspend fun activeFlights(): List<TrackedFlightEntity> = trackedDao.activeList()

    suspend fun archive(id: Long) = trackedDao.archive(id)

    suspend fun setAlias(id: Long, alias: String?) = trackedDao.setAlias(id, alias?.trim()?.takeIf { it.isNotEmpty() })

    suspend fun unarchive(id: Long) {
        trackedDao.unarchive(id)
        // Insert paths that bypass track() must also re-arm the self-cancelling worker.
        backgroundRefresh.ensureScheduled()
    }

    /**
     * Re-inserts a deleted flight (undo) as a fresh row with a new id. Snapshot
     * history and reminder state were intentionally dropped by [delete]; the
     * caller refreshes (and reconciles reminders) to repopulate them.
     */
    suspend fun restore(flight: TrackedFlightEntity): Long {
        val id = trackedDao.insert(flight.copy(id = 0))
        backgroundRefresh.ensureScheduled()
        return id
    }

    suspend fun delete(id: Long) {
        trackedDao.delete(id)
        snapshotDao.deleteForFlight(id)
        fixDao.deleteForFlight(id)
        emittedDao.deleteForFlight(id)
        lookupAttemptDao.deleteForFlight(id)
        trackBackfillAt.remove(id)
    }

    // ------------------------------------------------------------------ status

    fun observeSnapshot(flightId: Long): Flow<StatusSnapshot?> =
        snapshotDao.observeLatest(flightId).map { it?.toModel() }

    suspend fun latestSnapshot(flightId: Long): StatusSnapshot? = snapshotDao.latest(flightId)?.toModel()

    suspend fun isStatusLookupEligible(flightId: Long, now: Instant): Boolean =
        lookupAttemptDao.byFlightId(flightId)?.nextEligibleAt?.let { now.toEpochMilli() >= it } ?: true

    /**
     * Fetch fresh status via the provider chain, persist, diff, notify.
     * Returns the new snapshot or null (no key / not found / quota / error).
     */
    suspend fun refreshStatus(flightId: Long, force: Boolean = false): StatusSnapshot? {
        val flight = trackedDao.byId(flightId) ?: return null
        val previous = snapshotDao.latest(flightId)
        if (!force && previous != null) {
            val age = Duration.between(Instant.ofEpochMilli(previous.fetchedAt), Instant.now())
            if (age < REFRESH_DEBOUNCE) return previous.toModel()
        }

        val designator = flight.designator()
        // The stored format is our own (YYYY-MM-DD), but a corrupted pin must
        // degrade to an undated lookup, not crash every refresh (DS4-B17).
        val date = flight.dateLocal?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        val attemptedAt = Instant.now()
        val lookups = mutableListOf(statusProviders.fetchCandidates(designator, date))
        var candidates = lookups.last().candidates

        if (date != null) {
            // Providers with UTC-window queries can return neighbouring instances;
            // keep only candidates whose scheduled departure falls on the requested
            // DEPARTURE-AIRPORT-LOCAL date (lenient when schedule/zone unknown).
            val filtered = candidates.filter { FlightDates.matchesDepartureLocalDate(it, date) }
            if (filtered.isNotEmpty()) candidates = filtered
        } else {
            // Dateless lookups may resolve "nearest" to tomorrow while today's
            // flight is mid-air; double-check today (departure-airport local), and
            // for overnight departures (still airborne past dep-local midnight)
            // also yesterday.
            val now = Instant.now()
            val provisional = InstanceSelector.select(candidates, now)
            InstanceSelector.secondLookupDate(provisional, now)?.let { today ->
                val todayLookup = statusProviders.fetchCandidates(designator, today)
                lookups += todayLookup
                candidates = candidates + todayLookup.candidates
                val stillSuspicious = InstanceSelector.secondLookupDate(
                    InstanceSelector.select(candidates, now), now,
                ) != null
                if (stillSuspicious) {
                    val yesterdayLookup = statusProviders.fetchCandidates(designator, today.minusDays(1))
                    lookups += yesterdayLookup
                    candidates = candidates + yesterdayLookup.candidates
                }
            }
        }

        val snapshot = InstanceSelector.select(candidates, Instant.now()) ?: run {
            recordLookupAttempt(
                flightId = flightId,
                outcome = lookups.failureOutcome(),
                requestedDate = date,
                attemptedAt = attemptedAt,
            )
            return null
        }

        // Pin the resolved instance's departure-local date onto the tracked flight so
        // later dateless refreshes can never drift to the next day's instance
        // (PLAN.md §5 step 3 — this was the "departs in 22 h but it already left" bug).
        // Only pin when the departure zone is actually known: a UTC-guessed date can
        // be off by one for departures near local midnight, and a wrong pin is
        // stickier than no pin.
        if (flight.dateLocal == null) {
            val schedDep = snapshot.depTimes.scheduled
            val zone = FlightDates.zoneOf(snapshot.departure?.tz)
                ?: snapshot.departure?.let { dep ->
                    val row = dep.icao?.let { referenceDao.airportByIcao(it) }
                        ?: dep.iata?.let { referenceDao.airportByIata(it) }
                    FlightDates.zoneOf(row?.tz)
                }
            if (schedDep != null && zone != null) {
                trackedDao.pinDate(flightId, schedDep.atZone(zone).toLocalDate().toString())
            }
        }

        val entity = snapshot.toEntity(flightId)
        snapshotDao.insert(entity)
        recordLookupAttempt(flightId, LookupOutcome.SUCCESS, date, attemptedAt)

        // Diff against previous and emit through the persisted dedup ledger.
        val events = NotificationPlanner.diff(previous?.toModel(), snapshot)
        for (event in events) {
            val inserted = emittedDao.insertIgnoring(
                EmittedEventEntity(
                    trackedFlightId = flightId,
                    eventType = event.type.name,
                    fingerprint = event.fingerprint,
                    emittedAt = Instant.now().toEpochMilli(),
                    // Decoupled from the 3-day snapshot prune (glm 1.10, owner
                    // decision): pruning the dedup ledger with the data made
                    // terminal transitions re-fire on the next refresh.
                    expiresAt = Instant.now().plus(EVENT_LEDGER_RETENTION).toEpochMilli(),
                )
            )
            if (inserted != -1L) {
                notificationSink.post(flightId, flight.displayDesignator(), event)
            }
        }
        return snapshot
    }

    // ------------------------------------------------------------------ position

    fun observeLatestFix(flightId: Long): Flow<PositionFix?> =
        fixDao.observeLatest(flightId).map { it?.toModel() }

    fun observeTrack(flightId: Long): Flow<List<PositionFix>> =
        fixDao.observeTrack(flightId).map { list -> list.map { it.toModel() } }

    /** Persists the first validated fix from the current aircraft identity. */
    suspend fun pollPosition(flightId: Long): PositionFix? {
        val flight = trackedDao.byId(flightId) ?: return null
        val snapshot = snapshotDao.latest(flightId)

        val queries = positionQueries(
            currentHex = snapshot?.icao24,
            currentRegistration = snapshot?.registration,
            cachedHex = fixDao.latest(flightId)?.icao24,
            callsignGuess = flight.designator().callsignGuess,
        )

        for (q in queries) {
            val fix = positionProvider.fetch(q) ?: continue
            fixDao.insert(fix.toEntity(flightId, expiryFor(snapshot?.toModel())))
            return fix
        }
        return null
    }

    // In-memory per-flight throttle for the OpenSky trajectory backfill; callers
    // may invoke it from tight poll loops and rely on this to keep it polite.
    private val trackBackfillAt = java.util.concurrent.ConcurrentHashMap<Long, Instant>()

    /**
     * Replaces this flight's OpenSky-sourced trail with the trajectory OpenSky
     * currently has (optional feature — no-op without a configured API client).
     * Returns the number of stored waypoints; 0 covers unconfigured, throttled,
     * not-yet-flown, and failed cases alike.
     */
    suspend fun backfillTrack(flightId: Long, force: Boolean = false): Int {
        if (!openSkyTracks.isConfigured()) return 0
        val snapshot = snapshotDao.latest(flightId)?.toModel()
        val hex = (snapshot?.icao24 ?: fixDao.latest(flightId)?.icao24)
            ?.trim()?.lowercase(java.util.Locale.ROOT)
            ?.takeIf { it.matches(Regex("[0-9a-f]{6}")) } ?: return 0

        val now = Instant.now()
        val queryTime = openSkyQueryTime(snapshot, now) ?: return 0
        val last = trackBackfillAt[flightId]
        if (!force && last != null && Duration.between(last, now) < TRACK_BACKFILL_INTERVAL) return 0
        trackBackfillAt[flightId] = now

        val fixes = openSkyTracks.fetchTrack(hex, queryTime, now) ?: return 0
        val window = openSkyAcceptWindow(snapshot, now)
        val accepted = fixes.filter { it.at in window }
        if (accepted.isEmpty()) return 0
        fixDao.replaceBySource(
            flightId,
            OpenSkyTrackProvider.SOURCE,
            accepted.map { it.toEntity(flightId, expiryFor(snapshot)) },
        )
        return accepted.size
    }

    // ------------------------------------------------------------------ retention

    suspend fun prune() {
        val now = Instant.now().toEpochMilli()
        snapshotDao.pruneExpired(now)
        fixDao.pruneExpired(now)
        emittedDao.pruneExpired(now)
    }

    private fun expiryFor(snapshot: StatusSnapshot?): Long {
        val anchor = snapshot?.arrTimes?.best ?: Instant.now()
        return anchor.plus(RETENTION).toEpochMilli()
    }

    private suspend fun recordLookupAttempt(
        flightId: Long,
        outcome: LookupOutcome,
        requestedDate: java.time.LocalDate?,
        attemptedAt: Instant,
    ) {
        val previous = lookupAttemptDao.byFlightId(flightId)
        val previousOutcome = previous?.outcome?.let { value ->
            runCatching { LookupOutcome.valueOf(value) }.getOrNull()
        }
        val failures = LookupBackoffPolicy.consecutiveFailures(
            outcome, previousOutcome, previous?.consecutiveFailures ?: 0,
        )
        lookupAttemptDao.upsert(
            StatusLookupAttemptEntity(
                trackedFlightId = flightId,
                attemptedAt = attemptedAt.toEpochMilli(),
                outcome = outcome.name,
                consecutiveFailures = failures,
                nextEligibleAt = LookupBackoffPolicy.nextEligibleAt(
                    outcome, failures, requestedDate, attemptedAt,
                ).toEpochMilli(),
            )
        )
    }

    // ------------------------------------------------------------------ mapping

    fun TrackedFlightEntity.designator(): Designator =
        Designator(designatorIata, designatorIcao, flightNumber, suffix)

    fun TrackedFlightEntity.displayDesignator(): String =
        alias ?: designator().display

    private fun StatusSnapshot.toEntity(flightId: Long): StatusSnapshotEntity = StatusSnapshotEntity(
        trackedFlightId = flightId,
        provider = provider,
        fetchedAt = fetchedAt.toEpochMilli(),
        status = status.name,
        depIcao = departure?.icao, depIata = departure?.iata,
        arrIcao = arrival?.icao, arrIata = arrival?.iata,
        schedDep = depTimes.scheduled?.toEpochMilli(),
        estDep = depTimes.estimated?.toEpochMilli(),
        actDep = depTimes.actual?.toEpochMilli(),
        runwayEstDep = depTimes.runwayEstimated?.toEpochMilli(),
        runwayActDep = depTimes.runwayActual?.toEpochMilli(),
        schedArr = arrTimes.scheduled?.toEpochMilli(),
        estArr = arrTimes.estimated?.toEpochMilli(),
        actArr = arrTimes.actual?.toEpochMilli(),
        runwayEstArr = arrTimes.runwayEstimated?.toEpochMilli(),
        runwayActArr = arrTimes.runwayActual?.toEpochMilli(),
        depTerminal = depTerminal, depGate = depGate, depCheckInDesk = depCheckInDesk,
        arrTerminal = arrTerminal, arrGate = arrGate, baggageBelt = baggageBelt,
        aircraftModel = aircraftModel, registration = registration, icao24 = icao24,
        operatingDesignator = operatingDesignator, codeshareOf = codeshareOf,
        greatCircleKm = greatCircleKm,
        expiresAt = expiryFor(this),
    )

    private fun StatusSnapshotEntity.toModel(): StatusSnapshot = StatusSnapshot(
        provider = provider,
        fetchedAt = Instant.ofEpochMilli(fetchedAt),
        status = runCatching { FlightStatus.valueOf(status) }.getOrDefault(FlightStatus.UNKNOWN),
        departure = if (depIcao != null || depIata != null) AirportRef(depIcao, depIata) else null,
        arrival = if (arrIcao != null || arrIata != null) AirportRef(arrIcao, arrIata) else null,
        depTimes = MovementTimes(
            scheduled = schedDep?.let(Instant::ofEpochMilli),
            estimated = estDep?.let(Instant::ofEpochMilli),
            actual = actDep?.let(Instant::ofEpochMilli),
            runwayEstimated = runwayEstDep?.let(Instant::ofEpochMilli),
            runwayActual = runwayActDep?.let(Instant::ofEpochMilli),
        ),
        arrTimes = MovementTimes(
            scheduled = schedArr?.let(Instant::ofEpochMilli),
            estimated = estArr?.let(Instant::ofEpochMilli),
            actual = actArr?.let(Instant::ofEpochMilli),
            runwayEstimated = runwayEstArr?.let(Instant::ofEpochMilli),
            runwayActual = runwayActArr?.let(Instant::ofEpochMilli),
        ),
        depTerminal = depTerminal, depGate = depGate, depCheckInDesk = depCheckInDesk,
        arrTerminal = arrTerminal, arrGate = arrGate, baggageBelt = baggageBelt,
        aircraftModel = aircraftModel, registration = registration, icao24 = icao24,
        operatingDesignator = operatingDesignator, codeshareOf = codeshareOf,
        greatCircleKm = greatCircleKm,
    )

    private fun PositionFix.toEntity(flightId: Long, expiresAt: Long): PositionFixEntity = PositionFixEntity(
        trackedFlightId = flightId,
        at = at.toEpochMilli(),
        lat = lat, lon = lon,
        baroAltitudeFt = baroAltitudeFt,
        onGround = onGround,
        groundSpeedKt = groundSpeedKt,
        trackDeg = trackDeg,
        verticalRateFpm = verticalRateFpm,
        seenPosAgeSec = seenPosAgeSec,
        icao24 = icao24,
        source = source,
        expiresAt = expiresAt,
    )

    private fun PositionFixEntity.toModel(): PositionFix = PositionFix(
        at = Instant.ofEpochMilli(at),
        lat = lat, lon = lon,
        baroAltitudeFt = baroAltitudeFt,
        onGround = onGround,
        groundSpeedKt = groundSpeedKt,
        trackDeg = trackDeg,
        verticalRateFpm = verticalRateFpm,
        seenPosAgeSec = seenPosAgeSec,
        icao24 = icao24,
        callsign = null,
        registration = null,
        source = source,
    )
}

internal fun positionQueries(
    currentHex: String?,
    currentRegistration: String?,
    cachedHex: String?,
    callsignGuess: String?,
): List<PositionProvider.Query> = buildList {
    currentHex?.let { add(PositionProvider.Query.Hex(it)) }
    currentRegistration?.let { add(PositionProvider.Query.Registration(it)) }
    // A new status registration means a cached hex may identify the previous aircraft.
    if (currentRegistration == null) cachedHex?.let { add(PositionProvider.Query.Hex(it)) }
    callsignGuess?.let { add(PositionProvider.Query.Callsign(it)) }
}.distinct()

/**
 * Ordered status-provider failover with quota gating: AeroDataBox first (bigger
 * free window), AeroAPI second. Never routes around a rejected key. Returns ALL
 * candidate instances; the caller selects via [ch.lkmc.blipbird.domain.InstanceSelector].
 */
@Singleton
class StatusProviderChain @Inject constructor(
    private val aeroDataBox: AeroDataBoxProvider,
    private val aeroApi: AeroApiProvider,
    private val quota: QuotaLedger,
) {
    data class Lookup(val candidates: List<StatusSnapshot>, val outcome: LookupOutcome)

    suspend fun fetchCandidates(designator: Designator, date: java.time.LocalDate?): Lookup {
        var sawNotFound = false
        var sawTransientError = false
        for (provider in listOf<FlightStatusProvider>(aeroDataBox, aeroApi)) {
            if (!quota.canSpend(provider.name, provider.unitsPerLookup)) continue
            when (val result = provider.fetch(designator, date)) {
                is StatusResult.Found -> {
                    quota.record(provider.name, provider.unitsPerLookup)
                    return Lookup(result.flights, LookupOutcome.SUCCESS)
                }
                is StatusResult.NotFound -> {
                    quota.record(provider.name, provider.unitsPerLookup)
                    sawNotFound = true
                }
                is StatusResult.NoKey -> continue
                is StatusResult.Error -> if (result.retryable) sawTransientError = true
            }
        }
        val outcome = when {
            sawNotFound -> LookupOutcome.NOT_FOUND
            sawTransientError -> LookupOutcome.TRANSIENT_ERROR
            else -> LookupOutcome.NONRETRYABLE_ERROR
        }
        return Lookup(emptyList(), outcome)
    }
}

private fun List<StatusProviderChain.Lookup>.failureOutcome(): LookupOutcome = when {
    any { it.outcome == LookupOutcome.NOT_FOUND || it.outcome == LookupOutcome.SUCCESS } -> LookupOutcome.NOT_FOUND
    any { it.outcome == LookupOutcome.TRANSIENT_ERROR } -> LookupOutcome.TRANSIENT_ERROR
    else -> LookupOutcome.NONRETRYABLE_ERROR
}
