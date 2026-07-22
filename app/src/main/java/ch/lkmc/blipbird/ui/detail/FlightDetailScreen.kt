package ch.lkmc.blipbird.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.model.MovementTimes
import ch.lkmc.blipbird.core.model.StatusSnapshot
import ch.lkmc.blipbird.domain.FlightPhaseMachine
import ch.lkmc.blipbird.domain.GreatCircle
import ch.lkmc.blipbird.ui.components.StatusWord
import ch.lkmc.blipbird.ui.components.agoText
import ch.lkmc.blipbird.ui.components.countdownText
import ch.lkmc.blipbird.ui.components.localTime
import ch.lkmc.blipbird.ui.map.RouteMap
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightDetailScreen(
    flightId: Long,
    onBack: () -> Unit,
    viewModel: FlightDetailViewModel = hiltViewModel(key = "flight-$flightId"),
) {
    LaunchedEffect(flightId) { viewModel.setFlightId(flightId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.title, fontWeight = FontWeight.Bold)
                        if (state.alias != null) {
                            Text(state.designator, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = { StatusWord(state.view.status); Spacer(Modifier.padding(end = 12.dp)) },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { Hero(state) }
                item { MapCard(state) }
                item { KeyFacts(state) }
                item { Timeline(state) }
                state.daylight?.let { day ->
                    item {
                        SectionCard(stringResource(R.string.flight_ribbon)) {
                            FlightRibbon(
                                daylight = day,
                                weather = state.routeWeather,
                                depCode = state.depAirport?.code ?: "",
                                arrCode = state.arrAirport?.code ?: "",
                            )
                            if (state.routeWeather.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Weather data by Open-Meteo.com (CC BY 4.0)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    stringResource(R.string.weather_route_pending),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (state.airportWeather.isNotEmpty()) {
                    item { WeatherCard(state) }
                }
                item { AirlineCard(state) }
                item {
                    Text(
                        state.updatedAt?.let { stringResource(R.string.updated_ago, agoText(it)) }
                            ?: stringResource(R.string.updated_never),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun Hero(state: DetailUiState) {
    val snapshot = state.snapshot
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AirportColumn(
                    code = state.depAirport?.code ?: "···",
                    city = state.depAirport?.city,
                    time = snapshot?.depTimes?.best,
                    zone = state.depAirport?.tz,
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✈", style = MaterialTheme.typography.headlineSmall)
                    val at = state.view.nextEventAt
                    if (at != null && state.view.nextEventLabel != FlightPhaseMachine.NextEvent.LANDED_AT) {
                        Text(
                            countdownText(Duration.between(Instant.now(), at)),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                AirportColumn(
                    code = state.arrAirport?.code ?: "···",
                    city = state.arrAirport?.city,
                    time = snapshot?.arrTimes?.best,
                    zone = state.arrAirport?.tz,
                    modifier = Modifier.weight(1f),
                    alignEnd = true,
                )
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { state.view.progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
            snapshot?.codeshareOf?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.operated_by, snapshot.operatingDesignator ?: "?"),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun AirportColumn(
    code: String,
    city: String?,
    time: Instant?,
    zone: String?,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
) {
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(code, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        city?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        time?.let {
            val zid = zone?.let { z -> runCatching { ZoneId.of(z) }.getOrNull() } ?: ZoneId.systemDefault()
            Text(localTime(it, zid), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun MapCard(state: DetailUiState) {
    SectionCard(stringResource(R.string.live_map)) {
        val dep = state.depAirport?.let { a -> a.lat?.let { la -> a.lon?.let { lo -> GreatCircle.Point(la, lo) } } }
        val arr = state.arrAirport?.let { a -> a.lat?.let { la -> a.lon?.let { lo -> GreatCircle.Point(la, lo) } } }
        RouteMap(
            dep = dep,
            arr = arr,
            lastFix = state.lastFix,
            track = state.track,
            modifier = Modifier.fillMaxWidth().height(230.dp),
        )
        Spacer(Modifier.height(6.dp))
        val fix = state.lastFix
        Text(
            when {
                fix == null -> stringResource(R.string.map_no_position)
                fix.seenPosAgeSec > 120 -> stringResource(R.string.last_seen_ago, agoText(fix.at))
                else -> listOfNotNull(
                    fix.baroAltitudeFt?.let { "${it.toInt()} ft" },
                    fix.groundSpeedKt?.let { "${it.toInt()} kt" },
                    fix.trackDeg?.let { "${it.toInt()}°" },
                    fix.source,
                ).joinToString(" · ")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun KeyFacts(state: DetailUiState) {
    val s = state.snapshot ?: return
    SectionCard(stringResource(R.string.departure) + " · " + stringResource(R.string.arrival)) {
        Row(Modifier.fillMaxWidth()) {
            FactColumn(
                modifier = Modifier.weight(1f),
                facts = listOf(
                    stringResource(R.string.terminal) to (s.depTerminal ?: "—"),
                    stringResource(R.string.gate) to (s.depGate ?: "—"),
                    stringResource(R.string.check_in) to (s.depCheckInDesk ?: "—"),
                ),
            )
            FactColumn(
                modifier = Modifier.weight(1f),
                facts = listOf(
                    stringResource(R.string.terminal) to (s.arrTerminal ?: "—"),
                    stringResource(R.string.gate) to (s.arrGate ?: "—"),
                    stringResource(R.string.baggage_belt) to (s.baggageBelt ?: "—"),
                ),
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Row(Modifier.fillMaxWidth()) {
            FactColumn(
                modifier = Modifier.weight(1f),
                facts = listOf(stringResource(R.string.aircraft) to (s.aircraftModel ?: "—")),
            )
            FactColumn(
                modifier = Modifier.weight(1f),
                facts = listOf(stringResource(R.string.registration) to (s.registration ?: "—")),
            )
        }
    }
}

@Composable
private fun FactColumn(facts: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier) {
        facts.forEach { (label, value) ->
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun Timeline(state: DetailUiState) {
    val s = state.snapshot ?: return
    SectionCard(stringResource(R.string.timeline)) {
        TimelineRow("~ " + stringResource(R.string.check_in), derived = true, at = state.view.derivedCheckInAt, zone = state.depAirport?.tz)
        TimelineRow("~ Boarding", derived = true, at = state.view.derivedBoardingAt, zone = state.depAirport?.tz)
        TimesRow("Pushback", s.depTimes, useRunway = false, zone = state.depAirport?.tz)
        TimesRow("Takeoff", s.depTimes, useRunway = true, zone = state.depAirport?.tz)
        TimesRow("Landing", s.arrTimes, useRunway = true, zone = state.arrAirport?.tz)
        TimesRow("Gate arrival", s.arrTimes, useRunway = false, zone = state.arrAirport?.tz)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.derived_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TimelineRow(label: String, derived: Boolean, at: Instant?, zone: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            at?.let { localTime(it, zoneOf(zone)) } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TimesRow(label: String, times: MovementTimes, useRunway: Boolean, zone: String?) {
    val sched = if (useRunway) null else times.scheduled
    val est = if (useRunway) times.runwayEstimated else times.estimated
    val act = if (useRunway) times.runwayActual else times.actual
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        // scheduled (struck through when superseded)
        sched?.let {
            Text(
                localTime(it, zoneOf(zone)),
                style = MaterialTheme.typography.bodyMedium.copy(
                    textDecoration = if (est != null || act != null)
                        androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(horizontal = 4.dp))
        }
        val shown = act ?: est
        Text(
            shown?.let { localTime(it, zoneOf(zone)) } ?: (if (sched == null) "—" else ""),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (act != null) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

private fun zoneOf(tz: String?): ZoneId =
    tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

@Composable
private fun WeatherCard(state: DetailUiState) {
    SectionCard(stringResource(R.string.weather)) {
        state.airportWeather.forEach { w ->
            Text("${w.stationId} · ${w.decoded}", style = MaterialTheme.typography.bodyMedium)
            Text(
                w.rawMetar,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AirlineCard(state: DetailUiState) {
    val name = state.airlineName ?: return
    SectionCard(stringResource(R.string.airline)) {
        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        state.snapshot?.aircraftModel?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
