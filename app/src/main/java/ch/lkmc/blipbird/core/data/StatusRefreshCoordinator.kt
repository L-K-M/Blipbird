package ch.lkmc.blipbird.core.data

import ch.lkmc.blipbird.core.model.StatusSnapshot
import ch.lkmc.blipbird.platform.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Application-scoped entry point for refresh and refresh-work reconciliation. */
@Singleton
class StatusRefreshCoordinator @Inject constructor(
    private val repository: FlightRepository,
    private val reminders: ReminderScheduler,
    private val backgroundRefresh: BackgroundRefreshController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduleMutex = Mutex()

    suspend fun refreshFlight(flightId: Long, force: Boolean = false): StatusSnapshot? =
        try {
            repository.refreshStatus(flightId, force)
        } finally {
            reconcileReminderBestEffort(flightId)
            reconcileSchedule()
        }

    fun refreshNewFlights(flightIds: Collection<Long>) {
        if (flightIds.isEmpty()) return
        scope.launch {
            // Sequential by design: a newly created itinerary must not fan out a
            // large pasted batch against the provider/quota boundary.
            flightIds.distinct().forEach { refreshFlight(it, force = true) }
        }
    }

    /**
     * Phase-one scheduling facade. All startup and lifecycle paths converge here
     * so a worker cancelling itself cannot race a concurrent tracked-flight write.
     */
    suspend fun reconcileSchedule() = scheduleMutex.withLock {
        try {
            if (repository.activeFlights().isEmpty()) backgroundRefresh.cancel()
            else backgroundRefresh.schedule()
        } catch (e: Exception) {
            android.util.Log.w("StatusRefresh", "Background refresh reconciliation failed", e)
        }
    }

    private suspend fun reconcileReminderBestEffort(flightId: Long) {
        try {
            reminders.reconcile(flightId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("StatusRefresh", "Reminder reconciliation failed", e)
        }
    }
}
