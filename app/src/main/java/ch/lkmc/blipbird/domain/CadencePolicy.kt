package ch.lkmc.blipbird.domain

import ch.lkmc.blipbird.core.model.FlightStatus
import java.time.Duration
import java.time.Instant

/**
 * Adaptive status-refresh cadence (PLAN.md §8). Windows are non-overlapping and
 * anchored on best-known departure/arrival; the most frequent applicable cadence
 * wins. Returns null when a flight no longer needs background refreshes.
 */
object CadencePolicy {

    fun nextInterval(
        status: FlightStatus,
        bestDep: Instant?,
        bestArr: Instant?,
        arrivalResolved: Boolean,   // arrival gate + baggage known (or no alert enabled)
        now: Instant,
    ): Duration? {
        if (status == FlightStatus.CANCELLED) return null
        if (status == FlightStatus.ARRIVED && arrivalResolved) return null

        // Post-arrival: poll until gate/belt resolved, capped by caller via deadline.
        if (status == FlightStatus.ARRIVED || status == FlightStatus.LANDED) {
            return if (arrivalResolved) null else Duration.ofMinutes(30)
        }

        val dep = bestDep ?: return Duration.ofHours(6)
        val untilDep = Duration.between(now, dep)

        // Airborne windows
        if (status == FlightStatus.DEPARTED || status == FlightStatus.EN_ROUTE || status == FlightStatus.APPROACHING) {
            val arr = bestArr
            if (arr != null) {
                val untilArr = Duration.between(now, arr)
                if (untilArr <= Duration.ofMinutes(45)) return Duration.ofMinutes(15)
            }
            // Gate-critical tail: just departed
            if (untilDep >= Duration.ofMinutes(-30)) return Duration.ofMinutes(15)
            return Duration.ofHours(2)
        }

        // Pre-departure windows
        return when {
            untilDep > Duration.ofHours(48) -> null                       // app-open/manual only
            untilDep > Duration.ofHours(24) -> Duration.ofHours(6)
            untilDep > Duration.ofHours(3) -> Duration.ofHours(3)
            untilDep > Duration.ofMinutes(75) -> Duration.ofMinutes(30)
            else -> Duration.ofMinutes(15)                                // gate-critical
        }
    }

    /** Hard stop for arrival monitoring so a malformed feed can't create an immortal worker. */
    fun arrivalMonitoringDeadline(bestDep: Instant?, bestArr: Instant?): Instant? =
        bestArr?.plus(Duration.ofHours(4)) ?: bestDep?.plus(Duration.ofHours(24))
}
