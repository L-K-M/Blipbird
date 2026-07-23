package ch.lkmc.blipbird

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.os.bundleOf
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras

/**
 * Per-entry ViewModel scoping for the hand-rolled back stack (G10, B4 root
 * cause): every [Screen] on the stack owns a [ViewModelStore] that is cleared
 * when the entry leaves the stack, so `onCleared` finally fires on pop and
 * detail ViewModels stop accumulating per opened flight.
 *
 * The store map lives in an activity-retained ViewModel so entry ViewModels
 * survive configuration changes exactly like activity-scoped ones used to;
 * everything is torn down when the activity is truly finished.
 */
class NavEntryStoresViewModel : ViewModel() {
    private val stores = mutableMapOf<Screen, ViewModelStore>()

    fun storeFor(screen: Screen): ViewModelStore = stores.getOrPut(screen) { ViewModelStore() }

    /** Clear the stores of entries that are no longer on the stack. */
    fun retainOnly(alive: Set<Screen>) {
        (stores.keys - alive).forEach { stores.remove(it)?.clear() }
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

/**
 * The [ViewModelStoreOwner] handed to a screen's composition. ViewModels land
 * in the per-entry store, are created through the host activity's
 * (Hilt-aware) default factory, and receive the entry's arguments as their
 * `SavedStateHandle` defaults — so `hiltViewModel()` inside a screen just
 * works, scoped to the entry instead of the activity.
 */
class NavEntryOwner(
    private val activity: ComponentActivity,
    private val store: ViewModelStore,
    private val screen: Screen,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {

    override val viewModelStore: ViewModelStore get() = store

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = activity.defaultViewModelProviderFactory

    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras(activity.defaultViewModelCreationExtras).apply {
            // Isolate SavedStateHandles per entry (two entries of the same
            // ViewModel class must not collide on the activity's handle map).
            set(VIEW_MODEL_STORE_OWNER_KEY, this@NavEntryOwner)
            defaultArgs(screen)?.let { set(DEFAULT_ARGS_KEY, it) }
        }

    private fun defaultArgs(screen: Screen): Bundle? = when (screen) {
        is Screen.Detail -> bundleOf(MainActivity.EXTRA_FLIGHT_ID to screen.flightId)
        else -> null
    }
}
