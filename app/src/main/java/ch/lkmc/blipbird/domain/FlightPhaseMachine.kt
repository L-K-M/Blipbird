package ch.lkmc.blipbird.domain

import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.core.model.PositionFix
import ch.lkmc.blipbird.core.model.StatusSnapshot
import java.time.Duration
import java.time.Instant

/**
 * Pure derivation of the user-facing phase/status line (PLAN.md §6).
 *
 * Provider-reported status wins; when the provider only gives timestamps, the
 * machine derives a phase from them plus (optionally) live position data, and the
 * UI labels derived facts as such.
 */
object FlightPhaseMachine {

    /** Boarding is derived: scheduled departure minus this default (user-visible). */
    val DEFAULT_BOARDING_LEAD: Duration = Duration.ofMinutes(40)
    val CHECK_IN_LEAD: Duration = Duration.ofHours(3)
    val APPROACH_WINDOW: Duration = Duration.ofMinutes(45)

    data class View(
        val status: FlightStatus,
        /** Delay at departure, when computable and positive. */
        val depDelay: Duration?,
        /** The one phase-relevant countdown target the list row shows. */
        val nextEventAt: Instant?,
        val nextEventLabel: NextEvent,
        val derivedBoardingAt: Instant?,
        val derivedCheckInAt: Instant?,
        val progress: Float,
    )

    enum class NextEvent { DEPARTS_IN, LANDS_IN, LANDED_AT, NONE }

    fun derive(snapshot: StatusSnapshot?, lastFix: PositionFix?, now: Instant): View {
        if (snapshot == null) {
            return View(FlightStatus.UNKNOWN, null, null, NextEvent.NONE, null, null, 0f)
        }
        val dep = snapshot.depTimes
        val arr = snapshot.arrTimes

        val schedDep = dep.scheduled
        val bestDep = dep.best
        val bestArr = arr.best
        val derivedBoarding = bestDep?.minus(DEFAULT_BOARDING_LEAD)
        val derivedCheckIn = schedDep?.minus(CHECK_IN_LEAD)

        val depDelay = if (schedDep != null && bestDep != null && bestDep.isAfter(schedDep))
            Duration.between(schedDep, bestDep).takeIf { it.toMinutes() >= 5 } else null

        val airborne = lastFix != null && !lastFix.onGround &&
            Duration.between(lastFix.at, now).abs() < Duration.ofMinutes(30)

        val status = when (snapshot.status) {
            FlightStatus.CANCELLED -> FlightStatus.CANCELLED
            FlightStatus.DIVERTED -> FlightStatus.DIVERTED
            else -> when {
                arr.actual != null || arr.runwayActual != null -> {
                    if (arr.actual != null) FlightStatus.ARRIVED else FlightStatus.LANDED
                }
                dep.actual != null || dep.runwayActual != null || airborne -> {
                    val eta = bestArr
                    if (eta != null && Duration.between(now, eta) <= APPROACH_WINDOW && Duration.between(now, eta) > Duration.ZERO)
                        FlightStatus.APPROACHING
                    else FlightStatus.EN_ROUTE
                }
                snapshot.status == FlightStatus.DEPARTED -> FlightStatus.DEPARTED
                depDelay != null -> FlightStatus.DELAYED
                snapshot.status == FlightStatus.UNKNOWN && schedDep == null -> FlightStatus.UNKNOWN
                bestDep != null && bestDep.isBefore(now) -> FlightStatus.DEPARTED
                snapshot.status == FlightStatus.SCHEDULED || snapshot.status == FlightStatus.ON_TIME ->
                    if (schedDep != null && Duration.between(now, schedDep) < Duration.ofHours(24))
                        FlightStatus.ON_TIME else FlightStatus.SCHEDULED
                else -> snapshot.status
            }
        }

        val (nextAt, nextLabel) = when (status) {
            FlightStatus.SCHEDULED, FlightStatus.ON_TIME, FlightStatus.DELAYED ->
                bestDep to NextEvent.DEPARTS_IN
            FlightStatus.DEPARTED, FlightStatus.EN_ROUTE, FlightStatus.APPROACHING ->
                bestArr to NextEvent.LANDS_IN
            FlightStatus.LANDED, FlightStatus.ARRIVED ->
                (arr.actual ?: arr.runwayActual) to NextEvent.LANDED_AT
            else -> null to NextEvent.NONE
        }

        val progress = computeProgress(bestDep, bestArr, dep.actual ?: dep.runwayActual, arr.actual ?: arr.runwayActual, now)

        return View(status, depDelay, nextAt, nextLabel, derivedBoarding, derivedCheckIn, progress)
    }

    private fun computeProgress(dep: Instant?, arr: Instant?, actDep: Instant?, actArr: Instant?, now: Instant): Float {
        if (actArr != null) return 1f
        val start = actDep ?: dep ?: return 0f
        val end = arr ?: return 0f
        if (!now.isAfter(start)) return 0f
        if (!end.isAfter(start)) return 0f
        val total = Duration.between(start, end).seconds.toFloat()
        val done = Duration.between(start, now).seconds.toFloat()
        return (done / total).coerceIn(0f, 0.99f)
    }
}
