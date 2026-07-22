package ch.lkmc.blipbird.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.blipbird.core.data.FlightRepository
import ch.lkmc.blipbird.core.data.IdentityResolver
import ch.lkmc.blipbird.core.data.WeatherRepository
import ch.lkmc.blipbird.core.database.AirportEntity
import ch.lkmc.blipbird.core.database.ReferenceDao
import ch.lkmc.blipbird.core.model.AirportRef
import ch.lkmc.blipbird.core.model.AirportWeather
import ch.lkmc.blipbird.core.model.Designator
import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.core.model.PositionFix
import ch.lkmc.blipbird.core.model.StatusSnapshot
import ch.lkmc.blipbird.core.model.WeatherSample
import ch.lkmc.blipbird.domain.DaylightEngine
import ch.lkmc.blipbird.domain.FlightPhaseMachine
import ch.lkmc.blipbird.domain.GreatCircle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/** Countdown/progress re-derivation cadence while the screen is visible. */
private const val TICK_MILLIS = 30_000L

data class DetailUiState(
    val flightId: Long = 0,
    val title: String = "",
    val designator: String = "",
    val alias: String? = null,
    val airlineName: String? = null,
    val snapshot: StatusSnapshot? = null,
    val view: FlightPhaseMachine.View =
        FlightPhaseMachine.derive(null, null, Instant.EPOCH),
    val depAirport: AirportRef? = null,
    val arrAirport: AirportRef? = null,
    val lastFix: PositionFix? = null,
    val track: List<PositionFix> = emptyList(),
    val daylight: DaylightEngine.Result? = null,
    val routeWeather: List<WeatherSample> = emptyList(),
    val airportWeather: List<AirportWeather> = emptyList(),
    val refreshing: Boolean = false,
    val updatedAt: Instant? = null,
)

@HiltViewModel
class FlightDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: FlightRepository,
    private val referenceDao: ReferenceDao,
    private val weatherRepository: WeatherRepository,
    private val identity: IdentityResolver,
) : ViewModel() {

    // Set via setFlightId from the screen (hand-rolled nav has no route args container).
    private val flightId = MutableStateFlow(savedStateHandle.get<Long>("flightId") ?: -1L)

    private val refreshing = MutableStateFlow(false)
    private val daylight = MutableStateFlow<DaylightEngine.Result?>(null)
    private val routeWeather = MutableStateFlow<List<WeatherSample>>(emptyList())
    private val airportWeather = MutableStateFlow<List<AirportWeather>>(emptyList())
    private val enriched = MutableStateFlow<Pair<AirportRef?, AirportRef?>>(null to null)
    private val airlineName = MutableStateFlow<String?>(null)
    private val flightEntity = MutableStateFlow<ch.lkmc.blipbird.core.database.TrackedFlightEntity?>(null)

    private var pollJob: Job? = null
    private var lastComputedSnapshotAt: Instant? = null

    fun setFlightId(id: Long) {
        if (flightId.value == id) return
        flightId.value = id
        bind(id)
    }

    init {
        if (flightId.value > 0) bind(flightId.value)
    }

    private var boundId: Long = -1
    private val snapshot = MutableStateFlow<StatusSnapshot?>(null)
    private val lastFix = MutableStateFlow<PositionFix?>(null)
    private val track = MutableStateFlow<List<PositionFix>>(emptyList())

    /**
     * Re-derives the phase view between data emissions so the hero countdown and
     * progress bar keep moving. Stops with the last uiState collector.
     */
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(TICK_MILLIS)
        }
    }

    private fun bind(id: Long) {
        if (boundId == id) return
        boundId = id
        viewModelScope.launch {
            flightEntity.value = repository.flight(id)
            flightEntity.value?.let { f ->
                airlineName.value = identity.airlineName(
                    Designator(f.designatorIata, f.designatorIcao, f.flightNumber, f.suffix)
                )
            }
        }
        viewModelScope.launch {
            repository.observeSnapshot(id).collect { snap ->
                snapshot.value = snap
                if (snap != null) onSnapshot(snap)
            }
        }
        viewModelScope.launch { repository.observeLatestFix(id).collect { lastFix.value = it } }
        viewModelScope.launch { repository.observeTrack(id).collect { track.value = it } }
        viewModelScope.launch { repository.refreshStatus(id) }
        startPolling(id)
    }

    val uiState: StateFlow<DetailUiState> = combine(
        listOf(flightId, flightEntity, snapshot, lastFix, track, daylight, routeWeather, airportWeather, enriched, airlineName, refreshing, ticker)
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val id = values[0] as Long
        val flight = values[1] as ch.lkmc.blipbird.core.database.TrackedFlightEntity?
        val snap = values[2] as StatusSnapshot?
        val fix = values[3] as PositionFix?
        val trk = values[4] as List<PositionFix>
        val day = values[5] as DaylightEngine.Result?
        val rw = values[6] as List<WeatherSample>
        val aw = values[7] as List<AirportWeather>
        val (dep, arr) = values[8] as Pair<AirportRef?, AirportRef?>
        val airline = values[9] as String?
        val busy = values[10] as Boolean

        val designator = flight?.let {
            Designator(it.designatorIata, it.designatorIcao, it.flightNumber, it.suffix).display
        } ?: ""
        DetailUiState(
            flightId = id,
            title = flight?.alias ?: designator,
            designator = designator,
            alias = flight?.alias,
            airlineName = airline,
            snapshot = snap,
            view = FlightPhaseMachine.derive(snap, fix, Instant.now()),
            depAirport = dep ?: snap?.departure,
            arrAirport = arr ?: snap?.arrival,
            lastFix = fix,
            track = trk,
            daylight = day,
            routeWeather = rw,
            airportWeather = aw,
            refreshing = busy,
            updatedAt = snap?.fetchedAt,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            try {
                repository.refreshStatus(flightId.value, force = true)
                repository.pollPosition(flightId.value)
            } finally {
                refreshing.value = false
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private suspend fun onSnapshot(snap: StatusSnapshot) {
        // Enrich airports from the bundled reference table (coords + tz + names).
        val dep = enrich(snap.departure)
        val arr = enrich(snap.arrival)
        enriched.value = dep to arr

        // Recompute ribbon inputs only when the snapshot actually changed.
        if (lastComputedSnapshotAt == snap.fetchedAt) return
        lastComputedSnapshotAt = snap.fetchedAt

        computeDaylight(snap, dep, arr)
        fetchWeather(snap, dep, arr)
    }

    private suspend fun enrich(ref: AirportRef?): AirportRef? {
        if (ref == null) return null
        if (ref.lat != null && ref.tz != null) return ref
        val row: AirportEntity? = ref.iata?.let { referenceDao.airportByIata(it) }
            ?: ref.icao?.let { referenceDao.airportByIcao(it) }
        return if (row == null) ref else AirportRef(
            icao = ref.icao ?: row.icao,
            iata = ref.iata ?: row.iata,
            name = ref.name ?: row.name,
            city = ref.city ?: row.city,
            country = ref.country ?: row.country,
            lat = ref.lat ?: row.lat,
            lon = ref.lon ?: row.lon,
            tz = ref.tz ?: row.tz,
        )
    }

    private suspend fun computeDaylight(snap: StatusSnapshot, dep: AirportRef?, arr: AirportRef?) {
        val depLat = dep?.lat; val depLon = dep?.lon
        val arrLat = arr?.lat; val arrLon = arr?.lon
        if (depLat == null || depLon == null || arrLat == null || arrLon == null) {
            daylight.value = null; return
        }
        // Airborne window ≈ runway times when known, else gate times ± taxi allowance.
        val up = snap.depTimes.bestRunway ?: snap.depTimes.best?.plus(Duration.ofMinutes(15)) ?: return
        val down = snap.arrTimes.bestRunway ?: snap.arrTimes.best?.minus(Duration.ofMinutes(10)) ?: return
        if (!down.isAfter(up)) { daylight.value = null; return }
        daylight.value = withContext(Dispatchers.Default) {
            runCatching {
                DaylightEngine.compute(
                    GreatCircle.Point(depLat, depLon),
                    GreatCircle.Point(arrLat, arrLon),
                    up, down,
                )
            }.getOrNull()
        }
    }

    private suspend fun fetchWeather(snap: StatusSnapshot, dep: AirportRef?, arr: AirportRef?) {
        // Airport METARs (one batched call).
        val stations = listOfNotNull(dep?.icao, arr?.icao)
        if (stations.isNotEmpty()) {
            airportWeather.value = weatherRepository.airportWeather(stations)
        }
        // En-route samples at overflight hours (one multi-point call).
        val day = daylight.value ?: return
        if (day.samples.isEmpty()) return
        val sampleCount = 12
        val points = (0 until sampleCount).map { i ->
            val idx = (i.toDouble() / (sampleCount - 1) * (day.samples.size - 1)).toInt()
            val s = day.samples[idx]
            Triple(s.lat, s.lon, s.at)
        }
        routeWeather.value = weatherRepository.routeWeather(points)
    }

    /**
     * Foreground live polling (PLAN.md §4.2): ~10 s while this screen is visible and
     * the flight is plausibly airborne; ~60 s otherwise near departure; paused when
     * far from the operational window. Stops with the ViewModel.
     */
    private fun startPolling(id: Long) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                val snap = snapshot.value
                val view = FlightPhaseMachine.derive(snap, lastFix.value, Instant.now())
                val interval = when (view.status) {
                    FlightStatus.DEPARTED, FlightStatus.EN_ROUTE, FlightStatus.APPROACHING -> 10_000L
                    FlightStatus.ON_TIME, FlightStatus.DELAYED, FlightStatus.SCHEDULED -> {
                        val dep = snap?.depTimes?.best
                        if (dep != null && Duration.between(Instant.now(), dep).abs() < Duration.ofHours(2)) 60_000L else 0L
                    }
                    else -> 0L
                }
                if (interval > 0) {
                    repository.pollPosition(id)
                    delay(interval)
                } else {
                    delay(120_000L)
                }
            }
        }
    }
}
