package ch.lkmc.blipbird.ui.itinerary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.data.ItineraryRepository
import ch.lkmc.blipbird.core.data.designator
import ch.lkmc.blipbird.core.data.displayDesignator
import ch.lkmc.blipbird.core.model.DateIntentSource
import ch.lkmc.blipbird.core.model.TransitionIntent
import ch.lkmc.blipbird.domain.DesignatorParser
import kotlinx.coroutines.flow.collectLatest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ItineraryEditorScreen(
    draftId: String,
    itineraryId: Long?,
    draftStore: ItineraryDraftStoreViewModel,
    onBack: () -> Unit,
    onSaved: (Long?) -> Unit,
    onFirstTrack: () -> Unit,
    viewModel: ItineraryEditorViewModel = hiltViewModel(),
) {
    val drafts by draftStore.drafts.collectAsStateWithLifecycle()
    val persisted by viewModel.itinerary.collectAsStateWithLifecycle()
    val availableFlights by viewModel.availableFlights.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val repositoryError by viewModel.error.collectAsStateWithLifecycle()
    val creationCheckComplete by viewModel.creationCheckComplete.collectAsStateWithLifecycle()
    val creationCheckSucceeded by viewModel.creationCheckSucceeded.collectAsStateWithLifecycle()
    val committedItineraryId by viewModel.committedItineraryId.collectAsStateWithLifecycle()
    val missing by viewModel.missing.collectAsStateWithLifecycle()
    val draft = drafts[draftId]
    var localError by remember { mutableStateOf<String?>(null) }
    var showDiscard by remember { mutableStateOf(false) }
    var showPaste by rememberSaveable { mutableStateOf(false) }
    var pasteError by rememberSaveable { mutableStateOf<String?>(null) }
    var showExisting by remember { mutableStateOf(false) }
    var confirmDiscardAnswers by remember { mutableStateOf(false) }
    var dateRowId by remember { mutableStateOf<String?>(null) }
    var validationAttempted by remember { mutableStateOf(false) }
    var removeLegRowId by remember { mutableStateOf<String?>(null) }
    var replaceLegRowId by remember { mutableStateOf<String?>(null) }
    var focusRequest by remember { mutableStateOf<EditorFocusRequest?>(null) }
    var focusRequestSequence by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val nameFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(draftId, itineraryId, persisted) {
        if (draftStore.draft(draftId) == null) {
            when {
                itineraryId == null -> draftStore.put(ItineraryDraft(draftId = draftId))
                persisted != null -> draftStore.put(persisted!!.toDraft(draftId))
            }
        }
    }
    LaunchedEffect(viewModel, draftId) {
        viewModel.saved.collectLatest { event ->
            draftStore.remove(draftId)
            if (event.trackedNewFlights) onFirstTrack()
            onSaved(event.itineraryId.takeUnless { event.dissolved })
        }
    }
    LaunchedEffect(creationCheckComplete, committedItineraryId) {
        if (creationCheckComplete && committedItineraryId != null) {
            draftStore.remove(draftId)
            onSaved(checkNotNull(committedItineraryId))
        }
    }
    LaunchedEffect(missing) {
        if (missing) {
            draftStore.remove(draftId)
            onBack()
        }
    }

    fun update(transform: (ItineraryDraft) -> ItineraryDraft) {
        val current = draftStore.draft(draftId) ?: return
        val accepted = draftStore.put(transform(current).copy(dirty = true))
        localError = if (accepted) null else "Draft is too large to restore safely"
    }

    fun requestBack() {
        if (draft?.dirty == true) showDiscard = true else {
            draftStore.remove(draftId)
            onBack()
        }
    }
    fun requestSave() {
        val current = draft ?: return
        if (!creationCheckSucceeded) return
        validationAttempted = true
        localError = validateDraft(current)
        if (localError != null) {
            val target = firstInvalidFocusTarget(current)
            val invalidLeg = (target as? EditorFocusTarget.Leg)?.let { leg ->
                current.legs.indexOfFirst { it.rowId == leg.rowId }.takeIf { it >= 0 }
            }
            scope.launch {
                listState.animateScrollToItem(if (invalidLeg == null) 0 else 2 + invalidLeg)
                focusRequestSequence += 1
                focusRequest = target?.let { EditorFocusRequest(it, focusRequestSequence) }
            }
            return
        }
        if (viewModel.discardsAnsweredTransitions(current)) {
            confirmDiscardAnswers = true
        } else {
            viewModel.save(current)
        }
    }
    // Always consume system back: swallowing it while a save commits beats
    // cancelling the Room transaction by popping the entry (and its ViewModel).
    BackHandler { if (!saving) requestBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (itineraryId == null) stringResource(R.string.itinerary_create)
                        else stringResource(R.string.itinerary_edit),
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = ::requestBack, enabled = !saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(
                        enabled = draft != null && !saving && creationCheckSucceeded,
                        onClick = ::requestSave,
                    ) {
                        if (saving) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.save))
                    }
                },
            )
        },
    ) { padding ->
        if (draft == null || !creationCheckComplete || committedItineraryId != null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item("name") {
                        LaunchedEffect(focusRequest) {
                            if (focusRequest?.target == EditorFocusTarget.Name) nameFocusRequester.requestFocus()
                        }
                        OutlinedTextField(
                            value = draft.name,
                            enabled = !saving && creationCheckSucceeded,
                            onValueChange = { value ->
                                if (value.length <= ItineraryRepository.MAX_NAME_LENGTH) {
                                    update { it.copy(name = value) }
                                } else {
                                    localError = "Itinerary names can contain at most ${ItineraryRepository.MAX_NAME_LENGTH} characters"
                                }
                            },
                            label = { Text(stringResource(R.string.itinerary_name_optional)) },
                            supportingText = {
                                Text("${draft.name.length}/${ItineraryRepository.MAX_NAME_LENGTH}")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().focusRequester(nameFocusRequester),
                        )
                    }
                    item("flights-heading") {
                        Text(
                            stringResource(R.string.itinerary_flights_heading),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    itemsIndexed(draft.legs, key = { _, leg -> leg.rowId }) { index, leg ->
                        DraftLegCard(
                            index = index,
                            leg = leg,
                            count = draft.legs.size,
                            enabled = !saving && creationCheckSucceeded,
                            editing = itineraryId != null,
                            focusRequest = focusRequest,
                            validationError = if (validationAttempted) legValidationError(leg) else null,
                            onChange = { replacement ->
                                update { current ->
                                    current.copy(legs = current.legs.toMutableList().also { it[index] = replacement })
                                }
                            },
                            onIdentityChange = { replacement ->
                                update { current ->
                                    current.copy(legs = current.legs.replacedIdentity(index, replacement))
                                }
                            },
                            onPickDate = { dateRowId = leg.rowId },
                            onMoveEarlier = {
                                update { current -> current.copy(legs = current.legs.movedWithTransitions(index, index - 1)) }
                            },
                            onMoveLater = {
                                update { current -> current.copy(legs = current.legs.movedWithTransitions(index, index + 1)) }
                            },
                            onRemove = {
                                val wasPersistedMember = persisted?.legs?.any {
                                    it.flight.id == leg.originalMemberFlightId
                                } == true
                                if (itineraryId != null && wasPersistedMember) {
                                    removeLegRowId = leg.rowId
                                } else {
                                    update { current ->
                                        current.copy(legs = current.legs.removedPreservingTransitions(index))
                                    }
                                }
                            },
                            onReplace = { replaceLegRowId = leg.rowId },
                        )
                    }
                    item("add-actions") {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                enabled = !saving && draft.legs.size < ItineraryRepository.MAX_LEGS,
                                onClick = {
                                    update { it.copy(legs = it.legs + ItineraryDraftLeg()) }
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.itinerary_add_flight))
                            }
                            OutlinedButton(
                                enabled = !saving && draft.legs.size < ItineraryRepository.MAX_LEGS,
                                onClick = {
                                    pasteError = null
                                    showPaste = true
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Icon(Icons.Filled.ContentPaste, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.itinerary_paste_several))
                            }
                            if (availableFlights.any { flight -> draft.legs.none { it.existingFlightId == flight.id } }) {
                                OutlinedButton(
                                    enabled = !saving && draft.legs.size < ItineraryRepository.MAX_LEGS,
                                    onClick = { showExisting = true },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Icon(Icons.Filled.Flight, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.itinerary_add_tracked))
                                }
                            }
                        }
                    }
                    val error = localError ?: repositoryError
                    if (error != null) {
                        item("error") {
                            Text(
                                error,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                            )
                        }
                    }
                    if (creationCheckComplete && !creationCheckSucceeded) {
                        item("retry-verification") {
                            OutlinedButton(
                                onClick = viewModel::verifyRestoredDraft,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.itinerary_retry_verification))
                            }
                        }
                    }
                    item("save") {
                        Button(
                            onClick = ::requestSave,
                            enabled = !saving && creationCheckSucceeded,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.itinerary_save))
                        }
                    }
                }
            }
        }
    }

    dateRowId?.let { rowId ->
        val leg = draft?.legs?.firstOrNull { it.rowId == rowId }
        val initial = leg?.dateLocal?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial?.toUtcMillis())
        DatePickerDialog(
            onDismissRequest = { dateRowId = null },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        update { current ->
                            val index = current.legs.indexOfFirst { it.rowId == rowId }
                            val item = current.legs.getOrNull(index) ?: return@update current
                            val identityChanged = item.dateLocal != date
                            val replacement = item.withDepartureDateIdentity(date)
                            current.copy(
                                legs = if (identityChanged) {
                                    current.legs.replacedIdentity(index, replacement)
                                } else {
                                    current.legs.toMutableList().also { it[index] = replacement }
                                }
                            )
                        }
                    }
                    dateRowId = null
                }) { Text(stringResource(R.string.date_picker_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { dateRowId = null }) { Text(stringResource(R.string.cancel)) }
            },
        ) { DatePicker(picker) }
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text(stringResource(R.string.itinerary_discard_title)) },
            text = { Text(stringResource(R.string.itinerary_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscard = false
                    draftStore.remove(draftId)
                    onBack()
                }) { Text(stringResource(R.string.itinerary_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscard = false }) {
                    Text(stringResource(R.string.itinerary_keep_editing))
                }
            },
        )
    }

    if (confirmDiscardAnswers) {
        AlertDialog(
            onDismissRequest = { confirmDiscardAnswers = false },
            title = { Text(stringResource(R.string.itinerary_discard_answers_title)) },
            text = { Text(stringResource(R.string.itinerary_discard_answers_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscardAnswers = false
                    draft?.let(viewModel::save)
                }) { Text(stringResource(R.string.continue_label)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscardAnswers = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    replaceLegRowId?.let { rowId ->
        AlertDialog(
            onDismissRequest = { replaceLegRowId = null },
            title = { Text(stringResource(R.string.itinerary_replace_title)) },
            text = { Text(stringResource(R.string.itinerary_replace_body)) },
            confirmButton = {
                TextButton(onClick = {
                    update { current ->
                        val index = current.legs.indexOfFirst { it.rowId == rowId }
                        val leg = current.legs.getOrNull(index) ?: return@update current
                        current.copy(
                            legs = current.legs.replacedIdentity(index, leg.beginIdentityReplacement())
                        )
                    }
                    replaceLegRowId = null
                }) {
                    Text(
                        stringResource(R.string.itinerary_replace_flight),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { replaceLegRowId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    removeLegRowId?.let { rowId ->
        val dissolvesItinerary = draft?.let { it.itineraryId != null && it.legs.size == 2 } == true
        AlertDialog(
            onDismissRequest = { removeLegRowId = null },
            title = { Text(stringResource(R.string.itinerary_remove_title)) },
            text = {
                Text(
                    stringResource(
                        if (dissolvesItinerary) R.string.itinerary_remove_dissolve_body
                        else R.string.itinerary_remove_body
                    )
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        update { current ->
                            val index = current.legs.indexOfFirst { it.rowId == rowId }
                            current.copy(legs = current.legs.removedPreservingTransitions(index))
                        }
                        removeLegRowId = null
                    }) { Text(stringResource(R.string.itinerary_remove_keep_flight)) }
                    TextButton(onClick = {
                        update { current ->
                            val index = current.legs.indexOfFirst { it.rowId == rowId }
                            val removed = current.legs.getOrNull(index)
                            val flightId = removed?.originalMemberFlightId
                                ?: removed?.replacesFlightId
                                ?: removed?.existingFlightId
                            current.copy(
                                legs = current.legs.removedPreservingTransitions(index),
                                deleteRemovedFlightIds = if (flightId == null) {
                                    current.deleteRemovedFlightIds
                                } else {
                                    (current.deleteRemovedFlightIds + flightId).distinct()
                                },
                            )
                        }
                        removeLegRowId = null
                    }) {
                        Text(
                            stringResource(R.string.itinerary_remove_delete_flight),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { removeLegRowId = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showPaste) {
        PasteFlightsDialog(
            onDismiss = { showPaste = false },
            error = pasteError,
            onAdd = { input ->
                val bytes = input.toByteArray(Charsets.UTF_8).size
                if (bytes > ItineraryRepository.MAX_PASTE_BYTES) {
                    pasteError = "Pasted flight list is larger than 4 KiB"
                } else {
                    val tokens = DesignatorParser.splitBatch(input)
                    when {
                        tokens.isEmpty() -> pasteError = "No flight number recognized"
                        draft != null && tokens.size + draft.legs.count { it.designator.isNotBlank() } > ItineraryRepository.MAX_LEGS -> {
                            pasteError = "An itinerary can contain at most ${ItineraryRepository.MAX_LEGS} flights"
                        }
                        else -> {
                            update { current ->
                                val retained = current.legs.filter {
                                    it.designator.isNotBlank() || it.existingFlightId != null
                                }
                                current.copy(legs = retained + tokens.map { ItineraryDraftLeg(designator = it) })
                            }
                            pasteError = null
                            showPaste = false
                        }
                    }
                }
            },
        )
    }

    if (showExisting) {
        ModalBottomSheet(onDismissRequest = { showExisting = false }) {
            val choices = availableFlights.filter { flight ->
                draft?.legs?.none { it.existingFlightId == flight.id } != false
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 24.dp,
                    end = 24.dp,
                    bottom = 32.dp,
                ),
            ) {
                item {
                    Text(stringResource(R.string.itinerary_add_tracked), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                }
                itemsIndexed(choices, key = { _, flight -> flight.id }) { _, flight ->
                        TextButton(
                            onClick = {
                                update { current ->
                                    val blankIndex = current.legs.indexOfFirst {
                                        it.existingFlightId == null && it.designator.isBlank() && it.dateLocal.isBlank()
                                    }
                                    val added = ItineraryDraftLeg(
                                        existingFlightId = flight.id,
                                        designator = flight.designator().display,
                                        dateLocal = if (
                                            DateIntentSource.decode(flight.dateIntentSource) ==
                                            DateIntentSource.USER_CONFIRMED
                                        ) flight.dateLocal.orEmpty() else "",
                                        dateConfirmed = DateIntentSource.decode(flight.dateIntentSource) ==
                                            DateIntentSource.USER_CONFIRMED,
                                    )
                                    if (blankIndex >= 0) {
                                        current.copy(legs = current.legs.toMutableList().also { it[blankIndex] = added })
                                    } else {
                                        current.copy(legs = current.legs + added)
                                    }
                                }
                                showExisting = false
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(flight.displayDesignator())
                                if (flight.alias != null) Text(flight.designator().display)
                                val confirmed = DateIntentSource.decode(flight.dateIntentSource) ==
                                    DateIntentSource.USER_CONFIRMED
                                Text(
                                    if (confirmed) {
                                        flight.dateLocal?.let { value ->
                                            runCatching { LocalDate.parse(value) }.getOrNull()
                                                ?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                                        } ?: stringResource(R.string.itinerary_date_needs_confirmation)
                                    } else {
                                        stringResource(R.string.itinerary_date_needs_confirmation)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DraftLegCard(
    index: Int,
    leg: ItineraryDraftLeg,
    count: Int,
    enabled: Boolean,
    editing: Boolean,
    focusRequest: EditorFocusRequest?,
    validationError: String?,
    onChange: (ItineraryDraftLeg) -> Unit,
    onIdentityChange: (ItineraryDraftLeg) -> Unit,
    onPickDate: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onRemove: () -> Unit,
    onReplace: () -> Unit,
) {
    val designatorFocusRequester = remember(leg.rowId) { FocusRequester() }
    val dateFocusRequester = remember(leg.rowId) { FocusRequester() }
    LaunchedEffect(focusRequest) {
        val target = focusRequest?.target as? EditorFocusTarget.Leg
        if (target?.rowId == leg.rowId) {
            when (target.field) {
                InvalidLegField.DESIGNATOR -> designatorFocusRequester.requestFocus()
                InvalidLegField.DATE -> dateFocusRequester.requestFocus()
            }
        }
    }
    Card(
        modifier = Modifier.itineraryBorder(22.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = itinerarySurface()),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.itinerary_flight_number, index + 1),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(
                    onClick = onRemove,
                    enabled = enabled && count > if (editing) 1 else ItineraryRepository.MIN_LEGS,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.itinerary_remove_flight))
                }
            }
            val persistedIdentityLocked = leg.originalMemberFlightId != null && leg.existingFlightId != null
            OutlinedTextField(
                value = leg.designator,
                enabled = enabled && leg.existingFlightId == null,
                onValueChange = { value ->
                    val normalized = value.uppercase(Locale.ROOT)
                    val identityChanged = normalized != leg.designator
                    val replacement = leg.withDesignatorIdentity(normalized)
                    if (identityChanged) onIdentityChange(replacement) else onChange(replacement)
                },
                label = { Text(stringResource(R.string.itinerary_designator)) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(designatorFocusRequester),
            )
            val date = leg.dateLocal.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            OutlinedButton(
                onClick = onPickDate,
                enabled = enabled && !persistedIdentityLocked,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).focusRequester(dateFocusRequester),
            ) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    date?.let {
                        stringResource(
                            R.string.itinerary_departure_date_value,
                            it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                        )
                    } ?: stringResource(R.string.itinerary_choose_departure_date),
                    modifier = Modifier.weight(1f),
                )
            }
            if (date != null && !persistedIdentityLocked) {
                TextButton(
                    onClick = { onIdentityChange(leg.withoutDepartureDateIdentity()) },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.add_flight_date_clear))
                }
            }
            if (persistedIdentityLocked) {
                TextButton(
                    onClick = onReplace,
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.itinerary_replace_flight))
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    enabled = enabled && index > 0,
                    onClick = onMoveEarlier,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.itinerary_move_earlier)) }
                OutlinedButton(
                    enabled = enabled && index < count - 1,
                    onClick = onMoveLater,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.itinerary_move_later)) }
            }
            if (index < count - 1) {
                Text(
                    stringResource(R.string.itinerary_after_flight),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    transitionChoices().forEach { (intent, label) ->
                        FilterChip(
                            selected = TransitionIntent.decode(leg.transitionAfter) == intent,
                            enabled = enabled,
                            onClick = { onChange(leg.copy(transitionAfter = intent.name)) },
                            label = { Text(stringResource(label)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
            validationError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
        }
    }
}

@Composable
private fun PasteFlightsDialog(
    onDismiss: () -> Unit,
    error: String?,
    onAdd: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var inputError by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.itinerary_paste_several)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { value ->
                        if (value.toByteArray(Charsets.UTF_8).size <= ItineraryRepository.MAX_PASTE_BYTES) {
                            text = value
                            inputError = null
                        } else {
                            inputError = "Pasted flight list is larger than 4 KiB"
                        }
                    },
                    label = { Text(stringResource(R.string.add_flight_hint)) },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                (inputError ?: error)?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(text) }) { Text(stringResource(R.string.itinerary_add_flight)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun transitionChoices(): List<Pair<TransitionIntent, Int>> = listOf(
    TransitionIntent.DIRECT_CONNECTION to R.string.transition_direct,
    TransitionIntent.DESTINATION_STAY to R.string.transition_stay,
    TransitionIntent.SURFACE_TRANSFER to R.string.transition_surface,
    TransitionIntent.UNKNOWN to R.string.transition_unknown,
)

private fun validateDraft(draft: ItineraryDraft): String? = when {
    draft.name.length > ItineraryRepository.MAX_NAME_LENGTH ->
        "Itinerary names can contain at most ${ItineraryRepository.MAX_NAME_LENGTH} characters"
    draft.legs.size > ItineraryRepository.MAX_LEGS ->
        "An itinerary can contain at most ${ItineraryRepository.MAX_LEGS} flights"
    draft.legs.isEmpty() || (draft.itineraryId == null && draft.legs.size < ItineraryRepository.MIN_LEGS) ->
        "Add at least ${ItineraryRepository.MIN_LEGS} flights"
    draft.legs.any { it.existingFlightId == null && DesignatorParser.mergePair(DesignatorParser.parseToken(it.designator)) == null } ->
        "Check every flight number"
    draft.legs.any { !it.dateConfirmed || runCatching { LocalDate.parse(it.dateLocal) }.isFailure } ->
        "Choose a departure-airport date for every flight"
    else -> null
}

private enum class InvalidLegField { DESIGNATOR, DATE }

private sealed interface EditorFocusTarget {
    data object Name : EditorFocusTarget
    data class Leg(val rowId: String, val field: InvalidLegField) : EditorFocusTarget
}

private data class EditorFocusRequest(
    val target: EditorFocusTarget,
    val sequence: Int,
)

private fun firstInvalidFocusTarget(draft: ItineraryDraft): EditorFocusTarget? {
    if (draft.name.length > ItineraryRepository.MAX_NAME_LENGTH) return EditorFocusTarget.Name
    draft.legs.forEach { leg ->
        if (
            leg.existingFlightId == null &&
            DesignatorParser.mergePair(DesignatorParser.parseToken(leg.designator)) == null
        ) {
            return EditorFocusTarget.Leg(leg.rowId, InvalidLegField.DESIGNATOR)
        }
        if (!leg.dateConfirmed || runCatching { LocalDate.parse(leg.dateLocal) }.isFailure) {
            return EditorFocusTarget.Leg(leg.rowId, InvalidLegField.DATE)
        }
    }
    return null
}

private fun legValidationError(leg: ItineraryDraftLeg): String? = when {
    leg.existingFlightId == null &&
        DesignatorParser.mergePair(DesignatorParser.parseToken(leg.designator)) == null ->
        "Check this flight number"
    !leg.dateConfirmed || runCatching { LocalDate.parse(leg.dateLocal) }.isFailure ->
        "Choose and confirm this flight's departure-airport date"
    else -> null
}

internal fun ItineraryDraftLeg.withDesignatorIdentity(value: String): ItineraryDraftLeg {
    val normalized = value.uppercase(Locale.ROOT)
    return copy(designator = normalized)
}

internal fun ItineraryDraftLeg.beginIdentityReplacement(): ItineraryDraftLeg = copy(
    existingFlightId = null,
    replacesFlightId = originalMemberFlightId,
)

internal fun ItineraryDraftLeg.withDepartureDateIdentity(value: String): ItineraryDraftLeg {
    return copy(
        dateLocal = value,
        dateConfirmed = true,
    )
}

private fun ItineraryDraftLeg.withoutDepartureDateIdentity(): ItineraryDraftLeg {
    return copy(
        dateLocal = "",
        dateConfirmed = false,
    )
}

internal fun List<ItineraryDraftLeg>.replacedIdentity(
    index: Int,
    replacement: ItineraryDraftLeg,
): List<ItineraryDraftLeg> {
    if (index !in indices) return this
    return toMutableList().also { updated ->
        if (index > 0) {
            updated[index - 1] = updated[index - 1].copy(transitionAfter = TransitionIntent.UNKNOWN.name)
        }
        updated[index] = replacement.copy(transitionAfter = TransitionIntent.UNKNOWN.name)
    }
}

private fun List<ItineraryDraftLeg>.movedWithTransitions(from: Int, to: Int): List<ItineraryDraftLeg> {
    if (from !in indices || to !in indices || from == to) return this
    val priorIntents = zipWithNext().associate { (a, b) ->
        (a.rowId to b.rowId) to a.transitionAfter
    }
    val moved = toMutableList().also { list -> list.add(to, list.removeAt(from)) }
    return moved.mapIndexed { index, leg ->
        val next = moved.getOrNull(index + 1)
        leg.copy(
            transitionAfter = if (next == null) TransitionIntent.UNKNOWN.name
            else priorIntents[leg.rowId to next.rowId] ?: TransitionIntent.UNKNOWN.name
        )
    }
}

private fun List<ItineraryDraftLeg>.removedPreservingTransitions(index: Int): List<ItineraryDraftLeg> {
    if (index !in indices) return this
    val priorIntents = zipWithNext().associate { (a, b) ->
        (a.rowId to b.rowId) to a.transitionAfter
    }
    val remaining = filterIndexed { current, _ -> current != index }
    return remaining.mapIndexed { current, leg ->
        val next = remaining.getOrNull(current + 1)
        leg.copy(
            transitionAfter = if (next == null) TransitionIntent.UNKNOWN.name
            else priorIntents[leg.rowId to next.rowId] ?: TransitionIntent.UNKNOWN.name
        )
    }
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
