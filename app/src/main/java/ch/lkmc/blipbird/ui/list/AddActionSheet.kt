package ch.lkmc.blipbird.ui.list

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.AddRoad
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.lkmc.blipbird.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddActionSheet(
    canGroupExisting: Boolean,
    onDismiss: () -> Unit,
    onAddItinerary: () -> Unit,
    onTrackFlights: () -> Unit,
    onGroupExisting: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.itinerary_add)) },
                supportingContent = { Text(stringResource(R.string.itinerary_add_desc)) },
                leadingContent = { Icon(Icons.Filled.AddRoad, contentDescription = null) },
                modifier = Modifier.clickableListItem {
                    onDismiss()
                    onAddItinerary()
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.track_flights)) },
                supportingContent = { Text(stringResource(R.string.track_flights_desc)) },
                leadingContent = { Icon(Icons.Filled.Flight, contentDescription = null) },
                modifier = Modifier.clickableListItem {
                    onDismiss()
                    onTrackFlights()
                },
            )
            if (canGroupExisting) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.itinerary_group_existing)) },
                    supportingContent = { Text(stringResource(R.string.itinerary_group_existing_desc)) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
                    modifier = Modifier.clickableListItem {
                        onDismiss()
                        onGroupExisting()
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun Modifier.clickableListItem(onClick: () -> Unit): Modifier =
    clickable(onClick = onClick)
