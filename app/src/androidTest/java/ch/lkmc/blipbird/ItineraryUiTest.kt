package ch.lkmc.blipbird

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import ch.lkmc.blipbird.ui.list.AddActionSheet
import org.junit.Rule
import org.junit.Test

class ItineraryUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun addSheetExposesItineraryAndEquivalentFlightActions() {
        compose.setContent {
            MaterialTheme {
                AddActionSheet(
                    canGroupExisting = true,
                    onDismiss = {},
                    onAddItinerary = {},
                    onTrackFlights = {},
                    onGroupExisting = {},
                )
            }
        }

        compose.onNodeWithText("Add itinerary").assertHasClickAction()
        compose.onNodeWithText("Track flight(s)").assertHasClickAction()
        compose.onNodeWithText("Group existing flights").assertHasClickAction()
    }

    @Test
    fun groupingActionIsHiddenWithoutTwoUngroupedFlights() {
        compose.setContent {
            MaterialTheme {
                AddActionSheet(
                    canGroupExisting = false,
                    onDismiss = {},
                    onAddItinerary = {},
                    onTrackFlights = {},
                    onGroupExisting = {},
                )
            }
        }

        compose.onAllNodesWithText("Group existing flights").assertCountEquals(0)
    }
}
