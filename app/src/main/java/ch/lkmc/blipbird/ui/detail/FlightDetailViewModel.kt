package ch.lkmc.blipbird.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.blipbird.core.data.AirportEnricher
import ch.lkmc.blipbird.core.data.FlightRepository
import ch.lkmc.blipbird.core.data.IdentityResolver
import ch.lkmc.blipbird.core.data.StatusRefreshCoordinator
import ch.lkmc.blipbird.core.data.WeatherRepository
import ch.lkmc.blipbird.core.datastore.DossierSection
import ch.lkmc.blipbird.core.datastore.DossierSections
import ch.lkmc.blipbird.core.datastore.ProviderKeyStore
import ch.lkmc.blipbird.core.datastore.SettingsRepository
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
import ch.lkmc.blipbird.domain.JetlagEngine
import ch.lkmc.blipbird.domain.LookupOutcome
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
    /** Derived body-clock card (§9.5); null until both airport zones resolve. */
    val bodyClock: JetlagEngine.BodyClock? = null,
    val routeWeather: List<WeatherSample> = emptyList(),
    val airportWeather: List<AirportWeather> = emptyList(),
    val refreshing: Boolean = false,
    val updatedAt: Instant? = null,
    /** Which optional cards to draw (§9.6); all of them until the user says otherwise. */
    val sections: DossierSections = DossierSections(),
    /** OpenSky API client configured — gates the optional flown-path hint. */
    val hasOpenSky: Boolean = true,
    /** Latest lookup failure, null when the last lookup succeeded (G5). */
    val lookupProblem: LookupOutcome? = null,
)

/**
 * What the derived cards are built from. Its equality is the recompute guard:
 * an identical re-emission does no work, which is what the old
 * `lastComputedSnapshotAt` field did by hand.
 *
 * Only the two cards that actually cost a request take part in the key. Carrying
 * the whole [DossierSections] here would make a body-clock, airline, or map
 * toggle re-run the weather and daylight work for a snapshot already derived —
 * spending an aviationweather.gov and an Open-Meteo call to redraw cards that
 * were never hidden, which is precisely what §9.6 claims to avoid.
 */
private data class Derivation(
    val snapshot: StatusSnapshot?,
    val ribbon: Boolean,
    val weather: Boolean,
)

@HiltViewModel
class FlightDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: FlightRepository,
    private val statusRefresh: StatusRefreshCoordinator,
    private val airports: AirportEnricher,
    private val weatherRepository: WeatherRepository,
    private val identity: IdentityResolver,
    private val settings: SettingsRepository,
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
    private val _flightActive = MutableStateFlow<Boolean?>(null)
    val flightActive: StateFlow<Boolean?> = _flightActive

    private var pollJob: Job? = null

    // Declared above `init` so their initializers don't run after bind() resets
    // them (Kotlin runs property initializers and init blocks in declaration order).
    private var boundId: Long = -1
    private val snapshot = MutableStateFlow<StatusSnapshot?>(null)
    private val lastFix = MutableStateFlow<PositionFix?>(null)
    private val track = MutableStateFlow<List<PositionFix>>(emptyList())
    private val lookupAttempt = MutableStateFlow<FlightRepository.LookupAttempt?>(null)

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
     * Since the G10 nav rework this ViewModel is nav-entry-scoped — popping the
     * screen clears it and cancels the poll loop — so this gate's remaining job
     * is stopping foreground polling while the app is backgrounded.
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
            repository.observeFlight(id).collect { flight ->
                flightEntity.value = flight
                _flightActive.value = flight?.archived == false
                airlineName.value = flight?.let { f ->
                    identity.airlineName(
                        Designator(f.designatorIata, f.designatorIcao, f.flightNumber, f.suffix)
                    )
                }
            }
        }
        viewModelScope.launch { repository.observeSnapshot(id).collect { snapshot.value = it } }
        viewModelScope.launch {
            // Derived cards recompute when the snapshot changes *or* when a card is
            // shown/hidden: hiding one has to stop its fetch, and showing it again
            // has to fill it in without waiting for the next refresh. Airport
            // enrichment is cached, so re-running it on a toggle is nearly free.
            combine(snapshot, settings.dossierSections) { snap, visible ->
                Derivation(
                    snapshot = snap,
                    ribbon = visible.shows(DossierSection.RIBBON),
                    weather = visible.shows(DossierSection.WEATHER),
                )
            }
                .distinctUntilChanged()
                .collectLatest { (snap, ribbon, weather) ->
                    if (snap != null) derive(snap, ribbon, weather)
                }
        }
        viewModelScope.launch { repository.observeLatestFix(id).collect { lastFix.value = it } }
        viewModelScope.launch { repository.observeTrack(id).collect { track.value = it } }
        viewModelScope.launch { repository.observeLookupAttempt(id).collect { lookupAttempt.value = it } }
        viewModelScope.launch { statusRefresh.refreshFlight(id) }
        startPolling(id)
    }

    val uiState: StateFlow<DetailUiState> = combine(
        listOf(flightId, flightEntity, snapshot, lastFix, track, daylight, routeWeather, airportWeather, enriched, airlineName, refreshing, clock, hasOpenSky, lookupAttempt, settings.dossierSections)
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
        val attempt = values[13] as FlightRepository.LookupAttempt?
        val visible = values[14] as DossierSections

        val d = flight?.let { Designator(it.designatorIata, it.designatorIcao, it.flightNumber, it.suffix) }
        val designator = d?.display ?: ""
        val depAirport = dep ?: snap?.departure
        val arrAirport = arr ?: snap?.arrival
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
            depAirport = depAirport,
            arrAirport = arrAirport,
            lastFix = fix,
            track = trk,
            daylight = day,
            // Pure offset arithmetic — cheap enough to re-derive in the combine
            // rather than carry a fifteenth upstream flow. Note what it does *not*
            // read: `day` is null whenever the ribbon is hidden (§9.6), and the body
            // clock is an independent card that has to survive that, so its zones
            // come from the enriched airports rather than from the daylight result.
            bodyClock = snap?.arrTimes?.best?.let {
                JetlagEngine.compute(it, depAirport?.tz, arrAirport?.tz)
            },
            routeWeather = rw,
            airportWeather = aw,
            refreshing = busy,
            updatedAt = snap?.fetchedAt,
            sections = visible,
            hasOpenSky = openSky,
            lookupProblem = attempt?.outcome?.takeIf { it != LookupOutcome.SUCCESS },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            try {
                statusRefresh.refreshFlight(flightId.value, force = true)
                repository.pollPosition(flightId.value)
                repository.backfillTrack(flightId.value, force = true)
            } finally {
                refreshing.value = false
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private suspend fun derive(snap: StatusSnapshot, ribbon: Boolean, weather: Boolean) {
        // Enrich airports from the bundled reference table (coords + tz + names).
        val dep = airports.enrich(snap.departure)
        val arr = airports.enrich(snap.arrival)
        enriched.value = dep to arr

        computeDaylight(snap, dep, arr, ribbon)
        fetchWeather(dep, arr, weather)
    }

    private suspend fun computeDaylight(
        snap: StatusSnapshot,
        dep: AirportRef?,
        arr: AirportRef?,
        ribbonVisible: Boolean,
    ) {
        // The ribbon is the only consumer; hidden, its samples are dead work.
        if (!ribbonVisible) { daylight.value = null; return }
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

    private suspend fun fetchWeather(dep: AirportRef?, arr: AirportRef?, weatherVisible: Boolean) {
        // Airport METARs (one batched call) feed the weather card and nothing else,
        // so a hidden card means the aviationweather.gov request is simply not made.
        if (!weatherVisible) {
            airportWeather.value = emptyList()
        } else {
            val stations = listOfNotNull(dep?.icao, arr?.icao)
            // Clear only when there is nothing to ask for. Clearing before the call
            // instead would blank the card for the duration of every re-derivation —
            // including one triggered by toggling an unrelated card.
            if (stations.isEmpty()) airportWeather.value = emptyList()
            else airportWeather.value = weatherRepository.airportWeather(stations)
        }
        // En-route samples at overflight hours (one multi-point call). These are the
        // ribbon's weather half, and daylight is null whenever the ribbon is hidden —
        // so hiding it also spares Open-Meteo the request.
        val day = daylight.value
        if (day == null || day.samples.isEmpty()) { routeWeather.value = emptyList(); return }
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
