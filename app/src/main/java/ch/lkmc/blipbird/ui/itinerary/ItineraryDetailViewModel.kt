package ch.lkmc.blipbird.ui.itinerary

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.blipbird.core.data.AirportEnricher
import ch.lkmc.blipbird.core.data.FlightLifecycleCoordinator
import ch.lkmc.blipbird.core.data.FlightRepository
import ch.lkmc.blipbird.core.data.ItineraryRepository
import ch.lkmc.blipbird.core.data.StatusRefreshCoordinator
import ch.lkmc.blipbird.core.data.designator
import ch.lkmc.blipbird.core.datastore.DossierSection
import ch.lkmc.blipbird.core.datastore.ProviderKeyStore
import ch.lkmc.blipbird.core.datastore.SettingsRepository
import ch.lkmc.blipbird.core.model.AirportRef
import ch.lkmc.blipbird.core.model.BaggagePlan
import ch.lkmc.blipbird.core.model.BookingArrangement
import ch.lkmc.blipbird.core.model.DateIntentSource
import ch.lkmc.blipbird.core.model.Itinerary
import ch.lkmc.blipbird.core.model.ItineraryLeg
import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.core.model.StatusSnapshot
import ch.lkmc.blipbird.core.model.TransitionIntent
import ch.lkmc.blipbird.domain.FlightDates
import ch.lkmc.blipbird.domain.FlightPhaseMachine
import ch.lkmc.blipbird.domain.JetlagEngine
import ch.lkmc.blipbird.domain.LookupOutcome
import ch.lkmc.blipbird.domain.TransitionEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/**
 * One leg of the itinerary as the detail screen draws it: the user's own facts
 * (designator, alias, the confirmed departure-airport-local date) plus whatever
 * the status layer has resolved for that occurrence.
 *
 * [Immutable] for the same reason [ch.lkmc.blipbird.ui.list.FlightRow] is: every
 * field is a val, but the java.time types would otherwise infer the class as
 * unstable and re-run every leg card on an unrelated change.
 */
@Immutable
data class ItineraryLegUi(
    val legId: Long,
    val flightId: Long,
    /** 1-based position on the journey spine. */
    val position: Int,
    val title: String,
    val designator: String,
    val alias: String?,
    /** The leg's departure-airport-local date. */
    val date: LocalDate?,
    /** [date] is the user's own confirmation rather than a provider-pinned value. */
    val dateConfirmed: Boolean,
    val depCode: String?,
    val depCity: String?,
    val depTz: String?,
    val arrCode: String?,
    val arrCity: String?,
    val arrTz: String?,
    val depTime: Instant?,
    val arrTime: Instant?,
    /** Display-only calendar-day marker: +1 red-eye, −1 across the date line. */
    val arrDayOffset: Int?,
    val view: FlightPhaseMachine.View,
    val terminal: String?,
    val gate: String?,
    val baggageBelt: String?,
    val fetchedAt: Instant?,
    val lookupProblem: LookupOutcome?,
    /** Which service the problem belongs to; empty when unrecorded. */
    val lookupProviders: List<String> = emptyList(),
    /** A snapshot exists for this flight, whichever occurrence it describes. */
    val hasSnapshot: Boolean,
    /** That snapshot describes the occurrence the user confirmed (§8.8). */
    val occurrenceConfirmed: Boolean,
    /** The shared tick this row was derived from — never call `Instant.now()` in the card. */
    val now: Instant,
) {
    val routeResolved: Boolean get() = depCode != null && arrCode != null

    /** In the air right now — the one state where a leg card draws motion. */
    val airborne: Boolean
        get() = view.status == FlightStatus.DEPARTED ||
            view.status == FlightStatus.EN_ROUTE ||
            view.status == FlightStatus.APPROACHING
}

/** One edge of the journey spine: the user's answers plus the derived assessment. */
@Immutable
data class ItineraryTransitionUi(
    val transitionId: Long,
    /** The leg this edge follows on the spine. */
    val inboundLegId: Long,
    val intent: TransitionIntent,
    val booking: BookingArrangement,
    val baggage: BaggagePlan,
    val assessment: TransitionEngine.Assessment,
    /** Titles of the two legs it joins, for the "waiting for LX2801's route" copy. */
    val inboundTitle: String,
    val outboundTitle: String,
    val now: Instant,
)

@Immutable
data class ItineraryDetailUiState(
    val legs: List<ItineraryLegUi> = emptyList(),
    val transitions: List<ItineraryTransitionUi> = emptyList(),
    val bodyClock: JetlagEngine.Trip? = null,
    /** Airport chain across the whole plan once the routes resolve, e.g. "GVA → FRA → HND". */
    val routeChain: String? = null,
    val refreshing: Boolean = false,
    val hasStatusKey: Boolean = true,
) {
    /** No leg has any provider data yet — the screen explains that once, not per leg. */
    val awaitingFirstSnapshot: Boolean get() = legs.isNotEmpty() && legs.none { it.hasSnapshot }

    /**
     * The leg the journey is at: the first not yet arrived, else the last
     * (§7.2). -1 when the plan has no legs — callers compare it against
     * positions, and must not index [legs] with it.
     */
    val currentLegIndex: Int
        get() = legs.indexOfFirst {
            it.view.status != FlightStatus.LANDED && it.view.status != FlightStatus.ARRIVED
        }.takeIf { it >= 0 } ?: legs.lastIndex
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ItineraryDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val itineraries: ItineraryRepository,
    private val lifecycle: FlightLifecycleCoordinator,
    private val flights: FlightRepository,
    private val statusRefresh: StatusRefreshCoordinator,
    private val airports: AirportEnricher,
    settings: SettingsRepository,
    keyStore: ProviderKeyStore,
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
     * One shared tick (PLAN.md §6 Heartbeat) for every countdown on the screen —
     * leg phase lines and the connection's "until onward departure" alike.
     */
    private val clock: SharedFlow<Instant> = flow {
        while (true) { emit(Instant.now()); delay(30_000) }
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val refreshing = MutableStateFlow(false)

    /**
     * Member flight ids in spine order, re-emitting only when the *membership*
     * changes: the graph flow re-emits for every alias or answer edit, and this
     * chain's consumers only care about who is on the plan.
     */
    private val memberIds: Flow<List<Long>> = loadState
        .map { state -> (state as? ItineraryDetailLoadState.Found)?.itinerary?.legs?.map { it.flight.id }.orEmpty() }
        .distinctUntilChanged()

    /**
     * Provider-derived state per member flight, keyed by flight id. Rebuilt only
     * on membership change — rebuilding N Room flows on every graph edit would
     * drop and re-run the whole page.
     *
     * The quiet lookups ride this chain's own subscription instead of a standing
     * collector in `init`: a collector for the VM's whole life would hold
     * `loadState`'s Room observation open long after the UI stopped collecting,
     * silently defeating its WhileSubscribed policy. Launched, not inlined — a
     * lookup run must never delay the page's own emissions.
     */
    private val live: Flow<Map<Long, LegLive>> = memberIds
        .onEach(::launchQuietLookups)
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyMap())
            else combine(ids.map(::liveFlow)) { values -> values.associateBy { it.flightId } }
        }

    val uiState: StateFlow<ItineraryDetailUiState> = combine(
        loadState,
        live,
        clock,
        settings.dossierSections.map { it.shows(DossierSection.BODY_CLOCK) }.distinctUntilChanged(),
        combine(refreshing, keyStore.hasAnyStatusKey) { busy, hasKey -> busy to hasKey },
    ) { state, resolved, now, showBodyClock, (busy, hasKey) ->
        val itinerary = (state as? ItineraryDetailLoadState.Found)?.itinerary
        val legs = itinerary?.legs.orEmpty().mapIndexed { index, leg ->
            leg.toUi(index, resolved[leg.flight.id], now)
        }
        ItineraryDetailUiState(
            legs = legs,
            transitions = itinerary?.let { transitions(it, legs, resolved, now) }.orEmpty(),
            bodyClock = if (showBodyClock) bodyClock(itinerary, resolved) else null,
            routeChain = routeChain(legs),
            refreshing = busy,
            hasStatusKey = hasKey,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ItineraryDetailUiState())

    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    /** Members already given their one quiet lookup by this screen instance. */
    private val quietLookupAttempted = mutableSetOf<Long>()

    /** One lookup run at a time — a membership edit mid-run queues, never fans out. */
    private val quietLookupGate = Mutex()

    /**
     * A member that has never resolved shows nothing at all, and a plan more
     * than 48 h out is outside every background cadence (CadencePolicy), so
     * nothing would fill it in either. One debounced lookup per empty leg is the
     * difference between an itinerary that loads and one that looks broken.
     * Driven by the membership flow rather than once at open: a leg added by a
     * later edit arrives as a membership change and deserves the same first
     * lookup — otherwise it sits blank until a manual pull-to-refresh, with no
     * lookup attempt to even attribute the blankness to. Legs that already have
     * a snapshot are left to the worker and to pull-to-refresh rather than
     * spending a request here.
     */
    private fun launchQuietLookups(ids: List<Long>) {
        viewModelScope.launch {
            quietLookupGate.withLock {
                // A member removed by an edit forfeits its mark: coming back is
                // a deliberate re-add and earns a fresh first lookup, and the
                // set stays bounded by the current membership.
                quietLookupAttempted.retainAll(ids.toSet())
                try {
                    for (id in ids) {
                        // One attempt per present member per screen instance:
                        // membership re-emits on every add/remove, and a failed
                        // lookup must not turn each subsequent edit into a retry.
                        // Marked before the checks on purpose — if this id's
                        // eligibility read throws, the next run skips past it to
                        // the members after it instead of wedging on it forever.
                        if (!quietLookupAttempted.add(id)) continue
                        if (flights.latestSnapshot(id) != null) continue
                        refreshLegQuietly(id)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A failed eligibility read forfeits this run, not the
                    // screen's remaining lookups; worker and refresh still cover.
                    // Silent like every other ViewModel swallow here — the lookup
                    // path already records its own failure as the attributed
                    // outcome the leg card renders.
                }
            }
        }
    }

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

    fun updateIntent(transitionId: Long, value: TransitionIntent) = mutateTransition {
        itineraries.updateTransition(transitionId, intent = value)
    }

    /** Force a lookup for every member, sequentially — a 20-leg plan is not a fan-out. */
    fun refresh() {
        if (!refreshing.compareAndSet(expect = false, update = true)) return
        // A failed archive or transition edit leaves its message behind; the next
        // gesture shouldn't inherit it, the same way `mutate` clears before acting.
        error.value = null
        viewModelScope.launch {
            try {
                val legs = (loadState.value as? ItineraryDetailLoadState.Found)?.itinerary?.legs.orEmpty()
                for (leg in legs) {
                    refreshLegQuietly(leg.flight.id, force = true)
                }
            } finally {
                refreshing.value = false
            }
        }
    }

    // ------------------------------------------------------------------ derivation

    private data class LegLive(
        val flightId: Long,
        val snapshot: StatusSnapshot?,
        val departure: AirportRef?,
        val arrival: AirportRef?,
        val lookupProblem: LookupOutcome?,
        val lookupProviders: List<String>,
    )

    private fun liveFlow(flightId: Long): Flow<LegLive> = combine(
        flights.observeSnapshot(flightId),
        flights.observeLookupAttempt(flightId),
    ) { snapshot, attempt ->
        LegLive(
            flightId = flightId,
            snapshot = snapshot,
            // Enrichment is cached and fills the counterpart code the airport
            // match in TransitionEngine needs, plus the zones every local time
            // on this screen is rendered in.
            departure = airports.enrich(snapshot?.departure),
            arrival = airports.enrich(snapshot?.arrival),
            lookupProblem = attempt?.outcome?.takeIf { it != LookupOutcome.SUCCESS },
            lookupProviders = attempt?.providers.orEmpty(),
        )
    }

    private fun ItineraryLeg.toUi(index: Int, live: LegLive?, now: Instant): ItineraryLegUi {
        val snapshot = live?.snapshot
        val designator = flight.designator().display
        val confirmedDate = confirmedDate()
        val depTime = snapshot?.depTimes?.best
        val arrTime = snapshot?.arrTimes?.best
        val landed = snapshot?.arrTimes?.actual != null || snapshot?.arrTimes?.runwayActual != null
        return ItineraryLegUi(
            legId = id,
            flightId = flight.id,
            position = index + 1,
            title = flight.alias ?: designator,
            designator = designator,
            alias = flight.alias,
            date = confirmedDate ?: date,
            dateConfirmed = confirmedDate != null,
            depCode = live?.departure?.code ?: snapshot?.departure?.code,
            depCity = live?.departure?.city,
            depTz = live?.departure?.tz,
            arrCode = live?.arrival?.code ?: snapshot?.arrival?.code,
            arrCity = live?.arrival?.city,
            arrTz = live?.arrival?.tz,
            depTime = depTime,
            arrTime = arrTime,
            arrDayOffset = FlightDates.arrivalDayOffset(
                depTime, live?.departure?.tz, arrTime, live?.arrival?.tz,
            ),
            view = FlightPhaseMachine.derive(snapshot, null, now),
            terminal = snapshot?.depTerminal,
            gate = snapshot?.depGate,
            baggageBelt = snapshot?.baggageBelt?.takeIf { landed },
            fetchedAt = snapshot?.fetchedAt,
            lookupProblem = live?.lookupProblem,
            lookupProviders = live?.lookupProviders.orEmpty(),
            hasSnapshot = snapshot != null,
            occurrenceConfirmed = engineLeg(this, live).occurrenceConfirmed,
            now = now,
        )
    }

    private fun transitions(
        itinerary: Itinerary,
        legs: List<ItineraryLegUi>,
        resolved: Map<Long, LegLive>,
        now: Instant,
    ): List<ItineraryTransitionUi> {
        val legById = itinerary.legs.associateBy { it.id }
        val uiById = legs.associateBy { it.legId }
        return itinerary.transitions.mapNotNull { transition ->
            val inbound = legById[transition.inboundLegId] ?: return@mapNotNull null
            val outbound = legById[transition.outboundLegId] ?: return@mapNotNull null
            ItineraryTransitionUi(
                transitionId = transition.id,
                inboundLegId = transition.inboundLegId,
                intent = transition.intent,
                booking = transition.bookingArrangement,
                baggage = transition.baggagePlan,
                assessment = TransitionEngine.evaluate(
                    TransitionEngine.Input(
                        intent = transition.intent,
                        inbound = engineLeg(inbound, resolved[inbound.flight.id]),
                        outbound = engineLeg(outbound, resolved[outbound.flight.id]),
                        now = now,
                    )
                ),
                inboundTitle = uiById[inbound.id]?.title.orEmpty(),
                outboundTitle = uiById[outbound.id]?.title.orEmpty(),
                now = now,
            )
        }
    }

    private fun engineLeg(leg: ItineraryLeg, live: LegLive?): TransitionEngine.Leg =
        TransitionEngine.Leg(
            snapshot = live?.snapshot,
            confirmedDepartureDate = leg.confirmedDate(),
            departure = live?.departure,
            arrival = live?.arrival,
        )

    /**
     * The occurrence the user stands behind. A date the provider pinned for us
     * (`PROVIDER_RESOLVED_LEGACY`) or a dateless "next occurrence" flight is not
     * a confirmation, so it binds nothing — itineraries created through the
     * composer always carry `USER_CONFIRMED`.
     */
    private fun ItineraryLeg.confirmedDate(): LocalDate? = date
        ?.takeIf { DateIntentSource.decode(flight.dateIntentSource) == DateIntentSource.USER_CONFIRMED }

    private fun bodyClock(itinerary: Itinerary?, resolved: Map<Long, LegLive>): JetlagEngine.Trip? {
        val legs = itinerary?.legs.orEmpty()
        val start = legs.firstOrNull()?.let { resolved[it.flight.id]?.departure } ?: return null
        return JetlagEngine.trip(
            start.code,
            start.tz,
            legs.map { leg ->
                val live = resolved[leg.flight.id]
                JetlagEngine.TripLeg(
                    arrivalCode = live?.arrival?.code ?: UNRESOLVED_CODE,
                    arrival = live?.snapshot?.arrTimes?.best,
                    arrivalTz = live?.arrival?.tz,
                )
            },
        )
    }

    /**
     * "GVA → FRA → HND". Repeated codes collapse where a connection meets at one
     * airport; an unresolved leg contributes the same placeholder its card shows,
     * so the chain never implies a route Blipbird has not resolved.
     */
    private fun routeChain(legs: List<ItineraryLegUi>): String? {
        if (legs.none { it.routeResolved }) return null
        val codes = mutableListOf<String>()
        for (leg in legs) {
            val dep = leg.depCode ?: UNRESOLVED_CODE
            val arr = leg.arrCode ?: UNRESOLVED_CODE
            if (codes.lastOrNull() != dep) codes += dep
            if (codes.lastOrNull() != arr) codes += arr
        }
        return codes.joinToString(ROUTE_SEPARATOR)
    }

    /**
     * One leg's lookup, tolerating a provider failure but never a cancellation.
     * `runCatching` would swallow the `CancellationException` too, so a scope
     * cancelled mid-plan (the user navigating away) left the loop grinding
     * through every remaining leg instead of stopping. The provider's own reason
     * still reaches the user per leg, through `lookupProblem`.
     */
    private suspend fun refreshLegQuietly(flightId: Long, force: Boolean = false) {
        try {
            statusRefresh.refreshFlight(flightId, force = force)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Recorded on the snapshot; surfaced by the derivation below.
        }
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

/**
 * Placeholder for an airport a leg hasn't resolved yet. Punctuation rather than
 * words on purpose: it is the same mark the flight list uses for an unresolved
 * code, and it needs no translation.
 */
internal const val UNRESOLVED_CODE = "···"

private const val ROUTE_SEPARATOR = "  →  "

sealed interface ItineraryDetailLoadState {
    data object Loading : ItineraryDetailLoadState
    data object Missing : ItineraryDetailLoadState
    data class Found(val itinerary: Itinerary) : ItineraryDetailLoadState
}
