package ch.lkmc.blipbird

sealed interface Screen {
    data object List : Screen
    data class FlightDetail(val flightId: Long) : Screen
    data class ItineraryDetail(val itineraryId: Long) : Screen
    data class ItineraryEditor(val draftId: String, val itineraryId: Long?) : Screen
    data class GroupExistingFlights(val draftId: String) : Screen
    data object Settings : Screen
    data object Archived : Screen
}

internal fun Screen.encodeRoute(): String = when (this) {
    Screen.List -> "v1:list"
    is Screen.FlightDetail -> "v1:flight:$flightId"
    is Screen.ItineraryDetail -> "v1:itinerary:$itineraryId"
    is Screen.ItineraryEditor -> "v1:itinerary-editor:$draftId:${itineraryId ?: "new"}"
    is Screen.GroupExistingFlights -> "v1:group-existing:$draftId"
    Screen.Settings -> "v1:settings"
    Screen.Archived -> "v1:archived"
}

internal fun decodeRoute(value: Any): Screen = when (value) {
    is Long -> when (value) {
        LEGACY_LIST -> Screen.List
        LEGACY_SETTINGS -> Screen.Settings
        LEGACY_ARCHIVED -> Screen.Archived
        else -> if (value > 0) Screen.FlightDetail(value) else Screen.List
    }
    is String -> decodeTaggedRoute(value)
    else -> Screen.List
}

private fun decodeTaggedRoute(value: String): Screen {
    val parts = value.split(':')
    if (parts.firstOrNull() != "v1") return Screen.List
    return when (parts.getOrNull(1)) {
        "list" -> Screen.List
        "settings" -> Screen.Settings
        "archived" -> Screen.Archived
        "flight" -> parts.getOrNull(2)?.toLongOrNull()?.takeIf { it > 0 }
            ?.let { Screen.FlightDetail(it) } ?: Screen.List
        "itinerary" -> parts.getOrNull(2)?.toLongOrNull()?.takeIf { it > 0 }
            ?.let { Screen.ItineraryDetail(it) } ?: Screen.List
        "itinerary-editor" -> {
            val draftId = parts.getOrNull(2)?.takeIf(::validDraftId) ?: return Screen.List
            val itinerary = when (val raw = parts.getOrNull(3)) {
                "new" -> null
                else -> raw?.toLongOrNull()?.takeIf { it > 0 } ?: return Screen.List
            }
            Screen.ItineraryEditor(draftId, itinerary)
        }
        "group-existing" -> parts.getOrNull(2)?.takeIf(::validDraftId)
            ?.let { Screen.GroupExistingFlights(it) } ?: Screen.List
        else -> Screen.List
    }
}

/**
 * Pops [screen] only while it is still the visible top entry, and never removes
 * the root.
 *
 * A screen's "leave now" effects can fire late: the outgoing entry stays
 * composed for the whole exit transition, so a Room emission it still observes
 * (an itinerary the save just dissolved, a flight the delete just removed) can
 * re-run the effect *after* navigation already moved on. An unconditional
 * `removeAt(lastIndex)` then pops an unrelated entry — or pops `Screen.List`,
 * leaving `backStack.last()` with nothing to read.
 */
internal fun MutableList<Screen>.popIfTop(screen: Screen): Boolean {
    if (size <= 1 || lastOrNull() != screen) return false
    removeAt(lastIndex)
    return true
}

/** Pops every entry above the root, keeping the root itself. */
internal fun MutableList<Screen>.popToRoot() {
    while (size > 1) removeAt(lastIndex)
}

/**
 * Where [screen] sits on the journey into the app: its index in the live back
 * stack, else the last index it was seen at, else its static rank.
 *
 * The middle case is the one that matters. A popped entry stays composed for its
 * whole exit transition, so the screen the user is leaving is already off the
 * stack when the transition spec asks how deep it was.
 */
internal fun navigationDepth(
    screen: Screen,
    stack: List<Screen>,
    remembered: Map<Screen, Int>,
): Int = stack.indexOf(screen).takeIf { it >= 0 } ?: remembered[screen] ?: screenRank(screen)

/**
 * True when moving from [from] to [to] should animate as a push (the incoming
 * screen covers the outgoing one) rather than a pop.
 *
 * Equal depth counts as forward: that is a *replacement* at the same level, like
 * a notification deep link swapping one flight dossier for another.
 *
 * Depth, not screen kind: a flight dossier opened from an itinerary is one level
 * deeper than the itinerary, and going back to it has to run the pop. Ranking by
 * screen type gave both the same number, so that back step animated as a push —
 * the itinerary slid in from the wrong edge.
 */
internal fun navigationForward(
    from: Screen,
    to: Screen,
    stack: List<Screen>,
    remembered: Map<Screen, Int>,
): Boolean = navigationDepth(to, stack, remembered) >= navigationDepth(from, stack, remembered)

/**
 * Static fallback depth for an entry with no recorded stack position — only
 * reachable before the first stack sample, so it just needs to be sane.
 */
internal fun screenRank(screen: Screen): Int = when (screen) {
    is Screen.List -> 0
    is Screen.Settings -> 1
    is Screen.Archived -> 1
    is Screen.GroupExistingFlights -> 2
    is Screen.ItineraryEditor -> 2
    is Screen.FlightDetail -> 2
    is Screen.ItineraryDetail -> 2
}

private fun validDraftId(value: String): Boolean =
    value.length in 1..80 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

private const val LEGACY_LIST = -1L
private const val LEGACY_SETTINGS = -2L
private const val LEGACY_ARCHIVED = -3L
