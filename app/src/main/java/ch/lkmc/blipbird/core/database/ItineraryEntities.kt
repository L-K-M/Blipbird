package ch.lkmc.blipbird.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "itinerary",
    indices = [Index(value = ["creationRequestId"], unique = true)],
)
data class ItineraryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val creationRequestId: String,
    val name: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "itinerary_leg",
    foreignKeys = [
        ForeignKey(
            entity = ItineraryEntity::class,
            parentColumns = ["id"],
            childColumns = ["itineraryId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackedFlightEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackedFlightId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["itineraryId", "ordinal"], unique = true),
        Index(value = ["itineraryId", "id"], unique = true),
        Index(value = ["trackedFlightId"], unique = true),
    ],
)
data class ItineraryLegEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itineraryId: Long,
    val trackedFlightId: Long,
    val ordinal: Int,
)

@Entity(
    tableName = "itinerary_transition",
    foreignKeys = [
        ForeignKey(
            entity = ItineraryLegEntity::class,
            parentColumns = ["itineraryId", "id"],
            childColumns = ["itineraryId", "inboundLegId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ItineraryLegEntity::class,
            parentColumns = ["itineraryId", "id"],
            childColumns = ["itineraryId", "outboundLegId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["itineraryId", "inboundLegId"], unique = true),
        Index(value = ["itineraryId", "outboundLegId"], unique = true),
    ],
)
data class ItineraryTransitionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itineraryId: Long,
    val inboundLegId: Long,
    val outboundLegId: Long,
    val intent: String = "UNKNOWN",
    val bookingArrangement: String = "UNKNOWN",
    val baggagePlan: String = "UNKNOWN",
    val updatedAt: Long,
)
