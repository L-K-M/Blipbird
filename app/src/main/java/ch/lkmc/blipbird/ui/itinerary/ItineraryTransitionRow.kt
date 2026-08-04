package ch.lkmc.blipbird.ui.itinerary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.model.TimeCertainty
import ch.lkmc.blipbird.core.model.TransitionIntent
import ch.lkmc.blipbird.domain.TransitionEngine
import ch.lkmc.blipbird.ui.components.FlightProgressBar
import ch.lkmc.blipbird.ui.components.countdownText
import ch.lkmc.blipbird.ui.components.elapsedText
import ch.lkmc.blipbird.ui.components.withTabularNumbers
import java.time.Duration

// ---------------------------------------------------------------- transitions

/**
 * One edge of the spine: what [TransitionEngine] could derive for the gap
 * between two legs, leading with the factual state and only then with a
 * duration (§7.7).
 *
 * Deliberately not a card. This is what happens *between* two flights, so it
 * reads as connective tissue — a hairline running from the card above to the one
 * below, with the detail hanging off it — rather than as a third object competing
 * with the flights it joins.
 */
@Composable
internal fun TransitionRow(
    transition: ItineraryTransitionUi,
    enabled: Boolean,
    metrics: SpineMetrics,
    onBooking: () -> Unit,
    onBaggage: () -> Unit,
    onIntent: () -> Unit,
    onApplySuggestion: (TransitionIntent) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = metrics.connectorInset)
            .height(IntrinsicSize.Min),
    ) {
        // The thread lights up in the accent while the traveller is actually
        // between flights — colour as state, the spine's one live moment.
        Connector(live = transition.assessment.transfer?.inProgress == true)
        // weight, so the text column claims the rest of the row outright. It
        // already filled it, but only because the header inside happens to hold a
        // weighted child — take that weight away and the whole block would
        // silently shrink to its longest word.
        Column(
            Modifier
                .weight(1f)
                .padding(
                    start = metrics.connectorGap,
                    top = metrics.cardPadding,
                    bottom = metrics.cardPadding,
                )
        ) {
            TransitionHeader(transition, enabled, onIntent)
            Spacer(Modifier.height(4.dp))
            when (transition.intent) {
                TransitionIntent.DIRECT_CONNECTION -> ConnectionBody(
                    transition = transition,
                    enabled = enabled,
                    compact = metrics.compact,
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
            // A terminal change is the one airport fact the reported gates let
            // us state — the closest this screen can honestly get to "how far
            // is the walk".
            val fromTerminal = transition.assessment.inboundLocation.terminal
            val toTerminal = transition.assessment.outboundLocation.terminal
            if (
                transition.intent == TransitionIntent.DIRECT_CONNECTION &&
                fromTerminal != null && toTerminal != null && fromTerminal != toTerminal
            ) {
                Text(
                    stringResource(R.string.connection_terminal_change, fromTerminal, toTerminal),
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
    compact: Boolean,
    onBooking: () -> Unit,
    onBaggage: () -> Unit,
) {
    val assessment = transition.assessment
    // §7.7: the factual state leads, the duration follows — except the one calm
    // explanation whose only job is to say why a *refined* window is absent.
    // That reads after the facts it qualifies, not instead of them: hiding the
    // span, the gate and the countdown behind it was this card's old failure.
    val demotedExplanation = assessment.disruption == TransitionEngine.Disruption.GATE_TIMES_UNSUPPORTED
    val timingAllowed = assessment.disruption == null ||
        assessment.disruption in TIMING_STILL_MEANINGFUL || demotedExplanation
    if (!demotedExplanation) DisruptionLine(transition)
    val gatePromoted = timingAllowed && assessment.transfer != null && assessment.outboundLocation.known
    if (timingAllowed) {
        TransferBlock(transition)
        WindowBlock(transition)
    }
    if (demotedExplanation) DisruptionLine(transition)
    Spacer(Modifier.height(4.dp))
    // Two related answers share a line where the width allows it — stacked
    // full-height buttons were a third of this card's height saying two words.
    if (compact) {
        TextButton(onClick = onBooking, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.itinerary_booking_value, bookingLabel(transition.booking)))
        }
        TextButton(onClick = onBaggage, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.itinerary_baggage_value, baggageLabel(transition.baggage)))
        }
    } else {
        Row {
            TextButton(
                onClick = onBooking,
                enabled = enabled,
                modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.itinerary_booking_value, bookingLabel(transition.booking)), maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onBaggage,
                enabled = enabled,
                modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.itinerary_baggage_value, baggageLabel(transition.baggage)), maxLines = 1)
            }
        }
    }
    LocationBlock(assessment, outboundShownAbove = gatePromoted)
}

/**
 * What is factually known about the change of planes, §8.2 notwithstanding: the
 * onward gate, the live countdown with its progress while the traveller is on
 * the ground, and the gross span between the reported times. None of this is a
 * connection window — the windows keep their own block and their own gate.
 */
@Composable
private fun TransferBlock(transition: ItineraryTransitionUi) {
    val assessment = transition.assessment
    val transfer = assessment.transfer ?: return
    val now = transition.now
    val gap = transfer.gap.takeIf { !it.isNegative && !it.isZero }
    val untilDeparture = Duration.between(now, transfer.end)
        .takeIf { transfer.inProgress && !it.isNegative && !it.isZero }

    // On the ground with the onward flight still to leave: time left is the
    // headline (§8.5, widened from gate actuals to a reported landing).
    untilDeparture?.let { remaining ->
        // The one number a traveller mid-transfer cares about: headline size,
        // accent colour, digits that hold their width as the minutes tick.
        Text(
            stringResource(R.string.connection_until_onward, countdownText(remaining)),
            style = MaterialTheme.typography.titleLarge.withTabularNumbers(),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.connection_boarding_earlier),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
    }
    // The walk's destination, in the leg cards' own "Gate B42 · Terminal 1"
    // vocabulary. Reported, like everything here — the confirm-on-displays
    // footnote below still applies to it.
    val destination = listOfNotNull(
        assessment.outboundLocation.gate?.let { "${stringResource(R.string.gate)} $it" },
        assessment.outboundLocation.terminal?.let { "${stringResource(R.string.terminal)} $it" },
    ).joinToString("  ·  ").takeIf { it.isNotEmpty() }
    destination?.let {
        Text(
            it,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
    if (untilDeparture != null && gap != null) {
        Spacer(Modifier.height(4.dp))
        FlightProgressBar(
            progress = (gap.seconds - untilDeparture.seconds).toFloat() / gap.seconds.toFloat(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
            // A walk through a terminal, not a flight.
            planeVisible = false,
        )
    }
    gap?.let {
        if (untilDeparture == null && destination != null) Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.connection_reported_span, countdownText(it)),
            style = MaterialTheme.typography.bodyMedium.withTabularNumbers(),
        )
    }
    if (untilDeparture != null || destination != null || gap != null) {
        Spacer(Modifier.height(6.dp))
    }
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

    // The live "until onward departure" headline lives in TransferBlock now,
    // for every provider alike; this block is only the refined gate-to-gate
    // arithmetic §8.2 allows.
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

/**
 * Reported terminal/gate facts for the app user — never an instruction (§7.8).
 * [outboundShownAbove] means [TransferBlock] already promoted the onward gate;
 * the line isn't repeated here, but the confirm-on-displays footnote still
 * covers it.
 */
@Composable
private fun LocationBlock(assessment: TransitionEngine.Assessment, outboundShownAbove: Boolean) {
    val inbound = locationText(assessment.inboundLocation)
    val outbound = locationText(assessment.outboundLocation).takeUnless { outboundShownAbove }
    val anyReported = inbound != null || outbound != null ||
        (outboundShownAbove && assessment.outboundLocation.known)
    if (!anyReported && !assessment.onwardGatePending) return
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
    if (anyReported) {
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

/**
 * The vertical run of the spine between two legs. It spans the whole row, so it
 * meets the card above and the card below and the three read as one journey.
 */
@Composable
private fun Connector(live: Boolean) {
    Box(
        Modifier
            .width(2.dp)
            .fillMaxHeight()
            .background(
                if (live) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(1.dp),
            )
    )
}
