package ch.lkmc.blipbird.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class AppTheme { DAYLIGHT, DAYLIGHT_DYNAMIC, COCKPIT, HIGH_CONTRAST }

private val Context.settingsStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val NOTIF_CRITICAL = booleanPreferencesKey("notif_critical")
        val NOTIF_STATUS = booleanPreferencesKey("notif_status")
        val NOTIF_REMINDERS = booleanPreferencesKey("notif_reminders")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    }

    val theme: Flow<AppTheme> = context.settingsStore.data.map { p ->
        p[Keys.THEME]?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() } ?: AppTheme.DAYLIGHT_DYNAMIC
    }

    val notifCritical: Flow<Boolean> = context.settingsStore.data.map { it[Keys.NOTIF_CRITICAL] ?: true }
    val notifStatus: Flow<Boolean> = context.settingsStore.data.map { it[Keys.NOTIF_STATUS] ?: true }
    val notifReminders: Flow<Boolean> = context.settingsStore.data.map { it[Keys.NOTIF_REMINDERS] ?: true }
    val onboardingDone: Flow<Boolean> = context.settingsStore.data.map { it[Keys.ONBOARDING_DONE] ?: false }

    suspend fun setTheme(theme: AppTheme) = context.settingsStore.edit { it[Keys.THEME] = theme.name }
    suspend fun setNotifCritical(v: Boolean) = context.settingsStore.edit { it[Keys.NOTIF_CRITICAL] = v }
    suspend fun setNotifStatus(v: Boolean) = context.settingsStore.edit { it[Keys.NOTIF_STATUS] = v }
    suspend fun setNotifReminders(v: Boolean) = context.settingsStore.edit { it[Keys.NOTIF_REMINDERS] = v }
    suspend fun setOnboardingDone() = context.settingsStore.edit { it[Keys.ONBOARDING_DONE] = true }
}
