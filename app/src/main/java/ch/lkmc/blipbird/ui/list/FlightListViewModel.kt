package ch.lkmc.blipbird.ui.list

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.blipbird.core.data.FlightRepository
import ch.lkmc.blipbird.core.data.IdentityResolver
import ch.lkmc.blipbird.core.database.ReferenceDao
import ch.lkmc.blipbird.core.database.TrackedFlightEntity
import ch.lkmc.blipbird.core.datastore.ProviderKeyStore
import ch.lkmc.blipbird.core.model.AirportRef
import ch.lkmc.blipbird.core.model.Designator
import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.core.model.StatusSnapshot
import ch.lkmc.blipbird.core.model.TrackRequest
import ch.lkmc.blipbird.domain.DaylightEngine
import ch.lkmc.blipbird.domain.DesignatorParser
import ch.lkmc.blipbird.domain.FlightDates
import ch.lkmc.blipbird.domain.FlightPhaseMachine
import ch.lkmc.blipbird.domain.LookupOutcome
import ch.lkmc.blipbird.platform.ReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import java.util.concurrent.ConcurrentHashMap
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
    val refreshing: Boolean = false,
    val hasStatusKey: Boolean = true,
    val addError: String? = null,
    /** Number of archived flights — gates the "Past flights" entry point. */
    val archivedCount: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FlightListViewModel @Inject constructor(
    private val repository: FlightRepository,
    private val identity: IdentityResolver,
    private val referenceDao: ReferenceDao,
    private val reminders: ReminderScheduler,
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

    /** Reference-airport hits keyed by IATA/ICAO; misses are rare and just re-query. */
    private val airportCache = ConcurrentHashMap<String, ch.lkmc.blipbird.core.database.AirportEntity>()

    private val rows: StateFlow<List<FlightRow>> = repository.observeFlights()
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
            active.sortedBy { it.view.nextEventAt ?: Instant.MAX } +
                done.sortedByDescending { it.view.nextEventAt ?: Instant.MIN }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<ListUiState> =
        combine(
            rows,
            refreshing,
            keyStore.hasAnyStatusKey,
            addError,
            repository.observeArchivedFlights().map { it.size },
        ) { r, busy, hasKey, err, archivedCount ->
            ListUiState(
                rows = r,
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
            val dep = resolve(snapshot?.departure)
            val arr = resolve(snapshot?.arrival)
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

    /** Fill lat/lon/tz/city gaps from the bundled reference DB (AeroAPI omits coordinates). */
    private suspend fun resolve(ref: AirportRef?): AirportRef? {
        if (ref == null) return null
        if (ref.lat != null && ref.lon != null && ref.tz != null && ref.city != null) return ref
        val key = ref.iata ?: ref.icao ?: return ref
        val entity = airportCache[key]
            ?: (ref.iata?.let { referenceDao.airportByIata(it) } ?: ref.icao?.let { referenceDao.airportByIcao(it) })
                ?.also { airportCache[key] = it }
            ?: return ref
        return ref.copy(
            city = ref.city ?: entity.city,
            lat = ref.lat ?: entity.lat,
            lon = ref.lon ?: entity.lon,
            tz = ref.tz ?: entity.tz,
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
        viewModelScope.launch {
            _adding.value = true
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
                    launch { repository.refreshStatus(id, force = true) }
                }
                if (added > 0) onFirstTrack()
                onResult(failed == 0 && added > 0)
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
                    repository.refreshStatus(flight.id, force = true)
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

    fun archive(id: Long) = viewModelScope.launch {
        reminders.cancel(id)
        repository.archive(id)
    }

    fun unarchive(id: Long) = viewModelScope.launch {
        repository.unarchive(id)
        reminders.reconcile(id)
    }

    fun delete(id: Long) {
        deleteJob = viewModelScope.launch {
            lastDeleted = repository.flight(id)
            reminders.cancel(id)
            repository.delete(id)
        }
    }

    fun undoDelete() = viewModelScope.launch {
        deleteJob?.join()
        val flight = lastDeleted ?: return@launch
        lastDeleted = null
        val id = repository.restore(flight)
        repository.refreshStatus(id, force = true)
        reminders.reconcile(id)
    }

    fun clearAddError() { addError.value = null }
}
