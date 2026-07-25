package ch.lkmc.blipbird

import ch.lkmc.blipbird.domain.JetlagEngine
import ch.lkmc.blipbird.domain.JetlagEngine.Direction
import java.time.Instant
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The body-clock core (PLAN.md §9.5). Two things are under test: the *exact* half
 * (offset arithmetic across DST and the date line) and the *modelled* half (which
 * way round the 24-hour clock is shorter, and where the light windows land).
 */
class JetlagEngineTest {

    // ------------------------------------------------------------------ facts

    @Test fun `eastbound shift is the clock difference at the arrival instant`() {
        // ZRH (CEST, +2) → NRT (JST, +9) in July: +7 h.
        val bc = JetlagEngine.compute(
            arrival = Instant.parse("2026-07-23T00:25:00Z"),
            departureTz = "Europe/Zurich",
            arrivalTz = "Asia/Tokyo",
        )
        assertNotNull(bc)
        assertEquals(7 * 60, bc.shiftMinutes)
        assertEquals(LocalTime.of(9, 25), bc.arrivalLocalTime)
        assertEquals(LocalTime.of(2, 25), bc.arrivalOriginTime)
        assertFalse(bc.unchanged)
    }

    @Test fun `the same route in January uses the winter offset, not a cached one`() {
        // ZRH is CET (+1) in January, so the same pairing is +8 h, not +7 h.
        val bc = JetlagEngine.compute(
            arrival = Instant.parse("2026-01-23T00:25:00Z"),
            departureTz = "Europe/Zurich",
            arrivalTz = "Asia/Tokyo",
        )
        assertEquals(8 * 60, assertNotNull(bc).shiftMinutes)
    }

    @Test fun `date-line crossing reports the raw clock difference every timezone site shows`() {
        // LAX (PDT, -7) → NRT (+9): the Tokyo clock reads 16 h later, not 8 h earlier.
        val bc = JetlagEngine.compute(
            arrival = Instant.parse("2026-07-23T06:00:00Z"),
            departureTz = "America/Los_Angeles",
            arrivalTz = "Asia/Tokyo",
        )
        assertEquals(16 * 60, assertNotNull(bc).shiftMinutes)
    }

    @Test fun `a north-south flight has nothing for the body clock to do`() {
        // FRA → JNB: both +2 in July.
        val bc = JetlagEngine.compute(
            arrival = Instant.parse("2026-07-23T05:30:00Z"),
            departureTz = "Europe/Berlin",
            arrivalTz = "Africa/Johannesburg",
        )
        assertNotNull(bc)
        assertEquals(0, bc.shiftMinutes)
        assertTrue(bc.unchanged)
        assertNull(bc.plan)
    }

    @Test fun `half-hour zones keep their thirty minutes`() {
        // LHR (BST, +1) → KTM (+5:45): +4 h 45 min.
        val bc = JetlagEngine.compute(
            arrival = Instant.parse("2026-07-23T05:00:00Z"),
            departureTz = "Europe/London",
            arrivalTz = "Asia/Kathmandu",
        )
        assertEquals(4 * 60 + 45, assertNotNull(bc).shiftMinutes)
    }

    @Test fun `an unknown zone yields no card instead of a guessed one`() {
        val at = Instant.parse("2026-07-23T05:00:00Z")
        assertNull(JetlagEngine.compute(at, null, "Asia/Tokyo"))
        assertNull(JetlagEngine.compute(at, "Europe/Zurich", null))
        assertNull(JetlagEngine.compute(at, "Europe/Zurich", "Not/AZone"))
    }

    // ------------------------------------------------------------------ the model

    @Test fun `a small shift produces no plan at all`() {
        assertNull(JetlagEngine.planFor(60))       // +1 h
        assertNull(JetlagEngine.planFor(-120))     // -2 h
        assertNull(JetlagEngine.planFor(179))      // just under the threshold
        assertNotNull(JetlagEngine.planFor(180))   // exactly 3 h qualifies
    }

    @Test fun `a moderate eastward shift advances at one hour per day`() {
        // JFK → LHR, +5 h.
        val plan = assertNotNull(JetlagEngine.planFor(5 * 60))
        assertEquals(Direction.ADVANCE, plan.direction)
        assertEquals(5 * 60, plan.shiftMinutes)
        assertEquals(5, plan.days)
        assertFalse(plan.counterIntuitive)
    }

    @Test fun `a westward shift delays at the faster one and a half hours per day`() {
        // LHR → JFK, -5 h: 5 h of delay at 1.5 h/day rounds up to 4 days.
        val plan = assertNotNull(JetlagEngine.planFor(-5 * 60))
        assertEquals(Direction.DELAY, plan.direction)
        assertEquals(5 * 60, plan.shiftMinutes)
        assertEquals(4, plan.days)
        assertFalse(plan.counterIntuitive)
    }

    @Test fun `a very large eastward shift is modelled as a delay, the shorter way round`() {
        // ZRH → AKL, +10 h: advancing 10 h costs 10 days, delaying the other 14 h
        // costs 9.3 — so the model goes the counter-intuitive way and says so.
        val plan = assertNotNull(JetlagEngine.planFor(10 * 60))
        assertEquals(Direction.DELAY, plan.direction)
        assertEquals(14 * 60, plan.shiftMinutes)
        assertEquals(10, plan.days)
        assertTrue(plan.counterIntuitive)
    }

    @Test fun `nine hours east still advances - the crossover sits between nine and ten`() {
        assertEquals(Direction.ADVANCE, assertNotNull(JetlagEngine.planFor(9 * 60)).direction)
        assertEquals(Direction.DELAY, assertNotNull(JetlagEngine.planFor(10 * 60)).direction)
    }

    @Test fun `a raw sixteen-hour shift is eight hours of delay, not sixteen of advance`() {
        // LAX → NRT: the headline fact is +16 h, but only 8 h of body-clock work.
        val plan = assertNotNull(JetlagEngine.planFor(16 * 60))
        assertEquals(Direction.DELAY, plan.direction)
        assertEquals(8 * 60, plan.shiftMinutes)
        assertEquals(6, plan.days)   // 8 / 1.5 = 5.33, rounded up
        assertFalse(plan.counterIntuitive)
    }

    @Test fun `light windows straddle the estimated temperature minimum`() {
        // +7 h: CBTmin is 05:00 at home, so it lands at 12:00 on the destination
        // clock. Advancing means light after it, dim before it — i.e. deliberately
        // NOT chasing the destination's early morning sun on day one.
        val plan = assertNotNull(JetlagEngine.planFor(7 * 60))
        assertEquals(Direction.ADVANCE, plan.direction)
        assertEquals(LocalTime.of(12, 0), plan.seekLight.from)
        assertEquals(LocalTime.of(16, 0), plan.seekLight.to)
        assertEquals(LocalTime.of(8, 0), plan.avoidLight.from)
        assertEquals(LocalTime.of(12, 0), plan.avoidLight.to)
    }

    @Test fun `a delay seeks light before the temperature minimum instead`() {
        // -6 h (LHR → ORD): CBTmin lands at 23:00 destination time; a delay wants
        // light in the evening before it and dim light after.
        val plan = assertNotNull(JetlagEngine.planFor(-6 * 60))
        assertEquals(Direction.DELAY, plan.direction)
        assertEquals(LocalTime.of(19, 0), plan.seekLight.from)
        assertEquals(LocalTime.of(23, 0), plan.seekLight.to)
        assertEquals(LocalTime.of(23, 0), plan.avoidLight.from)
        assertEquals(LocalTime.of(3, 0), plan.avoidLight.to)
    }

    @Test fun `light windows wrap midnight rather than clamping`() {
        // +16 h puts CBTmin at 21:00; the four hours after it cross midnight.
        val plan = assertNotNull(JetlagEngine.planFor(16 * 60))
        assertEquals(LocalTime.of(17, 0), plan.seekLight.from)
        assertEquals(LocalTime.of(21, 0), plan.seekLight.to)
        assertEquals(LocalTime.of(21, 0), plan.avoidLight.from)
        assertEquals(LocalTime.of(1, 0), plan.avoidLight.to)
    }

    @Test fun `shortest shift folds the clock both ways`() {
        assertEquals(0, JetlagEngine.shortestShiftMinutes(0))
        assertEquals(0, JetlagEngine.shortestShiftMinutes(24 * 60))
        assertEquals(8 * 60, JetlagEngine.shortestShiftMinutes(16 * 60))
        assertEquals(8 * 60, JetlagEngine.shortestShiftMinutes(-16 * 60))
        assertEquals(12 * 60, JetlagEngine.shortestShiftMinutes(12 * 60))
    }

    // ------------------------------------------------------------------ itineraries

    private fun leg(code: String, at: String, tz: String?) =
        JetlagEngine.TripLeg(code, Instant.parse(at), tz)

    @Test fun `a round trip peaks away from home and returns to the starting clock`() {
        val trip = JetlagEngine.trip(
            startCode = "ZRH",
            startTz = "Europe/Zurich",
            legs = listOf(
                leg("NRT", "2026-07-23T00:25:00Z", "Asia/Tokyo"),
                leg("SIN", "2026-07-30T09:00:00Z", "Asia/Singapore"),
                leg("ZRH", "2026-08-06T05:00:00Z", "Europe/Zurich"),
            ),
        )
        assertNotNull(trip)
        assertEquals(listOf(7 * 60, 6 * 60, 0), trip.steps.map { it.shiftMinutes })
        assertEquals("NRT", trip.peak.arrivalCode)
        assertEquals(Direction.ADVANCE, assertNotNull(trip.peakPlan).direction)
        assertTrue(trip.returnsToStart)
        assertFalse(trip.incomplete)
    }

    @Test fun `a one-way trip does not claim to return home`() {
        val trip = JetlagEngine.trip(
            startCode = "LHR",
            startTz = "Europe/London",
            legs = listOf(
                leg("DXB", "2026-07-23T05:00:00Z", "Asia/Dubai"),
                leg("SYD", "2026-07-24T09:00:00Z", "Australia/Sydney"),
            ),
        )
        assertNotNull(trip)
        assertEquals(listOf(3 * 60, 9 * 60), trip.steps.map { it.shiftMinutes })
        assertEquals("SYD", trip.peak.arrivalCode)
        assertFalse(trip.returnsToStart)
    }

    @Test fun `an unresolved leg is skipped and flagged rather than guessed`() {
        val trip = JetlagEngine.trip(
            startCode = "ZRH",
            startTz = "Europe/Zurich",
            legs = listOf(
                leg("NRT", "2026-07-23T00:25:00Z", "Asia/Tokyo"),
                JetlagEngine.TripLeg("???", null, null),
            ),
        )
        assertNotNull(trip)
        assertEquals(1, trip.steps.size)
        assertTrue(trip.incomplete)
    }

    @Test fun `an itinerary that never leaves its own zone reports no plan`() {
        val trip = JetlagEngine.trip(
            startCode = "LHR",
            startTz = "Europe/London",
            legs = listOf(
                leg("EDI", "2026-07-23T08:00:00Z", "Europe/London"),
                leg("LHR", "2026-07-25T08:00:00Z", "Europe/London"),
            ),
        )
        assertNotNull(trip)
        assertNull(trip.peakPlan)
        assertTrue(trip.returnsToStart)
    }

    @Test fun `no resolvable leg yields no trip at all`() {
        assertNull(
            JetlagEngine.trip("ZRH", "Europe/Zurich", listOf(JetlagEngine.TripLeg("???", null, null)))
        )
        assertNull(JetlagEngine.trip("ZRH", null, listOf(leg("NRT", "2026-07-23T00:25:00Z", "Asia/Tokyo"))))
    }
}
