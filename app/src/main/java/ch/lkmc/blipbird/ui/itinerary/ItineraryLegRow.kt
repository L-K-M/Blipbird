package ch.lkmc.blipbird.ui.itinerary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.domain.FlightPhaseMachine
import ch.lkmc.blipbird.ui.components.StatusWord
import ch.lkmc.blipbird.ui.components.countdownText
import ch.lkmc.blipbird.ui.components.departsInText
import ch.lkmc.blipbird.ui.components.elapsedText
import ch.lkmc.blipbird.ui.components.landsInText
import ch.lkmc.blipbird.ui.components.localTime
import ch.lkmc.blipbird.ui.components.lookupProblemNeedsUser
import ch.lkmc.blipbird.ui.components.lookupProblemText
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// ---------------------------------------------------------------- legs

/**
 * One leg of the spine: the user's own facts, plus the resolved route and times
 * for the occurrence they confirmed.
 *
 * A flight is the thing this screen is *about*, so it is the thing that gets a
 * card — a filled container that reads as an object against the page. The
 * transition below it deliberately gets none.
 */
@Composable
internal fun LegRow(
    leg: ItineraryLegUi,
    metrics: SpineMetrics,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .itineraryBorder(LEG_CARD_RADIUS)
            // The label belongs to the action, not to the card's contents:
            // `clickable` merges its descendants, so a chevron carrying the same
            // string would read it out as if it were another line of the card.
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.itinerary_open_flight),
                onClick = onOpen,
            ),
        shape = RoundedCornerShape(LEG_CARD_RADIUS),
        // surfaceContainerHigh, not surface: in the dark scheme `surface` and
        // `background` are the same colour, so the card that carries the flight
        // was drawing an invisible box while the transition below it looked like
        // the boxed one. The weight now matches the meaning.
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(metrics.cardPadding)) {
            LegHeader(leg, metrics)
            if (leg.routeResolved) {
                Spacer(Modifier.height(14.dp))
                RouteRow(leg)
                Spacer(Modifier.height(10.dp))
                PhaseLine(leg)
            } else {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.itinerary_route_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LegFootnotes(leg)
        }
    }
}

private val LEG_CARD_RADIUS = 20.dp

/**
 * Ordinal and date sit above the flight number as one quiet line, so the row the
 * eye lands on first is the route below rather than the scaffolding around it.
 */
@Composable
private fun LegHeader(leg: ItineraryLegUi, metrics: SpineMetrics) {
    val date = leg.date?.takeIf { leg.dateConfirmed }?.format(
        DateTimeFormatter.ofLocalizedDate(if (metrics.compact) FormatStyle.MEDIUM else FormatStyle.LONG)
    )
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(stringResource(R.string.itinerary_leg_index, leg.position), date)
                    .joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(3.dp))
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
        Spacer(Modifier.width(4.dp))
        // Purely decorative: the card's own onClickLabel already names the action.
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
    if (date == null) {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.itinerary_date_needs_confirmation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Retrieval age, lookup reason and occurrence disagreement — the fine print. */
@Composable
private fun LegFootnotes(leg: ItineraryLegUi) {
    // Only a genuine disagreement, not a leg whose date was never confirmed in
    // the first place — those two need different words.
    if (leg.hasSnapshot && leg.dateConfirmed && !leg.occurrenceConfirmed) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.itinerary_occurrence_mismatch),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    // "Fetched", never "Updated": `fetchedAt` is this device's retrieval time,
    // and a fresh fetch can still carry old upstream values (§8.10).
    val problem = leg.lookupProblem
    val infoLine = listOfNotNull(
        leg.fetchedAt?.let {
            stringResource(R.string.itinerary_fetched_ago, elapsedText(Duration.between(it, leg.now)))
        },
        problem?.let { lookupProblemText(it, leg.lookupProviders) },
    ).joinToString("  ·  ")
    if (infoLine.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            infoLine,
            style = MaterialTheme.typography.labelSmall,
            // Split by who has to act. A 429 or an unreachable host retries itself
            // and only explains why a leg is thin; a missing key or a spent budget
            // waits on the user and keeps the alarm colour it always had.
            color = when {
                problem == null -> MaterialTheme.colorScheme.onSurfaceVariant
                lookupProblemNeedsUser(problem) -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.tertiary
            },
        )
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
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(
                Icons.Filled.Flight,
                contentDescription = null,
                modifier = Modifier.size(16.dp).rotate(90f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (leg.depTime != null && leg.arrTime != null) {
                Spacer(Modifier.height(2.dp))
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
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        if (time != null) {
            val zone = tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
            Spacer(Modifier.height(1.dp))
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
