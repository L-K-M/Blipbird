package ch.lkmc.blipbird

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import ch.lkmc.blipbird.core.database.ReferenceImporter
import ch.lkmc.blipbird.platform.NotificationEmitter
import ch.lkmc.blipbird.platform.RefreshWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class BlipbirdApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var referenceImporter: ReferenceImporter
    @Inject lateinit var notificationEmitter: NotificationEmitter

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        notificationEmitter.createChannels()
        RefreshWorker.schedule(this)
        appScope.launch { referenceImporter.ensureImported() }
    }

    /**
     * Persist the last uncaught exception to files/last_crash.txt (surfaced in
     * Settings → Diagnostics) so device-only crashes are debuggable without adb.
     * The work on the crashing thread is bounded (glm-A): the payload is capped
     * (deep cause chains can be huge) and the disk write runs on a short-lived
     * thread joined with a timeout, so a slow flash can't stretch the crash
     * dialog or turn the crash into an ANR.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val text = (
                    "${java.time.Instant.now()}\nthread: ${thread.name}\n" +
                        android.util.Log.getStackTraceString(throwable)
                    ).take(MAX_CRASH_LOG_CHARS)
                val file = java.io.File(filesDir, "last_crash.txt")
                val writer = Thread { runCatching { file.writeText(text) } }
                writer.start()
                writer.join(CRASH_WRITE_TIMEOUT_MS)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private companion object {
        const val MAX_CRASH_LOG_CHARS = 64_000
        const val CRASH_WRITE_TIMEOUT_MS = 2_000L
    }
}
