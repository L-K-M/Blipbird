package ch.lkmc.blipbird.domain

import java.time.Instant
import java.time.LocalTime
import kotlin.math.ceil
import kotlin.math.min

/**
 * Body-clock arithmetic for a time-zone change (PLAN.md §9.5). Pure JVM, fully
 * offline, no user sleep or health input — it reads only the two airport zones
 * already bundled with the app plus the arrival instant the status layer reports.
 *
 * Two very different kinds of output live here, and the UI must keep them apart
 * (§1 "never fakes precision"):
 *
 * - **Facts.** [BodyClock.shiftMinutes] and [BodyClock.arrivalOriginTime] are
 *   ordinary `java.time` arithmetic on zone offsets. They are exact, and the card
 *   shows them at full weight.
 * - **A rule of thumb.** [Plan] is a population-average model of *unassisted*
 *   circadian adaptation. Individual variation is large, so it is rendered small,
 *   hedged, and never in the same visual weight as provider data.
 *
 * The model is the standard phase-response account: left alone, the body clock
 * advances by roughly [ADVANCE_HOURS_PER_DAY] and delays by roughly
 * [DELAY_HOURS_PER_DAY] per day, so a 24-hour clock closes faster one way round
 * than the other. Whichever direction costs fewer days wins — which is why a
 * shift beyond about nine hours eastward is modelled as a *delay*, matching the
 * usual advice for very long eastward trips rather than contradicting it.
 *
 * Light timing follows the same account: bright light in the hours *after* the
 * core-body-temperature minimum (CBTmin) advances the clock; light in the hours
 * *before* it delays. CBTmin is estimated from an assumed habitual wake time
 * ([ASSUMED_WAKE_HOUR]) because Blipbird deliberately collects no sleep data —
 * the card states that assumption beside the windows instead of hiding it.
 *
 * Reference for both rates and the light-timing direction: Eastman & Burgess,
 * "How To Travel the World Without Jet Lag", Sleep Medicine Clinics 4(2), 2009.
 */
object JetlagEngine {

    /** Hours per day the body clock shifts *earlier* unassisted. */
    const val ADVANCE_HOURS_PER_DAY = 1.0

    /** Hours per day the body clock shifts *later* unassisted — the easier direction. */
    const val DELAY_HOURS_PER_DAY = 1.5

    /** Below this, a shift is not worth planning around; no [Plan] is produced. */
    const val NEGLIGIBLE_SHIFT_MINUTES = 180

    /** Assumed habitual wake time in the origin zone, stated in the UI. */
    const val ASSUMED_WAKE_HOUR = 7

    /** CBTmin sits roughly this long before habitual wake. */
    const val CBT_MIN_HOURS_BEFORE_WAKE = 2L

    /** Half-width of the seek/avoid light windows either side of CBTmin. */
    const val LIGHT_WINDOW_HOURS = 4L

    private const val MINUTES_PER_DAY = 24 * 60

    /** Which way the body clock has to move to line up with the destination. */
    enum class Direction {
        /** Earlier — sleep and wake before the body wants to. */
        ADVANCE,

        /** Later — stay up past when the body wants to sleep. */
        DELAY,
    }

    /** A destination-local clock window, wrapping midnight when it has to. */
    data class LightWindow(val from: LocalTime, val to: LocalTime)

    /**
     * Modelled adaptation for one shift. Every field is an estimate; see the class
     * KDoc. Absent (null) whenever the shift is under [NEGLIGIBLE_SHIFT_MINUTES].
     */
    data class Plan(
        val direction: Direction,
        /** Minutes the body clock must move in [direction] — not always the clock shift. */
        val shiftMinutes: Int,
        /** Days of unassisted adaptation at the documented rates, rounded up. */
        val days: Int,
        /** Destination-local window to seek bright light on the first full day. */
        val seekLight: LightWindow,
        /** Destination-local window to keep light dim on the first full day. */
        val avoidLight: LightWindow,
        /**
         * True when [direction] is the counter-intuitive one — the clock jumped
         * forward but delaying is the shorter route round, or vice versa. The UI
         * explains it so the recommendation doesn't read as a bug.
         */
        val counterIntuitive: Boolean,
    )

    /** One flight's body-clock picture. */
    data class BodyClock(
        /**
         * Signed clock difference in minutes, destination minus origin, both zone
         * offsets evaluated at the arrival instant so a home-zone DST transition
         * during a long flight is already accounted for. Positive = destination
         * clock reads later. Not normalized: Los Angeles → Tokyo is +16 h, the
         * figure every clock and timezone site shows.
         */
        val shiftMinutes: Int,
        /** Arrival on the destination clock. */
        val arrivalLocalTime: LocalTime,
        /** Arrival on the origin clock — what the body still thinks the time is. */
        val arrivalOriginTime: LocalTime,
        /** Rule-of-thumb adaptation, or null for a negligible (or zero) shift. */
        val plan: Plan?,
    ) {
        /** No time-zone change at all — the card collapses to one reassuring line. */
        val unchanged: Boolean get() = shortestShiftMinutes(shiftMinutes) == 0
    }

    /** One leg's arrival, as far as the status layer has resolved it. */
    data class TripLeg(
        val arrivalCode: String,
        val arrival: Instant?,
        val arrivalTz: String?,
    )

    /** Cumulative shift from the trip's starting zone at one leg's arrival. */
    data class TripStep(val arrivalCode: String, val shiftMinutes: Int)

    /** A whole itinerary's body-clock picture, relative to where it started. */
    data class Trip(
        val startCode: String,
        val steps: List<TripStep>,
        /** The step furthest from the starting clock. */
        val peak: TripStep,
        /** Rule-of-thumb adaptation for [peak], or null when even the peak is negligible. */
        val peakPlan: Plan?,
        /** The last resolved leg lands back on the starting clock. */
        val returnsToStart: Boolean,
        /** At least one leg had no resolved arrival zone, so the chain is partial. */
        val incomplete: Boolean,
    )

    /**
     * One flight's body-clock picture, or null when either zone is unknown — a
     * missing zone yields no card rather than a guessed one.
     */
    fun compute(arrival: Instant, departureTz: String?, arrivalTz: String?): BodyClock? {
        val originZone = FlightDates.zoneOf(departureTz) ?: return null
        val destinationZone = FlightDates.zoneOf(arrivalTz) ?: return null
        val atDestination = arrival.atZone(destinationZone)
        val atOrigin = arrival.atZone(originZone)
        // tzdb offsets are whole minutes for any plausible flight date.
        val shiftMinutes = (atDestination.offset.totalSeconds - atOrigin.offset.totalSeconds) / 60
        return BodyClock(
            shiftMinutes = shiftMinutes,
            arrivalLocalTime = atDestination.toLocalTime(),
            arrivalOriginTime = atOrigin.toLocalTime(),
            plan = planFor(shiftMinutes),
        )
    }

    /**
     * Cumulative shifts across an ordered itinerary, each leg measured against the
     * zone the trip started in. Null when the starting zone is unknown or no leg
     * resolved; legs that didn't resolve are skipped and set [Trip.incomplete].
     */
    fun trip(startCode: String, startTz: String?, legs: List<TripLeg>): Trip? {
        val startZone = FlightDates.zoneOf(startTz) ?: return null
        val steps = mutableListOf<TripStep>()
        var incomplete = false
        for (leg in legs) {
            val zone = FlightDates.zoneOf(leg.arrivalTz)
            val at = leg.arrival
            if (zone == null || at == null) {
                incomplete = true
                continue
            }
            val shift = (at.atZone(zone).offset.totalSeconds - at.atZone(startZone).offset.totalSeconds) / 60
            steps += TripStep(leg.arrivalCode, shift)
        }
        val peak = steps.maxByOrNull { shortestShiftMinutes(it.shiftMinutes) } ?: return null
        return Trip(
            startCode = startCode,
            steps = steps,
            peak = peak,
            peakPlan = planFor(peak.shiftMinutes),
            returnsToStart = shortestShiftMinutes(steps.last().shiftMinutes) == 0,
            incomplete = incomplete,
        )
    }

    /**
     * Rule-of-thumb adaptation for a signed clock shift, or null when the shift is
     * under [NEGLIGIBLE_SHIFT_MINUTES] the short way round.
     */
    fun planFor(shiftMinutes: Int): Plan? {
        // Minutes of movement each way round the 24-hour clock.
        val advanceMinutes = ((shiftMinutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        val delayMinutes = if (advanceMinutes == 0) 0 else MINUTES_PER_DAY - advanceMinutes
        if (min(advanceMinutes, delayMinutes) < NEGLIGIBLE_SHIFT_MINUTES) return null

        val daysIfAdvance = advanceMinutes / 60.0 / ADVANCE_HOURS_PER_DAY
        val daysIfDelay = delayMinutes / 60.0 / DELAY_HOURS_PER_DAY
        val advancing = daysIfAdvance <= daysIfDelay

        // The body clock has not shifted on arrival day, so CBTmin still sits where
        // the origin zone put it — which is this far along the destination clock.
        val cbtMin = LocalTime.of(ASSUMED_WAKE_HOUR, 0)
            .minusHours(CBT_MIN_HOURS_BEFORE_WAKE)
            .plusMinutes(shiftMinutes.toLong())
        val afterCbtMin = LightWindow(cbtMin, cbtMin.plusHours(LIGHT_WINDOW_HOURS))
        val beforeCbtMin = LightWindow(cbtMin.minusHours(LIGHT_WINDOW_HOURS), cbtMin)

        return Plan(
            direction = if (advancing) Direction.ADVANCE else Direction.DELAY,
            shiftMinutes = if (advancing) advanceMinutes else delayMinutes,
            days = ceil(if (advancing) daysIfAdvance else daysIfDelay).toInt().coerceAtLeast(1),
            seekLight = if (advancing) afterCbtMin else beforeCbtMin,
            avoidLight = if (advancing) beforeCbtMin else afterCbtMin,
            counterIntuitive = (advanceMinutes <= MINUTES_PER_DAY / 2) != advancing,
        )
    }

    /** Magnitude of a signed shift the short way round the clock, 0..720 minutes. */
    fun shortestShiftMinutes(shiftMinutes: Int): Int {
        val forward = ((shiftMinutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return min(forward, MINUTES_PER_DAY - forward)
    }
}
