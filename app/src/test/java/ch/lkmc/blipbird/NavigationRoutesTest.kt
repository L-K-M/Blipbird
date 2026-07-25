package ch.lkmc.blipbird

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NavigationRoutesTest {
    @Test
    fun everyTaggedRouteRoundTrips() {
        val routes = listOf(
            Screen.List,
            Screen.FlightDetail(42),
            Screen.ItineraryDetail(7),
            Screen.ItineraryEditor("draft-123", null),
            Screen.ItineraryEditor("draft-456", 7),
            Screen.GroupExistingFlights("draft-789"),
            Screen.Settings,
            Screen.Archived,
        )

        routes.forEach { assertEquals(it, decodeRoute(it.encodeRoute())) }
    }

    @Test
    fun legacyLongsStillDecode() {
        assertEquals(Screen.List, decodeRoute(-1L))
        assertEquals(Screen.Settings, decodeRoute(-2L))
        assertEquals(Screen.Archived, decodeRoute(-3L))
        assertEquals(Screen.FlightDetail(99), decodeRoute(99L))
    }

    @Test
    fun malformedAndFutureRoutesFallBackHome() {
        listOf(
            "v2:flight:4",
            "v1:flight:-4",
            "v1:itinerary:nope",
            "v1:itinerary-editor:bad:id:new",
            Any(),
        ).forEach { assertEquals(Screen.List, decodeRoute(it)) }
    }

    @Test
    fun notificationInvocationIdentityIncludesSemanticSlot() {
        val status = FlightInvocation(42, "status", "event-1")
        val reminder = FlightInvocation(42, "reminder-boarding", "event-1")

        assertNotEquals(status.key, reminder.key)
    }
}
