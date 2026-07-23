package ch.lkmc.blipbird

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import ch.lkmc.blipbird.core.datastore.Accent
import ch.lkmc.blipbird.core.datastore.SettingsRepository
import ch.lkmc.blipbird.core.datastore.ThemeMode
import ch.lkmc.blipbird.core.datastore.ThemeSpec
import ch.lkmc.blipbird.platform.AppIconSwitcher
import ch.lkmc.blipbird.ui.components.rememberReducedMotion
import ch.lkmc.blipbird.ui.detail.FlightDetailScreen
import ch.lkmc.blipbird.ui.list.FlightListScreen
import ch.lkmc.blipbird.ui.settings.SettingsScreen
import ch.lkmc.blipbird.ui.theme.BlipbirdMotion
import ch.lkmc.blipbird.ui.theme.BlipbirdTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    @Inject lateinit var appIconSwitcher: AppIconSwitcher

    /** Pending notification deep link; consumed by BlipbirdNav. */
    private val deepLinkFlights = MutableStateFlow<Long?>(null)

    /**
     * Last deep link already handed to navigation. Saved across process death:
     * removeExtra survives in-process recreation but NOT process death (the
     * system re-delivers the original intent), so this guard stops a stale
     * notification link from re-firing after the user backed out of it.
     */
    private var consumedDeepLink: Long = -1L

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
        consumedDeepLink = savedInstanceState?.getLong(KEY_CONSUMED_DEEP_LINK, -1L) ?: -1L
        // Re-sync the launcher alias with the stored choice: component enabled
        // state doesn't ride Auto Backup, so a restored device would otherwise
        // show the default icon while Settings claims the other. No-op normally.
        lifecycleScope.launch { appIconSwitcher.apply(settings.appIcon.first()) }
        // Honor the launch intent's deep link unless it was already consumed
        // before a restore (process death can restore saved state AND re-deliver
        // the original notification intent in the same onCreate).
        handleDeepLink(intent)

        setContent {
            val spec by settings.themeSpec.collectAsStateWithLifecycle(initialValue = ThemeSpec())
            // Cockpit forces dark regardless of the mode setting (its scheme is
            // dark-only), so system-bar icon styling must follow the resolved
            // app theme, not the system (a plain enableEdgeToEdge() left dark
            // icons on the near-black Cockpit background in OS light mode).
            val darkTheme = spec.accent == Accent.Cockpit || when (spec.mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(darkTheme) {
                val transparent = android.graphics.Color.TRANSPARENT
                val style = if (darkTheme) SystemBarStyle.dark(transparent)
                else SystemBarStyle.light(transparent, transparent)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            BlipbirdTheme(spec = spec, darkTheme = darkTheme) {
                BlipbirdNav(
                    deepLinkFlights = deepLinkFlights,
                    onDeepLinkConsumed = { deepLinkFlights.value = null },
                    onFirstTrack = { requestNotificationPermission() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A genuinely new notification tap may target the same flight again.
        consumedDeepLink = -1L
        handleDeepLink(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_CONSUMED_DEEP_LINK, consumedDeepLink)
    }

    private fun handleDeepLink(intent: Intent?) {
        intent.deepLinkFlightId()?.let { id ->
            if (id != consumedDeepLink) {
                deepLinkFlights.value = id
                consumedDeepLink = id
            }
            intent?.removeExtra(EXTRA_FLIGHT_ID)
        }
    }

    private fun Intent?.deepLinkFlightId(): Long? =
        this?.getLongExtra(EXTRA_FLIGHT_ID, -1L)?.takeIf { it > 0 }

    companion object {
        /** Must match the extra set by NotificationEmitter. */
        const val EXTRA_FLIGHT_ID = "flightId"
        private const val KEY_CONSUMED_DEEP_LINK = "consumedDeepLink"
    }
}

/** Survives configuration change and process death: screens encode to plain longs. */
private val BackStackSaver = listSaver<SnapshotStateList<Screen>, Long>(
    save = { stack ->
        stack.map { screen ->
            when (screen) {
                is Screen.List -> SAVED_LIST
                is Screen.Settings -> SAVED_SETTINGS
                is Screen.Detail -> screen.flightId
            }
        }
    },
    restore = { saved ->
        mutableStateListOf<Screen>().apply {
            saved.forEach { id ->
                add(
                    when (id) {
                        SAVED_LIST -> Screen.List
                        SAVED_SETTINGS -> Screen.Settings
                        else -> Screen.Detail(id)
                    }
                )
            }
            if (isEmpty()) add(Screen.List)
        }
    },
)
private const val SAVED_LIST = -1L
private const val SAVED_SETTINGS = -2L

@Composable
fun BlipbirdNav(
    deepLinkFlights: StateFlow<Long?>,
    onDeepLinkConsumed: () -> Unit,
    onFirstTrack: () -> Unit,
) {
    val backStack = rememberSaveable(saver = BackStackSaver) {
        mutableStateListOf<Screen>(Screen.List)
    }

    // Notification taps: navigate to the flight (both cold start and while running).
    LaunchedEffect(Unit) {
        deepLinkFlights.collect { flightId ->
            if (flightId != null) {
                val target = Screen.Detail(flightId)
                if (backStack.last() != target) {
                    // Replace any details on top so back from a notification tap
                    // returns to the list, not a trail of earlier deep links.
                    while (backStack.size > 1 && backStack.last() is Screen.Detail) {
                        backStack.removeAt(backStack.lastIndex)
                    }
                    backStack.add(target)
                }
                onDeepLinkConsumed()
            }
        }
    }

    val current = backStack.last()

    BackHandler(enabled = backStack.size > 1) { backStack.removeAt(backStack.lastIndex) }

    // Screen changes were hard cuts (REVIEW.md V2); they now run the named
    // push/pop specs from BlipbirdMotion (PLAN.md §10.2), or a plain crossfade
    // when the user removed animations.
    val reducedMotion = rememberReducedMotion()
    AnimatedContent(
        targetState = current,
        transitionSpec = {
            val forward = screenRank(targetState) >= screenRank(initialState)
            val transform = when {
                reducedMotion -> BlipbirdMotion.crossfade()
                forward -> BlipbirdMotion.push()
                else -> BlipbirdMotion.pop()
            }
            // Deeper screens render above shallower ones, so a push covers the
            // outgoing screen and a pop reveals the incoming one beneath.
            transform.apply { targetContentZIndex = screenRank(targetState).toFloat() }
        },
        label = "screen",
    ) { screen ->
        when (screen) {
            is Screen.List -> FlightListScreen(
                onOpenFlight = { backStack.add(Screen.Detail(it)) },
                onOpenSettings = { backStack.add(Screen.Settings) },
                onFirstTrack = onFirstTrack,
            )
            is Screen.Detail -> FlightDetailScreen(
                flightId = screen.flightId,
                onBack = { backStack.removeAt(backStack.lastIndex) },
            )
            is Screen.Settings -> SettingsScreen(
                onBack = { backStack.removeAt(backStack.lastIndex) },
            )
        }
    }
}

/**
 * Navigation depth for transition direction and z-order: moving to a deeper
 * (or equal — e.g. a deep link replacing one detail with another) screen is a
 * push, to a shallower one a pop.
 */
private fun screenRank(screen: Screen): Int = when (screen) {
    is Screen.List -> 0
    is Screen.Settings -> 1
    is Screen.Detail -> 2
}
