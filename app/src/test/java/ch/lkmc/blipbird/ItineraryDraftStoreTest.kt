package ch.lkmc.blipbird

import androidx.lifecycle.SavedStateHandle
import ch.lkmc.blipbird.ui.itinerary.ItineraryDraft
import ch.lkmc.blipbird.ui.itinerary.ItineraryDraftLeg
import ch.lkmc.blipbird.ui.itinerary.ItineraryDraftStoreViewModel
import ch.lkmc.blipbird.ui.itinerary.beginIdentityReplacement
import ch.lkmc.blipbird.ui.itinerary.replacedIdentity
import ch.lkmc.blipbird.ui.itinerary.withDepartureDateIdentity
import ch.lkmc.blipbird.ui.itinerary.withDesignatorIdentity
import ch.lkmc.blipbird.core.model.TransitionIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItineraryDraftStoreTest {
    @Test
    fun draftRoundTripsThroughSavedStateHandle() {
        val handle = SavedStateHandle()
        val original = ItineraryDraft(
            draftId = "draft-1",
            name = "Japan",
            legs = listOf(
                ItineraryDraftLeg(
                    existingFlightId = 42,
                    originalMemberFlightId = 42,
                    designator = "LX2801",
                    dateLocal = "2026-09-18",
                    dateConfirmed = true,
                ),
                ItineraryDraftLeg(designator = "NH204", dateLocal = "2026-09-18", dateConfirmed = true),
            ),
            dirty = true,
        )

        assertTrue(ItineraryDraftStoreViewModel(handle).put(original))
        assertEquals(original, ItineraryDraftStoreViewModel(handle).draft(original.draftId))
    }

    @Test
    fun oversizedDraftIsRejectedWithoutTruncation() {
        val store = ItineraryDraftStoreViewModel(SavedStateHandle())
        val oversized = ItineraryDraft(draftId = "large", name = "x".repeat(33 * 1024))

        assertFalse(store.put(oversized))
        assertEquals(null, store.draft("large"))
    }

    @Test
    fun confirmingDateOnAddedTrackedFlightDoesNotReplaceIt() {
        val added = ItineraryDraftLeg(
            existingFlightId = 42,
            designator = "LX2801",
        )

        val confirmed = added.withDepartureDateIdentity("2026-09-18")

        assertEquals(42, confirmed.existingFlightId)
        assertEquals(null, confirmed.replacesFlightId)
        assertTrue(confirmed.dateConfirmed)
    }

    @Test
    fun editingOriginalMemberIdentityNeedsExplicitReplacement() {
        val member = ItineraryDraftLeg(
            existingFlightId = 42,
            originalMemberFlightId = 42,
            designator = "LX2801",
            dateLocal = "2026-09-18",
            dateConfirmed = true,
        )

        val edited = member.withDesignatorIdentity("LX2802")

        assertEquals(42, edited.existingFlightId)
        assertEquals(null, edited.replacesFlightId)
    }

    @Test
    fun confirmedReplacementMarksOnlyTheOriginalMember() {
        val member = ItineraryDraftLeg(
            existingFlightId = 42,
            originalMemberFlightId = 42,
            designator = "LX2801",
        )

        val replacement = member.beginIdentityReplacement()

        assertEquals(null, replacement.existingFlightId)
        assertEquals(42, replacement.replacesFlightId)
    }

    @Test
    fun editingConfirmedUngroupedDateLeavesOriginalFlightUntouched() {
        val added = ItineraryDraftLeg(
            existingFlightId = 42,
            designator = "LX2801",
            dateLocal = "2026-09-18",
            dateConfirmed = true,
        )

        val edited = added.withDepartureDateIdentity("2026-09-19")

        assertEquals(42, edited.existingFlightId)
        assertEquals(null, edited.replacesFlightId)
    }

    @Test
    fun identityReplacementResetsIncomingAndOutgoingTransitionIntent() {
        val first = ItineraryDraftLeg(
            rowId = "first",
            transitionAfter = TransitionIntent.DIRECT_CONNECTION.name,
        )
        val second = ItineraryDraftLeg(
            rowId = "second",
            transitionAfter = TransitionIntent.DESTINATION_STAY.name,
        )
        val third = ItineraryDraftLeg(rowId = "third")

        val updated = listOf(first, second, third).replacedIdentity(
            index = 1,
            replacement = second.copy(designator = "LX2802"),
        )

        assertEquals(TransitionIntent.UNKNOWN.name, updated[0].transitionAfter)
        assertEquals(TransitionIntent.UNKNOWN.name, updated[1].transitionAfter)
    }
}
