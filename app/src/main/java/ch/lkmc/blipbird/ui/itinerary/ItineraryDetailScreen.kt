package ch.lkmc.blipbird.ui.itinerary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.data.designator
import ch.lkmc.blipbird.core.model.BaggagePlan
import ch.lkmc.blipbird.core.model.BookingArrangement
import ch.lkmc.blipbird.core.model.ItineraryTransition
import ch.lkmc.blipbird.core.model.TransitionIntent
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItineraryDetailScreen(
    itineraryId: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenFlight: (Long) -> Unit,
    viewModel: ItineraryDetailViewModel = hiltViewModel(),
) {
    val loadState by viewModel.loadState.collectAsStateWithLifecycle()
    val itinerary = (loadState as? ItineraryDetailLoadState.Found)?.itinerary
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val bodyClock by viewModel.bodyClock.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var bookingTransition by remember { mutableStateOf<ItineraryTransition?>(null) }
    var baggageTransition by remember { mutableStateOf<ItineraryTransition?>(null) }

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
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
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
                        Card(
                            modifier = Modifier.itineraryBorder(26.dp),
                            shape = RoundedCornerShape(26.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        ) {
                            Column(Modifier.padding(20.dp)) {
                                Text(
                                    graph.displayTitle(context),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.semantics { heading() },
                                )
                                graph.dateSpan(context)?.let {
                                    Spacer(Modifier.height(4.dp))
                                    Text(it, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    graph.designatorLine(
                                        separator = " ${stringResource(R.string.itinerary_designator_separator)} ",
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                    bodyClock?.let { trip ->
                        item("body-clock") { ItineraryBodyClockCard(trip) }
                    }
                    item("local-boundary") {
                        Card(
                            modifier = Modifier.itineraryBorder(18.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = itinerarySurface(0.5f),
                            ),
                        ) {
                            Text(
                                stringResource(R.string.itinerary_live_unavailable),
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    graph.legs.forEachIndexed { index, leg ->
                        item("leg-${leg.id}") {
                            Row(verticalAlignment = Alignment.Top) {
                                TimelineDot(index + 1)
                                Spacer(Modifier.width(12.dp))
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .itineraryBorder(22.dp)
                                        .clickable { onOpenFlight(leg.flight.id) },
                                    shape = RoundedCornerShape(22.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                ) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(
                                            leg.flight.designator().display,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.semantics { heading() },
                                        )
                                        leg.flight.alias?.let { alias ->
                                            Text(
                                                alias,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            leg.date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
                                                ?: stringResource(R.string.itinerary_date_needs_confirmation),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
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
                        graph.transitions.firstOrNull { it.inboundLegId == leg.id }?.let { transition ->
                            item("transition-${transition.id}") {
                                TransitionCard(
                                    transition = transition,
                                    enabled = !busy,
                                    onBooking = { bookingTransition = transition },
                                    onBaggage = { baggageTransition = transition },
                                )
                            }
                        }
                    }
                    item("bottom-space") { Spacer(Modifier.height(16.dp)) }
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

    bookingTransition?.let { transition ->
        ChoiceDialog(
            title = stringResource(R.string.itinerary_booking_title),
            helper = stringResource(R.string.itinerary_booking_helper),
            choices = listOf(
                BookingArrangement.BOOKED_TOGETHER to stringResource(R.string.booking_together),
                BookingArrangement.BOOKED_SEPARATELY to stringResource(R.string.booking_separate),
                BookingArrangement.UNKNOWN to stringResource(R.string.booking_unknown),
            ),
            selected = transition.bookingArrangement,
            onDismiss = { bookingTransition = null },
            onSelect = { value -> bookingTransition = null; viewModel.updateBooking(transition.id, value) },
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
            selected = transition.baggagePlan,
            onDismiss = { baggageTransition = null },
            onSelect = { value -> baggageTransition = null; viewModel.updateBaggage(transition.id, value) },
        )
    }
}

@Composable
private fun TimelineDot(number: Int) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(number.toString(), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TransitionCard(
    transition: ItineraryTransition,
    enabled: Boolean,
    onBooking: () -> Unit,
    onBaggage: () -> Unit,
) {
    val (icon, title, body) = when (transition.intent) {
        TransitionIntent.DIRECT_CONNECTION -> Triple(
            Icons.Filled.SyncAlt,
            stringResource(R.string.transition_direct),
            stringResource(R.string.transition_direct_pending),
        )
        TransitionIntent.DESTINATION_STAY -> Triple(
            Icons.Filled.UnfoldMore,
            stringResource(R.string.transition_stay),
            stringResource(R.string.transition_stay_body),
        )
        TransitionIntent.SURFACE_TRANSFER -> Triple(
            Icons.Filled.SyncAlt,
            stringResource(R.string.transition_surface),
            stringResource(R.string.transition_surface_body),
        )
        TransitionIntent.UNKNOWN -> Triple(
            Icons.Filled.UnfoldMore,
            stringResource(R.string.transition_unknown),
            stringResource(R.string.transition_unknown_body),
        )
    }
    Row(Modifier.padding(start = 14.dp)) {
        Box(
            Modifier
                .width(12.dp)
                .height(108.dp)
                .border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(18.dp))
        Card(
            modifier = Modifier.fillMaxWidth().itineraryBorder(18.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = itinerarySurface(0.42f)),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (transition.intent == TransitionIntent.DIRECT_CONNECTION) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onBooking, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(
                            stringResource(
                                R.string.itinerary_booking_value,
                                bookingLabel(transition.bookingArrangement),
                            )
                        )
                    }
                    TextButton(onClick = onBaggage, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(
                            stringResource(
                                R.string.itinerary_baggage_value,
                                baggageLabel(transition.baggagePlan),
                            )
                        )
                    }
                }
            }
        }
    }
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
