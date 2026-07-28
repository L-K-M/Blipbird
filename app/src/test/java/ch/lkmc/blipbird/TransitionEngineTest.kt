package ch.lkmc.blipbird

import ch.lkmc.blipbird.core.model.AirportRef
import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.core.model.MovementTimes
import ch.lkmc.blipbird.core.model.StatusSnapshot
import ch.lkmc.blipbird.core.model.TimeCertainty
import ch.lkmc.blipbird.core.model.TransitionIntent
import ch.lkmc.blipbird.domain.TransitionEngine
import ch.lkmc.blipbird.domain.TransitionEngine.Continuity
import ch.lkmc.blipbird.domain.TransitionEngine.Disruption
import ch.lkmc.blipbird.domain.TransitionEngine.RetrievalFreshness
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The connection arithmetic of `docs/ITINERARY_PROPOSAL.md` §8: gate milestones
 * only, two windows, and no derived output at all until both legs are bound to
 * the occurrence the user confirmed.
 */
class TransitionEngineTest {

    private val day = LocalDate.of(2026, 9, 18)
    private val now = Instant.parse("2026-09-18T05:00:00Z")
    private val fra = AirportRef(icao = "EDDF", iata = "FRA", tz = "Europe/Berlin")
    private val gva = AirportRef(icao = "LSGG", iata = "GVA", tz = "Europe/Zurich")
    private val hnd = AirportRef(icao = "RJTT", iata = "HND", tz = "Asia/Tokyo")

    // GVA 07:10 -> FRA 08:25, then FRA 11:10 -> HND: a 2 h 45 min scheduled window.
    private val inboundSnapshot = snapshot(
        departure = gva,
        arrival = fra,
        depTimes = MovementTimes(scheduled = Instant.parse("2026-09-18T05:10:00Z")),
        arrTimes = MovementTimes(scheduled = Instant.parse("2026-09-18T06:25:00Z")),
        arrTerminal = "1",
        arrGate = "A18",
    )
    private val outboundSnapshot = snapshot(
        departure = fra,
        arrival = hnd,
        depTimes = MovementTimes(scheduled = Instant.parse("2026-09-18T09:10:00Z")),
        arrTimes = MovementTimes(scheduled = Instant.parse("2026-09-18T21:35:00Z")),
        depTerminal = "1",
        depGate = "B42",
    )

    @Test
    fun boundLegsProduceBothWindowsAndTheirDifference() {
        val assessment = evaluate(
            inbound = inboundSnapshot.copy(
                arrTimes = inboundSnapshot.arrTimes.copy(
                    estimated = Instant.parse("2026-09-18T06:31:00Z"),
                ),
            ),
        )

        assertEquals(Continuity.SAME_AIRPORT, assessment.continuity)
        assertEquals("FRA", assessment.connectionAirport?.code)
        assertEquals(Duration.ofMinutes(165), assessment.latestScheduledWindow)
        assertEquals(Duration.ofMinutes(159), assessment.latestCalculatedWindow)
        assertEquals(Duration.ofMinutes(-6), assessment.changeFromScheduled)
        assertEquals(TimeCertainty.ESTIMATED, assessment.inboundIn?.certainty)
        assertEquals(TimeCertainty.SCHEDULED, assessment.outboundOut?.certainty)
        assertNull(assessment.disruption)
    }

    @Test
    fun anUnconfirmedOccurrenceProducesNoWindowAtAll() {
        val assessment = evaluate(inboundConfirmedDate = day.plusDays(1))

        assertEquals(Disruption.PENDING_ROUTE_CONFIRMATION, assessment.disruption)
        assertNull(assessment.latestScheduledWindow)
        assertNull(assessment.latestCalculatedWindow)
        assertNull(assessment.connectionAirport)
        assertFalse(assessment.inboundLocation.known)
        assertFalse(assessment.outboundLocation.known)
        assertFalse(assessment.inboundOccurrenceConfirmed)
        assertTrue(assessment.outboundOccurrenceConfirmed)
    }

    @Test
    fun aLegWithoutAnyConfirmedDateIsNeverBound() {
        val assessment = evaluate(outboundConfirmedDate = null)

        assertEquals(Disruption.PENDING_ROUTE_CONFIRMATION, assessment.disruption)
        assertFalse(assessment.outboundOccurrenceConfirmed)
    }

    @Test
    fun runwayTimesAreNeverSubstitutedForGateMilestones() {
        val assessment = evaluate(
            inbound = inboundSnapshot.copy(
                arrTimes = MovementTimes(runwayActual = Instant.parse("2026-09-18T06:19:00Z")),
            ),
        )

        assertNull(assessment.inboundIn)
        assertNull(assessment.latestCalculatedWindow)
        assertNull(assessment.latestScheduledWindow)
        assertEquals(Disruption.STALE_OR_MISSING_TIMES, assessment.disruption)
    }

    @Test
    fun timeRemainingAppearsOnlyAfterActualGateArrival() {
        val landed = inboundSnapshot.copy(
            status = FlightStatus.ARRIVED,
            arrTimes = inboundSnapshot.arrTimes.copy(actual = Instant.parse("2026-09-18T06:19:00Z")),
        )

        assertNull(evaluate().remainingUntilOutbound)

        val arrived = evaluate(inbound = landed, now = Instant.parse("2026-09-18T06:31:00Z"))
        assertTrue(arrived.arrivedAtGate)
        assertEquals(Duration.ofMinutes(159), arrived.remainingUntilOutbound)

        // Once the onward flight has actually left the gate there is nothing to count down to.
        val departed = evaluate(
            inbound = landed,
            outbound = outboundSnapshot.copy(
                status = FlightStatus.DEPARTED,
                depTimes = outboundSnapshot.depTimes.copy(actual = Instant.parse("2026-09-18T09:12:00Z")),
            ),
            now = Instant.parse("2026-09-18T09:20:00Z"),
        )
        assertNull(departed.remainingUntilOutbound)
    }

    @Test
    fun cancellationsAndDiversionsOutrankTiming() {
        val cancelledInbound = inboundSnapshot.copy(status = FlightStatus.CANCELLED)
        val cancelledOutbound = outboundSnapshot.copy(status = FlightStatus.CANCELLED)

        assertEquals(
            Disruption.INBOUND_CANCELLED,
            evaluate(inbound = cancelledInbound).disruption,
        )
        // Outbound cancellation is checked first (§8.9 precedence).
        assertEquals(
            Disruption.OUTBOUND_CANCELLED,
            evaluate(inbound = cancelledInbound, outbound = cancelledOutbound).disruption,
        )
        assertEquals(
            Disruption.DIVERTED,
            evaluate(inbound = inboundSnapshot.copy(status = FlightStatus.DIVERTED)).disruption,
        )
        // A cancelled flight's gate is not somewhere to send anyone.
        assertFalse(evaluate(inbound = cancelledInbound).inboundLocation.known)
    }

    @Test
    fun missingInboundGateArrivalIsNotHiddenByAnOnwardDeparture() {
        val assessment = evaluate(
            outbound = outboundSnapshot.copy(
                status = FlightStatus.EN_ROUTE,
                depTimes = outboundSnapshot.depTimes.copy(actual = Instant.parse("2026-09-18T09:12:00Z")),
            ),
            now = Instant.parse("2026-09-18T09:30:00Z"),
        )

        assertEquals(Disruption.OUTBOUND_DEPARTED_WITHOUT_INBOUND_IN, assessment.disruption)
    }

    @Test
    fun anOnwardDepartureBeforeTheInboundArrivalIsNeverACompletedConnection() {
        val assessment = evaluate(
            inbound = inboundSnapshot.copy(
                status = FlightStatus.ARRIVED,
                arrTimes = inboundSnapshot.arrTimes.copy(actual = Instant.parse("2026-09-18T09:40:00Z")),
            ),
            outbound = outboundSnapshot.copy(
                status = FlightStatus.EN_ROUTE,
                depTimes = outboundSnapshot.depTimes.copy(actual = Instant.parse("2026-09-18T09:12:00Z")),
            ),
            now = Instant.parse("2026-09-18T10:00:00Z"),
        )

        assertEquals(Disruption.OUTBOUND_BEFORE_INBOUND_IN, assessment.disruption)
    }

    @Test
    fun flightsAtDifferentAirportsGetNoConnectionGuidance() {
        val assessment = evaluate(
            outbound = outboundSnapshot.copy(departure = AirportRef(icao = "EDDM", iata = "MUC")),
        )

        assertEquals(Continuity.DIFFERENT_AIRPORTS, assessment.continuity)
        assertEquals(Disruption.AIRPORTS_DO_NOT_MEET, assessment.disruption)
        assertNull(assessment.connectionAirport)
        assertFalse(assessment.outboundLocation.known)
        // The raw arithmetic is still available for the details line.
        assertEquals(Duration.ofMinutes(165), assessment.latestScheduledWindow)
    }

    @Test
    fun airportIdentityMatchesOnEitherCodeSystem() {
        // One side known only by ICAO, the other only by IATA: no overlap to match on.
        assertEquals(
            Continuity.DIFFERENT_AIRPORTS,
            TransitionEngine.continuityOf(
                AirportRef(icao = "EDDF", iata = null),
                AirportRef(icao = null, iata = "FRA"),
            ),
        )
        // Which is exactly why the caller enriches the missing counterpart first.
        assertEquals(
            Continuity.SAME_AIRPORT,
            TransitionEngine.continuityOf(AirportRef(icao = "EDDF", iata = null), fra),
        )
        assertEquals(
            Continuity.SAME_AIRPORT,
            TransitionEngine.continuityOf(AirportRef(icao = "EDDF", iata = null), AirportRef(icao = "eddf", iata = null)),
        )
        assertEquals(
            Continuity.UNKNOWN,
            TransitionEngine.continuityOf(AirportRef(icao = null, iata = null), fra),
        )
    }

    @Test
    fun aDeparturePastItsTimeWithoutConfirmationIsFlagged() {
        val assessment = evaluate(now = Instant.parse("2026-09-18T09:40:00Z"))

        assertEquals(Disruption.DEPARTURE_PASSED_UNCONFIRMED, assessment.disruption)
    }

    @Test
    fun overlappingFlightsAreNotAConnection() {
        val assessment = evaluate(
            outbound = outboundSnapshot.copy(
                depTimes = MovementTimes(scheduled = Instant.parse("2026-09-18T06:00:00Z")),
            ),
        )

        assertEquals(Disruption.INVALID_OVERLAP, assessment.disruption)
        assertEquals(Duration.ofMinutes(-25), assessment.latestCalculatedWindow)
    }

    @Test
    fun anOldFetchDegradesToLastKnownButKeepsItsNumbers() {
        val stale = Instant.parse("2026-09-15T05:00:00Z")
        val assessment = evaluate(
            inbound = inboundSnapshot.copy(fetchedAt = stale),
            outbound = outboundSnapshot.copy(fetchedAt = stale),
        )

        assertEquals(RetrievalFreshness.LAST_KNOWN, assessment.inboundRetrieval.freshness)
        assertEquals(Duration.ofDays(3), assessment.inboundRetrieval.age)
        assertEquals(Disruption.STALE_OR_MISSING_TIMES, assessment.disruption)
        assertEquals(Duration.ofMinutes(165), assessment.latestScheduledWindow)
    }

    @Test
    fun anActualGateMilestoneIsFinalObservedRatherThanCadenceStale() {
        // Gate-critical cadence is 15 min, so a 90-minute-old fetch would be
        // "stale" for a scheduled time — but an actual milestone cannot improve.
        val at = Instant.parse("2026-09-18T06:19:00Z")
        val arrived = inboundSnapshot.copy(
            status = FlightStatus.ARRIVED,
            fetchedAt = Instant.parse("2026-09-18T05:00:00Z"),
            arrTimes = inboundSnapshot.arrTimes.copy(actual = at),
        )

        val state = TransitionEngine.retrievalState(
            snapshot = arrived,
            certainty = TimeCertainty.ACTUAL,
            now = Instant.parse("2026-09-18T06:30:00Z"),
        )

        assertEquals(RetrievalFreshness.RECENTLY_FETCHED, state.freshness)
        assertEquals(
            RetrievalFreshness.STALE_FETCH,
            TransitionEngine.retrievalState(
                snapshot = arrived,
                certainty = TimeCertainty.SCHEDULED,
                now = Instant.parse("2026-09-18T06:30:00Z"),
            ).freshness,
        )
    }

    /**
     * §8.2's provider table: AeroDataBox's normalized times can be gate or runway
     * values with undocumented certainty, so no window may be built from them —
     * and the state says exactly that instead of pretending the data is missing.
     */
    @Test
    fun aSourceWithoutGateMilestonesGetsNoWindow() {
        val assessment = evaluate(
            inbound = inboundSnapshot.copy(provider = "aerodatabox"),
            outbound = outboundSnapshot.copy(provider = "aerodatabox"),
        )

        assertEquals(Disruption.GATE_TIMES_UNSUPPORTED, assessment.disruption)
        assertNull(assessment.latestScheduledWindow)
        assertNull(assessment.latestCalculatedWindow)
        assertNull(assessment.inboundIn)
        assertNull(assessment.outboundOut)
        // The reported terminal and gate are strings, not times: still shown.
        assertTrue(assessment.inboundLocation.known)
        assertTrue(assessment.outboundLocation.known)
        assertFalse(TransitionEngine.carriesGateMilestones("aerodatabox"))
        assertFalse(TransitionEngine.carriesGateMilestones(null))
        assertTrue(TransitionEngine.carriesGateMilestones("aeroapi"))
    }

    /** A stopover's gross gap is not a gate-to-gate window, so it survives that. */
    @Test
    fun aStayStillReportsItsGapOnASourceWithoutGateMilestones() {
        val assessment = evaluate(
            intent = TransitionIntent.DESTINATION_STAY,
            inbound = inboundSnapshot.copy(provider = "aerodatabox"),
            outbound = outboundSnapshot.copy(provider = "aerodatabox"),
        )

        assertEquals(Duration.ofMinutes(165), assessment.breakDuration)
        assertNull(assessment.disruption)
    }

    @Test
    fun aStayReportsAGrossBreakAndNoConnectionWindow() {
        val assessment = evaluate(intent = TransitionIntent.DESTINATION_STAY)

        assertEquals(Duration.ofMinutes(165), assessment.breakDuration)
        assertNull(assessment.latestScheduledWindow)
        assertNull(assessment.latestCalculatedWindow)
        assertNull(assessment.remainingUntilOutbound)
        assertNull(assessment.disruption)
    }

    @Test
    fun aSurfaceTransferKeepsBothAirportsAndExcludesTravelTime() {
        val assessment = evaluate(
            intent = TransitionIntent.SURFACE_TRANSFER,
            outbound = outboundSnapshot.copy(departure = AirportRef(icao = "EDDM", iata = "MUC")),
        )

        assertEquals("FRA", assessment.inboundArrivalAirport?.code)
        assertEquals("MUC", assessment.outboundDepartureAirport?.code)
        assertEquals(Duration.ofMinutes(165), assessment.breakDuration)
        assertNull(assessment.disruption)
    }

    @Test
    fun anUnsetEdgeIsOnlyEverGivenASuggestion() {
        val short = evaluate(intent = TransitionIntent.UNKNOWN)
        assertEquals(TransitionIntent.DIRECT_CONNECTION, short.suggestion)
        assertNull(short.disruption)
        assertNull(short.latestCalculatedWindow)

        val long = evaluate(
            intent = TransitionIntent.UNKNOWN,
            outbound = outboundSnapshot.copy(
                depTimes = MovementTimes(scheduled = Instant.parse("2026-09-20T09:10:00Z")),
            ),
            outboundConfirmedDate = LocalDate.of(2026, 9, 20),
        )
        assertEquals(TransitionIntent.DESTINATION_STAY, long.suggestion)

        val elsewhere = evaluate(
            intent = TransitionIntent.UNKNOWN,
            outbound = outboundSnapshot.copy(departure = AirportRef(icao = "EDDM", iata = "MUC")),
        )
        assertNull(elsewhere.suggestion)

        // A confirmed edge is never second-guessed.
        assertNull(evaluate().suggestion)
    }

    @Test
    fun aGateFurtherOutThanTheAssignmentHorizonIsPendingNotMissing() {
        val far = evaluate(
            inbound = inboundSnapshot.copy(
                depTimes = MovementTimes(scheduled = Instant.parse("2026-09-20T05:10:00Z")),
                arrTimes = MovementTimes(scheduled = Instant.parse("2026-09-20T06:25:00Z")),
            ),
            outbound = outboundSnapshot.copy(
                depGate = null,
                depTimes = MovementTimes(scheduled = Instant.parse("2026-09-20T09:10:00Z")),
                arrTimes = MovementTimes(scheduled = Instant.parse("2026-09-20T21:35:00Z")),
            ),
            inboundConfirmedDate = LocalDate.of(2026, 9, 20),
            outboundConfirmedDate = LocalDate.of(2026, 9, 20),
        )

        assertTrue(far.onwardGatePending)
        assertEquals("1", far.outboundLocation.terminal)
        assertNull(far.outboundLocation.gate)

        // Same missing gate, but inside the horizon: it is simply not reported.
        assertFalse(evaluate(outbound = outboundSnapshot.copy(depGate = null)).onwardGatePending)
    }

    // ------------------------------------------------------------------ helpers

    private fun evaluate(
        intent: TransitionIntent = TransitionIntent.DIRECT_CONNECTION,
        inbound: StatusSnapshot? = inboundSnapshot,
        outbound: StatusSnapshot? = outboundSnapshot,
        inboundConfirmedDate: LocalDate? = day,
        outboundConfirmedDate: LocalDate? = day,
        now: Instant = this.now,
    ): TransitionEngine.Assessment = TransitionEngine.evaluate(
        TransitionEngine.Input(
            intent = intent,
            inbound = TransitionEngine.Leg(
                snapshot = inbound,
                confirmedDepartureDate = inboundConfirmedDate,
                departure = inbound?.departure,
                arrival = inbound?.arrival,
            ),
            outbound = TransitionEngine.Leg(
                snapshot = outbound,
                confirmedDepartureDate = outboundConfirmedDate,
                departure = outbound?.departure,
                arrival = outbound?.arrival,
            ),
            now = now,
        )
    )

    private fun snapshot(
        departure: AirportRef?,
        arrival: AirportRef?,
        depTimes: MovementTimes,
        arrTimes: MovementTimes,
        status: FlightStatus = FlightStatus.SCHEDULED,
        depTerminal: String? = null,
        depGate: String? = null,
        arrTerminal: String? = null,
        arrGate: String? = null,
    ) = StatusSnapshot(
        // Gate-milestone capable, so the connection window is allowed at all (§8.2).
        provider = "aeroapi",
        fetchedAt = Instant.parse("2026-09-18T04:55:00Z"),
        status = status,
        departure = departure,
        arrival = arrival,
        depTimes = depTimes,
        arrTimes = arrTimes,
        depTerminal = depTerminal,
        depGate = depGate,
        arrTerminal = arrTerminal,
        arrGate = arrGate,
    )
}
