package ch.lkmc.blipbird.ui.itinerary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.lkmc.blipbird.R

// ---------------------------------------------------------------- summary

/**
 * The trip's banner. Deliberately without the itinerary's title — the app bar
 * directly above already carries it, and a card that repeats the screen's own
 * name is a line of chrome, not information. What this card alone knows is the
 * whole plan's airport chain; the flight numbers stand in until routes resolve,
 * and each leg card below carries its own designator either way.
 */
@Composable
internal fun SummaryCard(
    dateSpan: String?,
    routeChain: String?,
    designators: String,
    padding: Dp,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().itineraryBorder(26.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(padding)) {
            // Same idiom as the leg cards: the quiet line above, the hero below.
            dateSpan?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = contrastAware(MaterialTheme.colorScheme.onPrimaryContainer, 0.8f),
                )
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Route,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    routeChain ?: designators,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    // A many-leg chain (or the unbounded designator fallback)
                    // must not turn the banner into a paragraph at title size.
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
            }
        }
    }
}

/**
 * Shown only while *no* member has resolved anything: one honest explanation
 * beats the same sentence repeated under every leg. Which explanation depends on
 * whether an approved status source is configured at all (§7.9).
 */
@Composable
internal fun LiveDetailsNotice(
    hasStatusKey: Boolean,
    padding: Dp,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().itineraryBorder(18.dp),
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
