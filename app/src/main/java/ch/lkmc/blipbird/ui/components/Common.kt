package ch.lkmc.blipbird.ui.components

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.domain.LookupOutcome
import ch.lkmc.blipbird.ui.theme.LocalExtendedColors
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

/**
 * Status word chip — always word + color, never color alone (accessibility).
 * Status changes flip through a Solari split-flap cascade (REVIEW.md I4) while
 * the chip color cross-fades to the new status color.
 */
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
    val background by animateColorAsState(color, tween(450), label = "status-chip-color")
    SplitFlapText(
        text = text.uppercase(),
        // Foreground follows the animated background so mid-transition frames
        // never drop below the WCAG pick for either endpoint's darker half.
        color = statusContentColor(background),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .animateContentSize()
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * In-app "reduce motion" override (§18), provided at the app root from the
 * settings store. [rememberReducedMotion] OR-s it with the system animator
 * scale, so users on an OS without an accessible animation toggle still get one.
 */
val LocalReduceMotionPref = staticCompositionLocalOf { false }

/**
 * True when the system animator scale is 0 ("remove animations") **or** the
 * in-app reduce-motion toggle is on; autonomous flourishes (split-flap cascades,
 * flapping, swoops) should sit still then. Direct-manipulation feedback that
 * tracks the finger is fine either way.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    val systemReduced = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) == 0f
    }
    return systemReduced || LocalReduceMotionPref.current
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
 * Short label for a failed lookup outcome, shared by list and detail (G5):
 * "no key", "quota", "rate limited" and "unreachable" must be distinguishable
 * in the UI instead of all rendering as silently missing data.
 */
fun lookupProblemRes(outcome: LookupOutcome): Int = when (outcome) {
    LookupOutcome.NO_KEY -> R.string.lookup_problem_no_key
    LookupOutcome.QUOTA_EXHAUSTED -> R.string.lookup_problem_quota
    LookupOutcome.RATE_LIMITED -> R.string.lookup_problem_rate_limited
    LookupOutcome.TRANSIENT_ERROR -> R.string.lookup_problem_offline
    LookupOutcome.NOT_FOUND -> R.string.lookup_problem_not_found
    LookupOutcome.NONRETRYABLE_ERROR -> R.string.lookup_problem_failed
    LookupOutcome.SUCCESS -> R.string.value_unknown   // never rendered
}

/**
 * Phase countdown lines shared by list and detail. Past-due targets switch to
 * in-progress copy (DS4-V20): while the data is stale, "Departing…" is honest
 * where "Departs in 0m" reads like a frozen clock.
 */
fun departsInText(untilDeparture: Duration): String =
    if (untilDeparture.isNegative) "Departing…" else "Departs in ${countdownText(untilDeparture)}"

fun landsInText(untilArrival: Duration): String =
    if (untilArrival.isNegative) "Landing…" else "Lands in ${countdownText(untilArrival)}"

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
