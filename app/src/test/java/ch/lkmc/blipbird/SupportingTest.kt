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
        assertTrue(d.text.contains("wind 240° 12 kt gusting 22"), d.text)
        assertEquals(21.0, d.temperatureC)
        assertEquals(240, d.windDirDeg)
        assertEquals(12, d.windSpeedKt)
        assertEquals(22, d.windGustKt)
        // 9999 = unrestricted visibility, not worth a mention
        assertTrue(!d.text.contains("visibility"), d.text)
    }

    @Test fun `decodes negative temperature`() {
        val d = MetarDecoder.decode("ZBAA 221200Z 36008KT CAVOK M05/M12 Q1030")
        assertEquals(-5.0, d.temperatureC)
        assertTrue(d.text.contains("clear skies", ignoreCase = true), d.text)
    }

    @Test fun `weather phenomena appear with visibility`() {
        val d = MetarDecoder.decode("EGLL 221150Z 25010KT 4000 -RA BKN008 14/12 Q1008")
        assertTrue(d.text.contains("light rain"), d.text)
        assertTrue(d.text.contains("visibility 4 km"), d.text)
    }

    @Test fun `heavy precipitation and multiple phenomena decode`() {
        // "+RA" previously never matched (the + became a regex quantifier), and
        // only the first phenomenon was reported.
        val d = MetarDecoder.decode("KJFK 221151Z 04008KT 1200 +RA BR OVC005 18/17 A2992")
        assertTrue(d.text.contains("heavy rain"), d.text)
        assertTrue(d.text.contains("mist"), d.text)
        assertTrue(d.text.contains("visibility 1.2 km"), d.text)
    }

    @Test fun `calm and variable winds read naturally`() {
        val calm = MetarDecoder.decode("LFPG 221200Z 00000KT CAVOK 22/10 Q1020")
        assertTrue(calm.text.contains("calm wind"), calm.text)
        val vrb = MetarDecoder.decode("LFPG 221200Z VRB03KT CAVOK 22/10 Q1020")
        assertTrue(vrb.text.contains("variable wind 3 kt"), vrb.text)
        assertEquals(3, vrb.windSpeedKt)
        assertEquals(null, vrb.windDirDeg)
    }

    @Test fun `statute-mile visibility decodes when reduced`() {
        val d = MetarDecoder.decode("KSFO 221156Z 28006KT 1/2SM FG OVC002 12/11 A3001")
        assertTrue(d.text.contains("fog"), d.text)
        assertTrue(d.text.contains("visibility 0.5 mi"), d.text)
    }

    @Test fun `convective cloud suffixes surface`() {
        val cb = MetarDecoder.decode("LSZH 221220Z 24012KT 9999 BKN025CB 21/12 Q1018")
        assertTrue(cb.text.contains("Broken clouds at 2,500 ft (cumulonimbus)"), cb.text)
        val tcu = MetarDecoder.decode("LSZH 221220Z 24012KT 9999 SCT040TCU 21/12 Q1018")
        assertTrue(tcu.text.contains("Scattered clouds at 4,000 ft (towering cumulus)"), tcu.text)
    }

    @Test fun `mixed-number statute-mile visibility joins across the space`() {
        val d = MetarDecoder.decode("KSFO 221156Z 28006KT 1 1/2SM BR OVC004 12/11 A3001")
        assertTrue(d.text.contains("visibility 1.5 mi"), d.text)
    }

    @Test fun `quarter-mile fractions keep their precision`() {
        val d = MetarDecoder.decode("KSFO 221156Z 28006KT 3/4SM FG OVC002 12/11 A3001")
        assertTrue(d.text.contains("visibility 0.75 mi"), d.text)
    }

    @Test fun `unknown intensity variants fall back to the base phenomenon`() {
        val d = MetarDecoder.decode("UUEE 221200Z 36008KT 2000 +DZ OVC003 04/03 Q1002")
        assertTrue(d.text.contains("heavy drizzle"), d.text)
    }

    @Test fun `mps winds convert to knots`() {
        val d = MetarDecoder.decode("UUEE 221200Z 36010G20MPS 9999 OVC020 04/03 Q1002")
        assertEquals(19, d.windSpeedKt)
        assertEquals(39, d.windGustKt)
        assertTrue(d.text.contains("wind 360° 19 kt gusting 39"), d.text)
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
        // Standard MPS group is 3-digit direction + 2-digit speed: 350° at 6 m/s.
        val d = MetarDecoder.decode("ZBAA 221200Z 35006MPS 9999 SCT040 28/08 Q1020")
        assertNotNull(d.windSpeedKt, "MPS wind must not be dropped")
        assertTrue(d.windSpeedKt!! > 0, "6 m/s should convert to >0 kt, got ${d.windSpeedKt}")
        assertTrue(d.text.contains("wind"), d.text)
    }

    @Test fun `unknown input falls back gracefully`() {
        val d = MetarDecoder.decode("XXXX")
        assertEquals("See raw report", d.text)
    }
}
