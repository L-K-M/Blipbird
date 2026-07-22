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
                if (event.fingerprint == "delay:status") {
                    context.getString(R.string.notif_delay_status_only)
                } else {
                    val realMins = (event.newValue?.let { nv ->
                        event.oldValue?.let { ov ->
                            runCatching { java.time.Duration.between(Instant.parse(ov), Instant.parse(nv)).toMinutes() }.getOrNull()
                        }
                    } ?: event.fingerprint.substringAfter(':').toLongOrNull()) ?: 0
                    context.getString(R.string.notif_delay, "${realMins}m", timeString(event.newValue))
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

    private fun notify(flightId: Long, channel: String, title: String, text: String) {
        // Re-checked here (not only in callers) so the permission contract is
        // local to the notify() call — the user can revoke it at any moment.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("flightId", flightId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, stableId(flightId, channel), intent,
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
