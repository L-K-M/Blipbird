package ch.lkmc.blipbird

import ch.lkmc.blipbird.core.model.AirportRef
import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.core.model.MovementTimes
import ch.lkmc.blipbird.core.model.StatusSnapshot
import ch.lkmc.blipbird.domain.NotificationPlanner
import ch.lkmc.blipbird.domain.NotificationPlanner.EventType
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationPlannerTest {

    private val sched: Instant = Instant.parse("2026-07-22T14:00:00Z")

    private fun snapshot(
        status: FlightStatus = FlightStatus.SCHEDULED,
        gate: String? = null,
        estDep: Instant? = null,
        actDep: Instant? = null,
        actArr: Instant? = null,
    ) = StatusSnapshot(
        provider = "test",
        fetchedAt = sched.minus(Duration.ofHours(2)),
        status = status,
        departure = AirportRef("LSGG", "GVA"),
        arrival = AirportRef("ZBAA", "PEK"),
        depTimes = MovementTimes(scheduled = sched, estimated = estDep, actual = actDep),
        arrTimes = MovementTimes(scheduled = sched.plus(Duration.ofHours(10)), actual = actArr),
        depGate = gate,
    )

    private fun single(events: List<NotificationPlanner.Event>, type: EventType): NotificationPlanner.Event {
        val matching = events.filter { it.type == type }
        assertEquals(1, matching.size, "expected exactly one $type in $events")
        return matching.first()
    }

    // ------------------------------------------------------------- gates

    @Test fun `first gate assignment notifies with its own fingerprint namespace`() {
        val events = NotificationPlanner.diff(snapshot(gate = null), snapshot(gate = "A61"))
        val e = single(events, EventType.GATE_ASSIGNED)
        assertEquals("gate-assigned:A61", e.fingerprint)
        assertEquals("A61", e.newValue)
        assertTrue(events.none { it.type == EventType.GATE_CHANGE })
    }

    @Test fun `gate on the very first snapshot is not an assignment event`() {
        val events = NotificationPlanner.diff(null, snapshot(gate = "A61"))
        assertTrue(events.none { it.type == EventType.GATE_ASSIGNED || it.type == EventType.GATE_CHANGE })
    }

    @Test fun `gate change keeps working and a change back to the original gate has a fresh fingerprint`() {
        val change = single(
            NotificationPlanner.diff(snapshot(gate = "A61"), snapshot(gate = "B12")),
            EventType.GATE_CHANGE,
        )
        assertEquals("gate:B12", change.fingerprint)
        // A61 was introduced as "gate-assigned:A61"; changing back emits "gate:A61",
        // which the ledger has never seen.
        val back = single(
            NotificationPlanner.diff(snapshot(gate = "B12"), snapshot(gate = "A61")),
            EventType.GATE_CHANGE,
        )
        assertEquals("gate:A61", back.fingerprint)
    }

    // ------------------------------------------------------------- delays

    @Test fun `delay carries real minutes while the fingerprint stays bucketed`() {
        val events = NotificationPlanner.diff(
            snapshot(),
            snapshot(estDep = sched.plus(Duration.ofMinutes(29))),
        )
        val e = single(events, EventType.DELAY)
        assertEquals("delay:15", e.fingerprint)
        assertEquals(29L, e.delayMinutes)
    }

    @Test fun `sub-threshold slip stays quiet`() {
        val events = NotificationPlanner.diff(
            snapshot(),
            snapshot(estDep = sched.plus(Duration.ofMinutes(10))),
        )
        assertTrue(events.none { it.type == EventType.DELAY || it.type == EventType.DELAY_RECOVERED })
    }

    @Test fun `further slip moves to the next bucket`() {
        val e = single(
            NotificationPlanner.diff(
                snapshot(estDep = sched.plus(Duration.ofMinutes(20))),
                snapshot(estDep = sched.plus(Duration.ofMinutes(50))),
            ),
            EventType.DELAY,
        )
        assertEquals("delay:45", e.fingerprint)
        assertEquals(50L, e.delayMinutes)
    }

    @Test fun `delay shrinking a bucket emits recovery with the remaining minutes`() {
        val events = NotificationPlanner.diff(
            snapshot(estDep = sched.plus(Duration.ofMinutes(50))),
            snapshot(estDep = sched.plus(Duration.ofMinutes(20))),
        )
        val e = single(events, EventType.DELAY_RECOVERED)
        assertEquals("delay-recovered:15", e.fingerprint)
        assertEquals(20L, e.delayMinutes)
    }

    @Test fun `estimate reverting to schedule emits back-on-time recovery`() {
        val events = NotificationPlanner.diff(
            snapshot(estDep = sched.plus(Duration.ofMinutes(30))),
            snapshot(estDep = null),
        )
        val e = single(events, EventType.DELAY_RECOVERED)
        assertEquals("delay-recovered:0", e.fingerprint)
        assertEquals(0L, e.delayMinutes)
        assertEquals(sched.toString(), e.newValue)
        assertTrue(events.none { it.type == EventType.DELAY })
    }

    @Test fun `no recovery without a prior notified delay`() {
        val events = NotificationPlanner.diff(
            snapshot(estDep = sched.plus(Duration.ofMinutes(10))),
            snapshot(estDep = null),
        )
        assertTrue(events.none { it.type == EventType.DELAY_RECOVERED })
    }

    // ------------------------------------------------------------- transitions

    @Test fun `departed and landed fire once on the transition`() {
        val dep = single(
            NotificationPlanner.diff(snapshot(), snapshot(actDep = sched.plus(Duration.ofMinutes(5)))),
            EventType.DEPARTED,
        )
        assertEquals("departed", dep.fingerprint)
        // Already-departed previous snapshot: no repeat.
        val again = NotificationPlanner.diff(
            snapshot(actDep = sched),
            snapshot(actDep = sched),
        )
        assertTrue(again.none { it.type == EventType.DEPARTED })
        val landed = single(
            NotificationPlanner.diff(
                snapshot(actDep = sched),
                snapshot(actDep = sched, actArr = sched.plus(Duration.ofHours(10))),
            ),
            EventType.LANDED,
        )
        assertEquals("landed", landed.fingerprint)
    }

    @Test fun `cancellation fires on transition only`() {
        val e = single(
            NotificationPlanner.diff(snapshot(), snapshot(status = FlightStatus.CANCELLED)),
            EventType.CANCELLED,
        )
        assertEquals("cancelled", e.fingerprint)
        val again = NotificationPlanner.diff(
            snapshot(status = FlightStatus.CANCELLED),
            snapshot(status = FlightStatus.CANCELLED),
        )
        assertTrue(again.none { it.type == EventType.CANCELLED })
    }

    @Test fun `quiet snapshot yields no events`() {
        assertTrue(NotificationPlanner.diff(snapshot(gate = "A61"), snapshot(gate = "A61")).isEmpty())
        assertNull(NotificationPlanner.diff(null, snapshot()).firstOrNull())
    }
}
