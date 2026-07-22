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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import ch.lkmc.blipbird.core.model.LightBand
import ch.lkmc.blipbird.core.model.SunEvent
import ch.lkmc.blipbird.core.model.SunEventType
import ch.lkmc.blipbird.core.model.WeatherSample
import ch.lkmc.blipbird.domain.DaylightEngine
import ch.lkmc.blipbird.ui.components.localTime
import ch.lkmc.blipbird.ui.theme.LocalExtendedColors

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

    Column(modifier) {
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

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(26.dp),
        ) {
            val w = size.width
            val h = size.height
            val samples = daylight.samples
            if (samples.size < 2) return@Canvas

            // Continuous gradient from per-sample light colors
            val stops = samples.filterIndexed { i, _ -> i % 8 == 0 || i == samples.lastIndex }
            val brush = Brush.horizontalGradient(
                colorStops = stops.map { s ->
                    s.fraction.toFloat() to bandColor(s.solarElevationDeg, ext.ribbonDay, ext.ribbonDusk, ext.ribbonNight)
                }.toTypedArray(),
            )
            drawRoundRect(brush = brush, size = Size(w, h), cornerRadius = CornerRadius(h / 2))

            // Sunrise/sunset markers
            for (event in daylight.events) {
                val f = fractionOf(event, daylight)
                val x = (f * w).toFloat()
                drawCircle(
                    color = if (event.type == SunEventType.SUNRISE) Color(0xFFFFD54F) else Color(0xFFFF8A65),
                    radius = h * 0.28f,
                    center = Offset(x, h / 2),
                )
            }

            // Aircraft position marker
            if (progress in 0.01f..0.995f) {
                val x = progress * w
                drawCircle(Color.White, radius = h * 0.34f, center = Offset(x, h / 2))
                drawCircle(Color(0xFF1667D9), radius = h * 0.34f, center = Offset(x, h / 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = h * 0.09f))
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(depCode, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            daylight.events.forEach { e ->
                Text(
                    (if (e.type == SunEventType.SUNRISE) "🌅 " else "🌇 ") + localTime(e.at),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(arrCode, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun fractionOf(event: SunEvent, daylight: DaylightEngine.Result): Double {
    val first = daylight.samples.first().at.epochSecond
    val last = daylight.samples.last().at.epochSecond
    if (last == first) return 0.0
    return ((event.at.epochSecond - first).toDouble() / (last - first)).coerceIn(0.0, 1.0)
}

/** Continuous color from solar elevation: warm day → orange dusk → deep night. */
private fun bandColor(elevationDeg: Double, day: Color, dusk: Color, night: Color): Color = when {
    elevationDeg >= 10.0 -> day
    elevationDeg >= DaylightEngine.SUNRISE_SET_DEG ->
        lerp(dusk, day, ((elevationDeg - DaylightEngine.SUNRISE_SET_DEG) / (10.0 - DaylightEngine.SUNRISE_SET_DEG)).toFloat())
    elevationDeg >= DaylightEngine.NAUTICAL_DEG ->
        lerp(night, dusk, ((elevationDeg - DaylightEngine.NAUTICAL_DEG) / (DaylightEngine.SUNRISE_SET_DEG - DaylightEngine.NAUTICAL_DEG)).toFloat())
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
