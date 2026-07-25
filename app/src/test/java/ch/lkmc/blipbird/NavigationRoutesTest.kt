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

    /**
     * `encodeRoute` doubles as the `SaveableStateProvider` key for each back-stack
     * entry, so two distinct screens sharing a key would silently share one screen's
     * scroll position and top-bar collapse state — or clobber it on pop.
     */
    @Test
    fun distinctScreensGetDistinctStateKeys() {
        val screens = listOf(
            Screen.List,
            Screen.FlightDetail(42),
            Screen.FlightDetail(43),
            Screen.ItineraryDetail(42),
            Screen.ItineraryEditor("draft-123", null),
            // Same itinerary id, different draft: pins draftId as a discriminator
            // in its own right, so a refactor that dropped it from the key would
            // fail here and not only in the round-trip test.
            Screen.ItineraryEditor("draft-456", null),
            Screen.ItineraryEditor("draft-123", 7),
            Screen.GroupExistingFlights("draft-123"),
            Screen.GroupExistingFlights("draft-456"),
            Screen.Settings,
            Screen.Archived,
        )
        val keys = screens.map { it.encodeRoute() }
        assertEquals(screens.size, keys.toSet().size, "route keys collide: $keys")
        // A flight and an itinerary with the same id must not share a key.
        assertNotEquals(Screen.FlightDetail(42).encodeRoute(), Screen.ItineraryDetail(42).encodeRoute())
    }

    /**
     * The key must depend only on a screen's identifying *values*. An encoder that
     * reached for `hashCode()` or identity would still round-trip and still look
     * unique, but two equal screens rebuilt across a process restart would land on
     * different keys and silently lose their state.
     */
    @Test
    fun equalScreensProduceTheSameStateKey() {
        assertEquals(Screen.FlightDetail(42).encodeRoute(), Screen.FlightDetail(42).encodeRoute())
        assertEquals(
            Screen.ItineraryEditor("draft-123", 7).encodeRoute(),
            Screen.ItineraryEditor("draft-123", 7).encodeRoute(),
        )
        assertEquals(Screen.Settings.encodeRoute(), Screen.Settings.encodeRoute())
    }

    /**
     * Compile-time guard, not a runtime one: adding a [Screen] subtype makes this
     * `when` non-exhaustive and breaks the build right here, so a new destination
     * cannot quietly inherit an untested state key.
     */
    @Test
    fun everyScreenTypeIsAccountedForInTheKeyTests() {
        val samples = listOf(
            Screen.List,
            Screen.FlightDetail(1),
            Screen.ItineraryDetail(1),
            Screen.ItineraryEditor("draft-1", null),
            Screen.GroupExistingFlights("draft-1"),
            Screen.Settings,
            Screen.Archived,
        )
        samples.forEach { screen ->
            val covered = when (screen) {
                is Screen.List -> true
                is Screen.FlightDetail -> true
                is Screen.ItineraryDetail -> true
                is Screen.ItineraryEditor -> true
                is Screen.GroupExistingFlights -> true
                is Screen.Settings -> true
                is Screen.Archived -> true
            }
            assertTrue(covered, "$screen needs a case in the state-key tests")
        }
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

    @Test
    fun notificationInvocationIdentityIncludesSemanticSlot() {
        val status = FlightInvocation(42, "status", "event-1")
        val reminder = FlightInvocation(42, "reminder-boarding", "event-1")

        assertNotEquals(status.key, reminder.key)
    }
}
