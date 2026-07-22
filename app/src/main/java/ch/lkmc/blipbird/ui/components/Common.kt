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

/** Status word chip — always word + color, never color alone (accessibility). */
@Composable
fun StatusWord(status: FlightStatus) {
    val ext = LocalExtendedColors.current
    val (text, color) = when (status) {
        FlightStatus.SCHEDULED -> stringResource(R.string.status_scheduled) to ext.statusNeutral
        FlightStatus.ON_TIME -> stringResource(R.string.status_on_time) to ext.statusOnTime
        FlightStatus.DELAYED -> stringResource(R.string.status_delayed) to ext.statusDelayed
        FlightStatus.DEPARTED -> stringResource(R.string.status_departed) to ext.statusEnRoute
        FlightStatus.EN_ROUTE -> stringResource(R.string.status_en_route) to ext.statusEnRoute
        FlightStatus.APPROACHING -> stringResource(R.string.status_approaching) to ext.statusEnRoute
        FlightStatus.LANDED -> stringResource(R.string.status_landed) to ext.statusNeutral
        FlightStatus.ARRIVED -> stringResource(R.string.status_arrived) to ext.statusOnTime
        FlightStatus.CANCELLED -> stringResource(R.string.status_cancelled) to ext.statusCancelled
        FlightStatus.DIVERTED -> stringResource(R.string.status_diverted) to ext.statusDelayed
        FlightStatus.UNKNOWN -> stringResource(R.string.status_unknown) to ext.statusNeutral
    }
    Text(
        text.uppercase(),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Countdown with adaptive granularity: days → h m → m. */
fun countdownText(d: Duration): String {
    val total = d.seconds
    val neg = total < 0
    val s = abs(total)
    val text = when {
        s >= 172_800 -> "${s / 86_400}d ${(s % 86_400) / 3600}h"
        s >= 3_600 -> "${s / 3600}h ${(s % 3600) / 60}m"
        else -> "${s / 60}m"
    }
    return if (neg) "-$text" else text
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
