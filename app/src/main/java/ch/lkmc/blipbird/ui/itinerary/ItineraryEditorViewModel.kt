package ch.lkmc.blipbird.ui.itinerary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.blipbird.core.data.FlightLifecycleCoordinator
import ch.lkmc.blipbird.core.data.ItineraryRepository
import ch.lkmc.blipbird.core.data.ItineraryValidationException
import ch.lkmc.blipbird.core.data.designator
import ch.lkmc.blipbird.core.data.displayDesignator
import ch.lkmc.blipbird.core.data.StatusRefreshCoordinator
import ch.lkmc.blipbird.core.database.TrackedFlightEntity
import ch.lkmc.blipbird.core.model.Itinerary
import ch.lkmc.blipbird.core.model.ItineraryDraftLegInput
import ch.lkmc.blipbird.core.model.BaggagePlan
import ch.lkmc.blipbird.core.model.BookingArrangement
import ch.lkmc.blipbird.core.model.SaveItineraryRequest
import ch.lkmc.blipbird.core.model.TransitionIntent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class ItinerarySavedEvent(
    val itineraryId: Long,
    val trackedNewFlights: Boolean,
    val dissolved: Boolean,
)

@HiltViewModel
class ItineraryEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val itineraries: ItineraryRepository,
    private val lifecycle: FlightLifecycleCoordinator,
    private val refreshCoordinator: StatusRefreshCoordinator,
) : ViewModel() {
    private val itineraryId = savedStateHandle.get<Long>(ItineraryNavArgs.ITINERARY_ID)
        ?.takeIf { it > 0 }
    private val draftId = savedStateHandle.get<String>(ItineraryNavArgs.DRAFT_ID)

    val itinerary: StateFlow<Itinerary?> = (itineraryId?.let(itineraries::observeItinerary) ?: flowOf(null))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val availableFlights: StateFlow<List<TrackedFlightEntity>> = itineraries.observeUngroupedFlights()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val saving = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val creationCheckComplete = MutableStateFlow(itineraryId != null)
    val creationCheckSucceeded = MutableStateFlow(itineraryId != null)
    val committedItineraryId = MutableStateFlow<Long?>(null)
    private var creationCheckRunning = false

    private val _saved = MutableSharedFlow<ItinerarySavedEvent>()
    val saved = _saved.asSharedFlow()

    init {
        verifyRestoredDraft()
    }

    fun verifyRestoredDraft() {
        if (itineraryId != null || creationCheckRunning) return
        creationCheckRunning = true
        creationCheckComplete.value = false
        creationCheckSucceeded.value = false
        error.value = null
        viewModelScope.launch {
            try {
                committedItineraryId.value = draftId?.let { itineraries.itineraryIdByCreationRequest(it) }
                creationCheckSucceeded.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error.value = "Couldn't verify this restored draft. Try again."
            } finally {
                creationCheckComplete.value = true
                creationCheckRunning = false
            }
        }
    }

    fun discardsAnsweredTransitions(draft: ItineraryDraft): Boolean {
        val current = itinerary.value ?: return false
        val flightByLeg = current.legs.associate { it.id to it.flight.id }
        val requested = draft.legs.zipWithNext().mapNotNull { (inbound, outbound) ->
            val first = inbound.existingFlightId ?: return@mapNotNull null
            val second = outbound.existingFlightId ?: return@mapNotNull null
            (first to second) to TransitionIntent.decode(inbound.transitionAfter)
        }.toMap()
        return current.transitions.any { transition ->
            val answered = transition.bookingArrangement != BookingArrangement.UNKNOWN ||
                transition.baggagePlan != BaggagePlan.UNKNOWN
            if (!answered) return@any false
            val inbound = flightByLeg[transition.inboundLegId] ?: return@any true
            val outbound = flightByLeg[transition.outboundLegId] ?: return@any true
            requested[inbound to outbound] != transition.intent
        }
    }

    fun save(draft: ItineraryDraft) {
        if (saving.value) return
        viewModelScope.launch {
            saving.value = true
            error.value = null
            try {
                val request = SaveItineraryRequest(
                    creationRequestId = draft.draftId,
                    itineraryId = draft.itineraryId,
                    name = draft.name,
                    deleteRemovedFlightIds = draft.deleteRemovedFlightIds,
                    legs = draft.legs.mapIndexed { index, leg ->
                        if (!leg.dateConfirmed) {
                            throw ItineraryValidationException(
                                index,
                                "Confirm the departure-airport date for flight ${index + 1}",
                            )
                        }
                        val date = runCatching { LocalDate.parse(leg.dateLocal) }.getOrNull()
                            ?: throw ItineraryValidationException(index, "Choose a departure-airport date for flight ${index + 1}")
                        ItineraryDraftLegInput(
                            existingFlightId = leg.existingFlightId,
                            replacesFlightId = leg.replacesFlightId,
                            designator = leg.designator,
                            departureLocalDate = date,
                            transitionAfter = TransitionIntent.decode(leg.transitionAfter),
                        )
                    },
                )
                val result = lifecycle.saveItinerary(request)
                refreshCoordinator.refreshNewFlights(result.newlyTrackedFlightIds)
                _saved.emit(
                    ItinerarySavedEvent(
                        itineraryId = result.itineraryId,
                        trackedNewFlights = result.newlyTrackedFlightIds.isNotEmpty(),
                        dissolved = result.dissolved,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: ItineraryValidationException) {
                error.value = e.message
            } catch (_: Exception) {
                error.value = "Couldn't save itinerary. Your draft is still here."
            } finally {
                saving.value = false
            }
        }
    }
}

object ItineraryNavArgs {
    const val ITINERARY_ID = "itineraryId"
    const val DRAFT_ID = "draftId"
}

fun Itinerary.toDraft(draftId: String): ItineraryDraft {
    val transitionByInbound = transitions.associateBy { it.inboundLegId }
    return ItineraryDraft(
        draftId = draftId,
        itineraryId = id,
        name = name.orEmpty(),
        legs = legs.map { leg ->
            ItineraryDraftLeg(
                existingFlightId = leg.flight.id,
                originalMemberFlightId = leg.flight.id,
                designator = leg.flight.designator().display,
                dateLocal = leg.flight.dateLocal.orEmpty(),
                dateConfirmed = true,
                transitionAfter = transitionByInbound[leg.id]?.intent?.name ?: TransitionIntent.UNKNOWN.name,
            )
        },
    )
}
