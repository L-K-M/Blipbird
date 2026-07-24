package ch.lkmc.blipbird

import ch.lkmc.blipbird.core.model.BaggagePlan
import ch.lkmc.blipbird.core.model.BookingArrangement
import ch.lkmc.blipbird.core.model.DateIntentSource
import ch.lkmc.blipbird.core.model.Itinerary
import ch.lkmc.blipbird.core.model.ItineraryLeg
import ch.lkmc.blipbird.core.model.TransitionIntent
import ch.lkmc.blipbird.core.database.TrackedFlightEntity
import ch.lkmc.blipbird.ui.itinerary.designatorLine
import kotlin.test.Test
import kotlin.test.assertEquals

class ItineraryModelsTest {
    @Test
    fun futureEnumValuesDecodeDefensively() {
        assertEquals(TransitionIntent.UNKNOWN, TransitionIntent.decode("FUTURE"))
        assertEquals(BookingArrangement.UNKNOWN, BookingArrangement.decode("FUTURE"))
        assertEquals(BaggagePlan.UNKNOWN, BaggagePlan.decode("FUTURE"))
        assertEquals(DateIntentSource.LEGACY_UNKNOWN, DateIntentSource.decode("FUTURE"))
    }

    @Test
    fun knownEnumValuesRoundTrip() {
        TransitionIntent.entries.forEach { assertEquals(it, TransitionIntent.decode(it.name)) }
        BookingArrangement.entries.forEach { assertEquals(it, BookingArrangement.decode(it.name)) }
        BaggagePlan.entries.forEach { assertEquals(it, BaggagePlan.decode(it.name)) }
        DateIntentSource.entries.forEach { assertEquals(it, DateIntentSource.decode(it.name)) }
    }

    @Test
    fun itinerarySummaryKeepsCanonicalDesignatorWhenFlightHasAlias() {
        val flight = TrackedFlightEntity(
            id = 42,
            designatorIata = "LX",
            designatorIcao = "SWR",
            flightNumber = "2801",
            suffix = null,
            dateLocal = "2026-09-18",
            alias = "Home",
            createdAt = 1,
        )
        val itinerary = Itinerary(
            id = 1,
            creationRequestId = "request",
            name = null,
            createdAt = 1,
            updatedAt = 1,
            legs = listOf(ItineraryLeg(id = 2, ordinal = 0, flight = flight)),
            transitions = emptyList(),
        )

        assertEquals("LX2801", itinerary.designatorLine())
    }
}
