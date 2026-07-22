package ch.lkmc.blipbird

import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.domain.CadencePolicy
import ch.lkmc.blipbird.domain.GreatCircle
import ch.lkmc.blipbird.domain.MetarDecoder
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GreatCircleTest {

    private val jfk = GreatCircle.Point(40.6413, -73.7781)
    private val lhr = GreatCircle.Point(51.4700, -0.4543)

    @Test fun `jfk to lhr distance is about 5540 km`() {
        val d = GreatCircle.distanceKm(jfk, lhr)
        assertTrue(abs(d - 5540) < 60, "got $d")
    }

    @Test fun `intermediate endpoints match inputs`() {
        val p0 = GreatCircle.intermediate(jfk, lhr, 0.0)
        val p1 = GreatCircle.intermediate(jfk, lhr, 1.0)
        assertTrue(abs(p0.lat - jfk.lat) < 1e-6 && abs(p1.lat - lhr.lat) < 1e-6)
    }

    @Test fun `great circle arcs poleward on north atlantic route`() {
        val mid = GreatCircle.intermediate(jfk, lhr, 0.5)
        assertTrue(mid.lat > 51.0, "north atlantic midpoint should be above both endpoints: ${mid.lat}")
    }

    @Test fun `antimeridian route splits into segments`() {
        val nrt = GreatCircle.Point(35.7653, 140.3856)
        val lax = GreatCircle.Point(33.9416, -118.4085)
        val segments = GreatCircle.routeSegments(nrt, lax)
        assertTrue(segments.size >= 2, "pacific route should split at the antimeridian")
    }

    @Test fun `zero distance is safe`() {
        val p = GreatCircle.intermediate(jfk, jfk, 0.5)
        assertEquals(jfk.lat, p.lat, 1e-9)
    }

    @Test fun `near-antipodal intermediate does not blow up`() {
        // Antipodal along the equator: angularDistance == PI, sin(d) ~ 0, so the
        // slerp divided by ~0 and returned garbage before the guard.
        val a = GreatCircle.Point(0.0, 0.0)
        val b = GreatCircle.Point(0.0, 180.0)
        val mid = GreatCircle.intermediate(a, b, 0.5)
        assertFalse(mid.lat.isNaN() || mid.lon.isNaN(), "antipodal must not be NaN: $mid")
        assertTrue(mid.lat in -90.0..90.0 && mid.lon in -180.0..180.0, "antipodal must be bounded: $mid")
    }

    @Test fun `antimeridian segments meet at the dateline`() {
        val nrt = GreatCircle.Point(35.7653, 140.3856)
        val lax = GreatCircle.Point(33.9416, -118.4085)
        val segments = GreatCircle.routeSegments(nrt, lax)
        assertTrue(segments.size >= 2, "pacific route should split at the antimeridian")
        val left = segments[0].last()
        val right = segments[1].first()
        assertTrue(kotlin.math.abs(left.lon) > 179.0 && kotlin.math.abs(right.lon) > 179.0,
            "dateline vertices expected near ±180: $left / $right")
        assertTrue(kotlin.math.abs(left.lat - right.lat) < 1.0,
            "dateline crossing latitude should match across the split: ${left.lat} vs ${right.lat}")
    }
}

class CadencePolicyTest {

    private val now: Instant = Instant.parse("2026-07-22T12:00:00Z")

    @Test fun `beyond 48h no background refresh`() {
        assertNull(CadencePolicy.nextInterval(FlightStatus.SCHEDULED, now.plus(Duration.ofHours(72)), null, false, now))
    }

    @Test fun `gate-critical window is 15 minutes`() {
        assertEquals(
            Duration.ofMinutes(15),
            CadencePolicy.nextInterval(FlightStatus.ON_TIME, now.plus(Duration.ofMinutes(60)), null, false, now),
        )
    }

    @Test fun `mid-cruise is 2 hours`() {
        assertEquals(
            Duration.ofHours(2),
            CadencePolicy.nextInterval(
                FlightStatus.EN_ROUTE,
                now.minus(Duration.ofHours(3)),
                now.plus(Duration.ofHours(5)),
                false, now,
            ),
        )
    }

    @Test fun `approach tightens to 15 minutes`() {
        assertEquals(
            Duration.ofMinutes(15),
            CadencePolicy.nextInterval(
                FlightStatus.EN_ROUTE,
                now.minus(Duration.ofHours(8)),
                now.plus(Duration.ofMinutes(30)),
                false, now,
            ),
        )
    }

    @Test fun `arrived and resolved stops refreshing`() {
        assertNull(CadencePolicy.nextInterval(FlightStatus.ARRIVED, now.minus(Duration.ofHours(9)), now.minus(Duration.ofMinutes(20)), true, now))
    }

    @Test fun `cancelled stops refreshing`() {
        assertNull(CadencePolicy.nextInterval(FlightStatus.CANCELLED, now.plus(Duration.ofHours(5)), null, false, now))
    }

    @Test fun `arrival deadline caps monitoring`() {
        val deadline = CadencePolicy.arrivalMonitoringDeadline(null, now)
        assertEquals(now.plus(Duration.ofHours(4)), deadline)
    }
}

class MetarDecoderTest {

    @Test fun `decodes wind temperature and clouds`() {
        val d = MetarDecoder.decode("LSGG 221220Z 24012G22KT 9999 BKN025 21/12 Q1018 NOSIG")
        assertTrue(d.text.contains("Broken clouds at 2,500 ft"), d.text)
        assertTrue(d.text.contains("wind 12 kt gusting 22"), d.text)
        assertEquals(21.0, d.temperatureC)
        assertEquals(240, d.windDirDeg)
        assertEquals(12, d.windSpeedKt)
        assertEquals(22, d.windGustKt)
    }

    @Test fun `decodes negative temperature`() {
        val d = MetarDecoder.decode("ZBAA 221200Z 36008KT CAVOK M05/M12 Q1030")
        assertEquals(-5.0, d.temperatureC)
        assertTrue(d.text.contains("clear skies", ignoreCase = true), d.text)
    }

    @Test fun `weather phenomena appear`() {
        val d = MetarDecoder.decode("EGLL 221150Z 25010KT 4000 -RA BKN008 14/12 Q1008")
        assertTrue(d.text.contains("light rain"), d.text)
    }

    @Test fun `unknown input falls back gracefully`() {
        val d = MetarDecoder.decode("XXXX")
        assertEquals("See raw report", d.text)
    }
}
