package ch.lkmc.blipbird

import ch.lkmc.blipbird.core.model.AirportRef
import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.core.model.MovementTimes
import ch.lkmc.blipbird.core.model.StatusSnapshot
import ch.lkmc.blipbird.domain.FlightPhaseMachine
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val NOW: Instant = Instant.parse("2026-07-22T12:00:00Z")

private fun snapshot(
    status: FlightStatus = FlightStatus.SCHEDULED,
    schedDep: Instant? = NOW.plus(Duration.ofHours(2)),
    estDep: Instant? = null,
    actDep: Instant? = null,
    runwayActDep: Instant? = null,
    schedArr: Instant? = NOW.plus(Duration.ofHours(8)),
    estArr: Instant? = null,
    actArr: Instant? = null,
    depGate: String? = "C27",
) = StatusSnapshot(
    provider = "test",
    fetchedAt = NOW,
    status = status,
    departure = AirportRef("ZBAA", "PEK"),
    arrival = AirportRef("LSGG", "GVA"),
    depTimes = MovementTimes(scheduled = schedDep, estimated = estDep, actual = actDep, runwayActual = runwayActDep),
    arrTimes = MovementTimes(scheduled = schedArr, estimated = estArr, actual = actArr),
    depGate = depGate,
)

class FlightPhaseMachineTest {

    @Test fun `no snapshot means unknown`() {
        val view = FlightPhaseMachine.derive(null, null, NOW)
        assertEquals(FlightStatus.UNKNOWN, view.status)
    }

    @Test fun `upcoming flight inside 24h is on time`() {
        val view = FlightPhaseMachine.derive(snapshot(), null, NOW)
        assertEquals(FlightStatus.ON_TIME, view.status)
        assertEquals(FlightPhaseMachine.NextEvent.DEPARTS_IN, view.nextEventLabel)
    }

    @Test fun `estimate after schedule marks delayed`() {
        val view = FlightPhaseMachine.derive(
            snapshot(estDep = NOW.plus(Duration.ofHours(3))), null, NOW,
        )
        assertEquals(FlightStatus.DELAYED, view.status)
        assertEquals(Duration.ofHours(1), view.depDelay)
    }

    @Test fun `small slip below five minutes is not a delay`() {
        val view = FlightPhaseMachine.derive(
            snapshot(estDep = NOW.plus(Duration.ofHours(2)).plusSeconds(120)), null, NOW,
        )
        assertEquals(FlightStatus.ON_TIME, view.status)
    }

    @Test fun `actual departure means en route with lands-in target`() {
        val view = FlightPhaseMachine.derive(
            snapshot(actDep = NOW.minus(Duration.ofHours(1)), schedDep = NOW.minus(Duration.ofMinutes(70))), null, NOW,
        )
        assertEquals(FlightStatus.EN_ROUTE, view.status)
        assertEquals(FlightPhaseMachine.NextEvent.LANDS_IN, view.nextEventLabel)
        assertTrue(view.progress > 0f)
    }

    @Test fun `close to arrival becomes approaching`() {
        val view = FlightPhaseMachine.derive(
            snapshot(
                actDep = NOW.minus(Duration.ofHours(7)),
                schedDep = NOW.minus(Duration.ofHours(7)),
                schedArr = NOW.plus(Duration.ofMinutes(30)),
            ),
            null, NOW,
        )
        assertEquals(FlightStatus.APPROACHING, view.status)
    }

    @Test fun `actual arrival wins`() {
        val view = FlightPhaseMachine.derive(
            snapshot(actDep = NOW.minus(Duration.ofHours(9)), actArr = NOW.minus(Duration.ofMinutes(10))), null, NOW,
        )
        assertEquals(FlightStatus.ARRIVED, view.status)
        assertEquals(1f, view.progress)
    }

    @Test fun `cancelled always wins`() {
        val view = FlightPhaseMachine.derive(snapshot(status = FlightStatus.CANCELLED), null, NOW)
        assertEquals(FlightStatus.CANCELLED, view.status)
    }

    @Test fun `derived boarding is departure minus lead`() {
        val view = FlightPhaseMachine.derive(snapshot(), null, NOW)
        assertEquals(
            NOW.plus(Duration.ofHours(2)).minus(FlightPhaseMachine.DEFAULT_BOARDING_LEAD),
            view.derivedBoardingAt,
        )
    }
}
