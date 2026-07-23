package ch.lkmc.blipbird.platform

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import ch.lkmc.blipbird.core.datastore.AppIcon
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies the launcher-icon choice by flipping the manifest activity-aliases'
 * component enabled state. The chosen alias is enabled BEFORE the others are
 * disabled — a moment with zero LAUNCHER components makes some launchers treat
 * the app as uninstalled and drop pinned shortcuts. Idempotent: no-ops (and so
 * spares the launcher a refresh) when the state already matches, which also
 * makes the startup re-sync after a backup restore free.
 */
@Singleton
class AppIconSwitcher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun apply(icon: AppIcon) {
        val pm = context.packageManager
        setAliasEnabled(pm, icon, enabled = true)
        AppIcon.entries.filter { it != icon }.forEach { setAliasEnabled(pm, it, enabled = false) }
    }

    private fun setAliasEnabled(pm: PackageManager, icon: AppIcon, enabled: Boolean) {
        val component = ComponentName(context, "${context.packageName}.${icon.aliasSuffix}")
        val desired = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        if (pm.getComponentEnabledSetting(component) != desired) {
            pm.setComponentEnabledSetting(component, desired, PackageManager.DONT_KILL_APP)
        }
    }
}
