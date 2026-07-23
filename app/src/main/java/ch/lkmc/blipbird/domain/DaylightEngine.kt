package ch.lkmc.blipbird.domain

import ch.lkmc.blipbird.core.model.LightBand
import ch.lkmc.blipbird.core.model.RouteSample
import ch.lkmc.blipbird.core.model.SunEvent
import ch.lkmc.blipbird.core.model.SunEventType
import org.shredzone.commons.suncalc.MoonIllumination
import org.shredzone.commons.suncalc.MoonPosition
import org.shredzone.commons.suncalc.SunPosition
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Daylight along the route, fully offline (PLAN.md §9.4).
 *
 * Samples the great-circle guide between wheels-up and wheels-down, evaluates the
 * geometric (true) solar elevation at each point's overflight instant via
 * commons-suncalc, classifies USNO light bands, and locates sunrise/sunset events
 * (bisected to the second; labeled to the minute in UI).
 *
 * When a geometric cruise altitude is known, cabin-visible sunrise/sunset markers
 * apply its horizon-dip correction. Otherwise events use the surface threshold.
 * Twilight band edges stay at their geometric USNO angles.
 */
object DaylightEngine {

    const val SUNRISE_SET_DEG = -0.8333
    const val CIVIL_DEG = -6.0
    const val NAUTICAL_DEG = -12.0
    const val ASTRONOMICAL_DEG = -18.0

    /** Max samples for extreme durations; ~1-minute steps otherwise. */
    private const val MAX_SAMPLES = 2048

    data class Result(
        val samples: List<RouteSample>,
        val events: List<SunEvent>,
        /** Fraction of airborne time in full daylight, 0..1. */
        val daylightFraction: Double,
    )

    fun classify(elevationDeg: Double): LightBand = when {
        elevationDeg >= SUNRISE_SET_DEG -> LightBand.DAY
        elevationDeg >= CIVIL_DEG -> LightBand.CIVIL_TWILIGHT
        elevationDeg >= NAUTICAL_DEG -> LightBand.NAUTICAL_TWILIGHT
        elevationDeg >= ASTRONOMICAL_DEG -> LightBand.ASTRONOMICAL_TWILIGHT
        else -> LightBand.NIGHT
    }

    fun trueSolarElevation(lat: Double, lon: Double, at: Instant): Double =
        SunPosition.compute().on(at).at(lat, lon).execute().trueAltitude

    fun solarAzimuth(lat: Double, lon: Double, at: Instant): Double =
        SunPosition.compute().on(at).at(lat, lon).execute().azimuth

    /** Horizon dip in degrees for a geometric altitude above the surface, refracted (1.75′·√h). */
    fun horizonDipDeg(altitudeMeters: Double): Double =
        if (altitudeMeters <= 0) 0.0 else 1.75 * kotlin.math.sqrt(altitudeMeters) / 60.0

    data class MoonSnapshot(
        /** Illuminated fraction of the lunar disc, 0 (new) .. 1 (full). */
        val illuminatedFraction: Double,
        /** True between new and full moon — the lit limb sits on the right (northern-sky convention). */
        val waxing: Boolean,
        /** Altitude above the local horizon, degrees; ≤ 0 means the moon is not up. */
        val altitudeDeg: Double,
    )

    /** Moon phase and local altitude for the ribbon's night decoration (REVIEW.md I10). */
    fun moon(lat: Double, lon: Double, at: Instant): MoonSnapshot {
        val illumination = MoonIllumination.compute().on(at).execute()
        val position = MoonPosition.compute().on(at).at(lat, lon).execute()
        return MoonSnapshot(
            illuminatedFraction = illumination.fraction,
            // suncalc phase runs -180° (new) → 0° (full) → +180° (next new).
            waxing = illumination.phase < 0.0,
            altitudeDeg = position.altitude,
        )
    }

    /**
     * @param cruiseAltitudeMeters explicit finite, nonnegative geometric cruise altitude
     * for the cabin-visible sunrise/sunset threshold, or null to use the surface threshold.
     */
    fun compute(
        from: GreatCircle.Point,
        to: GreatCircle.Point,
        wheelsUp: Instant,
        wheelsDown: Instant,
        cruiseAltitudeMeters: Double? = null,
    ): Result {
        require(wheelsDown.isAfter(wheelsUp)) { "wheelsDown must be after wheelsUp" }
        require(cruiseAltitudeMeters == null ||
            (cruiseAltitudeMeters.isFinite() && cruiseAltitudeMeters >= 0.0)
        ) { "cruiseAltitudeMeters must be finite and nonnegative" }
        val durationSec = Duration.between(wheelsUp, wheelsDown).seconds
        val steps = minOf(MAX_SAMPLES, maxOf(16, (durationSec / 60).toInt()))

        val samples = ArrayList<RouteSample>(steps + 1)
        for (i in 0..steps) {
            val f = i.toDouble() / steps
            val p = GreatCircle.intermediate(from, to, f)
            val t = wheelsUp.plusSeconds((durationSec * f).toLong())
            val elev = trueSolarElevation(p.lat, p.lon, t)
            samples += RouteSample(f, t, p.lat, p.lon, elev, classify(elev))
        }

        val threshold = SUNRISE_SET_DEG - (cruiseAltitudeMeters?.let { horizonDipDeg(it) } ?: 0.0)
        val events = findCrossings(from, to, wheelsUp, durationSec, samples, threshold, cruiseAltitudeMeters != null)

        val dayCount = samples.count { it.band == LightBand.DAY }
        return Result(samples, events, dayCount.toDouble() / samples.size)
    }

    private fun findCrossings(
        from: GreatCircle.Point,
        to: GreatCircle.Point,
        wheelsUp: Instant,
        durationSec: Long,
        samples: List<RouteSample>,
        thresholdDeg: Double,
        cabinCorrected: Boolean,
    ): List<SunEvent> {
        val events = mutableListOf<SunEvent>()
        fun elevAt(f: Double): Double {
            val p = GreatCircle.intermediate(from, to, f)
            val t = wheelsUp.plusSeconds((durationSec * f).toLong())
            return trueSolarElevation(p.lat, p.lon, t)
        }
        for (i in 1 until samples.size) {
            val e0 = samples[i - 1].solarElevationDeg - thresholdDeg
            val e1 = samples[i].solarElevationDeg - thresholdDeg
            if (e0 * e1 > 0 || e0 == 0.0) continue
            // Bisect the bracketing fractions to ~1 s of flight time.
            var lo = samples[i - 1].fraction
            var hi = samples[i].fraction
            var eLo = e0
            repeat(24) {
                val mid = (lo + hi) / 2
                val eMid = elevAt(mid) - thresholdDeg
                if (eLo * eMid <= 0) hi = mid else { lo = mid; eLo = eMid }
                if ((hi - lo) * durationSec < 1.0) return@repeat
            }
            val f = (lo + hi) / 2
            val p = GreatCircle.intermediate(from, to, f)
            val t = wheelsUp.plusSeconds((durationSec * f).toLong())
            events += SunEvent(
                type = if (e1 > e0) SunEventType.SUNRISE else SunEventType.SUNSET,
                at = t,
                lat = p.lat,
                lon = p.lon,
                azimuthDeg = solarAzimuth(p.lat, p.lon, t),
                cabinVisible = cabinCorrected,
            )
        }
        return events
    }

    // ------------------------------------------------------------------
    // Terminator / twilight iso-lines for the map overlay.
    // Solar declination + GMST from the NOAA/Spencer short form — accurate to a
    // few hundredths of a degree, far tighter than a 1°-longitude polyline needs.
    // ------------------------------------------------------------------

    data class SubsolarPoint(val declinationDeg: Double, val gmstHourAngleDeg: Double)

    fun subsolarPoint(at: Instant): SubsolarPoint {
        val jd = at.toEpochMilli() / 86_400_000.0 + 2_440_587.5
        val d = jd - 2_451_545.0
        // Sun ecliptic longitude (Meeus low precision)
        val g = Math.toRadians((357.529 + 0.98560028 * d) % 360.0)
        val q = (280.459 + 0.98564736 * d) % 360.0
        val l = Math.toRadians(q + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val ra = Math.toDegrees(atan2(cos(e) * sin(l), cos(l)))
        val dec = Math.toDegrees(asin(sin(e) * sin(l)))
        val gmst = (18.697374558 + 24.06570982441908 * d) % 24.0
        val gmstDeg = gmst * 15.0
        return SubsolarPoint(dec, (gmstDeg - ra + 540.0) % 360.0 - 180.0)
    }

    /**
     * Latitude of the iso-elevation curve (solar elevation == [elevationDeg]) at the given
     * longitude, or null where no solution exists (polar day/night at that longitude).
     *
     * Solves sin(e) = sinφ·sinδ + cosφ·cosδ·cos(H) for φ.
     */
    fun isoLatitudeDeg(at: SubsolarPoint, lonDeg: Double, elevationDeg: Double): Double? {
        val dec = Math.toRadians(at.declinationDeg)
        val h = Math.toRadians(at.gmstHourAngleDeg + lonDeg)
        val a = sin(dec)
        val b = cos(dec) * cos(h)
        val r = hypot(a, b)
        val c = sin(Math.toRadians(elevationDeg))
        if (abs(c) > r) return null
        val phi = asin(c / r) - atan2(b, a)
        val deg = Math.toDegrees(phi)
        return when {
            deg in -90.0..90.0 -> deg
            deg > 90.0 -> 180.0 - deg
            else -> -180.0 - deg
        }
    }

    /**
     * Night-side polygon (list of lat/lon pairs, lon from -180..180) for the given
     * elevation threshold, closed over the dark pole — ready to feed a map fill layer.
     */
    fun nightPolygon(at: Instant, elevationDeg: Double = SUNRISE_SET_DEG, stepDeg: Double = 2.0): List<GreatCircle.Point> {
        val sub = subsolarPoint(at)
        val darkPoleLat = if (sub.declinationDeg >= 0) -90.0 else 90.0
        val pts = mutableListOf<GreatCircle.Point>()
        var lon = -180.0
        while (lon <= 180.0) {
            val lat = isoLatitudeDeg(sub, lon, elevationDeg)
            pts += GreatCircle.Point(lat ?: darkPoleLat.let { if (it > 0) 89.9 else -89.9 }, lon)
            lon += stepDeg
        }
        // Close over the dark pole
        pts += GreatCircle.Point(darkPoleLat, 180.0)
        pts += GreatCircle.Point(darkPoleLat, -180.0)
        return pts
    }
}
