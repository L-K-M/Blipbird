package ch.lkmc.blipbird.domain

import ch.lkmc.blipbird.core.model.AirportRef
import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.core.model.MovementTimes
import ch.lkmc.blipbird.core.model.StatusSnapshot
import ch.lkmc.blipbird.core.model.TimeCertainty
import ch.lkmc.blipbird.core.model.TransitionIntent
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/**
 * What happens *between* two legs of an itinerary, as arithmetic
 * (`docs/ITINERARY_PROPOSAL.md` §8). Pure JVM, no wording: it returns typed
 * facts and the UI turns them into localized copy.
 *
 * Three rules shape everything here:
 *
 * - **Gate milestones only** (§8.2). The connection window is outbound gate
 *   departure minus inbound gate arrival. Runway `ON`/`OFF` values live in
 *   [MovementTimes.bestRunway] and are never substituted in — a mixed
 *   gate-to-runway duration would read like a real number and be wrong by a
 *   taxi.
 * - **Two windows, not a score** (§8.3/§8.4). [Assessment.latestScheduledWindow]
 *   and [Assessment.latestCalculatedWindow] are shown together, with their
 *   difference; no feasibility threshold is invented.
 * - **Occurrence before guidance** (§8.8). Every derived value is gated on both
 *   legs being bound to the occurrence the user confirmed — the snapshot's
 *   scheduled departure must fall on the leg's confirmed departure-airport-local
 *   date. An unbound leg yields [Disruption.PENDING_ROUTE_CONFIRMATION] and no
 *   window, no location facts, no suggestion.
 *
 * Deviation from the proposal worth knowing: §8.8 describes persisting the
 * confirmed operational airport pair in Ops storage so a *later* mismatch under
 * the same bindings can be told apart from a never-confirmed one. Blipbird has
 * no such table yet, so continuity is re-derived from the current snapshots on
 * every evaluation. The user-visible consequence is that a pair that stops
 * matching reports [Disruption.AIRPORTS_DO_NOT_MEET] as a present-tense fact
 * rather than as a change from a stored fingerprint; nothing here claims a
 * window it cannot currently derive either way.
 */
object TransitionEngine {

    /**
     * Gates are typically assigned 24–48 h out (§8.1). A missing gate further
     * out than this is "not yet assigned", not "not reported" — the absence of a
     * gate must never be dressed up as a problem.
     */
    val GATE_ASSIGNMENT_HORIZON: Duration = Duration.ofHours(24)

    /**
     * Prompt-only ceiling for suggesting `DIRECT_CONNECTION` on an unset edge
     * (§8.8). Never persisted, never a validity rule — the user confirms intent.
     */
    val CONNECTION_SUGGESTION_MAX_GAP: Duration = Duration.ofHours(24)

    /** Retrieval age under which a fetch counts as recent when no cadence applies. */
    val RECENT_FETCH_FALLBACK: Duration = Duration.ofHours(12)

    /** Retrieval age past which a fetch is only "last known" when no cadence applies. */
    val LAST_KNOWN_FALLBACK: Duration = Duration.ofHours(24)

    /** Floor for the cadence-derived "last known" limit, so 15 min cadences don't age out in an hour. */
    val LAST_KNOWN_FLOOR: Duration = Duration.ofHours(6)

    /**
     * Providers whose normalized `depTimes`/`arrTimes` are documented gate
     * milestones with a stated certainty, and are therefore allowed to carry a
     * connection window (§8.2).
     *
     * AeroAPI qualifies: its adapter maps `scheduled_out`/`estimated_out`/
     * `actual_out` and the matching `_in` fields, which are gate values by
     * definition. AeroDataBox does not, and the proposal's table says so in as
     * many words: `revisedTime` may be a gate *or* a runway time unless a
     * distinct `runwayTime` disambiguates it, `predictedTime` is experimental,
     * neither documents actual-versus-estimated, and the adapter additionally
     * files `runwayTime` as a runway *actual*. A duration built from one
     * provider's gate time and another's possibly-runway time reads like a real
     * number and can be wrong by a whole taxi — the proposal's explicit call is
     * to show no window rather than that. Correcting the adapter's family and
     * certainty mapping (with revised-, predicted- and runway-only fixtures) is
     * what flips this set, not a change here.
     */
    private val GATE_MILESTONE_PROVIDERS = setOf("aeroapi")

    /** True when [provider]'s times may be used as gate `OUT`/`IN` (§8.2). */
    fun carriesGateMilestones(provider: String?): Boolean =
        provider != null && provider in GATE_MILESTONE_PROVIDERS

    /** How the two flights' operational airports relate (§8.7). */
    enum class Continuity { SAME_AIRPORT, DIFFERENT_AIRPORTS, UNKNOWN }

    /**
     * How recently the endpoint's *snapshot* was retrieved (§8.10) — device
     * retrieval time, never a provider field-update time. The UI says
     * "Fetched … ago" for exactly that reason.
     */
    enum class RetrievalFreshness { RECENTLY_FETCHED, STALE_FETCH, LAST_KNOWN, UNKNOWN }

    data class EndpointRetrieval(
        val age: Duration?,
        val freshness: RetrievalFreshness,
    )

    /**
     * Ordered to match §8.9's precedence list, with
     * [PENDING_ROUTE_CONFIRMATION] hoisted to the front: without a bound
     * occurrence there is no trustworthy fact to rank at all.
     */
    enum class Disruption {
        PENDING_ROUTE_CONFIRMATION,
        OUTBOUND_CANCELLED,
        INBOUND_CANCELLED,
        DIVERTED,
        OUTBOUND_DEPARTED_WITHOUT_INBOUND_IN,
        OUTBOUND_BEFORE_INBOUND_IN,
        AIRPORTS_DO_NOT_MEET,
        DEPARTURE_PASSED_UNCONFIRMED,

        /**
         * The resolved snapshots come from a source whose normalized times are
         * not documented gate milestones, so no window is calculated from them
         * (§8.2). Not a fault of this itinerary — see [GATE_MILESTONE_PROVIDERS].
         */
        GATE_TIMES_UNSUPPORTED,
        STALE_OR_MISSING_TIMES,
        INVALID_OVERLAP,
    }

    /**
     * Disruptions after which reported terminal/gate facts stop being guidance
     * and start being noise — an unconfirmed occurrence, a cancelled flight, a
     * diversion, or two flights that are not at the same airport.
     */
    private val SUPPRESSES_LOCATION_FACTS = setOf(
        Disruption.PENDING_ROUTE_CONFIRMATION,
        Disruption.OUTBOUND_CANCELLED,
        Disruption.INBOUND_CANCELLED,
        Disruption.DIVERTED,
        Disruption.AIRPORTS_DO_NOT_MEET,
    )

    /** One end of the connection window, with the certainty it was taken at. */
    data class GateEndpoint(
        val instant: Instant,
        /** [TimeCertainty.ACTUAL], [TimeCertainty.ESTIMATED] or [TimeCertainty.SCHEDULED]. */
        val certainty: TimeCertainty,
        val provider: String,
        val fetchedAt: Instant,
    )

    /** Reported terminal/gate for one side of the transition; both may be null. */
    data class ReportedLocation(val terminal: String?, val gate: String?) {
        val known: Boolean get() = terminal != null || gate != null
    }

    /**
     * One leg as the engine sees it: the occurrence the user confirmed, plus the
     * latest snapshot and the reference-enriched airports the caller resolved for
     * it. [confirmedDepartureDate] is the leg's departure-airport-local date and
     * is null when the flight's date is not user-confirmed.
     */
    data class Leg(
        val snapshot: StatusSnapshot? = null,
        val confirmedDepartureDate: LocalDate? = null,
        val departure: AirportRef? = null,
        val arrival: AirportRef? = null,
    ) {
        /**
         * The snapshot only counts when it describes the occurrence the user
         * confirmed. [FlightDates.matchesDepartureLocalDate] is deliberately
         * lenient when the provider gave no scheduled departure or no zone —
         * that is a gap in the data, not evidence of the wrong day.
         */
        val occurrenceConfirmed: Boolean
            get() = snapshot != null && confirmedDepartureDate != null &&
                FlightDates.matchesDepartureLocalDate(snapshot, confirmedDepartureDate)

        val bound: StatusSnapshot? get() = snapshot?.takeIf { occurrenceConfirmed }
    }

    data class Input(
        val intent: TransitionIntent,
        val inbound: Leg,
        val outbound: Leg,
        val now: Instant,
    )

    data class Assessment(
        val intent: TransitionIntent,
        val continuity: Continuity,
        /** The airport both flights are reported at, when they meet at one. */
        val connectionAirport: AirportRef?,
        /** Where the inbound leg is reported to arrive, when its occurrence is bound. */
        val inboundArrivalAirport: AirportRef?,
        /** Where the outbound leg is reported to depart, when its occurrence is bound. */
        val outboundDepartureAirport: AirportRef?,
        /** Scheduled gate arrival → scheduled gate departure, from the latest snapshots (§8.3). */
        val latestScheduledWindow: Duration?,
        /** Actual/estimated/scheduled gate arrival → same chain for departure (§8.4). */
        val latestCalculatedWindow: Duration?,
        /** Calculated minus scheduled: "18 min shorter than latest scheduled" (§8.6). */
        val changeFromScheduled: Duration?,
        /** Time left before onward gate departure, once actual inbound gate arrival is known (§8.5). */
        val remainingUntilOutbound: Duration?,
        val inboundIn: GateEndpoint?,
        val outboundOut: GateEndpoint?,
        val inboundRetrieval: EndpointRetrieval,
        val outboundRetrieval: EndpointRetrieval,
        val disruption: Disruption?,
        /**
         * Gross gap between the legs' latest reported times — for a stay, a
         * surface transfer, or an edge the user hasn't classified yet. Travel
         * time between airports is explicitly **not** deducted (§9.4), and this
         * is not a connection window: it mixes whatever time families the
         * provider reports, which is why a direct connection never uses it.
         */
        val breakDuration: Duration?,
        val inboundLocation: ReportedLocation,
        val outboundLocation: ReportedLocation,
        /** True once actual inbound gate `IN` is known — only then is the traveller at the airport (§7.6). */
        val arrivedAtGate: Boolean,
        /** Onward gate simply isn't assigned yet, rather than unreported (§8.1). */
        val onwardGatePending: Boolean,
        /** Suggested intent for an unset edge, for the user to confirm (§8.8). */
        val suggestion: TransitionIntent?,
        val inboundOccurrenceConfirmed: Boolean,
        val outboundOccurrenceConfirmed: Boolean,
    ) {
        /** True when the transition has nothing derived to show beyond the user's own choice. */
        val hasTiming: Boolean
            get() = latestScheduledWindow != null || latestCalculatedWindow != null || breakDuration != null
    }

    fun evaluate(input: Input): Assessment {
        val now = input.now
        val inbound = input.inbound
        val outbound = input.outbound
        val inboundSnapshot = inbound.bound
        val outboundSnapshot = outbound.bound

        // Airports count only alongside a bound snapshot: a route resolved for
        // some other occurrence of the same flight number is not this leg's.
        val inboundArrival = if (inboundSnapshot != null) inbound.arrival else null
        val outboundDeparture = if (outboundSnapshot != null) outbound.departure else null
        val continuity = continuityOf(inboundArrival, outboundDeparture)

        val inboundIn = gateEndpoint(inboundSnapshot, inboundSnapshot?.arrTimes)
        val outboundOut = gateEndpoint(outboundSnapshot, outboundSnapshot?.depTimes)

        val latestScheduled = if (inboundIn == null || outboundOut == null) null else between(
            inboundSnapshot?.arrTimes?.scheduled,
            outboundSnapshot?.depTimes?.scheduled,
        )
        val latestCalculated = between(inboundIn?.instant, outboundOut?.instant)
        // A stay or a surface transfer measures a gross gap, not a gate-to-gate
        // window, so it reads the latest reported times of either family rather
        // than going dark on a provider without gate milestones.
        val grossGap = between(inboundSnapshot?.arrTimes?.best, outboundSnapshot?.depTimes?.best)
        val changeFromScheduled =
            if (latestScheduled != null && latestCalculated != null) latestCalculated.minus(latestScheduled)
            else null

        val actualIn = inboundSnapshot?.arrTimes?.actual
        val actualOut = outboundSnapshot?.depTimes?.actual
        val remaining = outboundOut?.instant
            ?.takeIf { actualIn != null && actualOut == null }
            ?.let { Duration.between(now, it) }

        val inboundRetrieval = retrievalState(inboundSnapshot, inboundIn?.certainty, now)
        val outboundRetrieval = retrievalState(outboundSnapshot, outboundOut?.certainty, now)

        val routeResolved = inboundArrival != null && outboundDeparture != null
        val disruption = when (input.intent) {
            TransitionIntent.DIRECT_CONNECTION -> connectionDisruption(
                inbound = inboundSnapshot,
                outbound = outboundSnapshot,
                routeResolved = routeResolved,
                continuity = continuity,
                inboundIn = inboundIn,
                outboundOut = outboundOut,
                latestCalculated = latestCalculated,
                inboundRetrieval = inboundRetrieval,
                outboundRetrieval = outboundRetrieval,
                now = now,
            )
            TransitionIntent.DESTINATION_STAY, TransitionIntent.SURFACE_TRANSFER -> breakDisruption(
                inbound = inboundSnapshot,
                outbound = outboundSnapshot,
                gap = grossGap,
            )
            // An unset edge asks the user a question; data states would only
            // crowd out the one thing that resolves it.
            TransitionIntent.UNKNOWN -> null
        }

        // Terminal/gate facts are guidance: they need a bound occurrence on both
        // sides, and they are suppressed by the states that make "go to gate B42"
        // actively misleading (§7.8/§7.9).
        val locationsAllowed = inboundSnapshot != null && outboundSnapshot != null &&
            input.intent != TransitionIntent.UNKNOWN &&
            disruption !in SUPPRESSES_LOCATION_FACTS

        return Assessment(
            intent = input.intent,
            continuity = continuity,
            connectionAirport = if (continuity == Continuity.SAME_AIRPORT) {
                richer(inboundArrival, outboundDeparture)
            } else {
                null
            },
            inboundArrivalAirport = inboundArrival,
            outboundDepartureAirport = outboundDeparture,
            latestScheduledWindow = latestScheduled.takeIf { input.intent == TransitionIntent.DIRECT_CONNECTION },
            latestCalculatedWindow = latestCalculated.takeIf { input.intent == TransitionIntent.DIRECT_CONNECTION },
            changeFromScheduled = changeFromScheduled.takeIf { input.intent == TransitionIntent.DIRECT_CONNECTION },
            remainingUntilOutbound = remaining.takeIf { input.intent == TransitionIntent.DIRECT_CONNECTION },
            inboundIn = inboundIn,
            outboundOut = outboundOut,
            inboundRetrieval = inboundRetrieval,
            outboundRetrieval = outboundRetrieval,
            disruption = disruption,
            breakDuration = grossGap.takeIf { input.intent != TransitionIntent.DIRECT_CONNECTION },
            inboundLocation = if (locationsAllowed) {
                ReportedLocation(inboundSnapshot?.arrTerminal, inboundSnapshot?.arrGate)
            } else {
                ReportedLocation(null, null)
            },
            outboundLocation = if (locationsAllowed) {
                ReportedLocation(outboundSnapshot?.depTerminal, outboundSnapshot?.depGate)
            } else {
                ReportedLocation(null, null)
            },
            arrivedAtGate = actualIn != null,
            onwardGatePending = locationsAllowed &&
                outboundSnapshot?.depGate == null &&
                outboundOut?.instant?.let { Duration.between(now, it) > GATE_ASSIGNMENT_HORIZON } == true,
            suggestion = suggestion(input.intent, continuity, grossGap),
            inboundOccurrenceConfirmed = inbound.occurrenceConfirmed,
            outboundOccurrenceConfirmed = outbound.occurrenceConfirmed,
        )
    }

    /**
     * Airport identity as sets of known codes (§8.7): equality on either the
     * IATA or the ICAO code, never on city, display name, or a reference-table
     * row id. Callers enrich the missing counterpart code before evaluating.
     */
    fun continuityOf(arrival: AirportRef?, departure: AirportRef?): Continuity {
        if (arrival == null || departure == null) return Continuity.UNKNOWN
        val arrivalCodes = codesOf(arrival)
        val departureCodes = codesOf(departure)
        if (arrivalCodes.isEmpty() || departureCodes.isEmpty()) return Continuity.UNKNOWN
        return if (arrivalCodes.any { it in departureCodes }) Continuity.SAME_AIRPORT
        else Continuity.DIFFERENT_AIRPORTS
    }

    /**
     * Retrieval recency for one endpoint (§8.10). The expected cadence comes from
     * [CadencePolicy], so a flight three days out isn't called stale for having a
     * six-hour-old snapshot it was never going to refresh. An **actual** gate
     * milestone is final observed rather than cadence-stale — no amount of
     * waiting improves it — so it only ages out at the absolute fallback.
     */
    fun retrievalState(
        snapshot: StatusSnapshot?,
        certainty: TimeCertainty?,
        now: Instant,
    ): EndpointRetrieval {
        if (snapshot == null) return EndpointRetrieval(null, RetrievalFreshness.UNKNOWN)
        val age = Duration.between(snapshot.fetchedAt, now).let {
            if (it.isNegative) Duration.ZERO else it
        }
        val cadence = CadencePolicy.nextInterval(
            status = FlightPhaseMachine.derive(snapshot, null, now).status,
            bestDep = snapshot.depTimes.best,
            bestArr = snapshot.arrTimes.best,
            arrivalResolved = snapshot.arrGate != null && snapshot.baggageBelt != null,
            now = now,
        )
        val recentLimit = cadence?.multipliedBy(2) ?: RECENT_FETCH_FALLBACK
        val lastKnownLimit = cadence
            ?.multipliedBy(4)
            ?.let { if (it < LAST_KNOWN_FLOOR) LAST_KNOWN_FLOOR else it }
            ?: LAST_KNOWN_FALLBACK
        val freshness = when {
            certainty == TimeCertainty.ACTUAL && age <= lastKnownLimit -> RetrievalFreshness.RECENTLY_FETCHED
            age <= recentLimit -> RetrievalFreshness.RECENTLY_FETCHED
            age <= lastKnownLimit -> RetrievalFreshness.STALE_FETCH
            else -> RetrievalFreshness.LAST_KNOWN
        }
        return EndpointRetrieval(age, freshness)
    }

    // ------------------------------------------------------------------ internals

    private fun connectionDisruption(
        inbound: StatusSnapshot?,
        outbound: StatusSnapshot?,
        routeResolved: Boolean,
        continuity: Continuity,
        inboundIn: GateEndpoint?,
        outboundOut: GateEndpoint?,
        latestCalculated: Duration?,
        inboundRetrieval: EndpointRetrieval,
        outboundRetrieval: EndpointRetrieval,
        now: Instant,
    ): Disruption? {
        if (inbound == null || outbound == null || !routeResolved) {
            return Disruption.PENDING_ROUTE_CONFIRMATION
        }
        if (outbound.status == FlightStatus.CANCELLED) return Disruption.OUTBOUND_CANCELLED
        if (inbound.status == FlightStatus.CANCELLED) return Disruption.INBOUND_CANCELLED
        if (inbound.status == FlightStatus.DIVERTED || outbound.status == FlightStatus.DIVERTED) {
            return Disruption.DIVERTED
        }
        val actualIn = inbound.arrTimes.actual
        val actualOut = outbound.depTimes.actual
        if (actualOut != null && actualIn == null) return Disruption.OUTBOUND_DEPARTED_WITHOUT_INBOUND_IN
        if (actualOut != null && actualIn != null && actualOut.isBefore(actualIn)) {
            return Disruption.OUTBOUND_BEFORE_INBOUND_IN
        }
        if (continuity != Continuity.SAME_AIRPORT) return Disruption.AIRPORTS_DO_NOT_MEET
        if (departurePassedUnconfirmed(outbound, now)) return Disruption.DEPARTURE_PASSED_UNCONFIRMED
        if (!carriesGateMilestones(inbound.provider) || !carriesGateMilestones(outbound.provider)) {
            return Disruption.GATE_TIMES_UNSUPPORTED
        }
        if (
            inboundIn == null || outboundOut == null ||
            inboundRetrieval.freshness == RetrievalFreshness.LAST_KNOWN ||
            outboundRetrieval.freshness == RetrievalFreshness.LAST_KNOWN
        ) {
            return Disruption.STALE_OR_MISSING_TIMES
        }
        if (latestCalculated != null && !latestCalculated.isAboveZero()) return Disruption.INVALID_OVERLAP
        return null
    }

    /**
     * A stay or surface transfer makes no connection claim, so only the states
     * that would make its gross gap meaningless apply.
     */
    private fun breakDisruption(
        inbound: StatusSnapshot?,
        outbound: StatusSnapshot?,
        gap: Duration?,
    ): Disruption? = when {
        inbound == null || outbound == null -> Disruption.PENDING_ROUTE_CONFIRMATION
        outbound.status == FlightStatus.CANCELLED -> Disruption.OUTBOUND_CANCELLED
        inbound.status == FlightStatus.CANCELLED -> Disruption.INBOUND_CANCELLED
        // A diversion moves the airport the break is spent at, so a stay or a
        // surface transfer needs the same flag a connection gets: without it the
        // gross gap reads as a normal one computed at the planned airport.
        inbound.status == FlightStatus.DIVERTED || outbound.status == FlightStatus.DIVERTED ->
            Disruption.DIVERTED
        gap == null -> Disruption.STALE_OR_MISSING_TIMES
        !gap.isAboveZero() -> Disruption.INVALID_OVERLAP
        else -> null
    }

    private fun departurePassedUnconfirmed(outbound: StatusSnapshot, now: Instant): Boolean {
        if (outbound.depTimes.actual != null || outbound.depTimes.runwayActual != null) return false
        val best = outbound.depTimes.best ?: return false
        if (!best.isBefore(now)) return false
        return when (outbound.status) {
            FlightStatus.DEPARTED, FlightStatus.EN_ROUTE, FlightStatus.APPROACHING,
            FlightStatus.LANDED, FlightStatus.ARRIVED, FlightStatus.CANCELLED, FlightStatus.DIVERTED,
            -> false
            else -> true
        }
    }

    /**
     * Prompt heuristic only (§8.8): same airport and a sane positive gap reads
     * like a connection, a long one like a stay, different airports like a
     * question only the user can answer.
     */
    private fun suggestion(
        intent: TransitionIntent,
        continuity: Continuity,
        gap: Duration?,
    ): TransitionIntent? {
        if (intent != TransitionIntent.UNKNOWN || gap == null) return null
        return when {
            continuity != Continuity.SAME_AIRPORT -> null
            !gap.isAboveZero() -> null
            gap <= CONNECTION_SUGGESTION_MAX_GAP -> TransitionIntent.DIRECT_CONNECTION
            else -> TransitionIntent.DESTINATION_STAY
        }
    }

    private fun gateEndpoint(snapshot: StatusSnapshot?, times: MovementTimes?): GateEndpoint? {
        if (snapshot == null || times == null) return null
        if (!carriesGateMilestones(snapshot.provider)) return null
        val actual = times.actual
        val estimated = times.estimated
        val scheduled = times.scheduled
        val (instant, certainty) = when {
            actual != null -> actual to TimeCertainty.ACTUAL
            estimated != null -> estimated to TimeCertainty.ESTIMATED
            scheduled != null -> scheduled to TimeCertainty.SCHEDULED
            else -> return null
        }
        return GateEndpoint(instant, certainty, snapshot.provider, snapshot.fetchedAt)
    }

    private fun between(from: Instant?, to: Instant?): Duration? =
        if (from == null || to == null) null else Duration.between(from, to)

    private fun codesOf(airport: AirportRef): Set<String> = setOfNotNull(
        airport.iata?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() },
        airport.icao?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() },
    )

    /** The ref carrying more resolved detail — the one worth showing as the connection airport. */
    private fun richer(first: AirportRef?, second: AirportRef?): AirportRef? {
        if (first == null) return second
        if (second == null) return first
        return if (detail(second) > detail(first)) second else first
    }

    private fun detail(airport: AirportRef): Int = listOfNotNull(
        airport.iata, airport.icao, airport.name, airport.city, airport.tz,
    ).size

    /**
     * Deliberately not named `isPositive`. `java.time.Duration.isPositive()`
     * arrived with JDK 18 and is present in recent `android.jar`s, so a member
     * of that name silently wins over an extension at compile time — and then
     * fails at runtime on every JVM (CI's Temurin 17 among them) and every
     * device below the API level that shipped it. The unit tests caught it as a
     * `NoSuchMethodError`; the name is what keeps it caught.
     */
    private fun Duration.isAboveZero(): Boolean = !isZero && !isNegative
}
