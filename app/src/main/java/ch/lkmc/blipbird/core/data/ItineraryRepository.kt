package ch.lkmc.blipbird.core.data

import androidx.room.withTransaction
import ch.lkmc.blipbird.core.database.ItineraryEntity
import ch.lkmc.blipbird.core.database.ItineraryGraphRow
import ch.lkmc.blipbird.core.database.ItineraryLegEntity
import ch.lkmc.blipbird.core.database.ItineraryTransitionEntity
import ch.lkmc.blipbird.core.database.TrackedFlightEntity
import ch.lkmc.blipbird.core.database.UserDatabase
import ch.lkmc.blipbird.core.model.BaggagePlan
import ch.lkmc.blipbird.core.model.BookingArrangement
import ch.lkmc.blipbird.core.model.DateIntentSource
import ch.lkmc.blipbird.core.model.Designator
import ch.lkmc.blipbird.core.model.Itinerary
import ch.lkmc.blipbird.core.model.ItineraryLeg
import ch.lkmc.blipbird.core.model.ItineraryTransition
import ch.lkmc.blipbird.core.model.SaveItineraryRequest
import ch.lkmc.blipbird.core.model.SaveItineraryResult
import ch.lkmc.blipbird.core.model.TransitionIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

class ItineraryValidationException(
    val legIndex: Int? = null,
    message: String,
) : IllegalArgumentException(message)

data class RemovedItinerary(
    val flightIds: List<Long>,
    val transitionIds: List<Long>,
)

@Singleton
class ItineraryRepository @Inject constructor(
    private val userDb: UserDatabase,
    private val identity: IdentityResolver,
    private val operationLocks: FlightOperationLocks,
) {
    private val itineraryDao get() = userDb.itineraryDao()
    private val trackedDao get() = userDb.trackedFlightDao()

    fun observeActive(): Flow<List<Itinerary>> = itineraryDao.observeAllGraphRows()
        .map { rows -> rows.toItineraries().filterNot { it.archived } }

    fun observeArchived(): Flow<List<Itinerary>> = itineraryDao.observeAllGraphRows()
        .map { rows -> rows.toItineraries().filter { it.archived } }

    fun observeItinerary(id: Long): Flow<Itinerary?> = itineraryDao.observeGraphRows(id)
        .map { it.toItineraries().singleOrNull() }

    fun observeCount(): Flow<Int> = itineraryDao.observeCount()

    suspend fun itineraryIdByCreationRequest(requestId: String): Long? =
        itineraryDao.itineraryByRequestId(requestId)?.id

    fun observeUngroupedFlights(): Flow<List<TrackedFlightEntity>> =
        trackedDao.observeUngroupedActive()

    suspend fun itinerary(id: Long): Itinerary? =
        itineraryDao.graphRows(id).toItineraries().singleOrNull()

    suspend fun allItineraries(): List<Itinerary> = itineraryDao.allGraphRows().toItineraries()

    suspend fun save(
        request: SaveItineraryRequest,
        beforeCommit: suspend (Itinerary?) -> Unit = {},
    ): SaveItineraryResult {
        if (request.itineraryId == null) {
            itineraryDao.itineraryByRequestId(request.creationRequestId)?.let { existing ->
                return SaveItineraryResult(existing.id, emptyList())
            }
        }
        val name = request.name?.trim()?.takeIf { it.isNotEmpty() }
        if (name != null && name.length > MAX_NAME_LENGTH) {
            throw ItineraryValidationException(message = "Itinerary names can contain at most $MAX_NAME_LENGTH characters")
        }
        val minimumLegs = if (request.itineraryId == null) MIN_LEGS else 1
        if (request.legs.size !in minimumLegs..MAX_LEGS) {
            throw ItineraryValidationException(message = "An itinerary needs between $minimumLegs and $MAX_LEGS flights")
        }
        if (request.creationRequestId.isBlank()) {
            throw ItineraryValidationException(message = "The itinerary draft has no creation request ID")
        }
        request.legs.forEachIndexed { index, leg ->
            if (leg.existingFlightId != null && leg.replacesFlightId != null) {
                throw ItineraryValidationException(
                    index,
                    "Flight ${index + 1} cannot keep and replace a tracked flight at once",
                )
            }
        }

        // Identity completion can read the Ops reference DB, so finish it before
        // opening the single User DB transaction that commits the whole graph.
        val completedDesignators = request.legs.mapIndexed { index, leg ->
            if (leg.existingFlightId != null) {
                null
            } else {
                identity.resolveToken(leg.designator)
                    ?: throw ItineraryValidationException(index, "Flight ${index + 1} is not a valid designator")
            }
        }

        val commit: suspend () -> SaveItineraryResult = {
            operationLocks.withFlights(
                request.legs.flatMap { listOfNotNull(it.existingFlightId, it.replacesFlightId) } +
                    request.deleteRemovedFlightIds
            ) {
                if (request.itineraryId == null) {
                    itineraryDao.itineraryByRequestId(request.creationRequestId)?.let { existing ->
                        return@withFlights SaveItineraryResult(existing.id, emptyList())
                    }
                }
                val lockedGraph = request.itineraryId?.let { itineraryId ->
                    itinerary(itineraryId) ?: if (
                        request.legs.size == 1 && dissolutionAlreadyCommitted(request, completedDesignators)
                    ) {
                        return@withFlights SaveItineraryResult(
                            itineraryId = itineraryId,
                            newlyTrackedFlightIds = emptyList(),
                            removedFlightIds = request.deleteRemovedFlightIds,
                            dissolved = true,
                        )
                    } else {
                        throw ItineraryValidationException(message = "This itinerary no longer exists")
                    }
                }
                if (lockedGraph?.legs?.any { it.flight.archived } == true) {
                    throw ItineraryValidationException(message = "Restore this itinerary before editing it")
                }
                if (
                    lockedGraph != null &&
                    lockedGraph.matchesCommittedEdit(request, completedDesignators, name)
                ) {
                    val destructiveTargets = (
                        request.legs.mapNotNull { it.replacesFlightId } + request.deleteRemovedFlightIds
                    ).distinct()
                    if (destructiveTargets.all { trackedDao.byId(it) == null }) {
                        return@withFlights SaveItineraryResult(lockedGraph.id, emptyList())
                    }
                    throw ItineraryValidationException(
                        message = "This draft is stale. Review its removed flights before saving again",
                    )
                }
                beforeCommit(lockedGraph)
                userDb.withTransaction {
            if (request.itineraryId == null) {
                itineraryDao.itineraryByRequestId(request.creationRequestId)?.let { existing ->
                    return@withTransaction SaveItineraryResult(existing.id, emptyList())
                }
            }

            val current = request.itineraryId?.let { itineraryId ->
                itineraryDao.itineraryEntity(itineraryId)
                    ?: throw ItineraryValidationException(message = "This itinerary no longer exists")
            }
            val currentLegs = current?.let { itineraryDao.legEntities(it.id) }.orEmpty()
            val currentTransitions = current?.let { itineraryDao.transitionEntities(it.id) }.orEmpty()
            val currentLegByFlight = currentLegs.associateBy { it.trackedFlightId }
            val replacementIds = request.legs.mapNotNull { it.replacesFlightId }.distinct()
            val deletedFlightIds = (replacementIds + request.deleteRemovedFlightIds).distinct()
            if (request.deleteRemovedFlightIds.any { current == null || it !in currentLegByFlight }) {
                throw ItineraryValidationException(message = "A removed flight no longer belongs to this itinerary")
            }
            replacementIds.forEach { replacedId ->
                val replaced = trackedDao.byId(replacedId)
                    ?: throw ItineraryValidationException(message = "A replaced flight no longer exists")
                val membership = itineraryDao.itineraryIdForFlight(replaced.id)
                if (membership != null && membership != current?.id) {
                    throw ItineraryValidationException(message = "A replaced flight belongs to another itinerary")
                }
            }
            val newFlightIds = mutableListOf<Long>()

            val flightIds = request.legs.mapIndexed { index, leg ->
                val existingId = leg.existingFlightId
                if (existingId != null) {
                    val flight = trackedDao.byId(existingId)
                        ?: throw ItineraryValidationException(index, "Flight ${index + 1} no longer exists")
                    val membership = itineraryDao.itineraryIdForFlight(existingId)
                    if (membership != null && membership != current?.id) {
                        throw ItineraryValidationException(index, "Flight ${index + 1} is already in another itinerary")
                    }
                    if (flight.archived) {
                        throw ItineraryValidationException(index, "Restore flight ${index + 1} before grouping it")
                    }
                    trackedDao.setDateIntent(
                        existingId,
                        leg.departureLocalDate.toString(),
                        DateIntentSource.USER_CONFIRMED.name,
                    )
                    existingId
                } else {
                    val designator = checkNotNull(completedDesignators[index])
                    val replaced = leg.replacesFlightId?.let { trackedDao.byId(it) }
                    trackedDao.insert(
                        TrackedFlightEntity(
                            designatorIata = designator.airlineIata,
                            designatorIcao = designator.airlineIcao,
                            flightNumber = designator.number,
                            suffix = designator.suffix,
                            dateLocal = leg.departureLocalDate.toString(),
                            dateIntentSource = DateIntentSource.USER_CONFIRMED.name,
                            alias = replaced?.alias,
                            createdAt = Instant.now().toEpochMilli(),
                        )
                    ).also(newFlightIds::add)
                }
            }
            if (flightIds.distinct().size != flightIds.size) {
                throw ItineraryValidationException(message = "The same tracked flight cannot appear twice")
            }
            if (deletedFlightIds.any { it in flightIds }) {
                throw ItineraryValidationException(message = "A deleted flight cannot remain in the same itinerary")
            }
            val canonicalOccurrences = trackedDao.byIds(flightIds).map { flight ->
                listOf(
                    flight.designatorIata ?: flight.designatorIcao.orEmpty(),
                    flight.flightNumber,
                    flight.suffix.orEmpty(),
                    flight.dateLocal.orEmpty(),
                ).joinToString(":")
            }
            if (canonicalOccurrences.distinct().size != canonicalOccurrences.size) {
                throw ItineraryValidationException(
                    message = "Review duplicate flight numbers and dates before saving",
                )
            }

            if (current != null && flightIds.size == 1) {
                itineraryDao.deleteItinerary(current.id)
                if (deletedFlightIds.isNotEmpty()) itineraryDao.deleteFlights(deletedFlightIds)
                return@withTransaction SaveItineraryResult(
                    itineraryId = current.id,
                    newlyTrackedFlightIds = newFlightIds,
                    removedFlightIds = deletedFlightIds,
                    dissolved = true,
                )
            }

            val now = Instant.now().toEpochMilli()
            val itineraryId = current?.id ?: itineraryDao.insertItinerary(
                ItineraryEntity(
                    creationRequestId = request.creationRequestId,
                    name = name,
                    createdAt = now,
                    updatedAt = now,
                )
            )

            val finalLegs = if (current == null) {
                flightIds.mapIndexed { ordinal, flightId ->
                    val id = itineraryDao.insertLeg(
                        ItineraryLegEntity(
                            itineraryId = itineraryId,
                            trackedFlightId = flightId,
                            ordinal = ordinal,
                        )
                    )
                    ItineraryLegEntity(id, itineraryId, flightId, ordinal)
                }
            } else {
                // Move old rows out of the dense range before assigning the new
                // order; direct swaps would violate the unique ordinal index.
                currentLegs.forEachIndexed { index, leg ->
                    itineraryDao.updateOrdinal(leg.id, TEMP_ORDINAL_BASE + index)
                }
                flightIds.mapIndexed { index, flightId ->
                    currentLegByFlight[flightId] ?: itineraryDao.insertLeg(
                        ItineraryLegEntity(
                            itineraryId = itineraryId,
                            trackedFlightId = flightId,
                            ordinal = TEMP_NEW_ORDINAL_BASE + index,
                        )
                    ).let { id ->
                        ItineraryLegEntity(id, itineraryId, flightId, TEMP_NEW_ORDINAL_BASE + index)
                    }
                }.also { legs ->
                    itineraryDao.deleteTransitions(itineraryId)
                    itineraryDao.deleteLegsExcept(itineraryId, legs.map { it.id })
                    legs.forEachIndexed { ordinal, leg -> itineraryDao.updateOrdinal(leg.id, ordinal) }
                }.mapIndexed { ordinal, leg -> leg.copy(ordinal = ordinal) }
            }

            val oldTransitionsByPair = currentTransitions.associateBy { it.inboundLegId to it.outboundLegId }
            if (current == null) itineraryDao.deleteTransitions(itineraryId)
            finalLegs.zipWithNext().forEachIndexed { index, (inbound, outbound) ->
                val desiredIntent = request.legs[index].transitionAfter
                val old = oldTransitionsByPair[inbound.id to outbound.id]
                val retainedAnswers = old?.takeIf { it.intent == desiredIntent.name }
                itineraryDao.insertTransition(
                    ItineraryTransitionEntity(
                        id = old?.id ?: 0,
                        itineraryId = itineraryId,
                        inboundLegId = inbound.id,
                        outboundLegId = outbound.id,
                        intent = desiredIntent.name,
                        bookingArrangement = retainedAnswers?.bookingArrangement
                            ?: BookingArrangement.UNKNOWN.name,
                        baggagePlan = retainedAnswers?.baggagePlan ?: BaggagePlan.UNKNOWN.name,
                        updatedAt = retainedAnswers?.updatedAt ?: now,
                    )
                )
            }
            itineraryDao.updateItinerary(itineraryId, name, now)
            if (deletedFlightIds.isNotEmpty()) itineraryDao.deleteFlights(deletedFlightIds)

            val graphLegs = itineraryDao.legEntities(itineraryId)
            val graphTransitions = itineraryDao.transitionEntities(itineraryId)
            requireValidGraph(graphLegs, graphTransitions)
                    SaveItineraryResult(itineraryId, newFlightIds, deletedFlightIds)
                }
            }
        }
        return request.itineraryId?.let { id -> operationLocks.withItinerary(id) { commit() } }
            ?: operationLocks.withCreationRequest(request.creationRequestId) { commit() }
    }

    suspend fun updateTransition(
        transitionId: Long,
        intent: TransitionIntent? = null,
        bookingArrangement: BookingArrangement? = null,
        baggagePlan: BaggagePlan? = null,
    ) {
        val ownerId = itineraryDao.transitionEntity(transitionId)?.itineraryId
            ?: throw ItineraryValidationException(message = "This transition no longer exists")
        operationLocks.withItinerary(ownerId) {
            userDb.withTransaction {
                val current = itineraryDao.transitionEntity(transitionId)
                    ?: throw ItineraryValidationException(message = "This transition no longer exists")
                val owner = itinerary(current.itineraryId)
                    ?: throw ItineraryValidationException(message = "This itinerary no longer exists")
                if (owner.archived) {
                    throw ItineraryValidationException(message = "Restore this itinerary before editing it")
                }
                val nextIntent = intent ?: TransitionIntent.decode(current.intent)
                val resetAnswers = intent != null && intent.name != current.intent
                itineraryDao.updateTransition(
                    transitionId = transitionId,
                    intent = nextIntent.name,
                    bookingArrangement = if (resetAnswers) {
                        BookingArrangement.UNKNOWN.name
                    } else {
                        (bookingArrangement ?: BookingArrangement.decode(current.bookingArrangement)).name
                    },
                    baggagePlan = if (resetAnswers) {
                        BaggagePlan.UNKNOWN.name
                    } else {
                        (baggagePlan ?: BaggagePlan.decode(current.baggagePlan)).name
                    },
                    updatedAt = Instant.now().toEpochMilli(),
                )
                itineraryDao.itineraryEntity(current.itineraryId)?.let {
                    itineraryDao.updateItinerary(it.id, it.name, Instant.now().toEpochMilli())
                }
            }
        }
    }

    private suspend fun dissolutionAlreadyCommitted(
        request: SaveItineraryRequest,
        completedDesignators: List<Designator?>,
    ): Boolean {
        if (request.deleteRemovedFlightIds.any { trackedDao.byId(it) != null }) return false
        request.legs.single().replacesFlightId?.let { if (trackedDao.byId(it) != null) return false }
        val requested = request.legs.single()
        val candidates = requested.existingFlightId?.let { listOfNotNull(trackedDao.byId(it)) }
            ?: trackedDao.activeList().filter { flight ->
                val designator = completedDesignators.single() ?: return@filter false
                flight.designatorIata == designator.airlineIata &&
                    flight.designatorIcao == designator.airlineIcao &&
                    flight.flightNumber == designator.number &&
                    flight.suffix == designator.suffix
            }
        return candidates.any { flight ->
            flight.dateLocal == requested.departureLocalDate.toString() &&
                itineraryDao.itineraryIdForFlight(flight.id) == null
        }
    }

    suspend fun setArchived(
        itineraryId: Long,
        archived: Boolean,
        beforeCommit: suspend (Itinerary) -> Unit = {},
    ): List<Long> =
        operationLocks.withItinerary(itineraryId) {
            val ids = itineraryDao.memberFlightIds(itineraryId)
            if (ids.isEmpty()) throw ItineraryValidationException(message = "This itinerary no longer exists")
            operationLocks.withFlights(ids) {
                val graph = itinerary(itineraryId)
                    ?: throw ItineraryValidationException(message = "This itinerary no longer exists")
                beforeCommit(graph)
                userDb.withTransaction {
                    itineraryDao.setMemberArchiveState(itineraryId, archived)
                    ids
                }
            }
        }

    suspend fun delete(
        itineraryId: Long,
        deleteFlights: Boolean,
        beforeCommit: suspend (Itinerary) -> Unit = {},
    ): RemovedItinerary =
        operationLocks.withItinerary(itineraryId) {
            val flights = itineraryDao.memberFlightIds(itineraryId)
            if (flights.isEmpty()) throw ItineraryValidationException(message = "This itinerary no longer exists")
            operationLocks.withFlights(flights) {
                val graph = itinerary(itineraryId)
                    ?: throw ItineraryValidationException(message = "This itinerary no longer exists")
                beforeCommit(graph)
                userDb.withTransaction {
                    val transitions = itineraryDao.transitionEntities(itineraryId).map { it.id }
                    itineraryDao.deleteItinerary(itineraryId)
                    if (deleteFlights) itineraryDao.deleteFlights(flights)
                    RemovedItinerary(if (deleteFlights) flights else emptyList(), transitions)
                }
            }
        }

    suspend fun deleteAllKeepingFlights(
        beforeCommit: suspend (List<Itinerary>) -> Unit = {},
    ): List<Long> = operationLocks.withAllItineraries {
        val graphs = allItineraries()
        beforeCommit(graphs)
        userDb.withTransaction {
            val ids = itineraryDao.allGraphRows().mapNotNull { it.transitionId }.distinct()
            itineraryDao.deleteAllItineraries()
            ids
        }
    }

    companion object {
        const val MIN_LEGS = 2
        const val MAX_LEGS = 20
        const val MAX_NAME_LENGTH = 120
        const val MAX_PASTE_BYTES = 4 * 1024
        const val MAX_SAVED_DRAFT_BYTES = 32 * 1024
        private const val TEMP_ORDINAL_BASE = 10_000
        private const val TEMP_NEW_ORDINAL_BASE = 20_000
    }
}

/**
 * A restored edit draft can be replayed after its transaction committed but
 * before navigation cleared the draft. Treat the already-materialized graph as
 * success, including legs whose old IDs were replaced by the first attempt.
 */
private fun Itinerary.matchesCommittedEdit(
    request: SaveItineraryRequest,
    completedDesignators: List<Designator?>,
    normalizedName: String?,
): Boolean {
    if (name != normalizedName || legs.size != request.legs.size) return false
    val legsMatch = legs.indices.all { index ->
        val persisted = legs[index].flight
        val requested = request.legs[index]
        if (persisted.dateLocal != requested.departureLocalDate.toString()) return@all false
        requested.existingFlightId?.let { return@all persisted.id == it }
        val designator = completedDesignators[index] ?: return@all false
        persisted.designatorIata == designator.airlineIata &&
            persisted.designatorIcao == designator.airlineIcao &&
            persisted.flightNumber == designator.number &&
            persisted.suffix == designator.suffix
    }
    if (!legsMatch || transitions.size != legs.size - 1) return false
    val transitionByInbound = transitions.associateBy { it.inboundLegId }
    return legs.dropLast(1).indices.all { index ->
        val transition = transitionByInbound[legs[index].id] ?: return@all false
        transition.outboundLegId == legs[index + 1].id &&
            transition.intent == request.legs[index].transitionAfter
    }
}

internal fun requireValidGraph(
    legs: List<ItineraryLegEntity>,
    transitions: List<ItineraryTransitionEntity>,
) {
    require(legs.size >= ItineraryRepository.MIN_LEGS) { "Persisted itineraries need at least two legs" }
    require(legs.map { it.ordinal } == legs.indices.toList()) { "Itinerary ordinals must be dense" }
    require(transitions.size == legs.size - 1) { "An itinerary needs exactly n - 1 transitions" }
    val expected = legs.zipWithNext().map { (a, b) -> a.id to b.id }.toSet()
    val actual = transitions.map { it.inboundLegId to it.outboundLegId }.toSet()
    require(actual == expected) { "Transitions must connect ordinal-adjacent legs" }
}

private fun List<ItineraryGraphRow>.toItineraries(): List<Itinerary> =
    groupBy { it.itineraryId }.values.mapNotNull { rows ->
        val first = rows.firstOrNull() ?: return@mapNotNull null
        val sorted = rows.sortedBy { it.ordinal }
        Itinerary(
            id = first.itineraryId,
            creationRequestId = first.creationRequestId,
            name = first.itineraryName,
            createdAt = first.itineraryCreatedAt,
            updatedAt = first.itineraryUpdatedAt,
            legs = sorted.map { ItineraryLeg(it.legId, it.ordinal, it.flight) },
            transitions = sorted.mapNotNull transition@ { row ->
                val id = row.transitionId ?: return@transition null
                ItineraryTransition(
                    id = id,
                    inboundLegId = row.legId,
                    outboundLegId = row.transitionOutboundLegId ?: return@transition null,
                    intent = TransitionIntent.decode(row.transitionIntent.orEmpty()),
                    bookingArrangement = BookingArrangement.decode(row.transitionBookingArrangement.orEmpty()),
                    baggagePlan = BaggagePlan.decode(row.transitionBaggagePlan.orEmpty()),
                    updatedAt = row.transitionUpdatedAt ?: first.itineraryUpdatedAt,
                )
            },
        )
    }.sortedByDescending { it.createdAt }
