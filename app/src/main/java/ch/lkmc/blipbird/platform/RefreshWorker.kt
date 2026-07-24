package ch.lkmc.blipbird.platform

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ch.lkmc.blipbird.core.data.BackgroundRefreshController
import ch.lkmc.blipbird.core.data.FlightLifecycleCoordinator
import ch.lkmc.blipbird.core.data.FlightOperationLocks
import ch.lkmc.blipbird.core.data.FlightRepository
import ch.lkmc.blipbird.core.data.PlatformCleanupController
import ch.lkmc.blipbird.core.data.StatusRefreshCoordinator
import ch.lkmc.blipbird.core.data.displayDesignator
import ch.lkmc.blipbird.domain.CadencePolicy
import ch.lkmc.blipbird.domain.FlightPhaseMachine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val statusRefresh: StatusRefreshCoordinator,
    private val notifications: NotificationEmitter,
    private val operationLocks: FlightOperationLocks,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val now = Instant.now()
        repository.prune()

        val flights = repository.activeFlights()
        if (flights.isEmpty()) {
            // Lifecycle mutations and startup reconcile the unique schedule.
            // Do not synchronously cancel this worker from inside itself.
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
                snapshot == null -> repository.isStatusLookupEligible(flight.id, now)
                interval == null -> false  // out of any refresh window
                else -> Duration.between(snapshot.fetchedAt, now) >= interval
            }
            var refreshed = false
            if (due) {
                val fresh = statusRefresh.refreshFlight(
                    flight.id,
                    reconcileBackgroundSchedule = false,
                )
                if (fresh != null) {
                    refreshed = true  // refreshStatus reconciled the ongoing card
                }
            }
            if (!refreshed) {
                // Keep the ongoing in-flight card's time-derived progress moving
                // between provider refreshes (F6) — a worker pass is exactly the
                // "legitimate update" cadence PLAN.md §13 allows for reposting.
                operationLocks.withFlight(flight.id) {
                    val active = repository.flight(flight.id)?.takeUnless { it.archived }
                        ?: return@withFlight
                    notifications.syncOngoing(
                        flight.id,
                        active.displayDesignator(),
                        repository.latestSnapshot(flight.id),
                    )
                }
            }
        }
        // Never synchronously cancel the unique work from inside its own worker.
        statusRefresh.reconcileSchedule(allowCancel = false)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "blipbird-refresh"

        fun schedule(context: Context): Operation {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            return WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }

        fun cancel(context: Context): Operation =
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }
}

/** Platform bridge used only by the centralized schedule reconciler. */
@Singleton
class WorkManagerRefreshController @Inject constructor(
    @ApplicationContext private val context: Context,
) : BackgroundRefreshController {
    override suspend fun schedule() = await(RefreshWorker.schedule(context))
    override suspend fun cancel() = await(RefreshWorker.cancel(context))

    private suspend fun await(operation: Operation) {
        withContext(Dispatchers.IO) { operation.result.get() }
    }
}

/** Durable runner for READY/PREPARED platform-cleanup outbox rows. */
@HiltWorker
class PlatformCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val lifecycle: FlightLifecycleCoordinator,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        lifecycle.processPending()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("PlatformCleanup", "Cleanup retry failed", e)
        Result.retry()
    }

    companion object {
        private const val UNIQUE_NAME = "blipbird-platform-cleanup"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PlatformCleanupWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}

@Singleton
class WorkManagerPlatformCleanupController @Inject constructor(
    @ApplicationContext private val context: Context,
) : PlatformCleanupController {
    override fun enqueue() = PlatformCleanupWorker.enqueue(context)
}

/**
 * Rebuilds exact-alarm reminders after a reboot or an exact-alarm permission
 * change (glm 1.19). [BootCompletedReceiver] enqueues this instead of doing the
 * work on its own ~10 s `goAsync()` budget: [ReminderScheduler.reconcileAll]
 * loops over every active flight (Room reads + AlarmManager calls) and can
 * exceed that window, and WorkManager survives process death and Doze deferral.
 */
@HiltWorker
class ReminderReconcileWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val reminders: ReminderScheduler,
    private val repository: FlightRepository,
    private val notifications: NotificationEmitter,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        reminders.reconcileAll()
        repository.activeFlights().forEach {
            notifications.cancelLegacyForFlight(it.id)
            repository.reconcileOngoing(it.id)
        }
        Result.success()
    } catch (e: CancellationException) {
        throw e   // let WorkManager's stop/cancel propagate, don't swallow it
    } catch (e: Exception) {
        // Transient post-boot failures (a Room lock, AlarmManager not ready yet):
        // back off and let WorkManager retry a couple of times before giving up,
        // rather than leaving reminders unreconciled until the next refresh cycle.
        // Log it so a persistent failure (a migration issue, say, not a lock)
        // leaves a breadcrumb instead of vanishing after the last attempt.
        android.util.Log.w("ReminderReconcile", "boot reconcile failed (attempt ${runAttemptCount + 1})", e)
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }

    companion object {
        private const val UNIQUE_NAME = "blipbird-boot-reconcile"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReminderReconcileWorker>().build(),
            )
        }
    }
}
