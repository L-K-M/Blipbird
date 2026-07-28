package ch.lkmc.blipbird.ui.itinerary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.model.BaggagePlan
import ch.lkmc.blipbird.core.model.BookingArrangement
import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.core.model.TimeCertainty
import ch.lkmc.blipbird.core.model.TransitionIntent
import ch.lkmc.blipbird.domain.FlightPhaseMachine
import ch.lkmc.blipbird.domain.TransitionEngine
import ch.lkmc.blipbird.ui.components.BirdRefreshIndicator
import ch.lkmc.blipbird.ui.components.StatusWord
import ch.lkmc.blipbird.ui.components.countdownText
import ch.lkmc.blipbird.ui.components.departsInText
import ch.lkmc.blipbird.ui.components.elapsedText
import ch.lkmc.blipbird.ui.components.landsInText
import ch.lkmc.blipbird.ui.components.localTime
import ch.lkmc.blipbird.ui.components.lookupProblemRes
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The journey spine (`docs/ITINERARY_PROPOSAL.md` §7.6): ordered leg rows joined
 * by explicit transition nodes. Each leg shows the user's own facts plus the
 * resolved route/times for the occurrence they confirmed; each transition shows
 * what [TransitionEngine] could derive for that edge, leading with the factual
 * state and only then with a duration (§7.7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItineraryDetailScreen(
    itineraryId: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenFlight: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ItineraryDetailViewModel = hiltViewModel(),
) {
    val loadState by viewModel.loadState.collectAsStateWithLifecycle()
    val itinerary = (loadState as? ItineraryDetailLoadState.Found)?.itinerary
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var bookingTransition by remember { mutableStateOf<ItineraryTransitionUi?>(null) }
    var baggageTransition by remember { mutableStateOf<ItineraryTransitionUi?>(null) }
    var intentTransition by remember { mutableStateOf<ItineraryTransitionUi?>(null) }

    LaunchedEffect(loadState) {
        if (loadState is ItineraryDetailLoadState.Missing || itinerary?.archived == true) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        itinerary?.displayTitle(context) ?: stringResource(R.string.itinerary_title),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onEdit, enabled = itinerary != null && !busy) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.itinerary_edit))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }, enabled = itinerary != null && !busy) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_actions))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.archive)) },
                                leadingIcon = { Icon(Icons.Filled.Archive, contentDescription = null) },
                                onClick = { menuOpen = false; viewModel.archive() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = { menuOpen = false; deleteOpen = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val graph = itinerary
        if (graph == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.itinerary_loading))
            }
        } else {
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.padding(padding).fillMaxSize(),
                state = pullState,
                indicator = {
                    BirdRefreshIndicator(
                        state = pullState,
                        isRefreshing = state.refreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                },
            ) {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    // Phones in portrait with a large font scale were the cramped
                    // case (§7.16): the spine's gutter and every card's padding
                    // shrink rather than squeezing the content into a column too
                    // narrow to hold a route row.
                    val metrics = spineMetrics(compact = maxWidth < COMPACT_WIDTH)
                    LazyColumn(
                        modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
                        contentPadding = PaddingValues(metrics.screenPadding),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (error != null) {
                            item("error") {
                                Text(
                                    error.orEmpty(),
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                                )
                            }
                        }
                        item("summary") {
                            SummaryCard(
                                title = graph.displayTitle(context),
                                dateSpan = graph.dateSpan(context),
                                routeChain = state.routeChain,
                                designators = graph.designatorLine(
                                    separator = " ${stringResource(R.string.itinerary_designator_separator)} ",
                                ),
                                padding = metrics.cardPadding,
                            )
                        }
                        state.bodyClock?.let { trip ->
                            item("body-clock") { ItineraryBodyClockCard(trip) }
                        }
                        if (state.awaitingFirstSnapshot) {
                            item("live-state") {
                                LiveDetailsNotice(
                                    hasStatusKey = state.hasStatusKey,
                                    padding = metrics.cardPadding,
                                    onOpenSettings = onOpenSettings,
                                )
                            }
                        }
                        state.legs.forEach { leg ->
                            item("leg-${leg.legId}") {
                                LegRow(
                                    leg = leg,
                                    metrics = metrics,
                                    onOpen = { onOpenFlight(leg.flightId) },
                                )
                            }
                            state.transitions.firstOrNull { it.inboundLegId == leg.legId }?.let { transition ->
                                item("transition-${transition.transitionId}") {
                                    TransitionRow(
                                        transition = transition,
                                        enabled = !busy,
                                        metrics = metrics,
                                        onBooking = { bookingTransition = transition },
                                        onBaggage = { baggageTransition = transition },
                                        onIntent = { intentTransition = transition },
                                        onApplySuggestion = { intent ->
                                            viewModel.updateIntent(transition.transitionId, intent)
                                        },
                                    )
                                }
                            }
                        }
                        item("bottom-space") { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }
        }
    }

    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text(stringResource(R.string.itinerary_delete_title)) },
            text = { Text(stringResource(R.string.itinerary_delete_body)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { deleteOpen = false; viewModel.delete(deleteFlights = false) }) {
                        Text(stringResource(R.string.itinerary_delete_keep_flights))
                    }
                    TextButton(onClick = { deleteOpen = false; viewModel.delete(deleteFlights = true) }) {
                        Text(
                            stringResource(R.string.itinerary_delete_with_flights),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteOpen = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    intentTransition?.let { transition ->
        ChoiceDialog(
            title = stringResource(R.string.itinerary_transition_title, transition.inboundTitle),
            helper = stringResource(R.string.itinerary_transition_helper),
            choices = listOf(
                TransitionIntent.DIRECT_CONNECTION to stringResource(R.string.transition_direct),
                TransitionIntent.DESTINATION_STAY to stringResource(R.string.transition_stay),
                TransitionIntent.SURFACE_TRANSFER to stringResource(R.string.transition_surface),
                TransitionIntent.UNKNOWN to stringResource(R.string.transition_unknown),
            ),
            selected = transition.intent,
            onDismiss = { intentTransition = null },
            onSelect = { value -> intentTransition = null; viewModel.updateIntent(transition.transitionId, value) },
        )
    }

    bookingTransition?.let { transition ->
        ChoiceDialog(
            title = stringResource(R.string.itinerary_booking_title),
            helper = stringResource(R.string.itinerary_booking_helper),
            choices = listOf(
                BookingArrangement.BOOKED_TOGETHER to stringResource(R.string.booking_together),
                BookingArrangement.BOOKED_SEPARATELY to stringResource(R.string.booking_separate),
                BookingArrangement.UNKNOWN to stringResource(R.string.booking_unknown),
            ),
            selected = transition.booking,
            onDismiss = { bookingTransition = null },
            onSelect = { value -> bookingTransition = null; viewModel.updateBooking(transition.transitionId, value) },
        )
    }
    baggageTransition?.let { transition ->
        ChoiceDialog(
            title = stringResource(R.string.itinerary_baggage_title),
            helper = null,
            choices = listOf(
                BaggagePlan.NO_CHECKED_BAG to stringResource(R.string.baggage_none),
                BaggagePlan.THROUGH_CHECKED to stringResource(R.string.baggage_through),
                BaggagePlan.COLLECT_AND_RECHECK to stringResource(R.string.baggage_recheck),
                BaggagePlan.UNKNOWN to stringResource(R.string.baggage_unknown),
            ),
            selected = transition.baggage,
            onDismiss = { baggageTransition = null },
            onSelect = { value -> baggageTransition = null; viewModel.updateBaggage(transition.transitionId, value) },
        )
    }
}

// ---------------------------------------------------------------- layout metrics

private val COMPACT_WIDTH = 400.dp

private data class SpineMetrics(
    /** Padding around the whole spine. */
    val screenPadding: Dp,
    /** Width of the left gutter that carries the leg number and the connector. */
    val gutter: Dp,
    /** Gap between the gutter and the cards. */
    val railGap: Dp,
    /** Padding inside each card. */
    val cardPadding: Dp,
    val compact: Boolean,
)

private fun spineMetrics(compact: Boolean): SpineMetrics = if (compact) {
    SpineMetrics(screenPadding = 12.dp, gutter = 30.dp, railGap = 8.dp, cardPadding = 13.dp, compact = true)
} else {
    SpineMetrics(screenPadding = 16.dp, gutter = 40.dp, railGap = 12.dp, cardPadding = 16.dp, compact = false)
}

// ---------------------------------------------------------------- summary

@Composable
private fun SummaryCard(
    title: String,
    dateSpan: String?,
    routeChain: String?,
    designators: String,
    padding: Dp,
) {
    Card(
        modifier = Modifier.fillMaxWidth().itineraryBorder(26.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(padding)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            dateSpan?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = contrastAware(MaterialTheme.colorScheme.onPrimaryContainer, 0.8f))
            }
            routeChain?.let {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Route,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(if (routeChain == null) 10.dp else 6.dp))
            Text(
                designators,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = contrastAware(MaterialTheme.colorScheme.onPrimaryContainer, 0.85f),
            )
        }
    }
}

/**
 * Shown only while *no* member has resolved anything: one honest explanation
 * beats the same sentence repeated under every leg. Which explanation depends on
 * whether an approved status source is configured at all (§7.9).
 */
@Composable
private fun LiveDetailsNotice(hasStatusKey: Boolean, padding: Dp, onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().itineraryBorder(18.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = itinerarySurface(0.5f)),
    ) {
        Column(Modifier.padding(padding)) {
            Text(
                stringResource(
                    if (hasStatusKey) R.string.itinerary_live_pending_title
                    else R.string.itinerary_live_no_key_title
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    if (hasStatusKey) R.string.itinerary_live_pending_body
                    else R.string.itinerary_live_no_key_body
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!hasStatusKey) {
                TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.itinerary_open_settings))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- legs

@Composable
private fun LegRow(leg: ItineraryLegUi, metrics: SpineMetrics, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        TimelineDot(leg.position, metrics.gutter)
        Spacer(Modifier.width(metrics.railGap))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .itineraryBorder(22.dp)
                .clickable(onClick = onOpen),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(metrics.cardPadding)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            leg.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() },
                        )
                        if (leg.alias != null) {
                            Text(
                                leg.designator,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (leg.hasSnapshot) {
                        Spacer(Modifier.width(8.dp))
                        StatusWord(leg.view.status)
                    }
                }
                Spacer(Modifier.height(2.dp))
                val date = leg.date?.takeIf { leg.dateConfirmed }?.format(
                    DateTimeFormatter.ofLocalizedDate(
                        if (metrics.compact) FormatStyle.MEDIUM else FormatStyle.FULL
                    )
                )
                Text(
                    date ?: stringResource(R.string.itinerary_date_needs_confirmation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (date != null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
                if (leg.routeResolved) {
                    Spacer(Modifier.height(10.dp))
                    RouteRow(leg)
                    Spacer(Modifier.height(8.dp))
                    PhaseLine(leg)
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.itinerary_route_pending),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Only a genuine disagreement, not a leg whose date was never
                // confirmed in the first place — those two need different words.
                if (leg.hasSnapshot && leg.dateConfirmed && !leg.occurrenceConfirmed) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.itinerary_occurrence_mismatch),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // "Fetched", never "Updated": `fetchedAt` is this device's
                // retrieval time, and a fresh fetch can still carry old upstream
                // values (§8.10).
                val infoLine = listOfNotNull(
                    leg.fetchedAt?.let {
                        stringResource(R.string.itinerary_fetched_ago, elapsedText(Duration.between(it, leg.now)))
                    },
                    leg.lookupProblem?.let { stringResource(lookupProblemRes(it)) },
                ).joinToString("  ·  ")
                if (infoLine.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        infoLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (leg.lookupProblem != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.itinerary_open_flight),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun RouteRow(leg: ItineraryLegUi) {
    Row(verticalAlignment = Alignment.Top) {
        AirportCell(
            code = leg.depCode,
            city = leg.depCity,
            time = leg.depTime,
            tz = leg.depTz,
            modifier = Modifier.weight(1f),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Icon(
                Icons.Filled.Flight,
                contentDescription = null,
                modifier = Modifier.size(18.dp).rotate(90f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (leg.depTime != null && leg.arrTime != null) {
                Text(
                    countdownText(Duration.between(leg.depTime, leg.arrTime)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AirportCell(
            code = leg.arrCode,
            city = leg.arrCity,
            time = leg.arrTime,
            tz = leg.arrTz,
            dayOffset = leg.arrDayOffset,
            alignEnd = true,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AirportCell(
    code: String?,
    city: String?,
    time: Instant?,
    tz: String?,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    dayOffset: Int? = null,
) {
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(
            code ?: UNRESOLVED_CODE,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
        )
        if (time != null) {
            val zone = tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    localTime(time, zone),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                // +1 red-eye / −1 across-the-date-line marker — display only.
                if (dayOffset != null && dayOffset != 0) {
                    Text(
                        if (dayOffset > 0) "+$dayOffset" else "−${-dayOffset}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        }
        city?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Phase countdown plus the one operational fact that matters at that phase. */
@Composable
private fun PhaseLine(leg: ItineraryLegUi) {
    val landed = leg.view.status == FlightStatus.LANDED || leg.view.status == FlightStatus.ARRIVED
    val phase = leg.view.nextEventAt?.let { at ->
        when (leg.view.nextEventLabel) {
            FlightPhaseMachine.NextEvent.DEPARTS_IN -> departsInText(Duration.between(leg.now, at))
            FlightPhaseMachine.NextEvent.LANDS_IN -> landsInText(Duration.between(leg.now, at))
            FlightPhaseMachine.NextEvent.LANDED_AT -> stringResource(
                R.string.itinerary_landed_at,
                localTime(at, leg.arrTz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()),
            )
            FlightPhaseMachine.NextEvent.NONE -> null
        }
    }
    val fact = if (landed) {
        leg.baggageBelt?.let { "${stringResource(R.string.baggage_belt)} $it" }
    } else {
        listOfNotNull(
            leg.terminal?.let { "${stringResource(R.string.terminal)} $it" },
            leg.gate?.let { "${stringResource(R.string.gate)} $it" },
        ).joinToString("  ·  ").takeIf { it.isNotEmpty() }
    }
    if (phase == null && fact == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (phase != null) {
            Text(
                phase,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = fact != null),
            )
        }
        if (fact != null) {
            if (phase != null) Spacer(Modifier.width(8.dp))
            Text(
                fact,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimelineDot(number: Int, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

// ---------------------------------------------------------------- transitions

@Composable
private fun TransitionRow(
    transition: ItineraryTransitionUi,
    enabled: Boolean,
    metrics: SpineMetrics,
    onBooking: () -> Unit,
    onBaggage: () -> Unit,
    onIntent: () -> Unit,
    onApplySuggestion: (TransitionIntent) -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Connector(metrics.gutter)
        Spacer(Modifier.width(metrics.railGap))
        Card(
            modifier = Modifier.fillMaxWidth().itineraryBorder(18.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = itinerarySurface(0.42f)),
        ) {
            Column(Modifier.padding(metrics.cardPadding)) {
                TransitionHeader(transition, enabled, onIntent)
                Spacer(Modifier.height(6.dp))
                when (transition.intent) {
                    TransitionIntent.DIRECT_CONNECTION -> ConnectionBody(
                        transition = transition,
                        enabled = enabled,
                        onBooking = onBooking,
                        onBaggage = onBaggage,
                    )
                    TransitionIntent.DESTINATION_STAY -> BreakBody(
                        transition = transition,
                        label = R.string.transition_stay_break,
                        body = R.string.transition_stay_body,
                    )
                    TransitionIntent.SURFACE_TRANSFER -> SurfaceBody(transition)
                    TransitionIntent.UNKNOWN -> UnsetBody(
                        transition = transition,
                        enabled = enabled,
                        onIntent = onIntent,
                        onApplySuggestion = onApplySuggestion,
                    )
                }
            }
        }
    }
}

@Composable
private fun TransitionHeader(transition: ItineraryTransitionUi, enabled: Boolean, onIntent: () -> Unit) {
    val icon = when (transition.intent) {
        TransitionIntent.DIRECT_CONNECTION, TransitionIntent.SURFACE_TRANSFER -> Icons.Filled.SyncAlt
        TransitionIntent.DESTINATION_STAY, TransitionIntent.UNKNOWN -> Icons.Filled.UnfoldMore
    }
    val title = transitionLabel(transition.intent)
    val airport = transition.assessment.connectionAirport?.code
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            if (airport != null && transition.intent == TransitionIntent.DIRECT_CONNECTION) {
                Text(
                    stringResource(R.string.connection_at_airport, airport),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (transition.intent != TransitionIntent.UNKNOWN) {
            TextButton(onClick = onIntent, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.itinerary_change_transition))
            }
        }
    }
}

@Composable
private fun ConnectionBody(
    transition: ItineraryTransitionUi,
    enabled: Boolean,
    onBooking: () -> Unit,
    onBaggage: () -> Unit,
) {
    val assessment = transition.assessment
    // §7.7: the factual state leads, the duration follows. Never the other way round.
    DisruptionLine(transition)
    if (assessment.disruption == null || assessment.disruption in TIMING_STILL_MEANINGFUL) {
        WindowBlock(transition)
    }
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = onBooking, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.itinerary_booking_value, bookingLabel(transition.booking)))
    }
    TextButton(onClick = onBaggage, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.itinerary_baggage_value, baggageLabel(transition.baggage)))
    }
    LocationBlock(assessment)
}

/**
 * Disruptions whose copy explains *how to read* the numbers rather than
 * replacing them — everything else suppresses the window entirely (§8.9).
 */
private val TIMING_STILL_MEANINGFUL = setOf(
    TransitionEngine.Disruption.STALE_OR_MISSING_TIMES,
    TransitionEngine.Disruption.DEPARTURE_PASSED_UNCONFIRMED,
)

@Composable
private fun DisruptionLine(transition: ItineraryTransitionUi) {
    val assessment = transition.assessment
    val disruption = assessment.disruption ?: return
    val emphasis = disruption !in TIMING_STILL_MEANINGFUL
    Text(
        stringResource(
            when (disruption) {
                TransitionEngine.Disruption.PENDING_ROUTE_CONFIRMATION -> R.string.transition_direct_pending
                TransitionEngine.Disruption.OUTBOUND_CANCELLED -> R.string.connection_outbound_cancelled
                TransitionEngine.Disruption.INBOUND_CANCELLED -> R.string.connection_inbound_cancelled
                TransitionEngine.Disruption.DIVERTED -> R.string.connection_diverted
                TransitionEngine.Disruption.OUTBOUND_DEPARTED_WITHOUT_INBOUND_IN -> R.string.connection_out_without_in
                TransitionEngine.Disruption.OUTBOUND_BEFORE_INBOUND_IN -> R.string.connection_out_before_in
                TransitionEngine.Disruption.AIRPORTS_DO_NOT_MEET -> R.string.connection_airports_differ
                TransitionEngine.Disruption.DEPARTURE_PASSED_UNCONFIRMED -> R.string.connection_departure_passed
                TransitionEngine.Disruption.GATE_TIMES_UNSUPPORTED -> R.string.connection_gate_times_unsupported
                TransitionEngine.Disruption.STALE_OR_MISSING_TIMES -> R.string.connection_stale_or_missing
                TransitionEngine.Disruption.INVALID_OVERLAP -> R.string.connection_invalid_overlap
            }
        ),
        style = if (emphasis) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
        fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
        color = when {
            disruption in CALM_STATES -> MaterialTheme.colorScheme.onSurfaceVariant
            emphasis -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
    // Name the leg that is holding the connection up — "pending" with no subject
    // is the least actionable message on the screen.
    if (disruption == TransitionEngine.Disruption.PENDING_ROUTE_CONFIRMATION) {
        val waiting = listOfNotNull(
            transition.inboundTitle.takeUnless { assessment.inboundOccurrenceConfirmed },
            transition.outboundTitle.takeUnless { assessment.outboundOccurrenceConfirmed },
        )
        if (waiting.size == 1) {
            Spacer(Modifier.height(3.dp))
            Text(
                stringResource(R.string.connection_pending_leg, waiting.single()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (disruption == TransitionEngine.Disruption.AIRPORTS_DO_NOT_MEET) {
        val arrival = assessment.inboundArrivalAirport?.code
        val departure = assessment.outboundDepartureAirport?.code
        if (arrival != null && departure != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                stringResource(
                    R.string.connection_airports_differ_body,
                    transition.inboundTitle, arrival, transition.outboundTitle, departure,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(6.dp))
}

/** States that are informational, not alarming — §7.1's "calm escalation". */
private val CALM_STATES = setOf(
    TransitionEngine.Disruption.PENDING_ROUTE_CONFIRMATION,
    TransitionEngine.Disruption.GATE_TIMES_UNSUPPORTED,
    TransitionEngine.Disruption.STALE_OR_MISSING_TIMES,
)

@Composable
private fun WindowBlock(transition: ItineraryTransitionUi) {
    val assessment = transition.assessment
    val scheduled = assessment.latestScheduledWindow
    val calculated = assessment.latestCalculatedWindow
    if (scheduled == null && calculated == null) return

    // Once the traveller is actually at the gate, time left is the headline and
    // the two windows drop below it (§8.5).
    assessment.remainingUntilOutbound?.takeIf { assessment.arrivedAtGate }?.let { remaining ->
        Text(
            stringResource(R.string.connection_until_onward, countdownText(remaining)),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.connection_boarding_earlier),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
    }
    // A negative window is never printed as a duration: countdownText clamps to
    // "0m", which would read as a connection that only just works.
    val shownScheduled = scheduled?.takeIf { !it.isNegative }
    val shownCalculated = calculated?.takeIf { !it.isNegative }
    shownScheduled?.let {
        Text(
            stringResource(R.string.connection_latest_scheduled, countdownText(it)),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    shownCalculated?.let {
        Text(
            stringResource(R.string.connection_latest_calculated, countdownText(it)),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
    if (shownScheduled != null && shownCalculated != null) {
        assessment.changeFromScheduled?.let { change ->
            val minutes = change.toMinutes()
            Text(
                when {
                    minutes > 0 -> stringResource(R.string.connection_change_longer, countdownText(change))
                    minutes < 0 -> stringResource(R.string.connection_change_shorter, countdownText(change.negated()))
                    else -> stringResource(R.string.connection_change_none)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val basis = listOfNotNull(
        assessment.inboundIn?.let { stringResource(arrivalCertaintyRes(it.certainty)) },
        assessment.outboundOut?.let { stringResource(departureCertaintyRes(it.certainty)) },
    )
    if (basis.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(
            basis.joinToString("  ·  "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val ages = listOfNotNull(
        assessment.inboundRetrieval.age?.let {
            stringResource(R.string.connection_inbound_fetched, elapsedText(it))
        },
        assessment.outboundRetrieval.age?.let {
            stringResource(R.string.connection_onward_fetched, elapsedText(it))
        },
    )
    if (ages.isNotEmpty()) {
        Text(
            ages.joinToString("  ·  "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Reported terminal/gate facts for the app user — never an instruction (§7.8). */
@Composable
private fun LocationBlock(assessment: TransitionEngine.Assessment) {
    val inbound = locationText(assessment.inboundLocation)
    val outbound = locationText(assessment.outboundLocation)
    if (inbound == null && outbound == null && !assessment.onwardGatePending) return
    Spacer(Modifier.height(6.dp))
    inbound?.let {
        Text(
            stringResource(R.string.connection_reported_inbound, it),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    outbound?.let {
        Text(
            stringResource(R.string.connection_reported_onward, it),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (assessment.onwardGatePending) {
        Text(
            stringResource(R.string.connection_onward_gate_pending),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (inbound != null || outbound != null) {
        Text(
            stringResource(R.string.connection_confirm_displays),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun locationText(location: TransitionEngine.ReportedLocation): String? = when {
    location.terminal != null && location.gate != null ->
        stringResource(R.string.location_terminal_gate, location.terminal, location.gate)
    location.terminal != null -> stringResource(R.string.location_terminal_only, location.terminal)
    location.gate != null -> stringResource(R.string.location_gate_only, location.gate)
    else -> null
}

@Composable
private fun BreakBody(transition: ItineraryTransitionUi, label: Int, body: Int) {
    transition.assessment.breakDuration?.takeIf { !it.isNegative }?.let {
        Text(
            stringResource(label, countdownText(it)),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(3.dp))
    }
    Text(
        stringResource(body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SurfaceBody(transition: ItineraryTransitionUi) {
    val assessment = transition.assessment
    val from = assessment.inboundArrivalAirport?.code
    val to = assessment.outboundDepartureAirport?.code
    assessment.breakDuration?.takeIf { !it.isNegative }?.let {
        Text(
            stringResource(R.string.transition_surface_break, countdownText(it)),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(3.dp))
    }
    Text(
        if (from != null && to != null) stringResource(R.string.transition_surface_route, from, to)
        else stringResource(R.string.transition_surface_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * An unset edge asks its one question and, when the data supports it, offers the
 * §8.8 suggestion as a *proposal* the user confirms — never as a silent default.
 */
@Composable
private fun UnsetBody(
    transition: ItineraryTransitionUi,
    enabled: Boolean,
    onIntent: () -> Unit,
    onApplySuggestion: (TransitionIntent) -> Unit,
) {
    Text(
        stringResource(R.string.transition_unknown_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val assessment = transition.assessment
    val suggestion = assessment.suggestion
    val airport = assessment.connectionAirport?.code
    // An unset edge gets no connection window of its own (§8.11) — the gross gap
    // is what the suggestion is offered against.
    val gap = assessment.breakDuration
    if (suggestion != null && airport != null && gap != null) {
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.transition_suggestion_context, airport, countdownText(gap)),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            onClick = { onApplySuggestion(suggestion) },
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.transition_apply_suggestion, transitionLabel(suggestion)))
        }
    }
    TextButton(onClick = onIntent, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.transition_set_what_happens))
    }
}

/** The vertical run of the spine between two legs. */
@Composable
private fun Connector(gutter: Dp) {
    Box(Modifier.width(gutter).height(CONNECTOR_HEIGHT), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .width(4.dp)
                .height(CONNECTOR_HEIGHT)
                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
        )
    }
}

private val CONNECTOR_HEIGHT = 64.dp

// ---------------------------------------------------------------- labels

@Composable
private fun transitionLabel(intent: TransitionIntent): String = stringResource(
    when (intent) {
        TransitionIntent.DIRECT_CONNECTION -> R.string.transition_direct
        TransitionIntent.DESTINATION_STAY -> R.string.transition_stay
        TransitionIntent.SURFACE_TRANSFER -> R.string.transition_surface
        TransitionIntent.UNKNOWN -> R.string.transition_unknown
    }
)

private fun arrivalCertaintyRes(certainty: TimeCertainty): Int = when (certainty) {
    TimeCertainty.ACTUAL -> R.string.connection_endpoint_actual_arrival
    TimeCertainty.ESTIMATED -> R.string.connection_endpoint_estimated_arrival
    else -> R.string.connection_endpoint_scheduled_arrival
}

private fun departureCertaintyRes(certainty: TimeCertainty): Int = when (certainty) {
    TimeCertainty.ACTUAL -> R.string.connection_endpoint_actual_departure
    TimeCertainty.ESTIMATED -> R.string.connection_endpoint_estimated_departure
    else -> R.string.connection_endpoint_scheduled_departure
}

@Composable
private fun bookingLabel(value: BookingArrangement): String = stringResource(
    when (value) {
        BookingArrangement.BOOKED_TOGETHER -> R.string.booking_together
        BookingArrangement.BOOKED_SEPARATELY -> R.string.booking_separate
        BookingArrangement.UNKNOWN -> R.string.booking_unknown
    }
)

@Composable
private fun baggageLabel(value: BaggagePlan): String = stringResource(
    when (value) {
        BaggagePlan.NO_CHECKED_BAG -> R.string.baggage_none
        BaggagePlan.THROUGH_CHECKED -> R.string.baggage_through
        BaggagePlan.COLLECT_AND_RECHECK -> R.string.baggage_recheck
        BaggagePlan.UNKNOWN -> R.string.baggage_unknown
    }
)

@Composable
private fun <T> ChoiceDialog(
    title: String,
    helper: String?,
    choices: List<Pair<T, String>>,
    selected: T,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                helper?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                choices.forEach { (value, label) ->
                    TextButton(
                        onClick = { onSelect(value) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(
                            if (value == selected) "$label (${stringResource(R.string.a11y_selected)})" else label,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
