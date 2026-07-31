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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.lkmc.blipbird.R

// ---------------------------------------------------------------- summary

@Composable
internal fun SummaryCard(
    title: String,
    dateSpan: String?,
    routeChain: String?,
    designators: String,
    padding: Dp,
) {
    Card(
        modifier = Modifier.fillMaxWidth().itineraryBorder(26.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(padding)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            dateSpan?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = contrastAware(MaterialTheme.colorScheme.onPrimaryContainer, 0.8f))
            }
            routeChain?.let {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Route,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(if (routeChain == null) 10.dp else 6.dp))
            Text(
                designators,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = contrastAware(MaterialTheme.colorScheme.onPrimaryContainer, 0.85f),
            )
        }
    }
}

/**
 * Shown only while *no* member has resolved anything: one honest explanation
 * beats the same sentence repeated under every leg. Which explanation depends on
 * whether an approved status source is configured at all (§7.9).
 */
@Composable
internal fun LiveDetailsNotice(hasStatusKey: Boolean, padding: Dp, onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().itineraryBorder(18.dp),
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
