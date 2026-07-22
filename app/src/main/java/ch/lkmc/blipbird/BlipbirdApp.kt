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
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                java.io.File(filesDir, "last_crash.txt").writeText(
                    "${java.time.Instant.now()}\nthread: ${thread.name}\n" +
                        android.util.Log.getStackTraceString(throwable)
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
