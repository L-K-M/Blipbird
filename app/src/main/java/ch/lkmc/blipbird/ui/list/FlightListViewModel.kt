package ch.lkmc.blipbird.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.blipbird.core.data.FlightRepository
import ch.lkmc.blipbird.core.data.IdentityResolver
import ch.lkmc.blipbird.core.database.TrackedFlightEntity
import ch.lkmc.blipbird.core.datastore.ProviderKeyStore
import ch.lkmc.blipbird.core.model.Designator
import ch.lkmc.blipbird.core.model.StatusSnapshot
import ch.lkmc.blipbird.core.model.TrackRequest
import ch.lkmc.blipbird.domain.DesignatorParser
import ch.lkmc.blipbird.domain.FlightPhaseMachine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

data class FlightRow(
    val id: Long,
    val title: String,             // designator or alias
    val subtitle: String?,         // alias present → designator shown small
    val depCode: String,
    val arrCode: String,
    val view: FlightPhaseMachine.View,
    val gate: String?,
    val terminal: String?,
    val updatedAt: Instant?,
    val airlineIata: String?,
)

data class ListUiState(
    val rows: List<FlightRow> = emptyList(),
    val refreshing: Boolean = false,
    val hasStatusKey: Boolean = true,
    val addError: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FlightListViewModel @Inject constructor(
    private val repository: FlightRepository,
    private val identity: IdentityResolver,
    keyStore: ProviderKeyStore,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val addError = MutableStateFlow<String?>(null)

    private val rows: StateFlow<List<FlightRow>> = repository.observeFlights()
        .flatMapLatest { flights ->
            if (flights.isEmpty()) flowOf(emptyList())
            else combine(flights.map { flight -> rowFlow(flight) }) { it.toList() }
        }
        .map { list -> list.sortedBy { it.view.nextEventAt ?: Instant.MAX } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<ListUiState> =
        combine(rows, refreshing, keyStore.hasAnyStatusKey, addError) { r, busy, hasKey, err ->
            ListUiState(rows = r, refreshing = busy, hasStatusKey = hasKey, addError = err)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListUiState())

    private fun rowFlow(flight: TrackedFlightEntity) =
        repository.observeSnapshot(flight.id).map { snapshot: StatusSnapshot? ->
            val view = FlightPhaseMachine.derive(snapshot, null, Instant.now())
            val designator = Designator(flight.designatorIata, flight.designatorIcao, flight.flightNumber, flight.suffix)
            FlightRow(
                id = flight.id,
                title = flight.alias ?: designator.display,
                subtitle = if (flight.alias != null) designator.display else null,
                depCode = snapshot?.departure?.code ?: "···",
                arrCode = snapshot?.arrival?.code ?: "···",
                view = view,
                gate = snapshot?.depGate,
                terminal = snapshot?.depTerminal,
                updatedAt = snapshot?.fetchedAt,
                airlineIata = designator.airlineIata,
            )
        }

    /** Batch add: "CA861, LX1612" or "CCA861/CA861"; each token resolves to one row. */
    fun addFlights(input: String, date: LocalDate?, alias: String?, onFirstTrack: () -> Unit) {
        viewModelScope.launch {
            val tokens = DesignatorParser.splitBatch(input)
            if (tokens.isEmpty()) { addError.value = "No flight number recognized"; return@launch }
            var added = 0
            for (token in tokens) {
                val designator = identity.resolveToken(token)
                if (designator == null) {
                    addError.value = "Couldn't parse “$token”"
                    continue
                }
                val id = repository.track(
                    TrackRequest(designator, date, alias.takeIf { tokens.size == 1 })
                )
                added++
                launch { repository.refreshStatus(id, force = true) }
            }
            if (added > 0) onFirstTrack()
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            refreshing.value = true
            try {
                for (flight in repository.activeFlights()) {
                    repository.refreshStatus(flight.id, force = true)
                }
            } finally {
                refreshing.value = false
            }
        }
    }

    fun archive(id: Long) = viewModelScope.launch { repository.archive(id) }
    fun delete(id: Long) = viewModelScope.launch { repository.delete(id) }
    fun clearAddError() { addError.value = null }
}
