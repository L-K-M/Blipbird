package ch.lkmc.blipbird.domain

import kotlin.math.PI
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
        if (d > PI - 1e-9) {
            // Antipodal (or near-antipodal): sin(d) ≈ 0 makes slerp degenerate.
            // Route via the north pole as a stable waypoint.
            val mid = intermediateViaPole(a, b, f)
            if (mid != null) return mid
        }
        val sinD = sin(d)
        if (sinD < 1e-8) return if (f <= 0.5) a else b
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
     * Antipodal fallback: route a → north pole → b. The pole used is the one on the
     * shorter detour; for real-world near-antipodal flights (extremely rare), this
     * yields a continuous polyline instead of garbage coordinates.
     */
    private fun intermediateViaPole(a: Point, b: Point, f: Double): Point? {
        val pole = Point(90.0, a.lon)
        val d1 = angularDistance(a, pole)
        val d2 = angularDistance(pole, b)
        val total = d1 + d2
        if (total < 1e-9 || d1 > PI - 1e-9 || d2 > PI - 1e-9) return null
        val fScaled = f * total
        return if (fScaled <= d1) intermediate(a, pole, (fScaled / d1).coerceIn(0.0, 1.0))
        else intermediate(pole, b, ((fScaled - d1) / d2).coerceIn(0.0, 1.0))
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
     * Interpolated ±180° seam vertices close the visual gap at each split.
     */
    fun routeSegments(a: Point, b: Point, steps: Int = 64): List<List<Point>> {
        val pts = (0..steps).map { intermediate(a, b, it.toDouble() / steps) }
        val segments = mutableListOf<MutableList<Point>>(mutableListOf(pts.first()))
        for (i in 1 until pts.size) {
            val prev = pts[i - 1]; val cur = pts[i]
            if (kotlin.math.abs(cur.lon - prev.lon) > 180.0) {
                // Interpolate the antimeridian seam vertex.
                val seam = antimeridianSeam(prev, cur)
                segments.last() += seam
                segments += mutableListOf(seam, cur)
            } else {
                segments.last() += cur
            }
        }
        return segments.filter { it.size >= 2 }
    }

    /**
     * Interpolated vertex at exactly ±180° where a segment crosses the antimeridian.
     * The longitude to interpolate toward is the direction that closes the shorter
     * wrap: if cur.lon > prev.lon we crossed +180°, else we crossed −180°.
     */
    private fun antimeridianSeam(prev: Point, cur: Point): Point {
        val targetLon = if (cur.lon > prev.lon) 180.0 else -180.0
        // Unwrap prev.lon into the same cycle as targetLon
        var pLon = prev.lon
        while (targetLon - pLon > 180) pLon += 360
        while (pLon - targetLon > 180) pLon -= 360
        val cLon = cur.lon
        val f = if (kotlin.math.abs(cLon - pLon) > 1e-9) (targetLon - pLon) / (cLon - pLon) else 0.5
        val midLat = prev.lat + (cur.lat - prev.lat) * f
        return Point(midLat, targetLon)
    }
}
