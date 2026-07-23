package ch.lkmc.blipbird.domain

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spherical great-circle math (Veness intermediate-point slerp formulas, MIT —
 * https://www.movable-type.co.uk/scripts/latlong.html).
 */
object GreatCircle {

    const val EARTH_RADIUS_KM = 6371.0

    data class Point(val lat: Double, val lon: Double)

    /** Central angle between two points, radians (haversine). */
    fun angularDistance(a: Point, b: Point): Double {
        val f1 = Math.toRadians(a.lat); val f2 = Math.toRadians(b.lat)
        val df = Math.toRadians(b.lat - a.lat); val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(df / 2) * sin(df / 2) + cos(f1) * cos(f2) * sin(dl / 2) * sin(dl / 2)
        return 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    fun distanceKm(a: Point, b: Point): Double = angularDistance(a, b) * EARTH_RADIUS_KM

    /** Intermediate point at fraction f (0..1) along the great circle a→b (slerp). */
    fun intermediate(a: Point, b: Point, f: Double): Point {
        val d = angularDistance(a, b)
        if (d < 1e-9) return a
        val sinD = sin(d)
        if (sinD < 1e-9) {
            // Near-antipodal (d≈π): slerp divides by sinD≈0 and returns garbage,
            // which breaks the route polyline, terminator, and daylight sampling
            // for polar / near-antipodal routes. Fall back to a stable linear
            // interpolation — for antipodal points no unique great circle exists
            // anyway, and bounded is better than NaN.
            return Point(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
        }
        val fa = sin((1 - f) * d) / sinD
        val fb = sin(f * d) / sinD
        val f1 = Math.toRadians(a.lat); val l1 = Math.toRadians(a.lon)
        val f2 = Math.toRadians(b.lat); val l2 = Math.toRadians(b.lon)
        val x = fa * cos(f1) * cos(l1) + fb * cos(f2) * cos(l2)
        val y = fa * cos(f1) * sin(l1) + fb * cos(f2) * sin(l2)
        val z = fa * sin(f1) + fb * sin(f2)
        val lat = atan2(z, sqrt(x * x + y * y))
        val lon = atan2(y, x)
        return Point(Math.toDegrees(lat), Math.toDegrees(lon))
    }

    /**
     * Signed cross-track distance (km) of p from the great circle through a→b;
     * negative is left of the track (Veness cross-track formula).
     */
    fun crossTrackKm(a: Point, b: Point, p: Point): Double {
        val d13 = angularDistance(a, p)
        val bearingDelta = Math.toRadians(initialBearing(a, p) - initialBearing(a, b))
        return asin((sin(d13) * sin(bearingDelta)).coerceIn(-1.0, 1.0)) * EARTH_RADIUS_KM
    }

    /**
     * Along-track distance (km) from a to the point on the great circle a→b
     * closest to p; negative when that point lies behind a.
     */
    fun alongTrackKm(a: Point, b: Point, p: Point): Double {
        val d13 = angularDistance(a, p)
        val bearingDelta = Math.toRadians(initialBearing(a, p) - initialBearing(a, b))
        val dxt = asin((sin(d13) * sin(bearingDelta)).coerceIn(-1.0, 1.0))
        val dat = acos((cos(d13) / cos(dxt)).coerceIn(-1.0, 1.0))
        val sign = if (cos(bearingDelta) >= 0) 1.0 else -1.0
        return sign * dat * EARTH_RADIUS_KM
    }

    /** Initial bearing (degrees, 0..360) from a toward b. */
    fun initialBearing(a: Point, b: Point): Double {
        val f1 = Math.toRadians(a.lat); val f2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(f2)
        val x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Route polyline for map rendering: n+1 points along a→b, split into segments at the
     * antimeridian so renderers never draw a line across the whole map.
     */
    fun routeSegments(a: Point, b: Point, steps: Int = 64): List<List<Point>> {
        val pts = (0..steps).map { intermediate(a, b, it.toDouble() / steps) }
        val segments = mutableListOf<MutableList<Point>>(mutableListOf(pts.first()))
        for (i in 1 until pts.size) {
            val prev = pts[i - 1]; val cur = pts[i]
            if (kotlin.math.abs(cur.lon - prev.lon) > 180.0) {
                // Antimeridian crossing: insert the dateline vertex on both
                // segments so the polyline doesn't visibly break across the date
                // line (the old code started the new segment at `cur`, leaving a
                // one-step gap between +180 and -180).
                val eastward = cur.lon - prev.lon < 0
                val crossLon = if (eastward) 180.0 else -180.0
                val unwrappedCurLon = if (eastward) cur.lon + 360.0 else cur.lon - 360.0
                // Degenerate when both vertices sit exactly on ±180°: unwrapping
                // lands on prev.lon and the division below would produce NaN.
                val denom = unwrappedCurLon - prev.lon
                val frac = if (kotlin.math.abs(denom) < 1e-12) 0.0 else (crossLon - prev.lon) / denom
                val crossLat = prev.lat + (cur.lat - prev.lat) * frac
                segments.last() += Point(crossLat, crossLon)
                segments += mutableListOf(Point(crossLat, -crossLon), cur)
            } else {
                segments.last() += cur
            }
        }
        return segments.filter { it.size >= 2 }
    }
}
