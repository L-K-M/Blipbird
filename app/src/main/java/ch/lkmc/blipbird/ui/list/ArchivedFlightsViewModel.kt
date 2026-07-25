package ch.lkmc.blipbird.ui.list

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.data.FlightRepository
import ch.lkmc.blipbird.core.data.FlightLifecycleCoordinator
import ch.lkmc.blipbird.core.data.IdentityResolver
import ch.lkmc.blipbird.core.data.ItineraryRepository
import ch.lkmc.blipbird.core.database.TrackedFlightEntity
import ch.lkmc.blipbird.core.model.Designator
import ch.lkmc.blipbird.ui.itinerary.dateSpan
import ch.lkmc.blipbird.ui.itinerary.designatorLine
import ch.lkmc.blipbird.ui.itinerary.displayTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * A single "Past flights" row. Archived flights don't update live, so this is
 * built once from whatever snapshot survives the retention prune — route/times
 * fade after a few days, leaving the designator and airline, which is enough to
 * recognise the flight and decide whether to restore or remove it.
 */
@Immutable
data class ArchivedRow(
    val id: Long,
    val title: String,             // alias, else the designator
    val subtitle: String?,         // designator, shown small, when an alias is set
    val airlineIata: String?,
    val airlineName: String?,
    val depCode: String?,
    val arrCode: String?,
    /** Departure date (device zone), if a snapshot is still cached; else null. */
    val whenLabel: String?,
)

@Immutable
data class ArchivedItineraryRow(
    val id: Long,
    val title: String,
    val dateSpan: String?,
    val designators: String,
    val flightCount: Int,
)

data class ArchivedUiState(
    val itineraries: List<ArchivedItineraryRow> = emptyList(),
    val flights: List<ArchivedRow> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class ArchivedFlightsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: FlightRepository,
    itineraryRepository: ItineraryRepository,
    private val identity: IdentityResolver,
    private val lifecycle: FlightLifecycleCoordinator,
) : ViewModel() {
    private val mutationError = MutableStateFlow<String?>(null)

    private val rows = repository.observeArchivedFlights()
        // Iterable.map is inline, so calling the suspend buildRow inside its
        // lambda is fine within this (suspend) Flow.map transform.
        .map { flights -> flights.map { buildRow(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<ArchivedUiState> = kotlinx.coroutines.flow.combine(
        rows,
        itineraryRepository.observeArchived(),
        mutationError,
    ) { flights, itineraries, error ->
        ArchivedUiState(
            flights = flights,
            itineraries = itineraries.map { itinerary ->
                ArchivedItineraryRow(
                    id = itinerary.id,
                    title = itinerary.displayTitle(context),
                    dateSpan = itinerary.dateSpan(context),
                    designators = itinerary.designatorLine(
                        limit = 4,
                        separator = " ${context.getString(R.string.itinerary_designator_separator)} ",
                        overflowLabel = { context.getString(R.string.itinerary_more_flights, it) },
                    ),
                    flightCount = itinerary.legs.size,
                )
            },
            error = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArchivedUiState())

    private suspend fun buildRow(f: TrackedFlightEntity): ArchivedRow {
        val designator = Designator(f.designatorIata, f.designatorIcao, f.flightNumber, f.suffix)
        val snap = repository.latestSnapshot(f.id)
        return ArchivedRow(
            id = f.id,
            title = f.alias ?: designator.display,
            subtitle = if (f.alias != null) designator.display else null,
            airlineIata = designator.airlineIata,
            airlineName = identity.airlineName(designator),
            depCode = snap?.departure?.code,
            arrCode = snap?.arrival?.code,
            whenLabel = snap?.depTimes?.best?.let { DATE_FORMAT.format(it) },
        )
    }

    private companion object {
        private val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE d MMM").withZone(ZoneId.systemDefault())
    }

    /** Move the flight back onto the active list and re-arm its reminders. */
    fun restore(id: Long) = mutate { lifecycle.restoreFlight(id) }

    /** Permanently remove the flight and its history — no undo. */
    fun deleteForever(id: Long) = mutate { lifecycle.deleteFlight(id) }

    fun restoreItinerary(id: Long) = mutate { lifecycle.restoreItinerary(id) }

    fun deleteItinerary(id: Long, deleteFlights: Boolean) = mutate {
        lifecycle.deleteItinerary(id, deleteFlights)
    }

    private fun mutate(block: suspend () -> Unit) = viewModelScope.launch {
        mutationError.value = null
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutationError.value = "Couldn't update past flights. Try again."
        }
    }
}
