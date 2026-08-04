package ch.lkmc.blipbird.ui.itinerary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.domain.JetlagEngine
import ch.lkmc.blipbird.ui.components.shiftLabel
import ch.lkmc.blipbird.ui.components.signedShiftLabel
import kotlin.math.abs

/**
 * The itinerary's body-clock summary (PLAN.md §9.5): how far the plan takes you
 * from the clock you started on, and the rule-of-thumb cost of the biggest shift.
 *
 * Same two-tier honesty as the per-flight card — the shift chain is exact offset
 * arithmetic, the days figure is a hedged population average. No connection or
 * transfer claim is made here, so this stays clear of the gates §4.6 keeps open.
 */
@Composable
internal fun ItineraryBodyClockCard(trip: JetlagEngine.Trip, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().itineraryBorder(18.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = itinerarySurface(0.42f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.body_clock).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(10.dp))

            val plan = trip.peakPlan
            if (plan == null) {
                Text(
                    stringResource(R.string.itinerary_body_clock_negligible),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    stringResource(
                        if (trip.peak.shiftMinutes > 0) R.string.itinerary_body_clock_peak_later
                        else R.string.itinerary_body_clock_peak_earlier,
                        shiftLabel(abs(trip.peak.shiftMinutes)),
                        trip.peak.arrivalCode,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    pluralStringResource(
                        when (plan.direction) {
                            JetlagEngine.Direction.ADVANCE -> R.plurals.itinerary_body_clock_plan_earlier
                            JetlagEngine.Direction.DELAY -> R.plurals.itinerary_body_clock_plan_later
                        },
                        plan.days,
                        shiftLabel(plan.shiftMinutes),
                        plan.days,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                chain(trip),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (trip.returnsToStart) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.itinerary_body_clock_returns, trip.startCode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (trip.incomplete) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.itinerary_body_clock_incomplete),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "ZRH · NRT +7 h · SIN +6 h · ZRH ±0" — cumulative shift from the starting clock. */
@Composable
private fun chain(trip: JetlagEngine.Trip): String {
    // map is inline, so the composable label lookup is legal inside it; joinToString
    // is not, hence the two steps.
    val labels = trip.steps.map { "${it.arrivalCode} ${signedShiftLabel(it.shiftMinutes)}" }
    return (listOf(trip.startCode) + labels).joinToString("  ·  ")
}
