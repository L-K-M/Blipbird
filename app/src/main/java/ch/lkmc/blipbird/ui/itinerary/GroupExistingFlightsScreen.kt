package ch.lkmc.blipbird.ui.itinerary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.data.ItineraryRepository
import ch.lkmc.blipbird.core.data.designator
import ch.lkmc.blipbird.core.data.displayDesignator
import ch.lkmc.blipbird.core.database.TrackedFlightEntity
import ch.lkmc.blipbird.core.model.DateIntentSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

@HiltViewModel
class GroupExistingFlightsViewModel @Inject constructor(
    repository: ItineraryRepository,
) : ViewModel() {
    val flights: StateFlow<List<TrackedFlightEntity>> = repository.observeUngroupedFlights()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupExistingFlightsScreen(
    draftId: String,
    draftStore: ItineraryDraftStoreViewModel,
    onBack: () -> Unit,
    onContinue: (ItineraryDraft) -> Unit,
    viewModel: GroupExistingFlightsViewModel = hiltViewModel(),
) {
    val flights by viewModel.flights.collectAsStateWithLifecycle()
    val drafts by draftStore.drafts.collectAsStateWithLifecycle()
    val selected = drafts[draftId]?.legs?.mapNotNull { it.existingFlightId }?.toSet().orEmpty()
    val ordered = flights.sortedWith(
        compareBy<TrackedFlightEntity> {
            DateIntentSource.decode(it.dateIntentSource) != DateIntentSource.USER_CONFIRMED
        }.thenBy {
            if (DateIntentSource.decode(it.dateIntentSource) == DateIntentSource.USER_CONFIRMED) {
                it.dateLocal
            } else {
                null
            }
        }.thenBy { it.createdAt }
    )
    LaunchedEffect(draftId) {
        if (draftStore.draft(draftId) == null) {
            draftStore.put(ItineraryDraft(draftId = draftId, legs = emptyList()))
        }
    }

    fun toggle(flight: TrackedFlightEntity) {
        val current = draftStore.draft(draftId)
            ?: ItineraryDraft(draftId = draftId, legs = emptyList())
        val currentSelected = current.legs.mapNotNull { it.existingFlightId }.toSet()
        val nextLegs = if (flight.id in currentSelected) {
            current.legs.filterNot { it.existingFlightId == flight.id }
        } else {
            if (current.legs.size >= ItineraryRepository.MAX_LEGS) return
            current.legs + flight.toDraftLeg()
        }
        draftStore.put(current.copy(legs = nextLegs, dirty = true))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.itinerary_group_existing), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(
                        enabled = selected.size in ItineraryRepository.MIN_LEGS..ItineraryRepository.MAX_LEGS,
                        onClick = {
                            val legs = ordered.filter { it.id in selected }.map { it.toDraftLeg() }
                            onContinue(ItineraryDraft(draftId = draftId, legs = legs, dirty = true))
                        },
                    ) { Text(stringResource(R.string.continue_label)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.itinerary_group_help),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.itinerary_selected_count, selected.size),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                items(ordered, key = { it.id }) { flight ->
                    val checked = flight.id in selected
                    val enabled = checked || selected.size < ItineraryRepository.MAX_LEGS
                    val stateLabel = stringResource(
                        if (checked) R.string.a11y_selected else R.string.a11y_not_selected
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .itineraryBorder(22.dp)
                            .semantics { stateDescription = stateLabel }
                            .clickable(enabled = enabled, role = Role.Checkbox) { toggle(flight) },
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer
                            else itinerarySurface(),
                        ),
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(flight.displayDesignator(), fontWeight = FontWeight.SemiBold)
                                if (flight.alias != null) {
                                    Text(flight.designator().display, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    if (DateIntentSource.decode(flight.dateIntentSource) == DateIntentSource.USER_CONFIRMED) {
                                        flight.dateLocal?.let { value ->
                                            runCatching { LocalDate.parse(value) }.getOrNull()
                                                ?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                                        } ?: stringResource(R.string.itinerary_date_needs_confirmation)
                                    } else {
                                        stringResource(R.string.itinerary_date_needs_confirmation)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun TrackedFlightEntity.toDraftLeg(): ItineraryDraftLeg {
    val confirmed = DateIntentSource.decode(dateIntentSource) == DateIntentSource.USER_CONFIRMED
    return ItineraryDraftLeg(
        existingFlightId = id,
        designator = designator().display,
        dateLocal = if (confirmed) dateLocal.orEmpty() else "",
        dateConfirmed = confirmed,
    )
}
