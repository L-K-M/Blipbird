package ch.lkmc.blipbird.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Launcher icon choice; [aliasSuffix] names the manifest activity-alias. */
enum class AppIcon(val aliasSuffix: String) {
    REGULAR("LauncherRegular"),
    FUN("LauncherFun"),
}

/**
 * What tints the app: the system wallpaper palette, the curated Cockpit scheme,
 * or any user-picked seed color (presets are just well-chosen seeds).
 */
sealed interface Accent {
    data object Dynamic : Accent
    data object Cockpit : Accent
    data class Seed(val argb: Long) : Accent

    fun serialize(): String = when (this) {
        Dynamic -> "dynamic"
        Cockpit -> "cockpit"
        is Seed -> "#%06X".format(argb and 0xFFFFFF)
    }

    companion object {
        /** Brand blue — the seed equivalent of the old fixed Daylight theme. */
        const val BRAND_SEED = 0xFF1667D9

        fun parse(value: String?): Accent? = when {
            value == null -> null
            value == "dynamic" -> Dynamic
            value == "cockpit" -> Cockpit
            value.matches(Regex("#[0-9a-fA-F]{6}")) ->
                Seed(0xFF000000L or value.substring(1).lowercase(Locale.ROOT).toLong(16))
            else -> null
        }
    }
}

data class ThemeSpec(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val accent: Accent = Accent.Dynamic,
    val highContrast: Boolean = false,
)

/**
 * Pre-appearance-redesign settings stored a single theme enum; map it onto the
 * split model so an update never visibly changes anyone's look.
 */
internal fun legacyThemeSpec(name: String?): ThemeSpec? = when (name) {
    "DAYLIGHT_DYNAMIC" -> ThemeSpec(accent = Accent.Dynamic)
    "DAYLIGHT" -> ThemeSpec(accent = Accent.Seed(Accent.BRAND_SEED))
    "COCKPIT" -> ThemeSpec(accent = Accent.Cockpit)
    "HIGH_CONTRAST" -> ThemeSpec(accent = Accent.Seed(Accent.BRAND_SEED), highContrast = true)
    else -> null
}

private val Context.settingsStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val LEGACY_THEME = stringPreferencesKey("theme")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent")
        val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
        val APP_ICON = stringPreferencesKey("app_icon")
        val NOTIF_CRITICAL = booleanPreferencesKey("notif_critical")
        val NOTIF_STATUS = booleanPreferencesKey("notif_status")
        val NOTIF_REMINDERS = booleanPreferencesKey("notif_reminders")
        val NOTIF_IN_FLIGHT = booleanPreferencesKey("notif_in_flight")
    }

    val themeSpec: Flow<ThemeSpec> = context.settingsStore.data.map { p ->
        val legacy = legacyThemeSpec(p[Keys.LEGACY_THEME])
        ThemeSpec(
            mode = p[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: legacy?.mode ?: ThemeMode.SYSTEM,
            accent = Accent.parse(p[Keys.ACCENT]) ?: legacy?.accent ?: Accent.Dynamic,
            highContrast = p[Keys.HIGH_CONTRAST] ?: legacy?.highContrast ?: false,
        )
    }

    val appIcon: Flow<AppIcon> = context.settingsStore.data.map { p ->
        p[Keys.APP_ICON]?.let { runCatching { AppIcon.valueOf(it) }.getOrNull() } ?: AppIcon.REGULAR
    }

    val notifCritical: Flow<Boolean> = context.settingsStore.data.map { it[Keys.NOTIF_CRITICAL] ?: true }
    val notifStatus: Flow<Boolean> = context.settingsStore.data.map { it[Keys.NOTIF_STATUS] ?: true }
    val notifReminders: Flow<Boolean> = context.settingsStore.data.map { it[Keys.NOTIF_REMINDERS] ?: true }
    val notifInFlight: Flow<Boolean> = context.settingsStore.data.map { it[Keys.NOTIF_IN_FLIGHT] ?: true }

    suspend fun setThemeMode(mode: ThemeMode) = context.settingsStore.edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setAccent(accent: Accent) = context.settingsStore.edit { it[Keys.ACCENT] = accent.serialize() }
    suspend fun setHighContrast(v: Boolean) = context.settingsStore.edit { it[Keys.HIGH_CONTRAST] = v }
    suspend fun setAppIcon(icon: AppIcon) = context.settingsStore.edit { it[Keys.APP_ICON] = icon.name }
    suspend fun setNotifCritical(v: Boolean) = context.settingsStore.edit { it[Keys.NOTIF_CRITICAL] = v }
    suspend fun setNotifStatus(v: Boolean) = context.settingsStore.edit { it[Keys.NOTIF_STATUS] = v }
    suspend fun setNotifReminders(v: Boolean) = context.settingsStore.edit { it[Keys.NOTIF_REMINDERS] = v }
    suspend fun setNotifInFlight(v: Boolean) = context.settingsStore.edit { it[Keys.NOTIF_IN_FLIGHT] = v }
}
