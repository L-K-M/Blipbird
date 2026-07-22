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
import kotlin.test.assertNotNull
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

    @Test fun `heavy precipitation decodes (+ prefix not treated as quantifier)`() {
        val d = MetarDecoder.decode("ZSPD 221200Z 18018G30KT 3000 +RA OVC010 22/21 Q1003")
        assertTrue(d.text.contains("heavy rain"), d.text)
    }

    @Test fun `ceiling is first BKN or OVC, not the last layer`() {
        // BKN025 is the ceiling; OVC100 must not win.
        val d = MetarDecoder.decode("KSFO 221656Z 27014KT 10SM BKN025 OVC100 16/11 A2998")
        assertTrue(d.text.contains("Broken clouds at 2,500 ft"), d.text)
        assertFalse(d.text.contains("10,000 ft"), d.text)
    }

    @Test fun `multiple weather phenomena are all reported`() {
        val d = MetarDecoder.decode("VTBD 221200Z 18012KT 4000 TSRA BR BKN010 30/26 Q1006")
        assertTrue(d.text.contains("thunderstorms with rain"), d.text)
        assertTrue(d.text.contains("mist"), d.text)
    }

    @Test fun `MPS wind unit decodes instead of being dropped`() {
        val d = MetarDecoder.decode("ZBAA 221200Z 3506MPS 9999 SCT040 28/08 Q1020")
        assertNotNull(d.windSpeedKt, "MPS wind must not be dropped")
        assertTrue(d.windSpeedKt!! > 0, "6 m/s should convert to >0 kt, got ${d.windSpeedKt}")
        assertTrue(d.text.contains("wind"), d.text)
    }

    @Test fun `unknown input falls back gracefully`() {
        val d = MetarDecoder.decode("XXXX")
        assertEquals("See raw report", d.text)
    }
}
