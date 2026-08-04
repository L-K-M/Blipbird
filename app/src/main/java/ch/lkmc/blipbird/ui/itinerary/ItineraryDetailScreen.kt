package ch.lkmc.blipbird.ui.itinerary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.model.BaggagePlan
import ch.lkmc.blipbird.core.model.BookingArrangement
import ch.lkmc.blipbird.core.model.TransitionIntent
import ch.lkmc.blipbird.ui.components.BirdRefreshIndicator

/** Air between the header cards, and between two legs with no edge of their own. */
private val SECTION_GAP = 12.dp

/**
 * The journey spine (`docs/ITINERARY_PROPOSAL.md` §7.6): ordered leg rows joined
 * by explicit transition nodes. This file assembles the spine and owns the
 * screen's dialogs; [LegRow] and [TransitionRow] draw the rows themselves, on
 * the shared measurements in [spineMetrics].
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
                    // No inter-item arrangement: a leg card and the transition
                    // below it must touch, so the connector meets both and the
                    // three read as one journey. Everything else brings its own
                    // bottom margin.
                    LazyColumn(
                        modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
                        contentPadding = PaddingValues(metrics.screenPadding),
                    ) {
                        if (error != null) {
                            item("error") {
                                Text(
                                    error.orEmpty(),
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .padding(bottom = SECTION_GAP)
                                        .semantics { liveRegion = LiveRegionMode.Assertive },
                                )
                            }
                        }
                        item("summary") {
                            Column(Modifier.padding(bottom = SECTION_GAP)) {
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
                        }
                        state.bodyClock?.let { trip ->
                            item("body-clock") {
                                Column(Modifier.padding(bottom = SECTION_GAP)) {
                                    ItineraryBodyClockCard(trip)
                                }
                            }
                        }
                        if (state.awaitingFirstSnapshot) {
                            item("live-state") {
                                Column(Modifier.padding(bottom = SECTION_GAP)) {
                                    LiveDetailsNotice(
                                        hasStatusKey = state.hasStatusKey,
                                        padding = metrics.cardPadding,
                                        onOpenSettings = onOpenSettings,
                                    )
                                }
                            }
                        }
                        state.legs.forEachIndexed { index, leg ->
                            item("leg-${leg.legId}") {
                                LegRow(
                                    leg = leg,
                                    metrics = metrics,
                                    onOpen = { onOpenFlight(leg.flightId) },
                                )
                            }
                            val transition = state.transitions.firstOrNull { it.inboundLegId == leg.legId }
                            if (transition != null) {
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
                            } else if (index < state.legs.lastIndex) {
                                // A pair with no edge of its own still needs air
                                // between the two cards.
                                item("gap-${leg.legId}") { Spacer(Modifier.height(SECTION_GAP)) }
                            }
                        }
                        item("bottom-space") { Spacer(Modifier.height(24.dp)) }
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
