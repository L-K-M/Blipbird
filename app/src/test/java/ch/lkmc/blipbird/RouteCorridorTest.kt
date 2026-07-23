package ch.lkmc.blipbird

import ch.lkmc.blipbird.domain.GreatCircle
import ch.lkmc.blipbird.domain.RouteCorridor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteCorridorTest {

    private val lhr = GreatCircle.Point(51.4700, -0.4543)
    private val jfk = GreatCircle.Point(40.6413, -73.7781)
    private val zrh = GreatCircle.Point(47.4647, 8.5492)
    private val gva = GreatCircle.Point(46.2381, 6.1090)

    @Test
    fun `cross and along track match a simple equatorial route`() {
        val a = GreatCircle.Point(0.0, 0.0)
        val b = GreatCircle.Point(0.0, 10.0)
        val p = GreatCircle.Point(1.0, 5.0)
        val oneDegKm = Math.toRadians(1.0) * GreatCircle.EARTH_RADIUS_KM
        assertTrue(abs(abs(GreatCircle.crossTrackKm(a, b, p)) - oneDegKm) < 5.0)
        assertTrue(abs(GreatCircle.alongTrackKm(a, b, p) - 5 * oneDegKm) < 5.0)
        // Behind the start point → negative along-track.
        assertTrue(GreatCircle.alongTrackKm(a, b, GreatCircle.Point(0.0, -3.0)) < 0.0)
    }

    @Test
    fun `mid-route fix on the great circle is plausible`() {
        val mid = GreatCircle.intermediate(lhr, jfk, 0.5)
        assertTrue(RouteCorridor.isPlausible(lhr, jfk, mid))
    }

    @Test
    fun `NAT-track style deviation stays plausible`() {
        // ~550 km south of the ideal arc mid-Atlantic — routine routing spread.
        val mid = GreatCircle.intermediate(lhr, jfk, 0.5)
        val shifted = GreatCircle.Point(mid.lat - 5.0, mid.lon)
        assertTrue(RouteCorridor.isPlausible(lhr, jfk, shifted))
    }

    @Test
    fun `reused callsign on another continent is rejected`() {
        val overIndia = GreatCircle.Point(22.0, 78.0)
        assertFalse(RouteCorridor.isPlausible(lhr, jfk, overIndia))
        val overAustralia = GreatCircle.Point(-25.0, 134.0)
        assertFalse(RouteCorridor.isPlausible(lhr, jfk, overAustralia))
    }

    @Test
    fun `holding just past the destination is plausible, far past is not`() {
        // intermediate() extrapolates cleanly for f > 1.
        val justPast = GreatCircle.intermediate(lhr, jfk, 1.01)   // ~55 km beyond JFK
        assertTrue(RouteCorridor.isPlausible(lhr, jfk, justPast))
        val farPast = GreatCircle.intermediate(lhr, jfk, 1.2)     // ~1100 km beyond
        assertFalse(RouteCorridor.isPlausible(lhr, jfk, farPast))
    }

    @Test
    fun `short hop keeps the minimum corridor width`() {
        val mid = GreatCircle.intermediate(zrh, gva, 0.5)
        // ~220 km off a ~230 km route: inside the 250 km minimum half-width.
        assertTrue(RouteCorridor.isPlausible(zrh, gva, GreatCircle.Point(mid.lat + 2.0, mid.lon)))
        // ~670 km off: out.
        assertFalse(RouteCorridor.isPlausible(zrh, gva, GreatCircle.Point(mid.lat + 6.0, mid.lon)))
    }

    @Test
    fun `degenerate route falls back to a radius check`() {
        assertTrue(RouteCorridor.isPlausible(zrh, zrh, GreatCircle.Point(zrh.lat + 1.0, zrh.lon)))
        assertFalse(RouteCorridor.isPlausible(zrh, zrh, GreatCircle.Point(zrh.lat + 9.0, zrh.lon)))
    }
}
