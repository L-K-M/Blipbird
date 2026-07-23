package ch.lkmc.blipbird.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.blipbird.core.data.FlightRepository
import ch.lkmc.blipbird.core.data.IdentityResolver
import ch.lkmc.blipbird.core.data.WeatherRepository
import ch.lkmc.blipbird.core.database.AirportEntity
import ch.lkmc.blipbird.core.database.ReferenceDao
import ch.lkmc.blipbird.core.datastore.ProviderKeyStore
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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

data class DetailUiState(
    val flightId: Long = 0,
    val title: String = "",
    val designator: String = "",
    val alias: String? = null,
    val airlineName: String? = null,
    val airlineIata: String? = null,
    val airlineIcao: String? = null,
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
    /** OpenSky API client configured — gates the optional flown-path hint. */
    val hasOpenSky: Boolean = true,
)

@HiltViewModel
class FlightDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: FlightRepository,
    private val referenceDao: ReferenceDao,
    private val weatherRepository: WeatherRepository,
    private val identity: IdentityResolver,
    keyStore: ProviderKeyStore,
) : ViewModel() {

    private val hasOpenSky = keyStore.hasOpenSkyClient

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

    // Declared above `init` so their initializers don't run after bind() resets
    // them (Kotlin runs property initializers and init blocks in declaration order).
    private var boundId: Long = -1
    private val snapshot = MutableStateFlow<StatusSnapshot?>(null)
    private val lastFix = MutableStateFlow<PositionFix?>(null)
    private val track = MutableStateFlow<List<PositionFix>>(emptyList())

    /**
     * One shared minute-tick (PLAN.md §6 Heartbeat) so the hero countdown / ETA
     * re-derives from a single time source. Without this, `Instant.now()` baked
     * into the [uiState] combine never updates and the countdown is frozen until
     * the next network write.
     */
    private val clock: SharedFlow<Instant> = flow {
        while (true) { emit(Instant.now()); delay(15_000) }
    }.distinctUntilChanged().shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /**
     * Whether this flight's screen is started (visible or behind a dialog).
     * Under the hand-rolled navigation the ViewModel is Activity-scoped and never
     * cleared, so the poll loop must gate on this instead of the ViewModel's own
     * lifetime — otherwise every detail screen ever opened keeps hitting the
     * ADS-B APIs (every 10 s while airborne) until the process dies.
     */
    private val screenVisible = MutableStateFlow(false)

    fun setScreenVisible(visible: Boolean) {
        screenVisible.value = visible
    }

    fun setFlightId(id: Long) {
        if (flightId.value == id) return
        flightId.value = id
        bind(id)
    }

    init {
        if (flightId.value > 0) bind(flightId.value)
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
        listOf(flightId, flightEntity, snapshot, lastFix, track, daylight, routeWeather, airportWeather, enriched, airlineName, refreshing, clock, hasOpenSky)
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
        val now = values[11] as Instant
        val openSky = values[12] as Boolean

        val d = flight?.let { Designator(it.designatorIata, it.designatorIcao, it.flightNumber, it.suffix) }
        val designator = d?.display ?: ""
        DetailUiState(
            flightId = id,
            title = flight?.alias ?: designator,
            designator = designator,
            alias = flight?.alias,
            airlineName = airline,
            airlineIata = d?.airlineIata,
            airlineIcao = d?.airlineIcao,
            snapshot = snap,
            view = FlightPhaseMachine.derive(snap, fix, now),
            depAirport = dep ?: snap?.departure,
            arrAirport = arr ?: snap?.arrival,
            lastFix = fix,
            track = trk,
            daylight = day,
            routeWeather = rw,
            airportWeather = aw,
            refreshing = busy,
            updatedAt = snap?.fetchedAt,
            hasOpenSky = openSky,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            try {
                repository.refreshStatus(flightId.value, force = true)
                repository.pollPosition(flightId.value)
                repository.backfillTrack(flightId.value, force = true)
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
                    // The engine defaults to the surface threshold since #57; the
                    // ribbon's cabin-visible markers deliberately assume a typical
                    // ~11 km cruise (PLAN.md §9.4) when the real altitude is unknown.
                    cruiseAltitudeMeters = 11_000.0,
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
            // collectLatest cancels the inner loop the moment the screen hides,
            // so not even an idle delay() timer outlives visibility.
            screenVisible.collectLatest { visible ->
                if (!visible) return@collectLatest
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
                        repository.backfillTrack(id)   // self-throttled; no-op without OpenSky creds
                        delay(interval)
                    } else {
                        // Also covers freshly-opened landed flights: one throttled
                        // backfill draws the completed exact path.
                        repository.backfillTrack(id)
                        delay(120_000L)
                    }
                }
            }
        }
    }
}
