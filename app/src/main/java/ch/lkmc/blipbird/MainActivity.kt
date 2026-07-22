package ch.lkmc.blipbird

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.blipbird.core.datastore.AppTheme
import ch.lkmc.blipbird.core.datastore.SettingsRepository
import ch.lkmc.blipbird.ui.detail.FlightDetailScreen
import ch.lkmc.blipbird.ui.list.FlightListScreen
import ch.lkmc.blipbird.ui.settings.SettingsScreen
import ch.lkmc.blipbird.ui.theme.BlipbirdTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Minimal explicit back stack (documented deviation from PLAN.md's Nav3 pick). */
sealed interface Screen {
    data object List : Screen
    data class Detail(val flightId: Long) : Screen
    data object Settings : Screen
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val deepLinkFlightId = intent.deepLinkFlightId()

        setContent {
            val theme by settings.theme.collectAsStateWithLifecycle(initialValue = AppTheme.DAYLIGHT_DYNAMIC)
            BlipbirdTheme(theme = theme) {
                BlipbirdNav(
                    initialDetailFlightId = deepLinkFlightId,
                    onFirstTrack = { requestNotificationPermission() },
                )
            }
        }
    }

    private fun Intent?.deepLinkFlightId(): Long? =
        this?.getLongExtra("flightId", -1L)?.takeIf { it > 0 }
}

@Composable
fun BlipbirdNav(
    initialDetailFlightId: Long?,
    onFirstTrack: () -> Unit,
) {
    val backStack = remember {
        mutableStateListOf<Screen>(Screen.List).also { stack ->
            initialDetailFlightId?.let { stack.add(Screen.Detail(it)) }
        }
    }
    val current = backStack.last()

    BackHandler(enabled = backStack.size > 1) { backStack.removeAt(backStack.lastIndex) }

    when (current) {
        is Screen.List -> FlightListScreen(
            onOpenFlight = { backStack.add(Screen.Detail(it)) },
            onOpenSettings = { backStack.add(Screen.Settings) },
            onFirstTrack = onFirstTrack,
        )
        is Screen.Detail -> FlightDetailScreen(
            flightId = current.flightId,
            onBack = { backStack.removeAt(backStack.lastIndex) },
        )
        is Screen.Settings -> SettingsScreen(
            onBack = { backStack.removeAt(backStack.lastIndex) },
        )
    }
}
