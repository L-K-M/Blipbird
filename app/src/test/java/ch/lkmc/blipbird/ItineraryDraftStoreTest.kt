package ch.lkmc.blipbird

import androidx.lifecycle.SavedStateHandle
import ch.lkmc.blipbird.ui.itinerary.ItineraryDraft
import ch.lkmc.blipbird.ui.itinerary.ItineraryDraftLeg
import ch.lkmc.blipbird.ui.itinerary.ItineraryDraftStoreViewModel
import ch.lkmc.blipbird.ui.itinerary.beginIdentityReplacement
import ch.lkmc.blipbird.ui.itinerary.replacedAt
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

    /**
     * `update` re-reads the draft from the store, so a row's captured index can
     * outlive a removal that shrank the list. Every list mutator must absorb
     * that instead of throwing IndexOutOfBoundsException.
     */
    @Test
    fun legReplacementIgnoresAnIndexThatNoLongerExists() {
        val legs = listOf(ItineraryDraftLeg(rowId = "first"), ItineraryDraftLeg(rowId = "second"))
        val replacement = ItineraryDraftLeg(rowId = "third", designator = "LX2803")

        assertEquals(legs, legs.replacedAt(2, replacement))
        assertEquals(legs, legs.replacedAt(-1, replacement))
        assertEquals(
            listOf(legs[0], replacement),
            legs.replacedAt(1, replacement),
        )
    }

    /**
     * The composer is filled in top to bottom: flight 1's number, its date, what
     * happens after it, then flight 2. Editing flight 2 must not wipe the answer
     * just given for flight 1 — "what happens" is the user's plan, not something
     * derived from either occurrence.
     */
    @Test
    fun editingALegKeepsTheTransitionChoicesAroundIt() {
        val first = ItineraryDraftLeg(
            rowId = "first",
            transitionAfter = TransitionIntent.DIRECT_CONNECTION.name,
        )
        val second = ItineraryDraftLeg(
            rowId = "second",
            transitionAfter = TransitionIntent.DESTINATION_STAY.name,
        )
        val third = ItineraryDraftLeg(rowId = "third")
        val legs = listOf(first, second, third)

        val typed = legs.replacedAt(1, second.withDesignatorIdentity("LX2802"))
        assertEquals(TransitionIntent.DIRECT_CONNECTION.name, typed[0].transitionAfter)
        assertEquals(TransitionIntent.DESTINATION_STAY.name, typed[1].transitionAfter)

        val dated = legs.replacedAt(1, second.withDepartureDateIdentity("2026-09-19"))
        assertEquals(TransitionIntent.DIRECT_CONNECTION.name, dated[0].transitionAfter)
        assertEquals(TransitionIntent.DESTINATION_STAY.name, dated[1].transitionAfter)

        // Even swapping the occurrence outright keeps the plan; the repository
        // still starts the rebuilt edge's booking/baggage answers at unknown.
        val replaced = legs.replacedAt(1, second.beginIdentityReplacement())
        assertEquals(TransitionIntent.DIRECT_CONNECTION.name, replaced[0].transitionAfter)
        assertEquals(TransitionIntent.DESTINATION_STAY.name, replaced[1].transitionAfter)
    }
}
