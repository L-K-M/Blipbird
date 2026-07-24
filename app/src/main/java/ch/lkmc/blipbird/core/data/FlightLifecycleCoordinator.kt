package ch.lkmc.blipbird.core.data

import androidx.room.withTransaction
import ch.lkmc.blipbird.core.database.OpsDatabase
import ch.lkmc.blipbird.core.database.PlatformCleanupTaskEntity
import ch.lkmc.blipbird.core.database.TrackedFlightEntity
import ch.lkmc.blipbird.core.database.UserDatabase
import ch.lkmc.blipbird.core.model.Itinerary
import ch.lkmc.blipbird.core.model.SaveItineraryRequest
import ch.lkmc.blipbird.core.model.SaveItineraryResult
import ch.lkmc.blipbird.platform.NotificationEmitter
import ch.lkmc.blipbird.platform.ReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private typealias CleanupPreparer = suspend (List<PlatformCleanupTaskEntity>) -> Unit

@Singleton
class FlightLifecycleCoordinator @Inject constructor(
    private val userDb: UserDatabase,
    private val opsDb: OpsDatabase,
    private val flights: FlightRepository,
    private val itineraries: ItineraryRepository,
    private val reminders: ReminderScheduler,
    private val notifications: NotificationEmitter,
    private val statusRefresh: StatusRefreshCoordinator,
    private val cleanupRetry: PlatformCleanupController,
    private val operationLocks: FlightOperationLocks,
) {
    private val processorMutex = Mutex()
    private val cleanupDao get() = opsDb.platformCleanupTaskDao()

    suspend fun saveItinerary(request: SaveItineraryRequest): SaveItineraryResult {
        val result = commitWithLockedCleanup { queueCleanup ->
            itineraries.save(request) { graph ->
                queueCleanup(cleanupTasksFor(graph, request))
            }
        }
        statusRefresh.reconcileSchedule()
        return result
    }

    suspend fun archiveItinerary(itineraryId: Long) {
        commitWithLockedCleanup { queueCleanup ->
            itineraries.setArchived(itineraryId, true) { graph ->
                queueCleanup(graph.cleanupTasks(DESIRED_ARCHIVED, includeFlights = true))
            }
        }
        statusRefresh.reconcileSchedule()
    }

    suspend fun restoreItinerary(itineraryId: Long) {
        val ids = itineraries.setArchived(itineraryId, false)
        statusRefresh.reconcileSchedule()
        reconcileBestEffort { processPending() }
        ids.forEach { id ->
            reconcileBestEffort { reminders.reconcile(id) }
            reconcileBestEffort { flights.reconcileOngoing(id) }
        }
    }

    suspend fun deleteItinerary(itineraryId: Long, deleteFlights: Boolean) {
        commitWithLockedCleanup { queueCleanup ->
            itineraries.delete(itineraryId, deleteFlights) { graph ->
                queueCleanup(graph.cleanupTasks(DESIRED_ABSENT, includeFlights = deleteFlights))
            }
        }
        statusRefresh.reconcileSchedule()
    }

    suspend fun deleteAllItinerariesKeepingFlights() {
        commitWithLockedCleanup { queueCleanup ->
            itineraries.deleteAllKeepingFlights { graphs ->
                queueCleanup(
                    graphs.flatMap { it.cleanupTasks(DESIRED_ABSENT, includeFlights = false) }
                )
            }
        }
        statusRefresh.reconcileSchedule()
    }

    suspend fun archiveFlight(flightId: Long) {
        processorMutex.withLock {
            val flight = flights.flight(flightId) ?: return@withLock
            commitWithCleanupLocked(
                listOf(flight.cleanupTask(DESIRED_ARCHIVED)),
            ) { flights.archive(flightId) }
        }
        statusRefresh.reconcileSchedule()
    }

    suspend fun restoreFlight(flightId: Long) {
        flights.unarchive(flightId)
        statusRefresh.reconcileSchedule()
        reconcileBestEffort { processPending() }
        reconcileBestEffort { reminders.reconcile(flightId) }
    }

    suspend fun deleteFlight(flightId: Long): TrackedFlightEntity? {
        val flight = processorMutex.withLock {
            val current = flights.flight(flightId) ?: return@withLock null
            commitWithCleanupLocked(
                listOf(current.cleanupTask(DESIRED_ABSENT)),
            ) { flights.delete(flightId) }
            current
        }
        statusRefresh.reconcileSchedule()
        return flight
    }

    suspend fun restoreDeletedFlight(flight: TrackedFlightEntity): Long {
        val id = flights.restore(flight)
        statusRefresh.reconcileSchedule()
        reconcileBestEffort { reminders.reconcile(id) }
        return id
    }

    suspend fun processPending() = processorMutex.withLock { processPendingLocked() }

    private suspend fun processPendingLocked() {
        // PREPARED means the process died around the User DB commit. Re-read the
        // authoritative user state before deciding whether cancellation is valid.
        cleanupDao.all().filter { it.state == STATE_PREPARED }
            .groupBy { it.mutationId }
            .forEach { (mutationId, tasks) ->
                if (tasks.all { desiredStateCommitted(it) }) {
                    cleanupDao.markReady(mutationId)
                } else {
                    cleanupDao.deleteMutation(mutationId)
                }
            }

        cleanupDao.all().filter { it.state == STATE_READY }.forEach { task ->
            when (task.targetKind) {
                TARGET_FLIGHT -> {
                    val committed = operationLocks.withFlight(task.targetId) {
                        if (!desiredStateCommitted(task)) return@withFlight false
                        reminders.cancel(task.targetId)
                        notifications.cancelAllForFlight(task.targetId)
                        true
                    }
                    if (!committed) {
                        cleanupDao.delete(task.id)
                        return@forEach
                    }
                    if (task.desiredUserState == DESIRED_ABSENT) {
                        flights.cleanupOperationalData(task.targetId)
                        // cleanupOperationalData waits for any in-flight refresh;
                        // cancel again afterward so a just-finished post cannot win.
                        reminders.cancel(task.targetId)
                        notifications.cancelAllForFlight(task.targetId)
                    }
                }
                TARGET_TRANSITION -> {
                    if (!desiredStateCommitted(task)) {
                        cleanupDao.delete(task.id)
                        return@forEach
                    }
                    // Transition platform slots land with live guidance.
                }
                else -> {
                    cleanupDao.delete(task.id)
                    return@forEach
                }
            }
            cleanupDao.delete(task.id)
        }
    }

    private suspend fun desiredStateCommitted(task: PlatformCleanupTaskEntity): Boolean =
        when (task.targetKind) {
            TARGET_FLIGHT -> {
                val flight = userDb.trackedFlightDao().byId(task.targetId)
                when (task.desiredUserState) {
                    DESIRED_ABSENT -> flight == null
                    DESIRED_ARCHIVED -> flight?.archived == true &&
                        flight.createdAt.toString() == task.expectedTargetGeneration
                    else -> false
                }
            }
            TARGET_TRANSITION -> {
                val transition = userDb.itineraryDao().transitionEntity(task.targetId)
                when (task.desiredUserState) {
                    DESIRED_ABSENT -> transition == null
                    DESIRED_ARCHIVED -> transition?.updatedAt?.toString() == task.expectedTargetGeneration &&
                        itineraries.itinerary(transition.itineraryId)?.archived == true
                    else -> false
                }
            }
            else -> false
        }

    private suspend fun prepare(tasks: List<PlatformCleanupTaskEntity>): String {
        require(tasks.isNotEmpty())
        val mutationId = UUID.randomUUID().toString()
        opsDb.withTransaction {
            cleanupDao.insertAll(tasks.map { it.copy(mutationId = mutationId) })
        }
        return mutationId
    }

    private suspend fun <T> commitWithCleanupLocked(
        tasks: List<PlatformCleanupTaskEntity>,
        block: suspend () -> T,
    ): T {
        val mutationId = prepare(tasks)
        val result = try {
            block()
        } catch (e: CancellationException) {
            // Leave PREPARED durable: startup recovery will determine whether
            // cancellation happened before or after the User DB commit.
            enqueueCleanupRetry()
            throw e
        } catch (e: Exception) {
            try {
                reconcileBestEffort { processPendingLocked() }
            } finally {
                enqueueCleanupRetry()
            }
            throw e
        }
        try {
            reconcileBestEffort {
                cleanupDao.markReady(mutationId)
                processPendingLocked()
            }
        } finally {
            enqueueCleanupRetry()
        }
        return result
    }

    private suspend fun <T> commitWithLockedCleanup(
        block: suspend (CleanupPreparer) -> T,
    ): T = processorMutex.withLock {
        var mutationId: String? = null
        val result = try {
            block { tasks ->
                if (tasks.isNotEmpty()) {
                    check(mutationId == null) { "Cleanup was already prepared for this mutation" }
                    mutationId = prepare(tasks)
                }
            }
        } catch (e: CancellationException) {
            // PREPARED is intentionally retained for process-death-style recovery.
            if (mutationId != null) enqueueCleanupRetry()
            throw e
        } catch (e: Exception) {
            if (mutationId != null) {
                try {
                    reconcileBestEffort { processPendingLocked() }
                } finally {
                    enqueueCleanupRetry()
                }
            }
            throw e
        }
        mutationId?.let { id ->
            try {
                reconcileBestEffort {
                    cleanupDao.markReady(id)
                    processPendingLocked()
                }
            } finally {
                enqueueCleanupRetry()
            }
        }
        result
    }

    private fun enqueueCleanupRetry() {
        try {
            cleanupRetry.enqueue()
        } catch (e: Exception) {
            android.util.Log.w("FlightLifecycle", "Platform cleanup retry scheduling failed", e)
        }
    }

    private suspend fun reconcileBestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("FlightLifecycle", "Platform cleanup remains queued", e)
        }
    }

    private suspend fun cleanupTasksFor(
        graph: Itinerary?,
        request: SaveItineraryRequest,
    ): List<PlatformCleanupTaskEntity> {
        val replacementIds = (
            request.legs.mapNotNull { it.replacesFlightId } + request.deleteRemovedFlightIds
        ).toSet()
        val graphTasks = graph?.graphCleanupTasksFor(request).orEmpty()
        val coveredFlightIds = graphTasks.filter { it.targetKind == TARGET_FLIGHT }.map { it.targetId }.toSet()
        val detachedFlightTasks = (replacementIds - coveredFlightIds).mapNotNull { flightId ->
            flights.flight(flightId)?.cleanupTask(DESIRED_ABSENT)
        }
        return (graphTasks + detachedFlightTasks).distinctBy { Triple(it.targetKind, it.targetId, it.desiredUserState) }
    }

    private fun Itinerary.graphCleanupTasksFor(
        request: SaveItineraryRequest,
    ): List<PlatformCleanupTaskEntity> {
        val replacementIds = (
            request.legs.mapNotNull { it.replacesFlightId } + request.deleteRemovedFlightIds
        ).toSet()
        val retainedPairs = request.legs.zipWithNext().mapNotNull { (inbound, outbound) ->
            val first = inbound.existingFlightId ?: return@mapNotNull null
            val second = outbound.existingFlightId ?: return@mapNotNull null
            first to second
        }.toSet()
        val flightByLeg = legs.associate { it.id to it.flight.id }
        val removedTransitions = transitions.filter { transition ->
            val inbound = flightByLeg[transition.inboundLegId] ?: return@filter true
            val outbound = flightByLeg[transition.outboundLegId] ?: return@filter true
            (inbound to outbound) !in retainedPairs
        }
        return buildList {
            legs.filter { it.flight.id in replacementIds }.forEach { leg ->
                add(leg.flight.cleanupTask(DESIRED_ABSENT))
            }
            removedTransitions.forEach { transition ->
                add(
                    cleanupTask(
                        targetKind = TARGET_TRANSITION,
                        targetId = transition.id,
                        generation = transition.updatedAt.toString(),
                        desiredState = DESIRED_ABSENT,
                    )
                )
            }
        }
    }

    private fun Itinerary.cleanupTasks(
        desiredState: String,
        includeFlights: Boolean,
    ): List<PlatformCleanupTaskEntity> = buildList {
        if (includeFlights) legs.forEach { add(it.flight.cleanupTask(desiredState)) }
        transitions.forEach { transition ->
            add(
                cleanupTask(
                    targetKind = TARGET_TRANSITION,
                    targetId = transition.id,
                    generation = transition.updatedAt.toString(),
                    desiredState = desiredState,
                )
            )
        }
    }

    private fun TrackedFlightEntity.cleanupTask(desiredState: String): PlatformCleanupTaskEntity =
        cleanupTask(TARGET_FLIGHT, id, createdAt.toString(), desiredState)

    private fun cleanupTask(
        targetKind: String,
        targetId: Long,
        generation: String,
        desiredState: String,
    ) = PlatformCleanupTaskEntity(
        mutationId = "pending",
        targetKind = targetKind,
        targetId = targetId,
        expectedTargetGeneration = generation,
        desiredUserState = desiredState,
        state = STATE_PREPARED,
        createdAt = Instant.now().toEpochMilli(),
    )

    private companion object {
        const val TARGET_FLIGHT = "FLIGHT"
        const val TARGET_TRANSITION = "TRANSITION"
        const val DESIRED_ABSENT = "ABSENT"
        const val DESIRED_ARCHIVED = "ARCHIVED"
        const val STATE_PREPARED = "PREPARED"
        const val STATE_READY = "READY"
    }
}
