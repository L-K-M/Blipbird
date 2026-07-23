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
import androidx.room.withTransaction
import ch.lkmc.blipbird.core.database.ReferenceDao
import ch.lkmc.blipbird.domain.FlightDates
import ch.lkmc.blipbird.domain.GreatCircle
import ch.lkmc.blipbird.domain.InstanceSelector
import ch.lkmc.blipbird.domain.LookupBackoffPolicy
import ch.lkmc.blipbird.domain.LookupOutcome
import ch.lkmc.blipbird.domain.NotificationPlanner
import ch.lkmc.blipbird.domain.RouteCorridor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Posted by the repository after ledger dedup; implemented by the platform layer. */
interface NotificationSink {
    suspend fun post(flightId: Long, designator: String, event: NotificationPlanner.Event)

    /**
     * Reconcile the ongoing in-flight notification (F6) with [snapshot]:
     * post/update while airborne, cancel otherwise. Null always cancels —
     * the delete/archive paths pass null so a stale ongoing card can't
     * outlive its flight. The snapshot is passed in (rather than looked up
     * by the sink) to keep the platform layer free of repository deps.
     */
    suspend fun syncOngoing(flightId: Long, designator: String, snapshot: StatusSnapshot?)
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

    suspend fun archive(id: Long) {
        trackedDao.archive(id)
        // An archived flight must not keep a live progress card up (F6).
        syncOngoingQuietly(id, "", null)
    }

    suspend fun setAlias(id: Long, alias: String?) = trackedDao.setAlias(id, alias?.trim()?.takeIf { it.isNotEmpty() })

    suspend fun unarchive(id: Long) {
        trackedDao.unarchive(id)
        // Insert paths that bypass track() must also re-arm the self-cancelling worker.
        backgroundRefresh.ensureScheduled()
        // Restore the ongoing card right away if the flight is mid-air (F6).
        trackedDao.byId(id)?.let { flight ->
            syncOngoingQuietly(id, flight.displayDesignator(), snapshotDao.latest(id)?.toModel())
        }
    }

    /**
     * Ongoing-card reconciliation is best-effort: it must never fail the
     * primary operation that triggered it (the DB write already committed),
     * and the card self-corrects on the next worker pass anyway.
     */
    private suspend fun syncOngoingQuietly(flightId: Long, designator: String, snapshot: StatusSnapshot?) {
        try {
            notificationSink.syncOngoing(flightId, designator, snapshot)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
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
        // Ops rows go first and atomically (glm 1.11): a crash between the two
        // databases then leaves the flight tracked with no derived data (a
        // benign re-fetch) instead of permanently orphaning ops rows — the
        // lookup-attempt table has no expiry-based prune to catch strays.
        opsDb.withTransaction {
            snapshotDao.deleteForFlight(id)
            fixDao.deleteForFlight(id)
            emittedDao.deleteForFlight(id)
            lookupAttemptDao.deleteForFlight(id)
        }
        trackedDao.delete(id)
        trackBackfillAt.remove(id)
        // A deleted flight must not keep a live progress card up (F6).
        syncOngoingQuietly(id, "", null)
    }

    // ------------------------------------------------------------------ status

    fun observeSnapshot(flightId: Long): Flow<StatusSnapshot?> =
        snapshotDao.observeLatest(flightId).map { it?.toModel() }

    suspend fun latestSnapshot(flightId: Long): StatusSnapshot? = snapshotDao.latest(flightId)?.toModel()

    suspend fun isStatusLookupEligible(flightId: Long, now: Instant): Boolean =
        lookupAttemptDao.byFlightId(flightId)?.nextEligibleAt?.let { now.toEpochMilli() >= it } ?: true

    /** Latest persisted lookup attempt, surfaced for error observability (G5). */
    data class LookupAttempt(
        val attemptedAt: Instant,
        val outcome: LookupOutcome?,
        val nextEligibleAt: Instant,
    )

    fun observeLookupAttempt(flightId: Long): Flow<LookupAttempt?> =
        lookupAttemptDao.observeByFlightId(flightId).map { entity ->
            entity?.let {
                LookupAttempt(
                    attemptedAt = Instant.ofEpochMilli(it.attemptedAt),
                    outcome = runCatching { LookupOutcome.valueOf(it.outcome) }.getOrNull(),
                    nextEligibleAt = Instant.ofEpochMilli(it.nextEligibleAt),
                )
            }
        }

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
                retryAfter = lookups.maxRetryAfter(),
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

        // Every fresh snapshot re-reconciles the ongoing in-flight card (F6):
        // ProgressStyle progress is a posted value, so it only advances when we
        // repost on legitimate data updates (PLAN.md §13).
        syncOngoingQuietly(flightId, flight.displayDesignator(), snapshot)

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

        // Hex/registration come from the selected status instance and are
        // trusted; a CALLSIGN can genuinely identify a different day's flight
        // (glm 1.16 / PLAN.md §5 step 5), so callsign-derived fixes must also
        // sit inside the route corridor before they're accepted.
        for (q in queries) {
            val fix = positionProvider.fetch(q) ?: continue
            if (q is PositionProvider.Query.Callsign) {
                val endpoints = corridorEndpoints(snapshot)
                if (endpoints != null &&
                    !RouteCorridor.isPlausible(endpoints.first, endpoints.second, GreatCircle.Point(fix.lat, fix.lon))
                ) continue
            }
            fixDao.insert(fix.toEntity(flightId, expiryFor(snapshot?.toModel())))
            return fix
        }
        return null
    }

    /**
     * Route endpoints for the corridor check, from the bundled reference DB
     * (status payloads don't persist coordinates). Null — no check possible —
     * when either airport or its coordinates are unknown.
     */
    private suspend fun corridorEndpoints(
        snapshot: StatusSnapshotEntity?,
    ): Pair<GreatCircle.Point, GreatCircle.Point>? {
        if (snapshot == null) return null
        val dep = airportPoint(snapshot.depIcao, snapshot.depIata) ?: return null
        val arr = airportPoint(snapshot.arrIcao, snapshot.arrIata) ?: return null
        return dep to arr
    }

    private suspend fun airportPoint(icao: String?, iata: String?): GreatCircle.Point? {
        val row = icao?.let { referenceDao.airportByIcao(it) }
            ?: iata?.let { referenceDao.airportByIata(it) }
            ?: return null
        val lat = row.lat ?: return null
        val lon = row.lon ?: return null
        return GreatCircle.Point(lat, lon)
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
        retryAfter: Duration? = null,
    ) {
        val previous = lookupAttemptDao.byFlightId(flightId)
        val previousOutcome = previous?.outcome?.let { value ->
            runCatching { LookupOutcome.valueOf(value) }.getOrNull()
        }
        val failures = LookupBackoffPolicy.consecutiveFailures(
            outcome, previousOutcome, previous?.consecutiveFailures ?: 0,
        )
        val policyEligibleAt = LookupBackoffPolicy.nextEligibleAt(
            outcome, failures, requestedDate, attemptedAt,
        )
        // A provider's explicit Retry-After extends — never shortens — the
        // policy backoff (glm-A: 429 used to just fall through the chain).
        val eligibleAt = retryAfter
            ?.let { maxOf(policyEligibleAt, attemptedAt.plus(it)) }
            ?: policyEligibleAt
        lookupAttemptDao.upsert(
            StatusLookupAttemptEntity(
                trackedFlightId = flightId,
                attemptedAt = attemptedAt.toEpochMilli(),
                outcome = outcome.name,
                consecutiveFailures = failures,
                nextEligibleAt = eligibleAt.toEpochMilli(),
            )
        )
    }

    // ------------------------------------------------------------------ mapping

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

fun TrackedFlightEntity.designator(): Designator =
    Designator(designatorIata, designatorIcao, flightNumber, suffix)

/**
 * Alias if set, else the designator — the one title used everywhere a flight
 * is named (list rows, notifications, reminders). Top-level so platform-side
 * callers can't drift into hand-rolling a slightly different string.
 */
fun TrackedFlightEntity.displayDesignator(): String =
    alias ?: designator().display

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
    data class Lookup(
        val candidates: List<StatusSnapshot>,
        val outcome: LookupOutcome,
        /** Longest provider-requested Retry-After seen during this lookup. */
        val retryAfter: Duration? = null,
    )

    suspend fun fetchCandidates(designator: Designator, date: java.time.LocalDate?): Lookup {
        val failures = mutableSetOf<LookupOutcome>()
        var retryAfter: Duration? = null
        for (provider in listOf<FlightStatusProvider>(aeroDataBox, aeroApi)) {
            // Reserve the unit atomically before the request so two concurrent
            // lookups can't both slip past the soft stop (B18); refund it below
            // when the outcome wasn't a billable lookup (no key / error). This
            // assumes the current providers (AeroDataBox via RapidAPI, AeroAPI)
            // only count successful calls, so errors and 429s aren't billed — if
            // a provider ever bills for failed requests, drop the refund on Error
            // and keep it only for NoKey.
            if (!quota.trySpend(provider.name, provider.unitsPerLookup)) {
                failures += LookupOutcome.QUOTA_EXHAUSTED
                continue
            }
            when (val result = provider.fetch(designator, date)) {
                is StatusResult.Found -> {
                    return Lookup(result.flights, LookupOutcome.SUCCESS)
                }
                is StatusResult.NotFound -> {
                    // A real lookup happened — keep the reserved unit.
                    failures += LookupOutcome.NOT_FOUND
                }
                is StatusResult.NoKey -> {
                    quota.refund(provider.name, provider.unitsPerLookup)
                    failures += LookupOutcome.NO_KEY
                }
                is StatusResult.Error -> {
                    quota.refund(provider.name, provider.unitsPerLookup)
                    failures += when {
                        result.rateLimited -> LookupOutcome.RATE_LIMITED
                        result.retryable -> LookupOutcome.TRANSIENT_ERROR
                        else -> LookupOutcome.NONRETRYABLE_ERROR
                    }
                    result.retryAfter?.let { ra -> retryAfter = maxOf(retryAfter ?: Duration.ZERO, ra) }
                }
            }
        }
        return Lookup(emptyList(), LookupOutcome.worstFailure(failures), retryAfter)
    }
}

private fun List<StatusProviderChain.Lookup>.failureOutcome(): LookupOutcome = when {
    // A lookup that returned candidates none of which survived instance
    // selection is a NOT_FOUND for the user, same as a definitive miss.
    any { it.outcome == LookupOutcome.NOT_FOUND || it.outcome == LookupOutcome.SUCCESS } -> LookupOutcome.NOT_FOUND
    else -> LookupOutcome.worstFailure(map { it.outcome })
}

private fun List<StatusProviderChain.Lookup>.maxRetryAfter(): Duration? =
    mapNotNull { it.retryAfter }.maxOrNull()
