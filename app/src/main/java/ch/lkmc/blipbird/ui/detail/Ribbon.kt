package ch.lkmc.blipbird.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.model.LightBand
import ch.lkmc.blipbird.core.model.SunEvent
import ch.lkmc.blipbird.core.model.SunEventType
import ch.lkmc.blipbird.core.model.WeatherSample
import ch.lkmc.blipbird.domain.DaylightEngine
import ch.lkmc.blipbird.domain.GreatCircle
import ch.lkmc.blipbird.ui.components.localTime
import ch.lkmc.blipbird.ui.theme.LocalExtendedColors
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The flight ribbon (PLAN.md §9.4): a horizontal strip of the whole flight,
 * colored continuously by solar elevation at overflight time, with sunrise/sunset
 * markers and weather glyphs at sampled waypoints.
 */
@Composable
fun FlightRibbon(
    daylight: DaylightEngine.Result,
    weather: List<WeatherSample>,
    depCode: String,
    arrCode: String,
    modifier: Modifier = Modifier,
    /** 0..1 flight progress; >0 draws the aircraft position marker on the strip. */
    progress: Float = 0f,
) {
    val ext = LocalExtendedColors.current
    val eventDescriptions = mutableListOf<String>()
    for (event in daylight.events) {
        eventDescriptions += stringResource(
            if (event.type == SunEventType.SUNRISE) R.string.sunrise_at else R.string.sunset_at,
            localTime(event.at),
        )
    }
    val eventsSummary = eventDescriptions.joinToString(", ")
    val progressPercent = (progress.coerceIn(0f, 1f) * 100).roundToInt()
    val daylightPercent = (daylight.daylightFraction.coerceIn(0.0, 1.0) * 100).roundToInt()
    val summary = if (eventsSummary.isEmpty()) {
        stringResource(R.string.ribbon_summary, depCode, arrCode, progressPercent, daylightPercent)
    } else {
        stringResource(
            R.string.ribbon_summary_with_events,
            depCode,
            arrCode,
            progressPercent,
            daylightPercent,
            eventsSummary,
        )
    }

    Column(modifier.clearAndSetSemantics { contentDescription = summary }) {
        // Weather glyph row above the gradient
        if (weather.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                weather.forEachIndexed { i, w ->
                    Text(
                        weatherGlyph(w.weatherCode, w.cloudCoverPct),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
        }

        // Continuous gradient computed once per daylight/theme change, not on
        // every draw (the old code rebuilt the Brush inside the Canvas lambda on
        // every recomposition / position poll).
        val ribbonBrush = remember(daylight, ext.ribbonDay, ext.ribbonDusk, ext.ribbonNight) {
            val samples = daylight.samples
            if (samples.size < 2) null else {
                val stops = samples.filterIndexed { i, _ -> i % 8 == 0 || i == samples.lastIndex }
                Brush.horizontalGradient(
                    colorStops = stops.map { s ->
                        s.fraction.toFloat() to bandColor(s.solarElevationDeg, ext.ribbonDay, ext.ribbonDusk, ext.ribbonNight)
                    }.toTypedArray(),
                )
            }
        }

        // Night decoration (I10): star field on the fully dark segments and a
        // moon glyph with its current phase — computed once per daylight change,
        // like the gradient above.
        val nightDecor = remember(daylight) { nightDecor(daylight) }

        // Reused across draws so the aircraft marker doesn't allocate a Path on
        // every position tick.
        val planePath = remember { Path() }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(26.dp),
        ) {
            val w = size.width
            val h = size.height
            if (ribbonBrush != null) {
                drawRoundRect(brush = ribbonBrush, size = Size(w, h), cornerRadius = CornerRadius(h / 2))
            }

            if (nightDecor.stars.isNotEmpty() || nightDecor.moon != null) {
                // Clip to the pill so edge-of-ribbon stars respect the rounded ends.
                val pill = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(h / 2, h / 2)))
                }
                clipPath(pill) {
                    for (star in nightDecor.stars) {
                        drawCircle(
                            color = ext.ribbonStars,
                            radius = h * star.radiusFrac,
                            center = Offset(star.xFrac * w, star.yFrac * h),
                            alpha = star.alpha,
                        )
                    }
                    nightDecor.moon?.let { moon ->
                        val center = Offset(moon.xFrac * w, h / 2)
                        val r = h * 0.22f
                        drawCircle(ext.ribbonMoon, radius = r, center = center)
                        if (moon.illuminatedFraction < 0.97f) {
                            // Carve the unlit part: a night-colored disc slides
                            // off the lit one (offset 0 = new, 2r = full); the
                            // lit limb stays on the right while waxing.
                            val shadowCenter = center + Offset(
                                (if (moon.waxing) -1f else 1f) * 2f * r * moon.illuminatedFraction, 0f,
                            )
                            val disc = Path().apply {
                                addOval(Rect(center - Offset(r, r), Size(2 * r, 2 * r)))
                            }
                            clipPath(disc) {
                                drawCircle(ext.ribbonNight, radius = r, center = shadowCenter)
                            }
                        }
                    }
                }
            }

            // Sunrise/sunset markers (theme-aware, not hardcoded)
            for (event in daylight.events) {
                val f = fractionOf(event, daylight)
                val x = (f * w).toFloat()
                drawCircle(
                    color = if (event.type == SunEventType.SUNRISE) ext.ribbonSunrise else ext.ribbonSunset,
                    radius = h * 0.28f,
                    center = Offset(x, h / 2),
                )
            }

            // Aircraft position marker: a little plane pointing along the strip
            // (dep→arr is left→right, so progress advances rightward), haloed in
            // white so it stays legible over any band from bright day to night.
            if (progress in 0.01f..0.995f) {
                val x = progress * w
                val cy = h / 2
                val s = h * 0.40f
                planePath.rewind()
                planePath.moveTo(x, cy - s)                 // nose (pre-rotation: up)
                planePath.lineTo(x + s * 0.72f, cy + s * 0.55f)
                planePath.lineTo(x, cy + s * 0.22f)
                planePath.lineTo(x - s * 0.72f, cy + s * 0.55f)
                planePath.close()
                // Rotate 90° so the nose points right, the direction of travel.
                rotate(degrees = 90f, pivot = Offset(x, cy)) {
                    drawPath(planePath, Color.White, style = Stroke(width = h * 0.14f))
                    drawPath(planePath, ext.ribbonAircraft)
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(depCode, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            val leftSide = stringResource(R.string.ribbon_side_left)
            val rightSide = stringResource(R.string.ribbon_side_right)
            daylight.events.forEach { e ->
                // The sun's azimuth vs the course at the event point tells the
                // passenger which side of the cabin the show is on. Non-emoji
                // arrows (U+2191/U+2193) — color emoji 🌅/🌇 render as tofu on
                // several OEM fonts; color matches the canvas marker.
                val side = when (cabinSide(e, daylight)) {
                    CabinSide.LEFT -> " · $leftSide"
                    CabinSide.RIGHT -> " · $rightSide"
                    null -> ""
                }
                val arrow = if (e.type == SunEventType.SUNRISE) "↑ " else "↓ "
                Text(
                    arrow + localTime(e.at) + side,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (e.type == SunEventType.SUNRISE) ext.ribbonSunrise else ext.ribbonSunset,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(arrCode, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private enum class CabinSide { LEFT, RIGHT }

/**
 * Which side of the cabin a sun event is visible from, or null when the sun sits
 * near dead ahead/astern (within ~20°), where naming a side would mislead.
 * Course is estimated from the route samples bracketing the event instant.
 */
private fun cabinSide(event: SunEvent, daylight: DaylightEngine.Result): CabinSide? {
    val samples = daylight.samples
    if (samples.size < 2) return null
    var idx = samples.indexOfFirst { it.at > event.at }
    if (idx == -1) idx = samples.lastIndex
    if (idx == 0) idx = 1
    val a = samples[idx - 1]
    val b = samples[idx]
    val course = GreatCircle.initialBearing(
        GreatCircle.Point(a.lat, a.lon),
        GreatCircle.Point(b.lat, b.lon),
    )
    val relative = (event.azimuthDeg - course + 360.0) % 360.0
    return when {
        relative in 20.0..160.0 -> CabinSide.RIGHT
        relative in 200.0..340.0 -> CabinSide.LEFT
        else -> null
    }
}

private fun fractionOf(event: SunEvent, daylight: DaylightEngine.Result): Double {
    val first = daylight.samples.first().at.epochSecond
    val last = daylight.samples.last().at.epochSecond
    if (last == first) return 0.0
    return ((event.at.epochSecond - first).toDouble() / (last - first)).coerceIn(0.0, 1.0)
}

/** Precomputed night-segment decoration (I10): star field plus a phase-correct moon glyph. */
private data class NightDecor(val stars: List<Star>, val moon: Moon?) {
    data class Star(val xFrac: Float, val yFrac: Float, val radiusFrac: Float, val alpha: Float)
    data class Moon(val xFrac: Float, val illuminatedFraction: Float, val waxing: Boolean)
}

private fun nightDecor(daylight: DaylightEngine.Result): NightDecor {
    val samples = daylight.samples
    if (samples.size < 2) return NightDecor(emptyList(), null)

    // Contiguous fraction ranges where the ribbon has bottomed out at the solid
    // night color — below nautical twilight, matching bandColor() above.
    val runs = mutableListOf<Pair<Double, Double>>()
    var runStart: Double? = null
    for (sample in samples) {
        val dark = sample.solarElevationDeg < DaylightEngine.NAUTICAL_DEG
        if (dark && runStart == null) runStart = sample.fraction
        if (!dark && runStart != null) {
            runs += runStart to sample.fraction
            runStart = null
        }
    }
    runStart?.let { runs += it to samples.last().fraction }

    // Deterministic star field — seeded on wheels-up so it's stable for the
    // flight instead of reshuffling on every recomposition.
    val random = Random(samples.first().at.epochSecond)
    val stars = mutableListOf<NightDecor.Star>()
    for ((start, end) in runs) {
        val width = end - start
        if (width < 0.03) continue
        val count = (width * 46).roundToInt().coerceAtLeast(2)
        repeat(count) {
            stars += NightDecor.Star(
                // Inset from the run edges so no star sits on the twilight blend.
                xFrac = (start + width * (0.06 + 0.88 * random.nextDouble())).toFloat(),
                yFrac = 0.18f + 0.64f * random.nextFloat(),
                radiusFrac = 0.035f + 0.03f * random.nextFloat(),
                alpha = 0.45f + 0.5f * random.nextFloat(),
            )
        }
    }

    // Moon at the middle of the longest dark run — only when it's actually up there.
    val moon = runs.maxByOrNull { it.second - it.first }
        ?.takeIf { it.second - it.first >= 0.05 }
        ?.let { (start, end) ->
            val midFraction = (start + end) / 2
            val sample = samples[samples.indexOfLast { it.fraction <= midFraction }.coerceAtLeast(0)]
            val snapshot = DaylightEngine.moon(sample.lat, sample.lon, sample.at)
            if (snapshot.altitudeDeg > 0.0) NightDecor.Moon(
                xFrac = midFraction.toFloat(),
                illuminatedFraction = snapshot.illuminatedFraction.toFloat(),
                waxing = snapshot.waxing,
            ) else null
        }

    return NightDecor(stars, moon)
}

/** Continuous color from solar elevation: warm day → orange dusk → civil twilight → night. */
private fun bandColor(elevationDeg: Double, day: Color, dusk: Color, night: Color): Color = when {
    elevationDeg >= 10.0 -> day
    elevationDeg >= DaylightEngine.SUNRISE_SET_DEG ->
        lerp(dusk, day, ((elevationDeg - DaylightEngine.SUNRISE_SET_DEG) / (10.0 - DaylightEngine.SUNRISE_SET_DEG)).toFloat())
    // Civil twilight as a solid dusk band so the ribbon shows the -6° transition
    // the LightBand enum models (the old code lerped night↔dusk straight across
    // [-12, -0.833] and hid it).
    elevationDeg >= DaylightEngine.CIVIL_DEG -> dusk
    elevationDeg >= DaylightEngine.NAUTICAL_DEG ->
        lerp(night, dusk, ((elevationDeg - DaylightEngine.NAUTICAL_DEG) / (DaylightEngine.CIVIL_DEG - DaylightEngine.NAUTICAL_DEG)).toFloat())
    else -> night
}

/**
 * WMO weather code → glyph; cloud cover fallback. Restricted to widely-supported
 * emoji — 🌤/🌫/🌨-style compound glyphs render as tofu boxes on several OEM fonts.
 */
fun weatherGlyph(code: Int?, cloudPct: Int?): String = when (code) {
    0 -> "☀️"
    1, 2 -> "⛅"
    3 -> "☁️"
    45, 48 -> "☁️"
    in 51..57 -> "🌧️"
    in 61..67 -> "🌧️"
    in 71..77 -> "❄️"
    in 80..82 -> "🌧️"
    85, 86 -> "❄️"
    in 95..99 -> "⚡"
    else -> when {
        cloudPct == null -> "·"
        cloudPct >= 80 -> "☁️"
        cloudPct >= 40 -> "⛅"
        else -> "☀️"
    }
}
