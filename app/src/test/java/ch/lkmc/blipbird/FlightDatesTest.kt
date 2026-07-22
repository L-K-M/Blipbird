package ch.lkmc.blipbird

import ch.lkmc.blipbird.core.model.AirportRef
import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.core.model.MovementTimes
import ch.lkmc.blipbird.core.model.StatusSnapshot
import ch.lkmc.blipbird.domain.FlightDates
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The date-line/midnight traps: a flight's identity date is departure-airport-
 * local; local clocks may show arrival before departure; elapsed time is always
 * Instant math.
 */
class FlightDatesTest {

    private fun snapshot(schedDep: Instant, depTz: String?) = StatusSnapshot(
        provider = "test",
        fetchedAt = Instant.EPOCH,
        status = FlightStatus.SCHEDULED,
        departure = AirportRef("ZBAA", "PEK", tz = depTz),
        arrival = AirportRef("LSGG", "GVA", tz = "Europe/Zurich"),
        depTimes = MovementTimes(scheduled = schedDep),
        arrTimes = MovementTimes(),
    )

    @Test fun `departure just after local midnight belongs to the local date, not the UTC date`() {
        // 00:30 on July 23 in Shanghai = 16:30Z on July 22.
        val dep = Instant.parse("2026-07-22T16:30:00Z")
        val s = snapshot(dep, "Asia/Shanghai")
        assertTrue(FlightDates.matchesDepartureLocalDate(s, LocalDate.of(2026, 7, 23)))
        assertFalse(FlightDates.matchesDepartureLocalDate(s, LocalDate.of(2026, 7, 22)))
    }

    @Test fun `departure just before local midnight west of Greenwich belongs to the local date`() {
        // 23:30 on July 22 in Los Angeles = 06:30Z on July 23.
        val dep = Instant.parse("2026-07-23T06:30:00Z")
        val s = snapshot(dep, "America/Los_Angeles")
        assertTrue(FlightDates.matchesDepartureLocalDate(s, LocalDate.of(2026, 7, 22)))
        assertFalse(FlightDates.matchesDepartureLocalDate(s, LocalDate.of(2026, 7, 23)))
    }

    @Test fun `unknown zone or schedule is lenient`() {
        val s = snapshot(Instant.parse("2026-07-22T16:30:00Z"), depTz = null)
        assertTrue(FlightDates.matchesDepartureLocalDate(s, LocalDate.of(2026, 1, 1)))
    }

    @Test fun `red-eye arrives plus one day`() {
        // PEK 23:30 Jul 22 (15:30Z) → GVA 03:30 Jul 23 local (01:30Z Jul 23).
        val offset = FlightDates.arrivalDayOffset(
            Instant.parse("2026-07-22T15:30:00Z"), "Asia/Shanghai",
            Instant.parse("2026-07-23T01:30:00Z"), "Europe/Zurich",
        )
        assertEquals(1, offset)
    }

    @Test fun `westbound across the date line lands the day before it departed`() {
        // AKL 00:10 Jul 23 local (12:10Z Jul 22) → HNL 18:00 Jul 22 local (04:00Z Jul 23).
        // 15h50m in the air, but the calendar goes backwards.
        val offset = FlightDates.arrivalDayOffset(
            Instant.parse("2026-07-22T12:10:00Z"), "Pacific/Auckland",
            Instant.parse("2026-07-23T04:00:00Z"), "Pacific/Honolulu",
        )
        assertEquals(-1, offset)
    }

    @Test fun `same-day flight has zero offset`() {
        val offset = FlightDates.arrivalDayOffset(
            Instant.parse("2026-07-22T18:45:00Z"), "Asia/Shanghai",     // 02:45 Jul 23 PEK
            Instant.parse("2026-07-23T05:40:00Z"), "Europe/Zurich",     // 07:40 Jul 23 GVA
        )
        assertEquals(0, offset)
    }

    @Test fun `ten-hour flight where twenty local hours pass is still ten hours of Instant math`() {
        // LAX 13:00 Jul 22 (20:00Z) → NRT lands 06:00Z Jul 23 = 15:00 Jul 23 local:
        // local clocks moved "26 hours"; the airplane flew 10.
        val dep = Instant.parse("2026-07-22T20:00:00Z")
        val arr = Instant.parse("2026-07-23T06:00:00Z")
        assertEquals(10, java.time.Duration.between(dep, arr).toHours())
        assertEquals(1, FlightDates.arrivalDayOffset(dep, "America/Los_Angeles", arr, "Asia/Tokyo"))
    }

    @Test fun `unknown arrival zone yields null offset instead of a guess`() {
        assertNull(
            FlightDates.arrivalDayOffset(
                Instant.parse("2026-07-22T15:30:00Z"), "Asia/Shanghai",
                Instant.parse("2026-07-23T01:30:00Z"), null,
            )
        )
    }
}
