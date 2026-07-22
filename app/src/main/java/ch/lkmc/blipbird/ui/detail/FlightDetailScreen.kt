package ch.lkmc.blipbird.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.outlined.DoorFront
import androidx.compose.material.icons.outlined.Luggage
import androidx.compose.material.icons.outlined.Domain
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.filled.FlightLand
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.model.MovementTimes
import ch.lkmc.blipbird.domain.FlightPhaseMachine
import ch.lkmc.blipbird.domain.GreatCircle
import ch.lkmc.blipbird.ui.components.FlightProgressBar
import ch.lkmc.blipbird.ui.components.StatusWord
import ch.lkmc.blipbird.ui.components.agoText
import ch.lkmc.blipbird.ui.components.countdownText
import ch.lkmc.blipbird.ui.components.localTime
import ch.lkmc.blipbird.ui.components.monogramColor
import ch.lkmc.blipbird.ui.map.MapLibreRouteMap
import ch.lkmc.blipbird.ui.theme.LocalExtendedColors
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightDetailScreen(
    flightId: Long,
    onBack: () -> Unit,
    viewModel: FlightDetailViewModel = hiltViewModel(key = "flight-$flightId"),
) {
    LaunchedEffect(flightId) { viewModel.setFlightId(flightId) }
    // Gate the ViewModel's live-position polling on this screen actually being
    // started: stops on navigation away AND when the app goes to background.
    LifecycleStartEffect(flightId, viewModel) {
        viewModel.setScreenVisible(true)
        onStopOrDispose { viewModel.setScreenVisible(false) }
    }
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Tablet / landscape: pair the equal-height card couples side by side.
                val wide = maxWidth >= 620.dp
                val hasWeather = state.airportWeather.isNotEmpty()
                val hasAirline = state.airlineName != null
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item { Hero(state) }
                    item { MapCard(state) }
                    if (wide && state.snapshot != null) {
                        item {
                            Row(verticalAlignment = Alignment.Top) {
                                Box(Modifier.weight(1f)) { KeyFacts(state) }
                                Spacer(Modifier.width(14.dp))
                                Box(Modifier.weight(1f)) { Timeline(state) }
                            }
                        }
                    } else {
                        item { KeyFacts(state) }
                        item { Timeline(state) }
                    }
                    state.daylight?.let { day ->
                        item {
                            SectionCard(stringResource(R.string.flight_ribbon)) {
                                FlightRibbon(
                                    daylight = day,
                                    weather = state.routeWeather,
                                    depCode = state.depAirport?.code ?: "",
                                    arrCode = state.arrAirport?.code ?: "",
                                    progress = state.view.progress,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    if (state.routeWeather.isNotEmpty()) "Weather data by Open-Meteo.com (CC BY 4.0)"
                                    else stringResource(R.string.weather_route_pending),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (wide && hasWeather && hasAirline) {
                        item {
                            Row(verticalAlignment = Alignment.Top) {
                                Box(Modifier.weight(1f)) { WeatherCard(state) }
                                Spacer(Modifier.width(14.dp))
                                Box(Modifier.weight(1f)) { AirlineCard(state) }
                            }
                        }
                    } else {
                        if (hasWeather) item { WeatherCard(state) }
                        if (hasAirline) item { AirlineCard(state) }
                    }
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
}

@Composable
private fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        // fillMaxWidth so content-sized cards (weather, airline) align with the rest.
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

// ---------------------------------------------------------------- hero

@Composable
private fun Hero(state: DetailUiState) {
    val snapshot = state.snapshot
    val cs = MaterialTheme.colorScheme
    val ext = LocalExtendedColors.current
    Column(
        Modifier
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.verticalGradient(
                    listOf(cs.primary.copy(alpha = 0.30f), cs.primaryContainer.copy(alpha = 0.55f), cs.surfaceVariant.copy(alpha = 0.4f)),
                )
            )
            .padding(20.dp),
    ) {
        // date + aircraft chip row
        val depBest = snapshot?.depTimes?.best
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            depBest?.let {
                val zone = state.depAirport?.tz?.let { z -> runCatching { ZoneId.of(z) }.getOrNull() } ?: ZoneId.systemDefault()
                Text(
                    DateTimeFormatter.ofPattern("EEE d MMM").withZone(zone).format(it),
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            snapshot?.aircraftModel?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AirportColumn(
                code = state.depAirport?.code ?: "···",
                city = state.depAirport?.city,
                time = snapshot?.depTimes?.best,
                zone = state.depAirport?.tz,
                modifier = Modifier.weight(1f),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Flight, contentDescription = null,
                    modifier = Modifier.size(22.dp).rotate(90f), tint = cs.primary)
                val dep = snapshot?.depTimes?.best; val arr = snapshot?.arrTimes?.best
                if (dep != null && arr != null) {
                    Text(
                        countdownText(Duration.between(dep, arr)),
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
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
                // "+1" for red-eyes, "−1" when local clocks say you land before
                // you left (westbound across the date line) — display only.
                dayOffset = ch.lkmc.blipbird.domain.FlightDates.arrivalDayOffset(
                    snapshot?.depTimes?.best, state.depAirport?.tz,
                    snapshot?.arrTimes?.best, state.arrAirport?.tz,
                ),
            )
        }
        Spacer(Modifier.height(6.dp))
        FlightProgressBar(
            progress = state.view.progress,
            color = ext.statusEnRoute,
            trackColor = cs.onSurface.copy(alpha = 0.15f),
        )
        Spacer(Modifier.height(8.dp))
        // countdown line
        val at = state.view.nextEventAt
        if (at != null) {
            val label = when (state.view.nextEventLabel) {
                FlightPhaseMachine.NextEvent.DEPARTS_IN -> "Departs in ${countdownText(Duration.between(Instant.now(), at))}"
                FlightPhaseMachine.NextEvent.LANDS_IN -> "Lands in ${countdownText(Duration.between(Instant.now(), at))}"
                FlightPhaseMachine.NextEvent.LANDED_AT -> "Landed at ${localTime(at, zoneOf(state.arrAirport?.tz))}"
                else -> null
            }
            label?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        snapshot?.codeshareOf?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.operated_by, snapshot.operatingDesignator ?: "?"),
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
            )
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
    dayOffset: Int? = null,
) {
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(
            code,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = androidx.compose.ui.unit.TextUnit(1f, androidx.compose.ui.unit.TextUnitType.Sp),
        )
        city?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        time?.let {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    localTime(it, zoneOf(zone)),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (dayOffset != null && dayOffset != 0) {
                    Text(
                        if (dayOffset > 0) "+$dayOffset" else "$dayOffset",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 2.dp, top = 2.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- map

@Composable
private fun MapCard(state: DetailUiState) {
    SectionCard(stringResource(R.string.live_map)) {
        val dep = state.depAirport?.let { a -> a.lat?.let { la -> a.lon?.let { lo -> GreatCircle.Point(la, lo) } } }
        val arr = state.arrAirport?.let { a -> a.lat?.let { la -> a.lon?.let { lo -> GreatCircle.Point(la, lo) } } }
        if (dep != null && arr != null) {
            MapLibreRouteMap(
                dep = dep,
                arr = arr,
                lastFix = state.lastFix,
                track = state.track,
                progress = state.view.progress,
                modifier = Modifier.fillMaxWidth().height(280.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        val fix = state.lastFix
        Text(
            when {
                fix == null -> stringResource(R.string.map_no_position)
                fix.seenPosAgeSec > 120 -> stringResource(R.string.last_seen_ago, agoText(fix.at))
                else -> listOfNotNull(
                    fix.baroAltitudeFt?.let { "${"%,d".format(it.toInt())} ft" },
                    fix.groundSpeedKt?.let { "${it.toInt()} kt" },
                    fix.trackDeg?.let { "${it.toInt()}°" },
                    "via ${fix.source}",
                ).joinToString("  ·  ")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "© OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

// ---------------------------------------------------------------- facts

@Composable
private fun KeyFacts(state: DetailUiState) {
    val s = state.snapshot ?: return
    // Airport-LOCAL wall-clock time + date; the date makes red-eyes and
    // date-line crossings unambiguous without a +1/−1 marker.
    fun timeDate(at: Instant?, tz: String?): String? = at?.let {
        val zone = zoneOf(tz)
        localTime(it, zone) + " · " + DateTimeFormatter.ofPattern("EEE d MMM").withZone(zone).format(it)
    }
    SectionCard(stringResource(R.string.key_facts)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                FactsHeader(Icons.Filled.FlightTakeoff, stringResource(R.string.departure))
                Fact(Icons.Outlined.Schedule, stringResource(R.string.departs), timeDate(s.depTimes.best, state.depAirport?.tz))
                Fact(Icons.Outlined.Domain, stringResource(R.string.terminal), s.depTerminal)
                Fact(Icons.Outlined.DoorFront, stringResource(R.string.gate), s.depGate)
                Fact(Icons.Outlined.Tag, stringResource(R.string.check_in), s.depCheckInDesk)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                FactsHeader(Icons.Filled.FlightLand, stringResource(R.string.arrival))
                Fact(Icons.Outlined.Schedule, stringResource(R.string.arrives), timeDate(s.arrTimes.best, state.arrAirport?.tz))
                Fact(Icons.Outlined.Domain, stringResource(R.string.terminal), s.arrTerminal)
                Fact(Icons.Outlined.DoorFront, stringResource(R.string.gate), s.arrGate)
                Fact(Icons.Outlined.Luggage, stringResource(R.string.baggage_belt), s.baggageBelt)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Fact(Icons.Filled.Flight, stringResource(R.string.aircraft), s.aircraftModel)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Fact(Icons.Outlined.Tag, stringResource(R.string.registration), s.registration)
            }
        }
    }
}

@Composable
private fun FactsHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Fact(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
        Icon(
            icon, contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value ?: "—",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (value != null) FontWeight.Bold else FontWeight.Normal,
                color = if (value != null) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

// ---------------------------------------------------------------- timeline

private enum class NodeState { DONE, ESTIMATED, DERIVED, PENDING }

private data class TimelineEntry(
    val label: String,
    val state: NodeState,
    val scheduled: Instant?,
    val shown: Instant?,
    val zone: String?,
    val derived: Boolean = false,
)

@Composable
private fun Timeline(state: DetailUiState) {
    val s = state.snapshot ?: return
    val depTz = state.depAirport?.tz
    val arrTz = state.arrAirport?.tz

    fun entry(label: String, times: MovementTimes, useRunway: Boolean, zone: String?): TimelineEntry {
        val sched = if (useRunway) null else times.scheduled
        val est = if (useRunway) times.runwayEstimated else times.estimated
        val act = if (useRunway) times.runwayActual else times.actual
        return TimelineEntry(
            label = label,
            state = when {
                act != null -> NodeState.DONE
                est != null -> NodeState.ESTIMATED
                else -> NodeState.PENDING
            },
            scheduled = sched,
            shown = act ?: est ?: sched.takeIf { useRunway.not() && it != null },
            zone = zone,
        )
    }

    val entries = listOf(
        TimelineEntry(
            stringResource(R.string.check_in), NodeState.DERIVED,
            null, state.view.derivedCheckInAt, depTz, derived = true,
        ),
        TimelineEntry(
            "Boarding", NodeState.DERIVED,
            null, state.view.derivedBoardingAt, depTz, derived = true,
        ),
        entry("Pushback", s.depTimes, useRunway = false, zone = depTz),
        entry("Takeoff", s.depTimes, useRunway = true, zone = depTz),
        entry("Landing", s.arrTimes, useRunway = true, zone = arrTz),
        entry("Gate arrival", s.arrTimes, useRunway = false, zone = arrTz),
    )
    val now = Instant.now()
    val nextIdx = entries.indexOfFirst { it.state != NodeState.DONE && (it.shown?.isAfter(now) != false) }

    SectionCard(stringResource(R.string.timeline)) {
        entries.forEachIndexed { i, e ->
            TimelineRow(
                entry = e,
                isFirst = i == 0,
                isLast = i == entries.lastIndex,
                isNext = i == nextIdx,
                doneAbove = i > 0 && entries[i - 1].state == NodeState.DONE,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.derived_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TimelineRow(
    entry: TimelineEntry,
    isFirst: Boolean,
    isLast: Boolean,
    isNext: Boolean,
    doneAbove: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val ext = LocalExtendedColors.current
    val nodeColor = when (entry.state) {
        NodeState.DONE -> ext.statusOnTime
        NodeState.ESTIMATED -> ext.statusEnRoute
        NodeState.DERIVED -> cs.tertiary
        NodeState.PENDING -> cs.outlineVariant
    }

    // Min-height rather than fixed so large accessibility fonts grow the row
    // instead of clipping; IntrinsicSize keeps the rail canvas bounded.
    Row(
        Modifier.heightIn(min = 44.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // rail
        Canvas(Modifier.width(26.dp).fillMaxSize()) {
            val cx = size.width / 2
            val cy = size.height / 2
            val lineColor = cs.outlineVariant.copy(alpha = 0.6f)
            if (!isFirst) drawLine(
                if (doneAbove) ext.statusOnTime.copy(alpha = 0.6f) else lineColor,
                Offset(cx, 0f), Offset(cx, cy - 8.dp.toPx()), 2.dp.toPx(), cap = StrokeCap.Round,
            )
            if (!isLast) drawLine(
                lineColor,
                Offset(cx, cy + 8.dp.toPx()), Offset(cx, size.height), 2.dp.toPx(), cap = StrokeCap.Round,
            )
            when (entry.state) {
                NodeState.DONE -> drawCircle(nodeColor, 5.dp.toPx(), Offset(cx, cy))
                NodeState.ESTIMATED, NodeState.DERIVED -> {
                    drawCircle(nodeColor, 5.dp.toPx(), Offset(cx, cy), style = Stroke(2.dp.toPx()))
                    drawCircle(nodeColor, 2.dp.toPx(), Offset(cx, cy))
                }
                NodeState.PENDING -> drawCircle(nodeColor, 4.5.dp.toPx(), Offset(cx, cy), style = Stroke(2.dp.toPx()))
            }
            if (isNext) drawCircle(nodeColor.copy(alpha = 0.25f), 9.dp.toPx(), Offset(cx, cy))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            (if (entry.derived) "~ " else "") + entry.label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isNext) FontWeight.SemiBold else FontWeight.Normal,
        )
        // scheduled — struck through only when genuinely superseded by a DIFFERENT time
        val shown = entry.shown
        val sched = entry.scheduled
        if (sched != null && shown != null && sched != shown &&
            Duration.between(sched, shown).abs() >= Duration.ofMinutes(1)
        ) {
            Text(
                localTime(sched, zoneOf(entry.zone)),
                style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.LineThrough),
                color = cs.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            shown?.let { localTime(it, zoneOf(entry.zone)) } ?: "—",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (entry.state == NodeState.DONE || isNext) FontWeight.Bold else FontWeight.Medium,
            color = if (shown != null) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

private fun zoneOf(tz: String?): ZoneId =
    tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

// ---------------------------------------------------------------- weather & airline

@Composable
private fun WeatherCard(state: DetailUiState) {
    SectionCard(stringResource(R.string.weather)) {
        state.airportWeather.forEachIndexed { i, w ->
            val isDep = w.stationId.equals(state.depAirport?.icao, ignoreCase = true)
            val airport = if (isDep) state.depAirport else state.arrAirport
            WeatherStation(
                w = w,
                roleIcon = if (isDep) Icons.Filled.FlightTakeoff else Icons.Filled.FlightLand,
                code = airport?.code ?: w.stationId,
                place = airport?.city ?: airport?.name,
            )
            if (i != state.airportWeather.lastIndex) {
                HorizontalDivider(
                    Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun WeatherStation(
    w: ch.lkmc.blipbird.core.model.AirportWeather,
    roleIcon: androidx.compose.ui.graphics.vector.ImageVector,
    code: String,
    place: String?,
) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            w.temperatureC?.let { "${it.roundToInt()}°" } ?: "–",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = cs.onSurface,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(roleIcon, contentDescription = null, modifier = Modifier.size(14.dp), tint = cs.primary)
                Spacer(Modifier.width(5.dp))
                Text(
                    listOfNotNull(code, place).joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                w.decoded,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            w.observedAt?.let {
                Text(
                    stringResource(R.string.observed_ago, agoText(it)),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
        if (w.windSpeedKt != null) {
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // METAR wind is FROM a direction; the arrow shows where it blows TO.
                Icon(
                    Icons.Filled.Navigation,
                    contentDescription = null,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate((((w.windDirDeg ?: 0) + 180) % 360).toFloat()),
                    tint = cs.primary,
                )
                Text(
                    "${w.windSpeedKt} kt" + (w.windGustKt?.let { " G$it" } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        w.rawMetar,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
    )
}

@Composable
private fun AirlineCard(state: DetailUiState) {
    val name = state.airlineName ?: return
    val s = state.snapshot
    SectionCard(stringResource(R.string.airline)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val mono = state.airlineIata ?: state.airlineIcao ?: name.take(2)
            Box(
                Modifier.size(44.dp).background(monogramColor(mono), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    mono.take(2).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val codes = listOfNotNull(
                    state.airlineIata?.let { "IATA $it" },
                    state.airlineIcao?.let { "ICAO $it" },
                ).joinToString("  ·  ")
                if (codes.isNotEmpty()) {
                    Text(codes, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // Route stats: great-circle distance (provider's, else computed) + block time.
        val dep = state.depAirport
        val arr = state.arrAirport
        val distanceKm = s?.greatCircleKm
            ?: if (dep?.lat != null && dep.lon != null && arr?.lat != null && arr.lon != null)
                GreatCircle.distanceKm(GreatCircle.Point(dep.lat!!, dep.lon!!), GreatCircle.Point(arr.lat!!, arr.lon!!))
            else null
        val blockTime = s?.depTimes?.best?.let { d -> s.arrTimes.best?.let { a -> Duration.between(d, a) } }
            ?.takeIf { !it.isNegative && !it.isZero }
        val pills = listOfNotNull(
            distanceKm?.let { stringResource(R.string.route_distance) to "${"%,d".format(it.roundToInt())} km" },
            blockTime?.let { stringResource(R.string.duration) to countdownText(it) },
        )
        if (pills.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pills.forEach { (label, value) -> StatPill(label, value) }
            }
        }
    }
}

@Composable
private fun StatPill(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .background(cs.surface.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}
