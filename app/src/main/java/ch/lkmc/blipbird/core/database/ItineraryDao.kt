package ch.lkmc.blipbird.core.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class ItineraryGraphRow(
    val itineraryId: Long,
    val creationRequestId: String,
    val itineraryName: String?,
    val itineraryCreatedAt: Long,
    val itineraryUpdatedAt: Long,
    val legId: Long,
    val ordinal: Int,
    @Embedded(prefix = "flight_") val flight: TrackedFlightEntity,
    val transitionId: Long?,
    val transitionOutboundLegId: Long?,
    val transitionIntent: String?,
    val transitionBookingArrangement: String?,
    val transitionBaggagePlan: String?,
    val transitionUpdatedAt: Long?,
)

@Dao
interface ItineraryDao {
    @Insert
    suspend fun insertItinerary(itinerary: ItineraryEntity): Long

    @Insert
    suspend fun insertLeg(leg: ItineraryLegEntity): Long

    @Insert
    suspend fun insertTransition(transition: ItineraryTransitionEntity): Long

    @Query("SELECT * FROM itinerary WHERE id = :id")
    suspend fun itineraryEntity(id: Long): ItineraryEntity?

    @Query("SELECT * FROM itinerary WHERE creationRequestId = :requestId LIMIT 1")
    suspend fun itineraryByRequestId(requestId: String): ItineraryEntity?

    @Query("SELECT * FROM itinerary_leg WHERE itineraryId = :itineraryId ORDER BY ordinal")
    suspend fun legEntities(itineraryId: Long): List<ItineraryLegEntity>

    @Query("SELECT * FROM itinerary_transition WHERE itineraryId = :itineraryId")
    suspend fun transitionEntities(itineraryId: Long): List<ItineraryTransitionEntity>

    @Query("SELECT * FROM itinerary_transition WHERE id = :transitionId")
    suspend fun transitionEntity(transitionId: Long): ItineraryTransitionEntity?

    @Query("SELECT itineraryId FROM itinerary_leg WHERE trackedFlightId = :flightId LIMIT 1")
    suspend fun itineraryIdForFlight(flightId: Long): Long?

    @Query("SELECT COUNT(*) FROM itinerary")
    fun observeCount(): Flow<Int>

    @Query("UPDATE itinerary SET name = :name, updatedAt = :updatedAt WHERE id = :itineraryId")
    suspend fun updateItinerary(itineraryId: Long, name: String?, updatedAt: Long)

    @Query("UPDATE itinerary_leg SET ordinal = :ordinal WHERE id = :legId")
    suspend fun updateOrdinal(legId: Long, ordinal: Int)

    @Query("DELETE FROM itinerary_transition WHERE itineraryId = :itineraryId")
    suspend fun deleteTransitions(itineraryId: Long)

    @Query("DELETE FROM itinerary_leg WHERE itineraryId = :itineraryId AND id NOT IN (:keepLegIds)")
    suspend fun deleteLegsExcept(itineraryId: Long, keepLegIds: List<Long>)

    @Query("DELETE FROM itinerary WHERE id = :itineraryId")
    suspend fun deleteItinerary(itineraryId: Long)

    @Query("DELETE FROM itinerary")
    suspend fun deleteAllItineraries()

    @Query(
        "UPDATE tracked_flight SET archived = :archived WHERE id IN " +
            "(SELECT trackedFlightId FROM itinerary_leg WHERE itineraryId = :itineraryId)"
    )
    suspend fun setMemberArchiveState(itineraryId: Long, archived: Boolean)

    @Query(
        "SELECT trackedFlightId FROM itinerary_leg WHERE itineraryId = :itineraryId ORDER BY ordinal"
    )
    suspend fun memberFlightIds(itineraryId: Long): List<Long>

    @Query("DELETE FROM tracked_flight WHERE id IN (:flightIds)")
    suspend fun deleteFlights(flightIds: List<Long>)

    @Query(
        "UPDATE itinerary_transition SET intent = :intent, " +
            "bookingArrangement = :bookingArrangement, baggagePlan = :baggagePlan, " +
            "updatedAt = :updatedAt WHERE id = :transitionId"
    )
    suspend fun updateTransition(
        transitionId: Long,
        intent: String,
        bookingArrangement: String,
        baggagePlan: String,
        updatedAt: Long,
    )

    @Query(GRAPH_QUERY + " ORDER BY i.createdAt DESC, l.ordinal")
    fun observeAllGraphRows(): Flow<List<ItineraryGraphRow>>

    @Query(GRAPH_QUERY + " ORDER BY i.createdAt DESC, l.ordinal")
    suspend fun allGraphRows(): List<ItineraryGraphRow>

    @Query(GRAPH_QUERY + " WHERE i.id = :itineraryId ORDER BY l.ordinal")
    fun observeGraphRows(itineraryId: Long): Flow<List<ItineraryGraphRow>>

    @Query(GRAPH_QUERY + " WHERE i.id = :itineraryId ORDER BY l.ordinal")
    suspend fun graphRows(itineraryId: Long): List<ItineraryGraphRow>

    companion object {
        const val GRAPH_QUERY = """
            SELECT
                i.id AS itineraryId,
                i.creationRequestId AS creationRequestId,
                i.name AS itineraryName,
                i.createdAt AS itineraryCreatedAt,
                i.updatedAt AS itineraryUpdatedAt,
                l.id AS legId,
                l.ordinal AS ordinal,
                f.id AS flight_id,
                f.designatorIata AS flight_designatorIata,
                f.designatorIcao AS flight_designatorIcao,
                f.flightNumber AS flight_flightNumber,
                f.suffix AS flight_suffix,
                f.dateLocal AS flight_dateLocal,
                f.dateIntentSource AS flight_dateIntentSource,
                f.alias AS flight_alias,
                f.createdAt AS flight_createdAt,
                f.archived AS flight_archived,
                t.id AS transitionId,
                t.outboundLegId AS transitionOutboundLegId,
                t.intent AS transitionIntent,
                t.bookingArrangement AS transitionBookingArrangement,
                t.baggagePlan AS transitionBaggagePlan,
                t.updatedAt AS transitionUpdatedAt
            FROM itinerary i
            JOIN itinerary_leg l ON l.itineraryId = i.id
            JOIN tracked_flight f ON f.id = l.trackedFlightId
            LEFT JOIN itinerary_transition t
                ON t.itineraryId = i.id AND t.inboundLegId = l.id
        """
    }
}
