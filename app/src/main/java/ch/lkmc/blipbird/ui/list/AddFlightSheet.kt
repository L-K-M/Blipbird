package ch.lkmc.blipbird.ui.list

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import ch.lkmc.blipbird.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFlightSheet(
    error: String?,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onAdd: (input: String, date: LocalDate?, alias: String?) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var alias by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.add_flight), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.add_flight_hint)) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            DateField(
                date = selectedDate,
                onOpenPicker = { showDatePicker = true },
                onClear = { selectedDate = null },
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it },
                label = { Text(stringResource(R.string.add_flight_alias_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            // Announced by the spinner's semantics so TalkBack signals the
            // in-flight submit — the button label stays "Track" throughout.
            val submittingLabel = stringResource(R.string.add_flight_submitting)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Button(
                    // Stay enabled (filled/primary) while submitting so the
                    // spinner keeps its on-primary contrast — a disabled button
                    // mutes the container and a white-ish indicator would wash
                    // out. The onClick guard, not `enabled`, is what stops a
                    // double-tap from enqueuing the same batch twice (V6).
                    enabled = input.isNotBlank(),
                    onClick = {
                        if (!submitting && input.isNotBlank()) {
                            onAdd(input, selectedDate, alias.trim().ifEmpty { null })
                        }
                    },
                ) {
                    if (submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(18.dp)
                                .semantics { contentDescription = submittingLabel },
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.add_flight_action))
                }
            }
        }
    }

    if (showDatePicker) {
        // The picker works in UTC-midnight millis; the sheet keeps a plain
        // LocalDate (no time-of-day, no zone) so the user's chosen calendar day
        // is what gets tracked regardless of the device zone.
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate?.toUtcMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    selectedDate = pickerState.selectedDateMillis?.let(::localDateFromUtcMillis)
                    showDatePicker = false
                }) { Text(stringResource(R.string.date_picker_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/**
 * A read-only field that opens the [DatePickerDialog] when tapped — no free-text
 * date parsing (the old plain field swallowed typos as silent parse errors, V6).
 * Optional: a trailing clear button removes the picked date; empty means "any date".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(
    date: LocalDate?,
    onOpenPicker: () -> Unit,
    onClear: () -> Unit,
) {
    val formatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    // A read-only text field ignores taps for editing, so drive the picker off
    // its press interactions instead of an overlay that would swallow the
    // trailing icon's own clicks.
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    LaunchedEffect(pressed) { if (pressed) onOpenPicker() }

    OutlinedTextField(
        value = date?.let { formatter.format(it) } ?: "",
        onValueChange = {},
        readOnly = true,
        label = { Text(stringResource(R.string.add_flight_date_optional)) },
        placeholder = { Text(stringResource(R.string.add_flight_date_placeholder)) },
        trailingIcon = {
            if (date != null) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.add_flight_date_clear))
                }
            } else {
                Icon(Icons.Filled.DateRange, contentDescription = null)
            }
        },
        interactionSource = interactionSource,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun LocalDate.toUtcMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun localDateFromUtcMillis(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
