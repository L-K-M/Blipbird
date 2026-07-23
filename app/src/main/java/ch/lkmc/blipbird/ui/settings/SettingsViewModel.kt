package ch.lkmc.blipbird.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.blipbird.core.data.QuotaLedger
import ch.lkmc.blipbird.core.datastore.Accent
import ch.lkmc.blipbird.core.datastore.ProviderKeyStore
import ch.lkmc.blipbird.core.datastore.SettingsRepository
import ch.lkmc.blipbird.core.datastore.ThemeMode
import ch.lkmc.blipbird.core.datastore.ThemeSpec
import ch.lkmc.blipbird.platform.ReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class QuotaRow(val provider: String, val used: Long, val allowance: Long?)

data class SettingsUiState(
    val spec: ThemeSpec = ThemeSpec(),
    val hasAdbKey: Boolean = false,
    val hasAeroApiKey: Boolean = false,
    val hasOpenSkyId: Boolean = false,
    val hasOpenSkySecret: Boolean = false,
    val notifCritical: Boolean = true,
    val notifStatus: Boolean = true,
    val notifReminders: Boolean = true,
    val quota: List<QuotaRow> = emptyList(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val keyStore: ProviderKeyStore,
    private val quotaLedger: QuotaLedger,
    private val reminders: ReminderScheduler,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.themeSpec,
        keyStore.keys,
        combine(settings.notifCritical, settings.notifStatus, settings.notifReminders) { c, s, r -> Triple(c, s, r) },
        quotaLedger.observeAll().map { rows ->
            rows.filter { it.periodKey == quotaLedger.periodKey() }
                .map { QuotaRow(it.provider, it.unitsUsed, quotaLedger.allowance(it.provider)) }
        },
    ) { spec, keys, notifs, quota ->
        SettingsUiState(
            spec = spec,
            hasAdbKey = keys.aeroDataBoxKey != null,
            hasAeroApiKey = keys.aeroApiKey != null,
            hasOpenSkyId = keys.openSkyClientId != null,
            hasOpenSkySecret = keys.openSkyClientSecret != null,
            notifCritical = notifs.first,
            notifStatus = notifs.second,
            notifReminders = notifs.third,
            quota = quota,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }
    fun setAccent(accent: Accent) = viewModelScope.launch { settings.setAccent(accent) }
    fun setHighContrast(v: Boolean) = viewModelScope.launch { settings.setHighContrast(v) }
    fun saveAdbKey(key: String) = viewModelScope.launch { keyStore.setAeroDataBoxKey(key) }
    fun saveAeroApiKey(key: String) = viewModelScope.launch { keyStore.setAeroApiKey(key) }
    fun clearAdbKey() = viewModelScope.launch { keyStore.setAeroDataBoxKey(null) }
    fun clearAeroApiKey() = viewModelScope.launch { keyStore.setAeroApiKey(null) }
    fun saveOpenSkyId(value: String) = viewModelScope.launch { keyStore.setOpenSkyClientId(value) }
    fun saveOpenSkySecret(value: String) = viewModelScope.launch { keyStore.setOpenSkyClientSecret(value) }
    fun clearOpenSkyId() = viewModelScope.launch { keyStore.setOpenSkyClientId(null) }
    fun clearOpenSkySecret() = viewModelScope.launch { keyStore.setOpenSkyClientSecret(null) }
    fun setNotifCritical(v: Boolean) = viewModelScope.launch { settings.setNotifCritical(v) }
    fun setNotifStatus(v: Boolean) = viewModelScope.launch { settings.setNotifStatus(v) }

    fun setNotifReminders(v: Boolean) = viewModelScope.launch {
        settings.setNotifReminders(v)
        // Apply immediately: cancels scheduled exact alarms when disabling,
        // re-schedules them when enabling (previously deferred to next refresh).
        reminders.reconcileAll()
    }
}
