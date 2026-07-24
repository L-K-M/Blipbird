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

private fun validDraftId(value: String): Boolean =
    value.length in 1..80 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

private const val LEGACY_LIST = -1L
private const val LEGACY_SETTINGS = -2L
private const val LEGACY_ARCHIVED = -3L
