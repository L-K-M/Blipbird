package ch.lkmc.blipbird.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.domain.JetlagEngine
import ch.lkmc.blipbird.ui.components.shiftLabel
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val CLOCK_FMT = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The body-clock card (PLAN.md §9.5). Deliberately two-tier: the shift and the
 * arrival-on-your-own-clock line are exact clock arithmetic and get the headline
 * weight; everything below the divider is the [JetlagEngine.Plan] rule of thumb
 * and is rendered small, hedged, and explicitly labelled as an estimate so it can
 * never read as measured provider data (§1 "never fakes precision").
 */
@Composable
internal fun BodyClockBody(
    bodyClock: JetlagEngine.BodyClock,
    originCode: String,
    destinationCode: String,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            headline(bodyClock.shiftMinutes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (bodyClock.unchanged) {
                stringResource(R.string.body_clock_unchanged)
            } else {
                stringResource(
                    R.string.body_clock_arrival,
                    bodyClock.arrivalLocalTime.format(CLOCK_FMT),
                    destinationCode,
                    bodyClock.arrivalOriginTime.format(CLOCK_FMT),
                    originCode,
                )
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        val plan = bodyClock.plan
        if (plan == null) {
            if (!bodyClock.unchanged) {
                Spacer(Modifier.height(6.dp))
                val threshold = JetlagEngine.NEGLIGIBLE_SHIFT_MINUTES / 60
                Text(
                    pluralStringResource(R.plurals.body_clock_negligible, threshold, threshold),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))

        Text(
            stringResource(
                when (plan.direction) {
                    JetlagEngine.Direction.ADVANCE -> R.string.body_clock_move_earlier
                    JetlagEngine.Direction.DELAY -> R.string.body_clock_move_later
                },
                shiftLabel(plan.shiftMinutes),
            ),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (plan.counterIntuitive) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    when (plan.direction) {
                        JetlagEngine.Direction.ADVANCE -> R.string.body_clock_counter_advance
                        JetlagEngine.Direction.DELAY -> R.string.body_clock_counter_delay
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            pluralStringResource(R.plurals.body_clock_days, plan.days, plan.days),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(
                R.string.body_clock_light,
                destinationCode,
                windowLabel(plan.seekLight),
                windowLabel(plan.avoidLight),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                R.string.body_clock_caveat,
                originCode,
                clock(LocalTime.of(JetlagEngine.ASSUMED_WAKE_HOUR, 0)),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "7 h later" / "8 h earlier" / "No time-zone change" — the exact clock fact. */
@Composable
private fun headline(shiftMinutes: Int): String = when {
    JetlagEngine.shortestShiftMinutes(shiftMinutes) == 0 -> stringResource(R.string.body_clock_no_change)
    shiftMinutes > 0 -> stringResource(R.string.body_clock_later, shiftLabel(shiftMinutes))
    else -> stringResource(R.string.body_clock_earlier, shiftLabel(-shiftMinutes))
}

@Composable
private fun windowLabel(window: JetlagEngine.LightWindow): String =
    stringResource(R.string.body_clock_window, clock(window.from), clock(window.to))

private fun clock(time: LocalTime): String = time.format(CLOCK_FMT)
