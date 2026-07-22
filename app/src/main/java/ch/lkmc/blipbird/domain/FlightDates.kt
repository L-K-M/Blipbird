package ch.lkmc.blipbird.domain

import ch.lkmc.blipbird.core.model.StatusSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Date/timezone helpers for the traps flights set (PLAN.md §5/§6): a flight's
 * identity date is its DEPARTURE-AIRPORT-LOCAL date; local clocks can show an
 * arrival "before" its departure (westbound across the date line) or a +1/+2
 * day arrival (red-eyes); elapsed time is always Instant math, never local-clock
 * subtraction.
 */
object FlightDates {

    fun zoneOf(tz: String?): ZoneId? = tz?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    /**
     * True when the snapshot's scheduled departure falls on [date] in the
     * DEPARTURE airport's local zone. Lenient (true) when the schedule or zone
     * is unknown — a provider mismatch must not silently drop the only candidate.
     */
    fun matchesDepartureLocalDate(snapshot: StatusSnapshot, date: LocalDate): Boolean {
        val sched = snapshot.depTimes.scheduled ?: return true
        val zone = zoneOf(snapshot.departure?.tz) ?: return true
        return sched.atZone(zone).toLocalDate() == date
    }

    /**
     * Calendar-day offset of the arrival relative to the departure, each in its
     * OWN airport zone: +1 for a red-eye landing "tomorrow", -1 when the local
     * clock says you land before you left (westbound across the date line),
     * null when either side is unknown. Display-only — never used for math.
     */
    fun arrivalDayOffset(
        departure: Instant?,
        departureTz: String?,
        arrival: Instant?,
        arrivalTz: String?,
    ): Int? {
        if (departure == null || arrival == null) return null
        val depZone = zoneOf(departureTz) ?: return null
        val arrZone = zoneOf(arrivalTz) ?: return null
        val depDate = departure.atZone(depZone).toLocalDate()
        val arrDate = arrival.atZone(arrZone).toLocalDate()
        return (arrDate.toEpochDay() - depDate.toEpochDay()).toInt()
    }
}
