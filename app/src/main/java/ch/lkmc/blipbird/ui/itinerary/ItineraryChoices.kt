package ch.lkmc.blipbird.ui.itinerary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.model.BaggagePlan
import ch.lkmc.blipbird.core.model.BookingArrangement
import ch.lkmc.blipbird.core.model.TransitionIntent

// ---------------------------------------------------------------- labels

@Composable
internal fun transitionLabel(intent: TransitionIntent): String = stringResource(
    when (intent) {
        TransitionIntent.DIRECT_CONNECTION -> R.string.transition_direct
        TransitionIntent.DESTINATION_STAY -> R.string.transition_stay
        TransitionIntent.SURFACE_TRANSFER -> R.string.transition_surface
        TransitionIntent.UNKNOWN -> R.string.transition_unknown
    }
)

@Composable
internal fun bookingLabel(value: BookingArrangement): String = stringResource(
    when (value) {
        BookingArrangement.BOOKED_TOGETHER -> R.string.booking_together
        BookingArrangement.BOOKED_SEPARATELY -> R.string.booking_separate
        BookingArrangement.UNKNOWN -> R.string.booking_unknown
    }
)

@Composable
internal fun baggageLabel(value: BaggagePlan): String = stringResource(
    when (value) {
        BaggagePlan.NO_CHECKED_BAG -> R.string.baggage_none
        BaggagePlan.THROUGH_CHECKED -> R.string.baggage_through
        BaggagePlan.COLLECT_AND_RECHECK -> R.string.baggage_recheck
        BaggagePlan.UNKNOWN -> R.string.baggage_unknown
    }
)

// ---------------------------------------------------------------- dialog

@Composable
internal fun <T> ChoiceDialog(
    title: String,
    helper: String?,
    choices: List<Pair<T, String>>,
    selected: T,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                helper?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                choices.forEach { (value, label) ->
                    TextButton(
                        onClick = { onSelect(value) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(
                            if (value == selected) "$label (${stringResource(R.string.a11y_selected)})" else label,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
