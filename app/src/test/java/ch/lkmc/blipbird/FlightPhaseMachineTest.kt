package ch.lkmc.blipbird

import ch.lkmc.blipbird.core.model.AirportRef
import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.core.model.MovementTimes
import ch.lkmc.blipbird.core.model.StatusSnapshot
import ch.lkmc.blipbird.domain.FlightPhaseMachine
import ch.lkmc.blipbird.domain.NotificationPlanner
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

class NotificationPlannerTest {

    @Test fun `gate change fires only when both known and different`() {
        val prev = snapshot(depGate = "C27")
        val cur = snapshot(depGate = "D14")
        val events = NotificationPlanner.diff(prev, cur)
        val gate = events.single { it.type == NotificationPlanner.EventType.GATE_CHANGE }
        assertEquals("C27", gate.oldValue)
        assertEquals("D14", gate.newValue)
    }

    @Test fun `gate appearing from null is not a change`() {
        val prev = snapshot(depGate = null)
        val cur = snapshot(depGate = "D14")
        assertTrue(NotificationPlanner.diff(prev, cur).none { it.type == NotificationPlanner.EventType.GATE_CHANGE })
    }

    @Test fun `delay below threshold does not fire`() {
        val cur = snapshot(estDep = NOW.plus(Duration.ofHours(2)).plusSeconds(600))
        assertTrue(NotificationPlanner.diff(null, cur).none { it.type == NotificationPlanner.EventType.DELAY })
    }

    @Test fun `delay fingerprint buckets re-notify on further slip`() {
        val d15 = snapshot(estDep = NOW.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(16)))
        val d45 = snapshot(estDep = NOW.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(46)))
        val f1 = NotificationPlanner.diff(null, d15).single { it.type == NotificationPlanner.EventType.DELAY }.fingerprint
        val f2 = NotificationPlanner.diff(null, d45).single { it.type == NotificationPlanner.EventType.DELAY }.fingerprint
        assertTrue(f1 != f2, "different slip buckets must have different fingerprints")
    }

    @Test fun `cancellation fires once on transition`() {
        val prev = snapshot()
        val cur = snapshot(status = FlightStatus.CANCELLED)
        assertNotNull(NotificationPlanner.diff(prev, cur).single { it.type == NotificationPlanner.EventType.CANCELLED })
        // Same state again: planner still reports (ledger dedups), fingerprint stable.
        val again = NotificationPlanner.diff(cur, cur)
        assertTrue(again.none { it.type == NotificationPlanner.EventType.CANCELLED })
    }

    @Test fun `departed and landed fire on actuals appearing`() {
        val prev = snapshot()
        val dep = snapshot(actDep = NOW)
        val events = NotificationPlanner.diff(prev, dep)
        assertNotNull(events.single { it.type == NotificationPlanner.EventType.DEPARTED })

        val landed = snapshot(actDep = NOW, actArr = NOW.plus(Duration.ofHours(8)))
        val events2 = NotificationPlanner.diff(dep, landed)
        assertNotNull(events2.single { it.type == NotificationPlanner.EventType.LANDED })
    }
}
