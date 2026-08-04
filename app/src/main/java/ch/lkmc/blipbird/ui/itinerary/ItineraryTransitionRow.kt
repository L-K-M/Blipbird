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
import ch.lkmc.blipbird.ui.components.countdownText
import ch.lkmc.blipbird.ui.components.elapsedText

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
        Connector()
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp)) {
            TransitionHeader(transition, enabled, onIntent)
            Spacer(Modifier.height(4.dp))
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

/**
 * The vertical run of the spine between two legs. It spans the whole row, so it
 * meets the card above and the card below and the three read as one journey.
 */
@Composable
private fun Connector() {
    Box(
        Modifier
            .width(2.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(1.dp))
    )
}
