package ch.lkmc.blipbird.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.blipbird.core.data.QuotaLedger
import ch.lkmc.blipbird.core.datastore.AppTheme
import ch.lkmc.blipbird.core.datastore.ProviderKeyStore
import ch.lkmc.blipbird.core.datastore.SettingsRepository
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
    val theme: AppTheme = AppTheme.DAYLIGHT_DYNAMIC,
    val hasAdbKey: Boolean = false,
    val hasAeroApiKey: Boolean = false,
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
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.theme,
        keyStore.keys,
        combine(settings.notifCritical, settings.notifStatus, settings.notifReminders) { c, s, r -> Triple(c, s, r) },
        quotaLedger.observeAll().map { rows ->
            rows.filter { it.periodKey == quotaLedger.periodKey() }
                .map { QuotaRow(it.provider, it.unitsUsed, quotaLedger.allowance(it.provider)) }
        },
    ) { theme, keys, notifs, quota ->
        SettingsUiState(
            theme = theme,
            hasAdbKey = keys.aeroDataBoxKey != null,
            hasAeroApiKey = keys.aeroApiKey != null,
            notifCritical = notifs.first,
            notifStatus = notifs.second,
            notifReminders = notifs.third,
            quota = quota,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(theme: AppTheme) = viewModelScope.launch { settings.setTheme(theme) }
    fun saveAdbKey(key: String) = viewModelScope.launch { keyStore.setAeroDataBoxKey(key) }
    fun saveAeroApiKey(key: String) = viewModelScope.launch { keyStore.setAeroApiKey(key) }
    fun setNotifCritical(v: Boolean) = viewModelScope.launch { settings.setNotifCritical(v) }
    fun setNotifStatus(v: Boolean) = viewModelScope.launch { settings.setNotifStatus(v) }
    fun setNotifReminders(v: Boolean) = viewModelScope.launch { settings.setNotifReminders(v) }
}
