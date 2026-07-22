package ch.lkmc.blipbird.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.data.FlightRepository
import ch.lkmc.blipbird.core.datastore.SettingsRepository
import ch.lkmc.blipbird.domain.FlightPhaseMachine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
) {
    private val alarmManager get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        const val EXTRA_FLIGHT_ID = "flightId"
        const val EXTRA_KIND = "kind"
        const val KIND_BOARDING = "boarding"
        const val KIND_LANDING_SOON = "landing_soon"
        private val LANDING_LEAD: Duration = Duration.ofMinutes(45)
    }

    fun canUseExactAlarms(): Boolean = alarmManager.canScheduleExactAlarms()

    suspend fun reconcile(flightId: Long) {
        if (!settings.notifReminders.first()) { cancel(flightId); return }
        if (!canUseExactAlarms()) return
        val snapshot = repository.latestSnapshot(flightId) ?: return
        val now = Instant.now()

        val boardingAt = snapshot.depTimes.best?.minus(FlightPhaseMachine.DEFAULT_BOARDING_LEAD)
        if (boardingAt != null && boardingAt.isAfter(now) && snapshot.depTimes.actual == null) {
            setExact(flightId, KIND_BOARDING, boardingAt)
        } else {
            cancelKind(flightId, KIND_BOARDING)
        }

        val landingSoonAt = snapshot.arrTimes.best?.minus(LANDING_LEAD)
        if (landingSoonAt != null && landingSoonAt.isAfter(now) && snapshot.arrTimes.actual == null) {
            setExact(flightId, KIND_LANDING_SOON, landingSoonAt)
        } else {
            cancelKind(flightId, KIND_LANDING_SOON)
        }
    }

    suspend fun reconcileAll() {
        for (flight in repository.activeFlights()) reconcile(flight.id)
    }

    fun cancel(flightId: Long) {
        cancelKind(flightId, KIND_BOARDING)
        cancelKind(flightId, KIND_LANDING_SOON)
    }

    private fun setExact(flightId: Long, kind: String, at: Instant) {
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pendingIntent(flightId, kind),
            )
        } catch (_: SecurityException) {
            // Permission revoked between check and call — WorkManager cadence covers it.
        }
    }

    private fun cancelKind(flightId: Long, kind: String) =
        alarmManager.cancel(pendingIntent(flightId, kind))

    private fun pendingIntent(flightId: Long, kind: String): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .putExtra(EXTRA_FLIGHT_ID, flightId)
            .putExtra(EXTRA_KIND, kind)
        val requestCode = (flightId * 10 + if (kind == KIND_BOARDING) 1 else 2).toInt()
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlatformEntryPoint {
    fun repository(): FlightRepository
    fun emitter(): NotificationEmitter
    fun reminderScheduler(): ReminderScheduler
}

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
        val pending = goAsync()
        val entryPoint = EntryPointAccessors.fromApplication(context, PlatformEntryPoint::class.java)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = entryPoint.repository()
                val emitter = entryPoint.emitter()
                val flight = repo.flight(flightId) ?: return@launch
                val snapshot = repo.latestSnapshot(flightId) ?: return@launch
                val designator = flight.alias ?: with(repo) { flight.displayDesignator() }
                val stale = Duration.between(snapshot.fetchedAt, Instant.now()) > Duration.ofMinutes(30)
                val fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
                val text = when (kind) {
                    ReminderScheduler.KIND_BOARDING -> {
                        val at = snapshot.depTimes.best?.minus(FlightPhaseMachine.DEFAULT_BOARDING_LEAD) ?: return@launch
                        context.getString(R.string.notif_boarding_soon, fmt.format(at))
                    }
                    ReminderScheduler.KIND_LANDING_SOON ->
                        context.getString(R.string.notif_landing_soon, 45)
                    else -> return@launch
                }
                val suffix = if (stale) " (${context.getString(R.string.notif_projected_suffix)})" else ""
                emitter.postReminder(flightId, designator, text + suffix)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Rebuilds reminders after reboot or exact-alarm permission changes. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val entryPoint = EntryPointAccessors.fromApplication(context, PlatformEntryPoint::class.java)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                entryPoint.reminderScheduler().reconcileAll()
            } finally {
                pending.finish()
            }
        }
    }
}
