package ch.lkmc.blipbird.platform

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Constraints
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ch.lkmc.blipbird.core.data.BackgroundRefreshController
import ch.lkmc.blipbird.core.data.FlightRepository
import ch.lkmc.blipbird.domain.CadencePolicy
import ch.lkmc.blipbird.domain.FlightPhaseMachine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single background refresher (PLAN.md §8): one unique 15-minute periodic
 * worker checks every active flight against [CadencePolicy] and fetches only the
 * flights that are due. WorkManager timing is best effort under Doze — the UI
 * says so, and precise *reminders* ride exact alarms instead (ReminderScheduler).
 */
@HiltWorker
class RefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: FlightRepository,
    private val reminders: ReminderScheduler,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val now = Instant.now()
        repository.prune()

        val flights = repository.activeFlights()
        if (flights.isEmpty()) {
            // Nothing to watch: stop waking the device every 15 minutes.
            // FlightRepository.track() re-arms via BackgroundRefreshController.
            WorkManager.getInstance(applicationContext).cancelUniqueWork(UNIQUE_NAME)
            return Result.success()
        }

        for (flight in flights) {
            val snapshot = repository.latestSnapshot(flight.id)
            val view = FlightPhaseMachine.derive(snapshot, null, now)
            val interval = CadencePolicy.nextInterval(
                status = view.status,
                bestDep = snapshot?.depTimes?.best,
                bestArr = snapshot?.arrTimes?.best,
                arrivalResolved = snapshot?.arrGate != null && snapshot.baggageBelt != null,
                now = now,
            )
            val deadline = CadencePolicy.arrivalMonitoringDeadline(
                snapshot?.depTimes?.best, snapshot?.arrTimes?.best,
            )
            if (deadline != null && now.isAfter(deadline)) continue

            val due = when {
                snapshot == null -> true   // never fetched
                interval == null -> false  // out of any refresh window
                else -> Duration.between(snapshot.fetchedAt, now) >= interval
            }
            if (due) {
                val fresh = repository.refreshStatus(flight.id)
                if (fresh != null) reminders.reconcile(flight.id)
            }
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "blipbird-refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }
    }
}

/** Data-layer hook so tracking a flight re-arms the (self-cancelling) worker. */
@Singleton
class WorkManagerRefreshController @Inject constructor(
    @ApplicationContext private val context: Context,
) : BackgroundRefreshController {
    override fun ensureScheduled() = RefreshWorker.schedule(context)
}
