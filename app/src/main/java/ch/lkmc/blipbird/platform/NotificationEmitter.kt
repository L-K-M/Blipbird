package ch.lkmc.blipbird.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ch.lkmc.blipbird.MainActivity
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.data.NotificationSink
import ch.lkmc.blipbird.core.datastore.SettingsRepository
import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.core.model.StatusSnapshot
import ch.lkmc.blipbird.domain.FlightPhaseMachine
import ch.lkmc.blipbird.domain.NotificationPlanner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationEmitter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : NotificationSink {

    companion object {
        const val CHANNEL_CRITICAL = "critical"
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_REMINDERS = "reminders"
        const val CHANNEL_ONGOING = "ongoing"

        /** Progress positions are expressed on this scale. */
        private const val PROGRESS_MAX = 1000

        // Fixed ARGB (notifications don't see the Compose theme): sky blue for
        // the airborne leg, muted gray for taxi segments and phase points.
        private const val COLOR_AIRBORNE = 0xFF5B8DEF.toInt()
        private const val COLOR_TAXI = 0xFF9AA0A6.toInt()
    }

    fun createChannels() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CRITICAL, context.getString(R.string.channel_critical), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = context.getString(R.string.channel_critical_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_status_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_reminders_desc) }
        )
        nm.createNotificationChannel(
            // LOW: the ongoing progress card must never buzz on re-posts.
            NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.channel_ongoing), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.channel_ongoing_desc) }
        )
    }

    private fun canPost(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    private fun timeString(iso: String?): String = iso?.let {
        runCatching { timeFormat.format(Instant.parse(it)) }.getOrDefault(it)
    } ?: "?"

    private fun timeString(at: Instant): String = timeFormat.format(at)

    override suspend fun post(flightId: Long, designator: String, event: NotificationPlanner.Event) {
        if (!canPost()) return
        val (channel, enabled) = when (event.type) {
            NotificationPlanner.EventType.GATE_ASSIGNED,
            NotificationPlanner.EventType.GATE_CHANGE,
            NotificationPlanner.EventType.CANCELLED,
            NotificationPlanner.EventType.DIVERTED -> CHANNEL_CRITICAL to settings.notifCritical.first()
            else -> CHANNEL_STATUS to settings.notifStatus.first()
        }
        if (!enabled) return

        val text = when (event.type) {
            NotificationPlanner.EventType.GATE_ASSIGNED ->
                context.getString(R.string.notif_gate_assigned, event.newValue ?: "?")
            NotificationPlanner.EventType.GATE_CHANGE ->
                context.getString(R.string.notif_gate_change, event.oldValue ?: "?", event.newValue ?: "?")
            NotificationPlanner.EventType.DELAY -> {
                if (event.fingerprint == "delay:status") {
                    // Provider reported "delayed" without a revised time.
                    context.getString(R.string.notif_delay_status_only)
                } else {
                    // Real minutes for copy — the bucketed fingerprint is a dedup key,
                    // not display text (a 29-min slip used to read "Delayed 15m").
                    val mins = event.delayMinutes ?: event.fingerprint.substringAfter(':').toLongOrNull() ?: 0
                    context.getString(R.string.notif_delay, "${mins}m", timeString(event.newValue))
                }
            }
            NotificationPlanner.EventType.DELAY_RECOVERED -> {
                val mins = event.delayMinutes ?: 0
                when {
                    mins > 0 -> context.getString(R.string.notif_delay_recovered, "${mins}m", timeString(event.newValue))
                    event.newValue != null -> context.getString(R.string.notif_back_on_time, timeString(event.newValue))
                    else -> context.getString(R.string.status_on_time)
                }
            }
            NotificationPlanner.EventType.CANCELLED -> context.getString(R.string.notif_cancelled)
            NotificationPlanner.EventType.DIVERTED -> context.getString(R.string.notif_diverted, event.newValue ?: "?")
            NotificationPlanner.EventType.DEPARTED -> context.getString(R.string.notif_departed, timeString(event.newValue))
            NotificationPlanner.EventType.LANDED -> context.getString(R.string.notif_landed, timeString(event.newValue))
        }
        notify(flightId, channel, designator, text)
    }

    fun postReminder(flightId: Long, designator: String, text: String) {
        if (!canPost()) return
        notify(flightId, CHANNEL_REMINDERS, designator, text)
    }

    // ------------------------------------------------------------ F6: ongoing

    /**
     * Reconcile the ongoing in-flight notification (F6, PLAN.md §13) with the
     * latest snapshot: posts/updates while the flight is airborne, cancels in
     * every other state. A null snapshot always cancels (delete/archive).
     * Progress only moves when this is called with fresh data — ProgressStyle
     * posts a value, the OS never recalculates the bar on its own.
     */
    override suspend fun syncOngoing(flightId: Long, designator: String, snapshot: StatusSnapshot?) {
        val view = snapshot?.let { FlightPhaseMachine.derive(it, null, Instant.now()) }
        val inFlight = view != null && when (view.status) {
            FlightStatus.DEPARTED, FlightStatus.EN_ROUTE, FlightStatus.APPROACHING -> true
            else -> false
        }
        if (snapshot == null || view == null || !inFlight || !canPost() || !settings.notifInFlight.first()) {
            cancelOngoing(flightId)
            return
        }

        val route = listOfNotNull(snapshot.departure?.code, snapshot.arrival?.code)
            .takeIf { it.size == 2 }?.let { (dep, arr) -> context.getString(R.string.notif_in_flight_route, dep, arr) }
        val eta = snapshot.arrTimes.best?.let { context.getString(R.string.notif_eta, timeString(it)) }
        val text = listOfNotNull(route, eta).joinToString("  ·  ")
            .ifEmpty { context.getString(R.string.status_en_route) }
        val progress = (view.progress * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX)

        val notification = if (Build.VERSION.SDK_INT >= 36) {
            ongoingNotification36(flightId, designator, text, progress, snapshot)
        } else {
            NotificationCompat.Builder(context, CHANNEL_ONGOING)
                .setSmallIcon(R.drawable.ic_bird_silhouette)
                .setContentTitle(designator)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setProgress(PROGRESS_MAX, progress, false)
                .setContentIntent(contentIntent(flightId, CHANNEL_ONGOING))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
        try {
            NotificationManagerCompat.from(context).notify(stableId(flightId, CHANNEL_ONGOING), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check above and the call.
        }
    }

    fun cancelOngoing(flightId: Long) =
        NotificationManagerCompat.from(context).cancel(stableId(flightId, CHANNEL_ONGOING))

    /**
     * API 36 `Notification.ProgressStyle`: when the actual runway times are
     * known, the bar is segmented into taxi-out / airborne / taxi-in with
     * points at takeoff and touchdown; otherwise a single airborne segment.
     * Promoted-ongoing treatment is requested on 36.1+ (never guaranteed —
     * OEM/user criteria apply).
     */
    @RequiresApi(36)
    private fun ongoingNotification36(
        flightId: Long,
        designator: String,
        text: String,
        progress: Int,
        snapshot: StatusSnapshot,
    ): Notification {
        val style = Notification.ProgressStyle()
            .setProgressTrackerIcon(Icon.createWithResource(context, R.drawable.ic_bird_silhouette))
            .setStyledByProgress(true)
            .setProgress(progress)

        val start = snapshot.depTimes.actual ?: snapshot.depTimes.best
        val end = snapshot.arrTimes.best
        val off = snapshot.depTimes.runwayActual
        val on = snapshot.arrTimes.bestRunway
        fun position(at: Instant): Int? {
            if (start == null || end == null || !end.isAfter(start)) return null
            val f = java.time.Duration.between(start, at).seconds.toDouble() /
                java.time.Duration.between(start, end).seconds.toDouble()
            return (f * PROGRESS_MAX).toInt().takeIf { it in 1 until PROGRESS_MAX }
        }
        val offPos = off?.let { position(it) }
        val onPos = on?.let { position(it) }
        if (offPos != null && onPos != null && offPos < onPos) {
            style.setProgressSegments(
                listOf(
                    Notification.ProgressStyle.Segment(offPos).setColor(COLOR_TAXI),
                    Notification.ProgressStyle.Segment(onPos - offPos).setColor(COLOR_AIRBORNE),
                    Notification.ProgressStyle.Segment(PROGRESS_MAX - onPos).setColor(COLOR_TAXI),
                )
            )
            style.setProgressPoints(
                listOf(
                    Notification.ProgressStyle.Point(offPos).setColor(COLOR_AIRBORNE),
                    Notification.ProgressStyle.Point(onPos).setColor(COLOR_AIRBORNE),
                )
            )
        } else {
            style.setProgressSegments(
                listOf(Notification.ProgressStyle.Segment(PROGRESS_MAX).setColor(COLOR_AIRBORNE))
            )
        }

        val builder = Notification.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_bird_silhouette)
            .setContentTitle(designator)
            .setContentText(text)
            .setStyle(style)
            .setContentIntent(contentIntent(flightId, CHANNEL_ONGOING))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
            builder.setRequestPromotedOngoing(true)
        }
        return builder.build()
    }

    /** Deep link into the flight's detail screen, stable per (flight, channel). */
    private fun contentIntent(flightId: Long, channel: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("flightId", flightId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, stableId(flightId, channel), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notify(flightId: Long, channel: String, title: String, text: String) {
        // Re-checked here (not only in callers) so the permission contract is
        // local to the notify() call — the user can revoke it at any moment.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val pending = contentIntent(flightId, channel)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_bird_silhouette)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context)
                .notify(stableId(flightId, channel), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check above and the call.
        }
    }

    /**
     * Stable, non-negative Int id for a (flight, channel) pair. Replaces the old
     * `flightId.toInt()` (truncates a Long) / `(flightId % Int.MAX_VALUE).toInt() * 10
     * + channel.hashCode() % 10` (Int overflow → negative ids; hashCode() % 10 can
     * be negative; large flightIds collapse onto the same slot) scheme that let
     * different flights replace each other's notifications.
     */
    private fun stableId(flightId: Long, discriminator: String): Int {
        var h = flightId xor discriminator.hashCode().toLong()
        h = h xor (h ushr 32)
        return h.toInt() and 0x7FFFFFFF
    }
}
