package ch.lkmc.blipbird.domain

import kotlin.math.abs

/**
 * Great-circle-corridor plausibility check for CALLSIGN-derived position fixes
 * (PLAN.md §5 step 5, glm 1.16/B20 remainder). Callsigns are reused day to day,
 * so a fix that matches the callsign can still belong to a different flight on a
 * different route; a fix far outside the corridor around the tracked route must
 * not be rendered as the live plane.
 *
 * The corridor is deliberately generous: real routes leave the great circle for
 * airways, NAT tracks, weather and ETOPS, so this screens out fixes that cannot
 * plausibly belong to the route, not ones merely off the ideal arc.
 */
object RouteCorridor {

    /** Minimum corridor half-width — short hops still get generous slack. */
    const val MIN_HALF_WIDTH_KM = 250.0

    /** Long-haul corridors widen with route length (so do real deviations). */
    const val HALF_WIDTH_FRACTION = 0.25

    /** Slack beyond each endpoint (departures, holding stacks, go-arounds). */
    const val ENDPOINT_MARGIN_KM = 150.0

    fun isPlausible(
        dep: GreatCircle.Point,
        arr: GreatCircle.Point,
        fix: GreatCircle.Point,
    ): Boolean {
        val routeKm = GreatCircle.distanceKm(dep, arr)
        if (routeKm < 1.0) {
            // Degenerate route (identical endpoints): radius check around it.
            return GreatCircle.distanceKm(dep, fix) <= MIN_HALF_WIDTH_KM + ENDPOINT_MARGIN_KM
        }
        val halfWidth = maxOf(MIN_HALF_WIDTH_KM, routeKm * HALF_WIDTH_FRACTION)
        if (abs(GreatCircle.crossTrackKm(dep, arr, fix)) > halfWidth) return false
        val along = GreatCircle.alongTrackKm(dep, arr, fix)
        return along >= -ENDPOINT_MARGIN_KM && along <= routeKm + ENDPOINT_MARGIN_KM
    }
}
