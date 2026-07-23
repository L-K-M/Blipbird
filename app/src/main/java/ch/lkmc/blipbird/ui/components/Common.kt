package ch.lkmc.blipbird.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.ui.theme.LocalExtendedColors
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Localized status word, shared by the chip and plain-text uses (share sheet). */
@Composable
fun statusText(status: FlightStatus): String = stringResource(
    when (status) {
        FlightStatus.SCHEDULED -> R.string.status_scheduled
        FlightStatus.ON_TIME -> R.string.status_on_time
        FlightStatus.DELAYED -> R.string.status_delayed
        FlightStatus.DEPARTED -> R.string.status_departed
        FlightStatus.EN_ROUTE -> R.string.status_en_route
        FlightStatus.APPROACHING -> R.string.status_approaching
        FlightStatus.LANDED -> R.string.status_landed
        FlightStatus.ARRIVED -> R.string.status_arrived
        FlightStatus.CANCELLED -> R.string.status_cancelled
        FlightStatus.DIVERTED -> R.string.status_diverted
        FlightStatus.UNKNOWN -> R.string.status_unknown
    }
)

/** Status word chip — always word + color, never color alone (accessibility). */
@Composable
fun StatusWord(status: FlightStatus) {
    val ext = LocalExtendedColors.current
    val text = statusText(status)
    val color = when (status) {
        FlightStatus.SCHEDULED -> ext.statusNeutral
        FlightStatus.ON_TIME -> ext.statusOnTime
        FlightStatus.DELAYED -> ext.statusDelayed
        FlightStatus.DEPARTED, FlightStatus.EN_ROUTE, FlightStatus.APPROACHING -> ext.statusEnRoute
        FlightStatus.LANDED -> ext.statusNeutral
        FlightStatus.ARRIVED -> ext.statusOnTime
        FlightStatus.CANCELLED -> ext.statusCancelled
        FlightStatus.DIVERTED -> ext.statusDelayed
        FlightStatus.UNKNOWN -> ext.statusNeutral
    }
    Text(
        text.uppercase(),
        color = statusContentColor(color),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Select the WCAG black/white foreground with the higher contrast ratio. */
internal fun statusContentColor(background: Color): Color =
    if (contrastRatio(Color.Black, background) >= contrastRatio(Color.White, background)) Color.Black else Color.White

internal fun contrastRatio(foreground: Color, background: Color): Double {
    fun luminance(color: Color): Double {
        fun linear(component: Float): Double {
            val value = component.toDouble()
            return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }

    val lighter = maxOf(luminance(foreground), luminance(background))
    val darker = minOf(luminance(foreground), luminance(background))
    return (lighter + 0.05) / (darker + 0.05)
}

/**
 * Countdown with adaptive granularity: days → h m → m. Past-due targets clamp to
 * "0m" — "Lands in -2h 15m" (stale data) is never shown to the user.
 */
fun countdownText(d: Duration): String {
    val s = d.seconds.coerceAtLeast(0)
    return when {
        s >= 172_800 -> "${s / 86_400}d ${(s % 86_400) / 3600}h"
        s >= 3_600 -> "${s / 3600}h ${(s % 3600) / 60}m"
        else -> "${s / 60}m"
    }
}

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

fun localTime(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    TIME_FMT.withZone(zone).format(at)

fun agoText(from: Instant, now: Instant = Instant.now()): String {
    val d = Duration.between(from, now)
    return when {
        d.toMinutes() < 1 -> "${d.seconds}s"
        d.toHours() < 1 -> "${d.toMinutes()}m"
        d.toDays() < 1 -> "${d.toHours()}h"
        else -> "${d.toDays()}d"
    }
}

/** Deterministic monogram color from an airline code (PLAN.md §4.3 logo strategy). */
fun monogramColor(code: String): Color {
    val palette = listOf(
        Color(0xFF1667D9), Color(0xFF00696E), Color(0xFF7B4FA6), Color(0xFFB3541E),
        Color(0xFF2E7D32), Color(0xFF9C27B0), Color(0xFF00838F), Color(0xFF5D4037),
        Color(0xFFAD1457), Color(0xFF283593), Color(0xFF00695C), Color(0xFFEF6C00),
    )
    val idx = abs(code.uppercase().hashCode()) % palette.size
    return palette[idx]
}
