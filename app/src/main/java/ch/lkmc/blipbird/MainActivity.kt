package ch.lkmc.blipbird

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.coroutines.cancellation.CancellationException
import ch.lkmc.blipbird.core.datastore.Accent
import ch.lkmc.blipbird.core.datastore.SettingsRepository
import ch.lkmc.blipbird.core.datastore.ThemeMode
import ch.lkmc.blipbird.core.datastore.ThemeSpec
import ch.lkmc.blipbird.platform.AppIconSwitcher
import ch.lkmc.blipbird.ui.components.LocalReduceMotionPref
import ch.lkmc.blipbird.ui.components.rememberReducedMotion
import ch.lkmc.blipbird.ui.detail.FlightDetailScreen
import ch.lkmc.blipbird.ui.itinerary.GroupExistingFlightsScreen
import ch.lkmc.blipbird.ui.itinerary.ItineraryDetailScreen
import ch.lkmc.blipbird.ui.itinerary.ItineraryDraft
import ch.lkmc.blipbird.ui.itinerary.ItineraryDraftStoreViewModel
import ch.lkmc.blipbird.ui.itinerary.ItineraryEditorScreen
import ch.lkmc.blipbird.ui.list.ArchivedFlightsScreen
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
import java.util.UUID

/**
 * Minimal explicit back stack (documented deviation from PLAN.md's Nav3 pick),
 * since G10 with per-entry ViewModel scoping (NavEntryScoping.kt) and
 * gesture-scrubbed predictive back — the two Nav3 features we actually needed.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var appIconSwitcher: AppIconSwitcher

    /** Pending notification deep link; consumed by BlipbirdNav. */
    private val deepLinkFlights = MutableStateFlow<FlightInvocation?>(null)

    /**
     * Last deep link already handed to navigation. Saved across process death:
     * removeExtra survives in-process recreation but NOT process death (the
     * system re-delivers the original intent), so this guard stops a stale
     * notification link from re-firing after the user backed out of it.
     */
    private var consumedDeepLink: String? = null

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
        consumedDeepLink = savedInstanceState?.getString(KEY_CONSUMED_DEEP_LINK)
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
            val reduceMotion by settings.reduceMotion.collectAsStateWithLifecycle(initialValue = false)
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
                CompositionLocalProvider(LocalReduceMotionPref provides reduceMotion) {
                    BlipbirdNav(
                        deepLinkFlights = deepLinkFlights,
                        onDeepLinkConsumed = { deepLinkFlights.value = null },
                        onFirstTrack = { requestNotificationPermission() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A new tap is a new user invocation even when the notification's
        // immutable event token has not changed since the previous tap.
        handleDeepLink(intent, allowRepeat = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CONSUMED_DEEP_LINK, consumedDeepLink)
    }

    private fun handleDeepLink(intent: Intent?, allowRepeat: Boolean = false) {
        intent.flightInvocation()?.let { invocation ->
            if (allowRepeat || invocation.key != consumedDeepLink) {
                deepLinkFlights.value = invocation
                consumedDeepLink = invocation.key
            }
            intent?.removeExtra(EXTRA_FLIGHT_ID)
            intent?.removeExtra(EXTRA_EVENT_TOKEN)
            intent?.removeExtra(EXTRA_NOTIFICATION_SLOT)
        }
    }

    private fun Intent?.flightInvocation(): FlightInvocation? {
        val intent = this ?: return null
        val uri = intent.data
        val segments = uri?.takeIf { it.scheme == "blipbird" && it.host == "flight" }?.pathSegments.orEmpty()
        val versioned = segments.firstOrNull() == "v1"
        val uriId = segments.getOrNull(if (versioned) 1 else 0)?.toLongOrNull()?.takeIf { it > 0 }
        val id = uriId ?: intent.getLongExtra(EXTRA_FLIGHT_ID, -1L).takeIf { it > 0 } ?: return null
        val slot = segments.getOrNull(if (versioned) 2 else 1)
            ?: intent.getStringExtra(EXTRA_NOTIFICATION_SLOT)
            ?: "legacy"
        val token = uri?.getQueryParameter("event")
            ?: intent.getStringExtra(EXTRA_EVENT_TOKEN)
            ?: "legacy-$id"
        return FlightInvocation(id, slot, token)
    }

    companion object {
        /** Must match the extra set by NotificationEmitter. */
        const val EXTRA_FLIGHT_ID = "flightId"
        const val EXTRA_EVENT_TOKEN = "eventToken"
        const val EXTRA_NOTIFICATION_SLOT = "notificationSlot"
        private const val KEY_CONSUMED_DEEP_LINK = "consumedDeepLink"
    }
}

data class FlightInvocation(val flightId: Long, val slot: String, val eventToken: String) {
    val key: String get() = "$flightId:$slot:$eventToken"
}

/** Tagged routes avoid collisions between positive flight and itinerary IDs. */
private val BackStackSaver = listSaver<SnapshotStateList<Screen>, Any>(
    save = { stack -> stack.map { it.encodeRoute() } },
    restore = { saved ->
        mutableStateListOf<Screen>().apply {
            saved.map(::decodeRoute).forEach { screen -> if (lastOrNull() != screen) add(screen) }
            if (isEmpty()) add(Screen.List)
        }
    },
)

@Composable
fun BlipbirdNav(
    deepLinkFlights: StateFlow<FlightInvocation?>,
    onDeepLinkConsumed: () -> Unit,
    onFirstTrack: () -> Unit,
) {
    val backStack = rememberSaveable(saver = BackStackSaver) {
        mutableStateListOf<Screen>(Screen.List)
    }
    val draftStore: ItineraryDraftStoreViewModel = viewModel()
    val drafts by draftStore.drafts.collectAsStateWithLifecycle()

    /**
     * Per-entry `rememberSaveable` state, the UI-state counterpart to the
     * per-entry ViewModel stores below. Without it, `AnimatedContent` disposes a
     * screen once its exit transition settles and every saveable it owns dies with
     * it — so returning from a flight rebuilt the list with its `LargeTopAppBar`
     * fully expanded and its grid scrolled back to the top. Navigation 3 supplies
     * this for free; the hand-rolled stack has to do it by hand.
     */
    val screenState = rememberSaveableStateHolder()

    fun navigate(target: Screen) {
        val existing = backStack.indexOf(target)
        if (existing >= 0) {
            while (backStack.lastIndex > existing) backStack.removeAt(backStack.lastIndex)
        } else {
            backStack.add(target)
        }
    }

    // Notification taps: navigate to the flight (both cold start and while running).
    LaunchedEffect(Unit) {
        deepLinkFlights.collect { invocation ->
            if (invocation != null) {
                val target = Screen.FlightDetail(invocation.flightId)
                if (backStack.last() != target) {
                    // Replace any details on top so back from a notification tap
                    // returns to the list, not a trail of earlier deep links.
                    while (backStack.size > 1 && backStack.last() is Screen.FlightDetail) {
                        backStack.removeAt(backStack.lastIndex)
                    }
                    backStack.add(target)
                }
                onDeepLinkConsumed()
            }
        }
    }

    val current = backStack.last()

    // Predictive back (G10 unlock): the gesture scrubs the pop transition —
    // dragging reveals the previous screen under the departing one, releasing
    // commits the pop, cancelling springs the current screen back.
    val seekable = remember { SeekableTransitionState(current) }
    var predictiveBackInProgress by remember { mutableStateOf(false) }
    val dirtyEditor = (current as? Screen.ItineraryEditor)
        ?.let { drafts[it.draftId]?.dirty } == true
    PredictiveBackHandler(enabled = backStack.size > 1 && !dirtyEditor) { progress ->
        val previous = backStack[backStack.size - 2]
        predictiveBackInProgress = true
        try {
            progress.collect { event -> seekable.seekTo(event.progress, targetState = previous) }
            if (backStack.popIfTop(current)) {
                (current as? Screen.ItineraryEditor)?.let { draftStore.remove(it.draftId) }
                (current as? Screen.GroupExistingFlights)?.let { draftStore.remove(it.draftId) }
            }
        } catch (_: CancellationException) {
            // Gesture cancelled: settle back onto the current screen.
        } finally {
            predictiveBackInProgress = false
        }
    }
    // Drives every non-gesture transition (pushes, deep links, committed pops)
    // to completion; suspended while the finger owns the transition fraction.
    LaunchedEffect(current, predictiveBackInProgress) {
        if (!predictiveBackInProgress &&
            (seekable.currentState != current || seekable.targetState != current)
        ) {
            seekable.animateTo(current)
        }
    }

    // Per-entry ViewModel stores (G10): retained across rotation, cleared for
    // entries that left the stack — but only once the transition settles, so a
    // popped screen keeps its (frozen) state while it animates out.
    val storesVm: NavEntryStoresViewModel = viewModel()
    val activity = LocalActivity.current as ComponentActivity
    // Routes whose saveable state this holder is currently keeping. Diffing against
    // it means we only ever drop keys we actually provided, and a popped screen's
    // scroll/collapse state is released rather than retained for the session.
    val savedRoutes = remember { mutableSetOf<String>() }
    LaunchedEffect(seekable, storesVm) {
        snapshotFlow { Triple(seekable.currentState, seekable.targetState, backStack.toList()) }
            .collect { (from, to, stack) ->
                if (from != to) return@collect
                storesVm.retainOnly(stack.toSet())
                val alive = stack.mapTo(mutableSetOf()) { it.encodeRoute() }
                (savedRoutes - alive).forEach { screenState.removeState(it) }
                savedRoutes.clear()
                savedRoutes += alive
            }
    }

    // Screen changes were hard cuts (REVIEW.md V2); they now run the named
    // push/pop specs from BlipbirdMotion (PLAN.md §10.2), or a plain crossfade
    // when the user removed animations.
    val reducedMotion = rememberReducedMotion()
    val transition = rememberTransition(seekable, label = "screen")
    transition.AnimatedContent(
        transitionSpec = {
            val forward = screenRank(targetState) >= screenRank(initialState)
            // Deeper screens render above shallower ones, so a push covers the
            // outgoing screen and a pop reveals the incoming one beneath.
            val zIndex = screenRank(targetState).toFloat()
            when {
                reducedMotion -> BlipbirdMotion.crossfade(zIndex)
                forward -> BlipbirdMotion.push(zIndex)
                else -> BlipbirdMotion.pop(zIndex)
            }
        },
    ) { screen ->
        val owner = remember(screen) {
            NavEntryOwner(activity, storesVm.storeFor(screen), screen)
        }
        screenState.SaveableStateProvider(screen.encodeRoute()) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                when (screen) {
                    is Screen.List -> FlightListScreen(
                        onOpenFlight = { navigate(Screen.FlightDetail(it)) },
                        onOpenItinerary = { navigate(Screen.ItineraryDetail(it)) },
                        onAddItinerary = {
                            val draftId = UUID.randomUUID().toString()
                            draftStore.put(ItineraryDraft(draftId = draftId))
                            navigate(Screen.ItineraryEditor(draftId, null))
                        },
                        onGroupFlights = {
                            navigate(Screen.GroupExistingFlights(UUID.randomUUID().toString()))
                        },
                        onOpenSettings = { navigate(Screen.Settings) },
                        onOpenArchived = { navigate(Screen.Archived) },
                        onFirstTrack = onFirstTrack,
                    )
                    is Screen.FlightDetail -> FlightDetailScreen(
                        flightId = screen.flightId,
                        onBack = { backStack.popIfTop(screen) },
                    )
                    is Screen.ItineraryDetail -> ItineraryDetailScreen(
                        itineraryId = screen.itineraryId,
                        onBack = { backStack.popIfTop(screen) },
                        onEdit = {
                            navigate(
                                Screen.ItineraryEditor(
                                    draftId = UUID.randomUUID().toString(),
                                    itineraryId = screen.itineraryId,
                                )
                            )
                        },
                        onOpenFlight = { navigate(Screen.FlightDetail(it)) },
                    )
                    is Screen.ItineraryEditor -> ItineraryEditorScreen(
                        draftId = screen.draftId,
                        itineraryId = screen.itineraryId,
                        draftStore = draftStore,
                        onBack = { backStack.popIfTop(screen) },
                        // Only the editor entry that is still on top may navigate on
                        // its own save; a late replay from a popped entry must not
                        // unwind the stack the user has already moved on from.
                        onSaved = { itineraryId ->
                            if (backStack.popIfTop(screen)) {
                                if (itineraryId == null) backStack.popToRoot()
                                else navigate(Screen.ItineraryDetail(itineraryId))
                            }
                        },
                        onFirstTrack = onFirstTrack,
                    )
                    is Screen.GroupExistingFlights -> GroupExistingFlightsScreen(
                        draftId = screen.draftId,
                        draftStore = draftStore,
                        onBack = {
                            draftStore.remove(screen.draftId)
                            backStack.popIfTop(screen)
                        },
                        onContinue = { draft ->
                            if (draftStore.put(draft) && backStack.popIfTop(screen)) {
                                navigate(Screen.ItineraryEditor(draft.draftId, null))
                            }
                        },
                    )
                    is Screen.Settings -> SettingsScreen(
                        onBack = { backStack.popIfTop(screen) },
                    )
                    is Screen.Archived -> ArchivedFlightsScreen(
                        onBack = { backStack.popIfTop(screen) },
                    )
                }
            }
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
    is Screen.Archived -> 1
    is Screen.GroupExistingFlights -> 2
    is Screen.ItineraryEditor -> 2
    is Screen.FlightDetail -> 2
    is Screen.ItineraryDetail -> 2
}
