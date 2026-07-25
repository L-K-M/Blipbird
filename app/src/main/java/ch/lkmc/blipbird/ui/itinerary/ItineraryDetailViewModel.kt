package ch.lkmc.blipbird.ui.itinerary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.blipbird.core.data.AirportEnricher
import ch.lkmc.blipbird.core.data.FlightLifecycleCoordinator
import ch.lkmc.blipbird.core.data.FlightRepository
import ch.lkmc.blipbird.core.data.ItineraryRepository
import ch.lkmc.blipbird.core.model.BaggagePlan
import ch.lkmc.blipbird.core.model.BookingArrangement
import ch.lkmc.blipbird.core.model.Itinerary
import ch.lkmc.blipbird.core.model.StatusSnapshot
import ch.lkmc.blipbird.domain.JetlagEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ItineraryDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val itineraries: ItineraryRepository,
    private val lifecycle: FlightLifecycleCoordinator,
    private val flights: FlightRepository,
    private val airports: AirportEnricher,
) : ViewModel() {
    private val itineraryId = savedStateHandle.get<Long>(ItineraryNavArgs.ITINERARY_ID)
        ?.takeIf { it > 0 }

    val loadState: StateFlow<ItineraryDetailLoadState> = itineraryId
        ?.let(itineraries::observeItinerary)
        ?.map { itinerary ->
            if (itinerary == null) ItineraryDetailLoadState.Missing
            else ItineraryDetailLoadState.Found(itinerary)
        }
        ?.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ItineraryDetailLoadState.Loading,
        ) ?: flowOf(ItineraryDetailLoadState.Missing)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ItineraryDetailLoadState.Loading)

    /**
     * Cumulative body-clock shift across the plan (PLAN.md §9.5), measured from the
     * zone the first leg departs. Needs each leg's resolved route, so it stays null
     * in zero-key mode — consistent with every other route-derived surface.
     */
    val bodyClock: StateFlow<JetlagEngine.Trip?> = loadState
        .map { state ->
            (state as? ItineraryDetailLoadState.Found)?.itinerary?.legs?.map { it.flight.id }.orEmpty()
        }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList())
            else combine(ids.map(flights::observeSnapshot)) { it.toList() }
        }
        .map(::tripFrom)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    fun archive() = mutate { id -> lifecycle.archiveItinerary(id) }

    fun delete(deleteFlights: Boolean) = mutate { id ->
        lifecycle.deleteItinerary(id, deleteFlights)
    }

    fun updateBooking(transitionId: Long, value: BookingArrangement) = mutateTransition {
        itineraries.updateTransition(transitionId, bookingArrangement = value)
    }

    fun updateBaggage(transitionId: Long, value: BaggagePlan) = mutateTransition {
        itineraries.updateTransition(transitionId, baggagePlan = value)
    }

    private suspend fun tripFrom(snapshots: List<StatusSnapshot?>): JetlagEngine.Trip? {
        val start = airports.enrich(snapshots.firstOrNull()?.departure) ?: return null
        val legs = snapshots.map { snapshot ->
            val arrival = airports.enrich(snapshot?.arrival)
            JetlagEngine.TripLeg(
                arrivalCode = arrival?.code ?: UNRESOLVED_CODE,
                arrival = snapshot?.arrTimes?.best,
                arrivalTz = arrival?.tz,
            )
        }
        return JetlagEngine.trip(start.code, start.tz, legs)
    }

    private fun mutate(block: suspend (Long) -> Unit) {
        val id = itineraryId ?: return
        if (!busy.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            error.value = null
            try {
                block(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error.value = "Couldn't update itinerary. Nothing was changed."
            } finally {
                busy.value = false
            }
        }
    }

    private fun mutateTransition(block: suspend () -> Unit) {
        if (!busy.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            error.value = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error.value = "Couldn't update itinerary. Nothing was changed."
            } finally {
                busy.value = false
            }
        }
    }
}

/** Placeholder for a leg whose route hasn't resolved; such legs are skipped anyway. */
private const val UNRESOLVED_CODE = "???"

sealed interface ItineraryDetailLoadState {
    data object Loading : ItineraryDetailLoadState
    data object Missing : ItineraryDetailLoadState
    data class Found(val itinerary: Itinerary) : ItineraryDetailLoadState
}
