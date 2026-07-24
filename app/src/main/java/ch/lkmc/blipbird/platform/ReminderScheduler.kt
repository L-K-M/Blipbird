package ch.lkmc.blipbird.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.data.FlightOperationLocks
import ch.lkmc.blipbird.core.data.FlightRepository
import ch.lkmc.blipbird.core.data.displayDesignator
import ch.lkmc.blipbird.core.datastore.SettingsRepository
import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.domain.FlightPhaseMachine
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Time-anchored reminders (boarding, landing-soon) on exact alarms when the user
 * granted SCHEDULE_EXACT_ALARM (PLAN.md §12.2): one stable PendingIntent identity
 * per flight/event, reconciled whenever the source milestone changes, and rebuilt
 * on boot / permission-state change by [BootCompletedReceiver]. Degrades silently
 * to the WorkManager cadence when not granted.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: FlightRepository,
    private val settings: SettingsRepository,
    private val operationLocks: FlightOperationLocks,
    private val notifications: NotificationEmitter,
) {
    private val alarmManager get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        const val EXTRA_FLIGHT_ID = "flightId"
        const val EXTRA_KIND = "kind"
        const val KIND_BOARDING = "boarding"
        const val KIND_LANDING_SOON = "landing_soon"
        private val LANDING_LEAD: Duration = Duration.ofMinutes(45)
    }

    // Before S exact alarms need no permission; canScheduleExactAlarms is API 31+.
    fun canUseExactAlarms(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

    suspend fun reconcile(flightId: Long) = operationLocks.withFlight(flightId) {
        val active = repository.flight(flightId)?.archived == false
        if (!active || !settings.notifReminders.first()) {
            cancel(flightId)
            return@withFlight
        }
        val snapshot = repository.latestSnapshot(flightId)
        if (snapshot == null) {
            cancel(flightId)
            return@withFlight
        }
        val now = Instant.now()
        val terminal = snapshot.status in setOf(
            FlightStatus.CANCELLED,
            FlightStatus.DIVERTED,
            FlightStatus.LANDED,
            FlightStatus.ARRIVED,
        )

        val boardingAt = snapshot.depTimes.best?.minus(FlightPhaseMachine.DEFAULT_BOARDING_LEAD)
        // "Up" = gate-actual OR runway-actual (wheels-off). AeroDataBox only fills
        // runwayActual, so checking depTimes.actual alone would keep a boarding
        // alarm armed after the flight is already airborne.
        val up = snapshot.depTimes.actual ?: snapshot.depTimes.runwayActual
        val airborne = snapshot.status in setOf(
            FlightStatus.DEPARTED,
            FlightStatus.EN_ROUTE,
            FlightStatus.APPROACHING,
        )
        reconcileKind(
            flightId,
            KIND_BOARDING,
            boardingAt,
            boardingAt?.isAfter(now) == true && up == null && !airborne && !terminal,
        )

        val landingSoonAt = snapshot.arrTimes.best?.minus(LANDING_LEAD)
        val down = snapshot.arrTimes.actual ?: snapshot.arrTimes.runwayActual
        reconcileKind(
            flightId,
            KIND_LANDING_SOON,
            landingSoonAt,
            landingSoonAt?.isAfter(now) == true && down == null && !terminal,
        )
    }

    suspend fun reconcileAll() {
        for (flight in repository.activeFlights()) reconcile(flight.id)
    }

    fun cancel(flightId: Long) {
        cancelKind(flightId, KIND_BOARDING)
        cancelKind(flightId, KIND_LANDING_SOON)
    }

    private fun reconcileKind(flightId: Long, kind: String, at: Instant?, wanted: Boolean) {
        if (!wanted || at == null) {
            cancelKind(flightId, kind)
            return
        }
        if (canUseExactAlarms() && setExact(flightId, kind, at)) {
            ReminderDeliveryWorker.cancel(context, flightId, kind)
        } else {
            cancelAlarmKind(flightId, kind)
            ReminderDeliveryWorker.schedule(context, flightId, kind, at)
        }
    }

    private fun setExact(flightId: Long, kind: String, at: Instant): Boolean {
        return try {
            // Releases before semantic data URIs had no Intent data and remain a
            // distinct PendingIntent until explicitly removed during migration.
            alarmManager.cancel(legacyPendingIntent(flightId, kind))
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pendingIntent(flightId, kind),
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun cancelKind(flightId: Long, kind: String) {
        cancelAlarmKind(flightId, kind)
        ReminderDeliveryWorker.cancel(context, flightId, kind)
    }

    private fun cancelAlarmKind(flightId: Long, kind: String) {
        alarmManager.cancel(pendingIntent(flightId, kind))
        alarmManager.cancel(legacyPendingIntent(flightId, kind))
    }

    private fun pendingIntent(flightId: Long, kind: String): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .putExtra(EXTRA_FLIGHT_ID, flightId)
            .putExtra(EXTRA_KIND, kind)
            .setData(
                Uri.Builder().scheme("blipbird").authority("alarm")
                    .appendPath("v1").appendPath("flight")
                    .appendPath(flightId.toString()).appendPath(kind).build()
            )
        // Stable, non-negative request code. The old `(flightId * 10 + n).toInt()`
        // truncated Long ids and overflowed Int once flightId exceeded ~214M.
        var h = flightId xor kind.hashCode().toLong()
        h = h xor (h ushr 32)
        val requestCode = h.toInt() and 0x7FFFFFFF
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun legacyPendingIntent(flightId: Long, kind: String): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .putExtra(EXTRA_FLIGHT_ID, flightId)
            .putExtra(EXTRA_KIND, kind)
        var h = flightId xor kind.hashCode().toLong()
        h = h xor (h ushr 32)
        return PendingIntent.getBroadcast(
            context,
            h.toInt() and 0x7FFFFFFF,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Revalidate a fired exact alarm or delayed-work fallback before posting. */
    suspend fun deliverIfEligible(flightId: Long, kind: String, requiresExactAlarm: Boolean): Boolean =
        operationLocks.withFlight(flightId) {
            if (!settings.notifReminders.first()) return@withFlight false
            val exactAvailable = canUseExactAlarms()
            if (requiresExactAlarm != exactAvailable) return@withFlight false
            val flight = repository.flight(flightId)?.takeUnless { it.archived } ?: return@withFlight false
            val snapshot = repository.latestSnapshot(flightId) ?: return@withFlight false
            if (
                snapshot.status == FlightStatus.CANCELLED ||
                snapshot.status == FlightStatus.DIVERTED ||
                snapshot.status == FlightStatus.LANDED ||
                snapshot.status == FlightStatus.ARRIVED
            ) return@withFlight false
            val now = Instant.now()
            val trigger: Instant
            val milestone: Instant
            val actual: Instant?
            val text: String
            val fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
            when (kind) {
                KIND_BOARDING -> {
                    if (
                        snapshot.status == FlightStatus.DEPARTED ||
                        snapshot.status == FlightStatus.EN_ROUTE ||
                        snapshot.status == FlightStatus.APPROACHING
                    ) return@withFlight false
                    actual = snapshot.depTimes.actual ?: snapshot.depTimes.runwayActual
                    milestone = snapshot.depTimes.best ?: return@withFlight false
                    trigger = milestone.minus(FlightPhaseMachine.DEFAULT_BOARDING_LEAD)
                    text = context.getString(R.string.notif_boarding_soon, fmt.format(trigger))
                }
                KIND_LANDING_SOON -> {
                    actual = snapshot.arrTimes.actual ?: snapshot.arrTimes.runwayActual
                    milestone = snapshot.arrTimes.best ?: return@withFlight false
                    trigger = milestone.minus(LANDING_LEAD)
                    text = context.getString(R.string.notif_landing_soon, LANDING_LEAD.toMinutes())
                }
                else -> return@withFlight false
            }
            if (!reminderDeliveryEligible(trigger, milestone, actual, now)) return@withFlight false
            val stale = Duration.between(snapshot.fetchedAt, now) > Duration.ofMinutes(30)
            val suffix = if (stale) " (${context.getString(R.string.notif_projected_suffix)})" else ""
            notifications.postReminder(
                flightId = flightId,
                designator = flight.displayDesignator(),
                kind = kind,
                eventToken = "$kind:${trigger.toEpochMilli()}",
                text = text + suffix,
            )
            true
        }
}

internal fun reminderDeliveryEligible(
    trigger: Instant,
    milestone: Instant,
    actual: Instant?,
    now: Instant,
): Boolean = actual == null && !trigger.isAfter(now) && milestone.isAfter(now)

/**
 * Fires a reminder. Rechecks the latest local state before posting so a stale
 * alarm can never emit a reminder that is no longer wanted; the projected label
 * is applied when the data is old (offline branch, PLAN.md §12.2).
 */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val flightId = intent.getLongExtra(ReminderScheduler.EXTRA_FLIGHT_ID, -1)
        val kind = intent.getStringExtra(ReminderScheduler.EXTRA_KIND) ?: return
        if (flightId < 0) return
        ReminderDeliveryWorker.enqueueFiredAlarm(context, flightId, kind)
    }
}

@HiltWorker
class ReminderDeliveryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val scheduler: ReminderScheduler,
    private val repository: FlightRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        val flightId = inputData.getLong(ReminderScheduler.EXTRA_FLIGHT_ID, -1)
        val kind = inputData.getString(ReminderScheduler.EXTRA_KIND) ?: return Result.failure()
        if (flightId <= 0) return Result.failure()
        val requiresExact = inputData.getBoolean(KEY_REQUIRES_EXACT, false)
        if (!requiresExact) {
            val snapshot = repository.latestSnapshot(flightId)
            if (snapshot == null || Duration.between(snapshot.fetchedAt, Instant.now()) > Duration.ofMinutes(10)) {
                try {
                    repository.refreshStatus(flightId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The local projected branch below remains available offline.
                }
            }
        }
        val posted = scheduler.deliverIfEligible(
            flightId,
            kind,
            requiresExact,
        )
        if (!posted) scheduler.reconcile(flightId)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("ReminderDelivery", "Reminder delivery failed", e)
        Result.retry()
    }

    companion object {
        private const val KEY_REQUIRES_EXACT = "requiresExactAlarm"

        fun schedule(context: Context, flightId: Long, kind: String, at: Instant) {
            enqueue(
                context = context,
                flightId = flightId,
                kind = kind,
                requiresExact = false,
                delayMillis = Duration.between(Instant.now(), at).toMillis().coerceAtLeast(0),
            )
        }

        fun enqueueFiredAlarm(context: Context, flightId: Long, kind: String) {
            enqueue(context, flightId, kind, requiresExact = true, delayMillis = 0)
        }

        fun cancel(context: Context, flightId: Long, kind: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(flightId, kind))
        }

        private fun enqueue(
            context: Context,
            flightId: Long,
            kind: String,
            requiresExact: Boolean,
            delayMillis: Long,
        ) {
            val request = OneTimeWorkRequestBuilder<ReminderDeliveryWorker>()
                .setInputData(
                    workDataOf(
                        ReminderScheduler.EXTRA_FLIGHT_ID to flightId,
                        ReminderScheduler.EXTRA_KIND to kind,
                        KEY_REQUIRES_EXACT to requiresExact,
                    )
                )
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(flightId, kind),
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        private fun workName(flightId: Long, kind: String) = "blipbird-reminder:$flightId:$kind"
    }
}

/** Rebuilds reminders after reboot or exact-alarm permission changes. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Hand the reconcile to WorkManager rather than run it on the ~10 s
        // goAsync budget (glm 1.19): reconcileAll loops over every active flight
        // and can outlast that window, and the work must survive the process
        // being torn down right after boot.
        ReminderReconcileWorker.enqueue(context)
    }
}
