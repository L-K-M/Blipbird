package ch.lkmc.blipbird.core.model

import ch.lkmc.blipbird.core.database.TrackedFlightEntity
import java.time.LocalDate

enum class DateIntentSource {
    USER_CONFIRMED,
    NEXT_OCCURRENCE,
    PROVIDER_RESOLVED_LEGACY,
    LEGACY_UNKNOWN,
    ;

    companion object {
        fun decode(value: String): DateIntentSource =
            entries.firstOrNull { it.name == value } ?: LEGACY_UNKNOWN
    }
}

enum class TransitionIntent {
    DIRECT_CONNECTION,
    DESTINATION_STAY,
    SURFACE_TRANSFER,
    UNKNOWN,
    ;

    companion object {
        fun decode(value: String): TransitionIntent =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

enum class BookingArrangement {
    BOOKED_TOGETHER,
    BOOKED_SEPARATELY,
    UNKNOWN,
    ;

    companion object {
        fun decode(value: String): BookingArrangement =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

enum class BaggagePlan {
    NO_CHECKED_BAG,
    THROUGH_CHECKED,
    COLLECT_AND_RECHECK,
    UNKNOWN,
    ;

    companion object {
        fun decode(value: String): BaggagePlan =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

data class ItineraryLeg(
    val id: Long,
    val ordinal: Int,
    val flight: TrackedFlightEntity,
) {
    val date: LocalDate?
        get() = flight.dateLocal?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

data class ItineraryTransition(
    val id: Long,
    val inboundLegId: Long,
    val outboundLegId: Long,
    val intent: TransitionIntent,
    val bookingArrangement: BookingArrangement,
    val baggagePlan: BaggagePlan,
    val updatedAt: Long,
)

data class Itinerary(
    val id: Long,
    val creationRequestId: String,
    val name: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val legs: List<ItineraryLeg>,
    val transitions: List<ItineraryTransition>,
) {
    val archived: Boolean get() = legs.isNotEmpty() && legs.all { it.flight.archived }
}

data class ItineraryDraftLegInput(
    val existingFlightId: Long?,
    val replacesFlightId: Long? = null,
    val designator: String,
    val departureLocalDate: LocalDate,
    val transitionAfter: TransitionIntent,
)

data class SaveItineraryRequest(
    val creationRequestId: String,
    val itineraryId: Long?,
    val name: String?,
    val legs: List<ItineraryDraftLegInput>,
    val deleteRemovedFlightIds: List<Long> = emptyList(),
)

data class SaveItineraryResult(
    val itineraryId: Long,
    val newlyTrackedFlightIds: List<Long>,
    val removedFlightIds: List<Long> = emptyList(),
    val dissolved: Boolean = false,
)
