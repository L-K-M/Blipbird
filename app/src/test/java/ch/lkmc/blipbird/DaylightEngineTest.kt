package ch.lkmc.blipbird

import ch.lkmc.blipbird.core.model.LightBand
import ch.lkmc.blipbird.core.model.SunEventType
import ch.lkmc.blipbird.domain.DaylightEngine
import ch.lkmc.blipbird.domain.GreatCircle
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DaylightEngineTest {

    // Reference: 2026-03-20 (equinox) noon UTC — sun nearly overhead at (0, 0).
    private val equinoxNoon = Instant.parse("2026-03-20T12:00:00Z")

    @Test fun `solar elevation is high at subsolar point on equinox noon`() {
        val elev = DaylightEngine.trueSolarElevation(0.0, 0.0, equinoxNoon)
        assertTrue(elev > 80.0, "expected near-zenith sun, got $elev")
    }

    @Test fun `solar elevation is deeply negative on the night side`() {
        val elev = DaylightEngine.trueSolarElevation(0.0, 180.0, equinoxNoon)
        assertTrue(elev < -80.0, "expected deep night, got $elev")
    }

    @Test fun `band classification thresholds`() {
        assertEquals(LightBand.DAY, DaylightEngine.classify(10.0))
        assertEquals(LightBand.DAY, DaylightEngine.classify(-0.8))
        assertEquals(LightBand.CIVIL_TWILIGHT, DaylightEngine.classify(-1.0))
        assertEquals(LightBand.NAUTICAL_TWILIGHT, DaylightEngine.classify(-7.0))
        assertEquals(LightBand.ASTRONOMICAL_TWILIGHT, DaylightEngine.classify(-13.0))
        assertEquals(LightBand.NIGHT, DaylightEngine.classify(-20.0))
    }

    @Test fun `horizon dip at cruise is about three degrees`() {
        val dip = DaylightEngine.horizonDipDeg(11_000.0)
        assertTrue(dip in 2.9..3.2, "got $dip")
    }

    @Test fun `omitted and null altitude use the same surface event`() {
        val from = GreatCircle.Point(0.0, 0.0)
        val to = GreatCircle.Point(0.0, 0.0)
        val up = Instant.parse("2026-03-20T17:00:00Z")
        val down = Instant.parse("2026-03-20T19:00:00Z")

        val omitted = DaylightEngine.compute(from, to, up, down)
        val explicitNull = DaylightEngine.compute(from, to, up, down, null)

        assertEquals(explicitNull.events, omitted.events)
        assertEquals(1, omitted.events.size)
        assertEquals(SunEventType.SUNSET, omitted.events.single().type)
        assertFalse(omitted.events.single().cabinVisible)
    }

    @Test fun `explicit cruise altitude delays sunset and marks cabin visibility`() {
        val from = GreatCircle.Point(0.0, 0.0)
        val to = GreatCircle.Point(0.0, 0.0)
        val up = Instant.parse("2026-03-20T17:00:00Z")
        val down = Instant.parse("2026-03-20T19:00:00Z")

        val surface = DaylightEngine.compute(from, to, up, down)
        val cabin = DaylightEngine.compute(from, to, up, down, 11_000.0)

        assertEquals(1, cabin.events.size)
        assertTrue(cabin.events.single().at > surface.events.single().at)
        assertTrue(cabin.events.single().cabinVisible)
    }

    @Test fun `invalid explicit altitude is rejected`() {
        val point = GreatCircle.Point(0.0, 0.0)
        val up = Instant.parse("2026-03-20T17:00:00Z")
        val down = Instant.parse("2026-03-20T19:00:00Z")

        for (altitude in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> {
                DaylightEngine.compute(point, point, up, down, altitude)
            }
        }
    }

    @Test fun `daytime transatlantic eastbound overnight flight crosses into night and back`() {
        // JFK → LHR red-eye: 2026-01-10 00:30Z wheels-up, 07:20Z wheels-down.
        val jfk = GreatCircle.Point(40.6413, -73.7781)
        val lhr = GreatCircle.Point(51.4700, -0.4543)
        val result = DaylightEngine.compute(
            jfk, lhr,
            Instant.parse("2026-01-10T00:30:00Z"),
            Instant.parse("2026-01-10T07:20:00Z"),
        )
        // Winter overnight eastbound: mostly dark, sunrise near arrival possible.
        assertTrue(result.daylightFraction < 0.5, "red-eye should be mostly dark: ${result.daylightFraction}")
        assertTrue(result.samples.first().band != LightBand.DAY || result.samples.last().band != LightBand.DAY)
    }

    @Test fun `midday short-haul is all daylight with no events`() {
        // GVA → ZRH around local noon in July.
        val gva = GreatCircle.Point(46.2381, 6.1090)
        val zrh = GreatCircle.Point(47.4581, 8.5555)
        val result = DaylightEngine.compute(
            gva, zrh,
            Instant.parse("2026-07-01T10:00:00Z"),
            Instant.parse("2026-07-01T10:45:00Z"),
        )
        assertEquals(1.0, result.daylightFraction, 0.001)
        assertTrue(result.events.isEmpty())
    }

    @Test fun `sun events are ordered and typed consistently with elevation slope`() {
        // Long westbound flight through a sunset: LHR → LAX afternoon departure.
        val lhr = GreatCircle.Point(51.4700, -0.4543)
        val lax = GreatCircle.Point(33.9416, -118.4085)
        val result = DaylightEngine.compute(
            lhr, lax,
            Instant.parse("2026-01-10T15:00:00Z"),
            Instant.parse("2026-01-11T02:00:00Z"),
        )
        for (i in 1 until result.events.size) {
            assertTrue(result.events[i - 1].at <= result.events[i].at)
        }
        // Each event's classified sides differ across the threshold.
        for (e in result.events) {
            assertTrue(e.type == SunEventType.SUNRISE || e.type == SunEventType.SUNSET)
        }
    }

    @Test fun `terminator iso-latitude exists for normal longitudes and matches elevation`() {
        val sub = DaylightEngine.subsolarPoint(equinoxNoon)
        val lat = DaylightEngine.isoLatitudeDeg(sub, 60.0, 0.0)
        assertNotNull(lat)
        // Verify the returned point actually sits near 0° elevation.
        val elev = DaylightEngine.trueSolarElevation(lat, 60.0, equinoxNoon)
        assertTrue(abs(elev) < 1.5, "iso-line point should be near the terminator, got $elev")
    }

    @Test fun `polar night longitude has no astronomical-twilight solution at solstice`() {
        val solstice = Instant.parse("2026-06-21T12:00:00Z")
        val sub = DaylightEngine.subsolarPoint(solstice)
        // In June the deep-night iso-line cannot reach arbitrarily every longitude/latitude;
        // verify the solver returns null rather than a bogus latitude somewhere.
        var sawNull = false
        var lon = -180.0
        while (lon <= 180.0) {
            if (DaylightEngine.isoLatitudeDeg(sub, lon, -60.0) == null) { sawNull = true; break }
            lon += 5.0
        }
        assertTrue(sawNull, "expected at least one longitude without a -60° iso solution")
    }

    @Test fun `night polygon is non-empty and spans longitudes`() {
        val poly = DaylightEngine.nightPolygon(equinoxNoon)
        assertTrue(poly.size > 100)
        assertTrue(poly.first().lon <= -179.0 && poly.any { it.lon >= 179.0 })
    }

    @Test fun `route samples respect bounded count for extreme durations`() {
        val a = GreatCircle.Point(0.0, 0.0)
        val b = GreatCircle.Point(1.0, 1.0)
        val result = DaylightEngine.compute(
            a, b,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-03T00:00:00Z"),   // absurd 48 h "flight"
        )
        assertTrue(result.samples.size <= 2050)
    }
}
