package ch.lkmc.blipbird

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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
    fun popIfTopRemovesOnlyTheRequestingTopEntry() {
        val editor = Screen.ItineraryEditor("draft-1", 7)
        val stack = mutableListOf(Screen.List, Screen.ItineraryDetail(7), editor)

        assertTrue(stack.popIfTop(editor))
        assertEquals(listOf<Screen>(Screen.List, Screen.ItineraryDetail(7)), stack)

        // The editor already left the stack; its late callback must be inert
        // rather than pop the itinerary detail underneath it.
        assertFalse(stack.popIfTop(editor))
        assertEquals(listOf<Screen>(Screen.List, Screen.ItineraryDetail(7)), stack)
    }

    /** A pop that empties the stack would crash the `backStack.last()` read. */
    @Test
    fun popIfTopNeverRemovesTheRoot() {
        val stack = mutableListOf<Screen>(Screen.List)

        assertFalse(stack.popIfTop(Screen.List))
        assertEquals(listOf<Screen>(Screen.List), stack)
    }

    @Test
    fun popToRootKeepsExactlyTheRoot() {
        val stack = mutableListOf(
            Screen.List,
            Screen.ItineraryDetail(7),
            Screen.ItineraryEditor("draft-1", 7),
        )

        stack.popToRoot()
        assertEquals(listOf<Screen>(Screen.List), stack)

        stack.popToRoot()
        assertEquals(listOf<Screen>(Screen.List), stack)
    }

    /**
     * The dissolving-save sequence that used to empty the stack: the editor pops
     * itself and unwinds to the root, then its still-composed entry replays the
     * same callback once Room reports the itinerary gone.
     */
    @Test
    fun replayedEditorSaveAfterDissolveLeavesTheRootIntact() {
        val editor = Screen.ItineraryEditor("draft-1", 7)
        val stack = mutableListOf(Screen.List, Screen.ItineraryDetail(7), editor)

        repeat(2) {
            if (stack.popIfTop(editor)) stack.popToRoot()
        }

        assertEquals(listOf<Screen>(Screen.List), stack)
    }

    /**
     * The bug this pins: a flight dossier opened *from* an itinerary is the same
     * kind of screen as the itinerary, so ranking by screen type made the back
     * step read as a push — the itinerary slid in from the trailing edge.
     */
    @Test
    fun backFromAFlightOpenedInsideAnItineraryIsAPop() {
        val itinerary = Screen.ItineraryDetail(7)
        val flight = Screen.FlightDetail(42)
        val stack = mutableListOf(Screen.List, itinerary, flight)
        val depths = stack.withIndex().associate { (index, screen) -> screen to index }

        // Push: the flight sits one level deeper than the itinerary it came from.
        assertTrue(navigationForward(itinerary, flight, stack, depths))

        // Pop: the flight is already off the stack while it animates out, so its
        // depth comes from the remembered sample.
        stack.popIfTop(flight)
        assertFalse(navigationForward(flight, itinerary, stack, depths))
        assertEquals(1, navigationDepth(itinerary, stack, depths))
        assertEquals(2, navigationDepth(flight, stack, depths))
    }

    @Test
    fun ordinaryPushesAndPopsKeepTheirDirection() {
        val itinerary = Screen.ItineraryDetail(7)
        val stack = mutableListOf(Screen.List, itinerary)
        val depths = stack.withIndex().associate { (index, screen) -> screen to index }

        assertTrue(navigationForward(Screen.List, itinerary, stack, depths))
        assertFalse(navigationForward(itinerary, Screen.List, stack, depths))
    }

    /** A deep link swapping one dossier for another is a replacement, not a back step. */
    @Test
    fun replacingAnEntryAtTheSameDepthAnimatesForward() {
        val previous = Screen.FlightDetail(42)
        val next = Screen.FlightDetail(43)
        val stack = mutableListOf(Screen.List, next)
        val depths = mapOf(Screen.List to 0, previous to 1, next to 1)

        assertTrue(navigationForward(previous, next, stack, depths))
    }

    /** With no sample yet (first frame after a restore) the static rank stands in. */
    @Test
    fun unknownDepthFallsBackToTheStaticRank() {
        assertEquals(
            screenRank(Screen.Settings),
            navigationDepth(Screen.Settings, emptyList(), emptyMap()),
        )
    }

    @Test
    fun notificationInvocationIdentityIncludesSemanticSlot() {
        val status = FlightInvocation(42, "status", "event-1")
        val reminder = FlightInvocation(42, "reminder-boarding", "event-1")

        assertNotEquals(status.key, reminder.key)
    }
}
