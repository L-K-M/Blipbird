package ch.lkmc.blipbird.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ch.lkmc.blipbird.MainActivity
import ch.lkmc.blipbird.R
import ch.lkmc.blipbird.core.data.NotificationSink
import ch.lkmc.blipbird.core.datastore.SettingsRepository
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

        /**
         * One notification slot per flight and channel:
         * id = (flightId % 500_000_000) * 4 + channel index.
         * The old scheme used channel.hashCode() % 10, which can be negative and
         * lets two flight/channel pairs collide (a gate change silently
         * overwriting another flight's delay alert).
         */
        internal fun notificationId(flightId: Long, channel: String): Int {
            val index = when (channel) {
                CHANNEL_CRITICAL -> 0
                CHANNEL_STATUS -> 1
                CHANNEL_REMINDERS -> 2
                else -> 3
            }
            // floorMod: Room ids are always positive, but keep the ID space
            // non-negative even for a hypothetical negative input.
            return Math.floorMod(flightId, 500_000_000L).toInt() * 4 + index
        }
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
    }

    private fun canPost(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun timeString(iso: String?): String = iso?.let {
        runCatching {
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(it))
        }.getOrDefault(it)
    } ?: "?"

    override suspend fun post(flightId: Long, designator: String, event: NotificationPlanner.Event) {
        if (!canPost()) return
        val (channel, enabled) = when (event.type) {
            NotificationPlanner.EventType.GATE_CHANGE,
            NotificationPlanner.EventType.CANCELLED,
            NotificationPlanner.EventType.DIVERTED -> CHANNEL_CRITICAL to settings.notifCritical.first()
            else -> CHANNEL_STATUS to settings.notifStatus.first()
        }
        if (!enabled) return

        val text = when (event.type) {
            NotificationPlanner.EventType.GATE_CHANGE ->
                context.getString(R.string.notif_gate_change, event.oldValue ?: "?", event.newValue ?: "?")
            NotificationPlanner.EventType.DELAY -> {
                val mins = event.fingerprint.substringAfter(':').toLongOrNull() ?: 0
                context.getString(R.string.notif_delay, "${mins}m", timeString(event.newValue))
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

    private fun notify(flightId: Long, channel: String, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("flightId", flightId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, flightId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
                .notify(notificationId(flightId, channel), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check above and the call.
        }
    }
}
