package ch.lkmc.blipbird.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.ui.components.FlightProgressBar
import ch.lkmc.blipbird.ui.components.StatusWord
import ch.lkmc.blipbird.ui.components.countdownText
import ch.lkmc.blipbird.ui.components.monogramColor
import ch.lkmc.blipbird.ui.theme.LocalExtendedColors
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightListScreen(
    onOpenFlight: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onFirstTrack: () -> Unit,
    viewModel: FlightListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val archivedMsg = stringResource(R.string.flight_archived)
    val deletedMsg = stringResource(R.string.flight_deleted)
    val undoLabel = stringResource(R.string.undo)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.my_flights), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddSheet = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_flight))
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { viewModel.refreshAll() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (state.rows.isEmpty()) {
                Column(Modifier.fillMaxSize()) {
                    if (!state.hasStatusKey) {
                        DataSourceCta(
                            onOpenSettings = onOpenSettings,
                            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 16.dp),
                        )
                    }
                    EmptyState(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        onAdd = { showAddSheet = true },
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    // Extra bottom room so the FAB never covers the last card.
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                ) {
                    if (!state.hasStatusKey) {
                        item(key = "data-source-cta") { DataSourceCta(onOpenSettings) }
                    }
                    items(state.rows, key = { it.id }) { row ->
                        SwipeableFlightRow(
                            row = row,
                            onClick = { onOpenFlight(row.id) },
                            onArchive = {
                                viewModel.archive(row.id)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = archivedMsg,
                                        actionLabel = undoLabel,
                                        duration = SnackbarDuration.Short,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) viewModel.unarchive(row.id)
                                }
                            },
                            onDelete = {
                                viewModel.delete(row.id)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = deletedMsg,
                                        actionLabel = undoLabel,
                                        duration = SnackbarDuration.Short,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete()
                                }
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        AddFlightSheet(
            error = state.addError,
            onDismiss = { showAddSheet = false; viewModel.clearAddError() },
            onAdd = { input, date, alias ->
                viewModel.addFlights(input, date, alias, onFirstTrack)
                showAddSheet = false
            },
        )
    }
}

/** Swipe right → archive (undoable), swipe left → delete (undoable). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableFlightRow(
    row: FlightRow,
    onClick: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onArchive(); true }
                SwipeToDismissBoxValue.EndToStart -> { onDelete(); true }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = { SwipeBackground(dismissState.dismissDirection) },
    ) {
        FlightRowCard(row, onClick = onClick)
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    val ext = LocalExtendedColors.current
    val (color, icon, alignment) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd ->
            Triple(ext.statusOnTime, Icons.Outlined.Archive, Alignment.CenterStart)
        SwipeToDismissBoxValue.EndToStart ->
            Triple(ext.statusCancelled, Icons.Filled.Delete, Alignment.CenterEnd)
        SwipeToDismissBoxValue.Settled -> Triple(Color.Transparent, null, Alignment.Center)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(color, RoundedCornerShape(22.dp))
            .padding(horizontal = 24.dp),
        contentAlignment = alignment,
    ) {
        icon?.let {
            Icon(
                it,
                contentDescription = if (direction == SwipeToDismissBoxValue.StartToEnd)
                    stringResource(R.string.archive) else stringResource(R.string.delete),
                tint = Color.White,
            )
        }
    }
}

/** Shown while no status API key is configured (README's promised honest CTA). */
@Composable
private fun DataSourceCta(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.onboarding_keys_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.onboarding_keys_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings)) }
            }
        }
    }
}

@Composable
private fun FlightRowCard(row: FlightRow, onClick: () -> Unit) {
    val ext = LocalExtendedColors.current
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Monogram(row.airlineIata ?: row.title.take(2))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    row.subtitle?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                StatusWord(row.view.status)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.depCode,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                )
                Icon(
                    Icons.Filled.Flight, contentDescription = null,
                    modifier = Modifier.padding(horizontal = 6.dp).size(15.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    row.arrCode,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    phaseTime(row),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            FlightProgressBar(
                progress = row.view.progress,
                color = ext.statusEnRoute,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f),
            )
            // Landed: the useful fact is the baggage belt; otherwise terminal/gate.
            val landed = row.view.status == FlightStatus.LANDED || row.view.status == FlightStatus.ARRIVED
            val factLine = if (landed) {
                row.baggageBelt?.let { "${stringResource(R.string.baggage_belt)} $it" }.orEmpty()
            } else {
                listOfNotNull(
                    row.terminal?.let { "${stringResource(R.string.terminal)} $it" },
                    row.gate?.let { "${stringResource(R.string.gate)} $it" },
                ).joinToString("  ·  ")
            }
            if (factLine.isNotEmpty()) {
                Text(factLine, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun phaseTime(row: FlightRow): String {
    val at = row.view.nextEventAt ?: return stringResource(R.string.value_unknown)
    return when (row.view.nextEventLabel) {
        ch.lkmc.blipbird.domain.FlightPhaseMachine.NextEvent.DEPARTS_IN ->
            "Departs in ${countdownText(Duration.between(Instant.now(), at))}"
        ch.lkmc.blipbird.domain.FlightPhaseMachine.NextEvent.LANDS_IN ->
            "Lands in ${countdownText(Duration.between(Instant.now(), at))}"
        ch.lkmc.blipbird.domain.FlightPhaseMachine.NextEvent.LANDED_AT ->
            "Landed ${ch.lkmc.blipbird.ui.components.localTime(at)}"
        else -> stringResource(R.string.value_unknown)
    }
}

@Composable
private fun Monogram(code: String) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(monogramColor(code), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(code.take(2).uppercase(), color = Color.White, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier, onAdd: () -> Unit) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.Flight, contentDescription = null, modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.empty_list_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_list_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAdd) { Text(stringResource(R.string.add_flight)) }
    }
}
