package ch.lkmc.blipbird.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import ch.lkmc.blipbird.core.datastore.AppTheme

/**
 * Theme engine (PLAN.md §10): each [AppTheme] maps to ColorScheme pairs plus
 * [ExtendedColors] for app-specific roles. Icon-derived seeds: radar cyan
 * 0xFF19D3F3 on deep blue 0xFF0B3FA8.
 */
@Immutable
data class ExtendedColors(
    val statusOnTime: Color,
    val statusDelayed: Color,
    val statusCancelled: Color,
    val statusEnRoute: Color,
    val statusNeutral: Color,
    val ribbonDay: Color,
    val ribbonDusk: Color,
    val ribbonNight: Color,
    val ribbonSunrise: Color,
    val ribbonSunset: Color,
    val ribbonAircraft: Color,
    val routeLine: Color,
    val mapStyleUrl: String,
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        statusOnTime = Color(0xFF2E7D32),
        statusDelayed = Color(0xFFB26A00),
        statusCancelled = Color(0xFFC62828),
        statusEnRoute = Color(0xFF1667D9),
        statusNeutral = Color(0xFF5F6368),
        ribbonDay = Color(0xFF8FD3FF),
        ribbonDusk = Color(0xFFFF9E5E),
        ribbonNight = Color(0xFF0A1633),
        ribbonSunrise = Color(0xFFFFD54F),
        ribbonSunset = Color(0xFFFF8A65),
        ribbonAircraft = Color(0xFF1667D9),
        routeLine = Color(0xFF19D3F3),
        mapStyleUrl = "https://tiles.openfreemap.org/styles/liberty",
    )
}

private val DaylightLight = lightColorScheme(
    primary = Color(0xFF1667D9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF00696E),
    tertiary = Color(0xFF6F5675),
    surface = Color(0xFFFAF9FD),
    background = Color(0xFFFAF9FD),
)

private val DaylightDark = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF00468C),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFF4DD9E1),
    tertiary = Color(0xFFDCBCE1),
)

private val CockpitScheme = darkColorScheme(
    primary = Color(0xFF53F2A0),            // avionics green
    onPrimary = Color(0xFF00210F),
    primaryContainer = Color(0xFF0A2818),
    onPrimaryContainer = Color(0xFF53F2A0),
    secondary = Color(0xFFFFB454),          // amber
    onSecondary = Color(0xFF2A1800),
    surface = Color(0xFF050807),            // near-black AMOLED
    background = Color(0xFF05050A),         // avoids penTile smear on scroll
    onSurface = Color(0xFFCFE8D8),
    onBackground = Color(0xFFCFE8D8),
    surfaceVariant = Color(0xFF10251A),
    onSurfaceVariant = Color(0xFF9BC7AB),
)

private val HighContrastLight = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF222222),
    onPrimaryContainer = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    onBackground = Color(0xFF000000),
)

private val HighContrastDark = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFFDDDDDD),
    onPrimaryContainer = Color(0xFF000000),
    surface = Color(0xFF000000),
    background = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    onBackground = Color(0xFFFFFFFF),
)

@Composable
fun BlipbirdTheme(
    theme: AppTheme,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = when (theme) {
        AppTheme.DAYLIGHT -> if (darkTheme) DaylightDark else DaylightLight
        AppTheme.DAYLIGHT_DYNAMIC ->
            if (Build.VERSION.SDK_INT >= 31) {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else if (darkTheme) DaylightDark else DaylightLight
        AppTheme.COCKPIT -> CockpitScheme
        AppTheme.HIGH_CONTRAST -> if (darkTheme) HighContrastDark else HighContrastLight
    }

    val extended = when (theme) {
        AppTheme.COCKPIT -> ExtendedColors(
            statusOnTime = Color(0xFF53F2A0),
            statusDelayed = Color(0xFFFFB454),
            statusCancelled = Color(0xFFFF6B6B),
            statusEnRoute = Color(0xFF53D2F2),
            statusNeutral = Color(0xFF7BA38C),
            ribbonDay = Color(0xFF1E5F8A),
            ribbonDusk = Color(0xFF8A5A1E),
            ribbonNight = Color(0xFF03110A),
            ribbonSunrise = Color(0xFFFFB454),
            ribbonSunset = Color(0xFFB5621E),
            ribbonAircraft = Color(0xFF53F2A0),
            routeLine = Color(0xFF53F2A0),
            mapStyleUrl = "https://tiles.openfreemap.org/styles/dark",
        )
        AppTheme.HIGH_CONTRAST -> ExtendedColors(
            statusOnTime = if (darkTheme) Color(0xFF7CFF9B) else Color(0xFF005E20),
            statusDelayed = if (darkTheme) Color(0xFFFFD37C) else Color(0xFF7A4A00),
            statusCancelled = if (darkTheme) Color(0xFFFF8C8C) else Color(0xFF9E0000),
            statusEnRoute = if (darkTheme) Color(0xFF9CC7FF) else Color(0xFF003FA3),
            statusNeutral = if (darkTheme) Color(0xFFCCCCCC) else Color(0xFF333333),
            ribbonDay = if (darkTheme) Color(0xFFB7DCFF) else Color(0xFF9CC7EA),
            ribbonDusk = Color(0xFFE08840),
            ribbonNight = Color(0xFF000000),
            ribbonSunrise = if (darkTheme) Color.White else Color(0xFF333333),
            ribbonSunset = if (darkTheme) Color.White else Color(0xFF333333),
            ribbonAircraft = if (darkTheme) Color.White else Color.Black,
            routeLine = if (darkTheme) Color.White else Color.Black,
            mapStyleUrl = if (darkTheme)
                "https://tiles.openfreemap.org/styles/dark"
            else "https://tiles.openfreemap.org/styles/positron",
        )
        else -> ExtendedColors(
            statusOnTime = Color(0xFF2E7D32),
            statusDelayed = Color(0xFFB26A00),
            statusCancelled = Color(0xFFC62828),
            statusEnRoute = Color(0xFF1667D9),
            statusNeutral = Color(0xFF5F6368),
            ribbonDay = Color(0xFF8FD3FF),
            ribbonDusk = Color(0xFFFF9E5E),
            ribbonNight = Color(0xFF0A1633),
            ribbonSunrise = Color(0xFFFFD54F),
            ribbonSunset = Color(0xFFFF8A65),
            ribbonAircraft = Color(0xFF1667D9),
            routeLine = Color(0xFF19D3F3),
            mapStyleUrl = if (darkTheme)
                "https://tiles.openfreemap.org/styles/dark"
            else "https://tiles.openfreemap.org/styles/liberty",
        )
    }

    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
