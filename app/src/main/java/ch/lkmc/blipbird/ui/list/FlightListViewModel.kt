package ch.lkmc.blipbird.ui.list

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.data.AirportEnricher
import ch.lkmc.blipbird.core.data.FlightRepository
import ch.lkmc.blipbird.core.data.FlightLifecycleCoordinator
import ch.lkmc.blipbird.core.data.IdentityResolver
import ch.lkmc.blipbird.core.data.ItineraryRepository
import ch.lkmc.blipbird.core.data.StatusRefreshCoordinator
import ch.lkmc.blipbird.core.database.TrackedFlightEntity
import ch.lkmc.blipbird.core.datastore.ProviderKeyStore
import ch.lkmc.blipbird.core.model.AirportRef
import ch.lkmc.blipbird.core.model.Designator
import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.core.model.Itinerary
import ch.lkmc.blipbird.core.model.StatusSnapshot
import ch.lkmc.blipbird.core.model.TrackRequest
import ch.lkmc.blipbird.core.model.TransitionIntent
import ch.lkmc.blipbird.domain.DaylightEngine
import ch.lkmc.blipbird.domain.DesignatorParser
import ch.lkmc.blipbird.domain.FlightDates
import ch.lkmc.blipbird.domain.FlightPhaseMachine
import ch.lkmc.blipbird.domain.LookupOutcome
import ch.lkmc.blipbird.core.data.displayDesignator
import ch.lkmc.blipbird.ui.itinerary.UNRESOLVED_CODE
import ch.lkmc.blipbird.ui.itinerary.dateSpan
import ch.lkmc.blipbird.ui.itinerary.designatorLine
import ch.lkmc.blipbird.ui.itinerary.displayTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/**
 * Marked [Immutable] so Compose can skip a row whose value is unchanged: every
 * field is an immutable val, but the java.time types would otherwise make the
 * class infer as unstable and re-run [FlightRowCard] on unrelated list changes
 * (P4). Rows still recompose when they should — the shared `now` tick changes
 * every field-derived countdown, and `==` catches any data change.
 */
@Immutable
data class FlightRow(
    val id: Long,
    val title: String,             // designator or alias
    val subtitle: String?,         // alias present → designator shown small
    val depCode: String,
    val arrCode: String,
    val depCity: String?,
    val arrCity: String?,
    val depTime: Instant?,
    val depTz: String?,
    val arrTime: Instant?,
    val arrTz: String?,
    /** Display-only calendar-day marker: +1 red-eye, −1 across the date line. */
    val arrDayOffset: Int?,
    /** True solar elevation at the departure airport at departure time. */
    val solarElevationDeg: Double?,
    val view: FlightPhaseMachine.View,
    val gate: String?,
    val terminal: String?,
    val baggageBelt: String?,      // shown instead of gate once landed
    val updatedAt: Instant?,
    val airlineIata: String?,
    /** The shared ticker value this row was derived from — composables must use
     *  this instead of calling `Instant.now()` themselves (DS4-G9). */
    val now: Instant,
    /** Latest lookup failure, null when the last lookup succeeded (G5). */
    val lookupProblem: LookupOutcome? = null,
)

data class ListUiState(
    val rows: List<FlightRow> = emptyList(),
    val itineraries: List<ItineraryHomeRow> = emptyList(),
    val refreshing: Boolean = false,
    val hasStatusKey: Boolean = true,
    val addError: String? = null,
    /** Number of archived flights — gates the "Past flights" entry point. */
    val archivedCount: Int = 0,
    val ungroupedCount: Int = 0,
    /**
     * True only until the flights flow first emits — the pre-Room window on a
     * cold start. Gates a loading skeleton so the list no longer flashes the
     * "No flights yet" empty state before the saved flights load (glm 3.10).
     */
    val loading: Boolean = true,
)

@Immutable
data class ItineraryHomeRow(
    val id: Long,
    val title: String,
    val dateSpan: String?,
    val designators: String,
    val flightCount: Int,
    val transitions: List<TransitionIntent>,
    val sortDate: LocalDate?,
    val createdAt: Long,
    /** Airport chain once the member routes resolve, e.g. "GVA → FRA → HND". */
    val routeChain: String? = null,
    /** The leg the card leads with: the first one that hasn't arrived yet (§7.2). */
    val currentLeg: ItineraryHomeLeg? = null,
)

/** The one member flight an itinerary card features, with its live phase line. */
@Immutable
data class ItineraryHomeLeg(
    val title: String,
    val depCode: String?,
    val arrCode: String?,
    val arrTz: String?,
    val view: FlightPhaseMachine.View,
    /** The shared ticker value this row was derived from (DS4-G9). */
    val now: Instant,
)

/**
 * Loading vs loaded distinction for the list, folded into one flow so [uiState]
 * stays at five combine args (no six-arg typed overload). [Loading] is the
 * pre-first-emission state; once the flights flow emits — even an empty list —
 * we're [Loaded] and the skeleton gives way to the list or the empty state.
 */
private sealed interface RowsState {
    data object Loading : RowsState
    data class Loaded(val rows: List<FlightRow>) : RowsState
}

private sealed interface ItineraryRowsState {
    data object Loading : ItineraryRowsState
    data class Loaded(val rows: List<ItineraryHomeRow>) : ItineraryRowsState
}

private data class HomeContentState(
    val flights: List<FlightRow>,
    val itineraries: List<ItineraryHomeRow>,
    val loading: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FlightListViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: FlightRepository,
    private val itineraryRepository: ItineraryRepository,
    private val lifecycle: FlightLifecycleCoordinator,
    private val statusRefresh: StatusRefreshCoordinator,
    private val identity: IdentityResolver,
    private val airports: AirportEnricher,
    keyStore: ProviderKeyStore,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val addError = MutableStateFlow<String?>(null)

    /**
     * True while [addFlights] is resolving and tracking a pasted batch, so the
     * sheet can show progress and disable its button instead of looking inert
     * during the token lookups + track writes (V6). Kept out of [uiState] to
     * avoid a sixth combine arg (no typed overload); the sheet collects it directly.
     */
    private val _adding = MutableStateFlow(false)
    val adding: StateFlow<Boolean> = _adding.asStateFlow()

    /**
     * One shared minute-tick (PLAN.md §6 Heartbeat) so every row's countdown
     * re-derives from a single time source instead of each row holding its own
     * timer. Without this, `Instant.now()` baked into [rowFlow] never updates and
     * the "Departs in 2 h 14 m" label is frozen until the next network write.
     * Multicast via shareIn so N rows share one upstream ticker.
     */
    private val clock: SharedFlow<Instant> = flow {
        while (true) { emit(Instant.now()); delay(30_000) }
    }.distinctUntilChanged().shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val rowsState: StateFlow<RowsState> = repository.observeFlights()
        .flatMapLatest { flights ->
            if (flights.isEmpty()) flowOf(emptyList())
            else combine(flights.map { flight -> rowFlow(flight) }) { it.toList() }
        }
        .map { list ->
            // Finished flights carry their (past) landing time as nextEventAt,
            // which would pin them above every upcoming flight. Active flights
            // sort by next event; finished ones sink, most recently landed first.
            val (done, active) = list.partition {
                it.view.status == FlightStatus.LANDED || it.view.status == FlightStatus.ARRIVED
            }
            RowsState.Loaded(
                active.sortedBy { it.view.nextEventAt ?: Instant.MAX } +
                    done.sortedByDescending { it.view.nextEventAt ?: Instant.MIN }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RowsState.Loading)

    private val itineraryRowsState: StateFlow<ItineraryRowsState> = itineraryRepository.observeActive()
        .flatMapLatest { itineraries ->
            if (itineraries.isEmpty()) flowOf(emptyList())
            else combine(itineraries.map(::itineraryRowFlow)) { it.toList() }
        }
        .map { rows ->
            ItineraryRowsState.Loaded(
                rows.sortedWith(
                    compareBy<ItineraryHomeRow> { it.sortDate == null }
                        .thenBy { it.sortDate }
                        .thenBy { it.createdAt }
                )
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ItineraryRowsState.Loading)

    /**
     * An itinerary card with its members' live state folded in: without this the
     * card could only ever show flight numbers, which is exactly the "nothing
     * loads" impression the detail screen used to give. Snapshots are read from
     * Room — no extra provider requests — and the member flows are rebuilt only
     * when the graph itself changes.
     */
    private fun itineraryRowFlow(itinerary: Itinerary): Flow<ItineraryHomeRow> {
        val ids = itinerary.legs.map { it.flight.id }
        val snapshots: Flow<List<StatusSnapshot?>> =
            if (ids.isEmpty()) flowOf(emptyList())
            else combine(ids.map(repository::observeSnapshot)) { it.toList() }
        return combine(snapshots, clock) { snaps, now ->
            val routes = snaps.map { snapshot ->
                airports.enrich(snapshot?.departure) to airports.enrich(snapshot?.arrival)
            }
            val views = snaps.map { FlightPhaseMachine.derive(it, null, now) }
            val currentIndex = views.indexOfFirst {
                it.status != FlightStatus.ARRIVED && it.status != FlightStatus.LANDED
            }.takeIf { it >= 0 } ?: views.lastIndex
            ItineraryHomeRow(
                id = itinerary.id,
                title = itinerary.displayTitle(context),
                dateSpan = itinerary.dateSpan(context),
                designators = itinerary.designatorLine(
                    limit = 4,
                    separator = " ${context.getString(R.string.itinerary_designator_separator)} ",
                    overflowLabel = { context.getString(R.string.itinerary_more_flights, it) },
                ),
                flightCount = itinerary.legs.size,
                transitions = itinerary.transitions.map { it.intent },
                sortDate = itinerary.legs.mapNotNull { it.date }.minOrNull(),
                createdAt = itinerary.createdAt,
                routeChain = routeChain(routes),
                currentLeg = itinerary.legs.getOrNull(currentIndex)?.let { leg ->
                    ItineraryHomeLeg(
                        title = leg.flight.displayDesignator(),
                        depCode = routes.getOrNull(currentIndex)?.first?.code,
                        arrCode = routes.getOrNull(currentIndex)?.second?.code,
                        arrTz = routes.getOrNull(currentIndex)?.second?.tz,
                        view = views[currentIndex],
                        now = now,
                    )
                },
            )
        }
    }

    /** Null until at least one leg resolved, so the card never invents a route. */
    private fun routeChain(routes: List<Pair<AirportRef?, AirportRef?>>): String? {
        if (routes.none { it.first != null || it.second != null }) return null
        val codes = mutableListOf<String>()
        for ((departure, arrival) in routes) {
            val dep = departure?.code ?: UNRESOLVED_CODE
            val arr = arrival?.code ?: UNRESOLVED_CODE
            if (codes.lastOrNull() != dep) codes += dep
            codes += arr
        }
        return codes.joinToString("  →  ")
    }

    private val contentState = combine(rowsState, itineraryRowsState) { flights, itineraries ->
        val loadedFlights = (flights as? RowsState.Loaded)?.rows.orEmpty()
        val loadedItineraries = (itineraries as? ItineraryRowsState.Loaded)?.rows.orEmpty()
        HomeContentState(
            flights = loadedFlights,
            itineraries = loadedItineraries,
            loading = flights is RowsState.Loading || itineraries is ItineraryRowsState.Loading,
        )
    }

    val uiState: StateFlow<ListUiState> =
        combine(
            contentState,
            refreshing,
            keyStore.hasAnyStatusKey,
            addError,
            combine(
                repository.observeArchivedFlights().map { it.size },
                itineraryRepository.observeArchived().map { it.size },
            ) { flights, itineraries -> flights + itineraries },
        ) { content, busy, hasKey, err, archivedCount ->
            ListUiState(
                rows = content.flights,
                itineraries = content.itineraries,
                ungroupedCount = content.flights.size,
                loading = content.loading,
                refreshing = busy,
                hasStatusKey = hasKey,
                addError = err,
                archivedCount = archivedCount,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListUiState())

    private fun rowFlow(flight: TrackedFlightEntity) =
        combine(
            repository.observeSnapshot(flight.id),
            repository.observeLookupAttempt(flight.id),
            clock,
        ) { snapshot: StatusSnapshot?, attempt: FlightRepository.LookupAttempt?, now: Instant ->
            val view = FlightPhaseMachine.derive(snapshot, null, now)
            val designator = Designator(flight.designatorIata, flight.designatorIcao, flight.flightNumber, flight.suffix)
            val dep = airports.enrich(snapshot?.departure)
            val arr = airports.enrich(snapshot?.arrival)
            val depTime = snapshot?.depTimes?.best
            val arrTime = snapshot?.arrTimes?.best
            FlightRow(
                id = flight.id,
                title = flight.alias ?: designator.display,
                subtitle = if (flight.alias != null) designator.display else null,
                depCode = snapshot?.departure?.code ?: "···",
                arrCode = snapshot?.arrival?.code ?: "···",
                depCity = dep?.city,
                arrCity = arr?.city,
                depTime = depTime,
                depTz = dep?.tz,
                arrTime = arrTime,
                arrTz = arr?.tz,
                arrDayOffset = FlightDates.arrivalDayOffset(depTime, dep?.tz, arrTime, arr?.tz),
                solarElevationDeg = if (dep?.lat != null && dep.lon != null && depTime != null)
                    DaylightEngine.trueSolarElevation(dep.lat, dep.lon, depTime) else null,
                view = view,
                gate = snapshot?.depGate,
                terminal = snapshot?.depTerminal,
                baggageBelt = snapshot?.baggageBelt,
                updatedAt = snapshot?.fetchedAt,
                airlineIata = designator.airlineIata,
                now = now,
                lookupProblem = attempt?.outcome?.takeIf { it != LookupOutcome.SUCCESS },
            )
        }

    /**
     * Batch add: "CA861, LX1612" or "CCA861/CA861"; each token resolves to one row.
     * [onResult] reports whether every token was accepted, so the sheet can stay
     * open (showing the error) when something didn't parse.
     */
    fun addFlights(
        input: String,
        date: LocalDate?,
        alias: String?,
        onFirstTrack: () -> Unit,
        onResult: (allAccepted: Boolean) -> Unit = {},
    ) {
        // Ignore a re-entrant call while a batch is still resolving — defense in
        // depth behind the sheet's own submit guard. compareAndSet flips the flag
        // atomically, closing the check-then-set gap if two callers ever race.
        if (!_adding.compareAndSet(expect = false, update = true)) {
            // Still honor the callback contract every other path keeps: report
            // "not accepted" so a caller awaiting onResult can't hang on the drop.
            onResult(false)
            return
        }
        viewModelScope.launch {
            try {
                val tokens = DesignatorParser.splitBatch(input)
                if (tokens.isEmpty()) {
                    addError.value = "No flight number recognized"
                    onResult(false)
                    return@launch
                }
                var added = 0
                var failed = 0
                for (token in tokens) {
                    val designator = identity.resolveToken(token)
                    if (designator == null) {
                        addError.value = "Couldn't parse “$token”"
                        failed++
                        continue
                    }
                    val id = repository.track(
                        TrackRequest(designator, date, alias.takeIf { tokens.size == 1 })
                    )
                    added++
                    statusRefresh.reconcileSchedule()
                    launch { statusRefresh.refreshFlight(id, force = true) }
                }
                if (added > 0) onFirstTrack()
                onResult(failed == 0 && added > 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A DAO/IO failure or parser bug would otherwise escape the
                // coroutine (crash) and leave the sheet with a silently vanished
                // spinner. Surface it and report the result instead.
                addError.value = "Couldn't add flight — please try again"
                onResult(false)
            } finally {
                // Clears once the tokens are resolved and tracked; the per-flight
                // refreshes above run in the background (child launches) and
                // don't hold the sheet's spinner.
                _adding.value = false
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            refreshing.value = true
            try {
                for (flight in repository.activeFlights()) {
                    statusRefresh.refreshFlight(flight.id, force = true)
                }
            } finally {
                refreshing.value = false
            }
        }
    }

    fun rename(id: Long, alias: String?) = viewModelScope.launch { repository.setAlias(id, alias) }

    /**
     * Single-slot undo for the snackbar after a swipe-delete (a replaced snackbar
     * forfeits the older undo, like most inbox-style apps). [deleteJob] lets
     * undoDelete wait out an in-flight delete so a fast Undo tap can't observe
     * a not-yet-captured entity.
     */
    private var lastDeleted: TrackedFlightEntity? = null
    private var deleteJob: Job? = null

    fun archive(id: Long, onResult: (Boolean) -> Unit = {}) = viewModelScope.launch {
        try {
            lifecycle.archiveFlight(id)
            onResult(true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            onResult(false)
        }
    }

    fun unarchive(id: Long, onResult: (Boolean) -> Unit = {}) = viewModelScope.launch {
        try {
            lifecycle.restoreFlight(id)
            onResult(true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            onResult(false)
        }
    }

    fun delete(id: Long, onResult: (Boolean) -> Unit = {}) {
        deleteJob = viewModelScope.launch {
            try {
                lastDeleted = lifecycle.deleteFlight(id)
                onResult(lastDeleted != null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                lastDeleted = null
                onResult(false)
            }
        }
    }

    fun undoDelete(onResult: (Boolean) -> Unit = {}) = viewModelScope.launch {
        try {
            deleteJob?.join()
            val flight = lastDeleted ?: return@launch onResult(false)
            val id = lifecycle.restoreDeletedFlight(flight)
            lastDeleted = null
            onResult(true)
            launch {
                try {
                    statusRefresh.refreshFlight(id, force = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            onResult(false)
        }
    }

    fun archiveItinerary(id: Long, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        try {
            lifecycle.archiveItinerary(id)
            onResult(true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            onResult(false)
        }
    }

    fun restoreItinerary(id: Long, onResult: (Boolean) -> Unit = {}) = viewModelScope.launch {
        try {
            lifecycle.restoreItinerary(id)
            onResult(true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            onResult(false)
        }
    }

    fun clearAddError() { addError.value = null }
}
