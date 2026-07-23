package ch.lkmc.blipbird.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import ch.lkmc.blipbird.ui.components.contrastRatio
import kotlin.math.abs

/**
 * Seed-color scheme engine: any user-picked color becomes a full light + dark
 * [ColorScheme]. Accent slots are derived in HSL from the seed's hue and
 * saturation; neutral surfaces stay Material's crafted defaults so a wild seed
 * can't wreck legibility, and light-mode accents are darkened until they clear
 * WCAG 4.5:1 against white (a lemon-yellow seed becomes a readable olive
 * rather than an unreadable button).
 */
internal data class Hsl(val h: Float, val s: Float, val l: Float)

internal fun Color.toHsl(): Hsl {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val l = (max + min) / 2f
    if (max == min) return Hsl(0f, 0f, l)
    val d = max - min
    val s = d / (1f - abs(2f * l - 1f))
    val h = when (max) {
        red -> ((green - blue) / d + if (green < blue) 6f else 0f)
        green -> (blue - red) / d + 2f
        else -> (red - green) / d + 4f
    } * 60f
    return Hsl(h % 360f, s.coerceIn(0f, 1f), l)
}

internal fun hslColor(h: Float, s: Float, l: Float): Color {
    val hue = ((h % 360f) + 360f) % 360f
    val sat = s.coerceIn(0f, 1f)
    val lig = l.coerceIn(0f, 1f)
    val c = (1f - abs(2f * lig - 1f)) * sat
    val x = c * (1f - abs((hue / 60f) % 2f - 1f))
    val m = lig - c / 2f
    val (r, g, b) = when {
        hue < 60f -> Triple(c, x, 0f)
        hue < 120f -> Triple(x, c, 0f)
        hue < 180f -> Triple(0f, c, x)
        hue < 240f -> Triple(0f, x, c)
        hue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m)
}

/** HSV decomposition for the color-picker UI (hue 0..360, sat/value 0..1). */
internal fun Color.toHsv(): Triple<Float, Float, Float> {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val d = max - min
    val h = when {
        d == 0f -> 0f
        max == red -> ((green - blue) / d + if (green < blue) 6f else 0f) * 60f
        max == green -> ((blue - red) / d + 2f) * 60f
        else -> ((red - green) / d + 4f) * 60f
    }
    val s = if (max == 0f) 0f else d / max
    return Triple(h % 360f, s, max)
}

/** Lowers lightness until [color] clears [min] contrast against [background]. */
internal fun darkenUntilContrast(color: Color, background: Color, min: Double = 4.5): Color {
    var hsl = color.toHsl()
    var candidate = color
    var guard = 0
    while (contrastRatio(candidate, background) < min && hsl.l > 0.03f && guard++ < 50) {
        hsl = hsl.copy(l = hsl.l - 0.02f)
        candidate = hslColor(hsl.h, hsl.s, hsl.l)
    }
    return candidate
}

internal fun accentColorScheme(seedArgb: Long, dark: Boolean): ColorScheme {
    val (h, s, _) = Color(seedArgb).toHsl()
    return if (dark) {
        val primary = hslColor(h, (s * 0.85f).coerceAtMost(0.9f), 0.76f)
        darkColorScheme(
            primary = primary,
            onPrimary = hslColor(h, s * 0.9f, 0.14f),
            primaryContainer = hslColor(h, s * 0.6f, 0.27f),
            onPrimaryContainer = hslColor(h, s * 0.65f, 0.88f),
            secondary = hslColor(h, s * 0.4f, 0.72f),
            onSecondary = hslColor(h, s * 0.5f, 0.14f),
            secondaryContainer = hslColor(h, s * 0.35f, 0.24f),
            onSecondaryContainer = hslColor(h, s * 0.35f, 0.86f),
            tertiary = hslColor(h + 50f, s * 0.55f, 0.75f),
            onTertiary = hslColor(h + 50f, s * 0.6f, 0.14f),
            surfaceTint = primary,
        )
    } else {
        val white = Color.White
        lightColorScheme(
            primary = darkenUntilContrast(hslColor(h, s.coerceAtMost(0.95f), 0.42f), white),
            onPrimary = white,
            primaryContainer = hslColor(h, s * 0.55f, 0.90f),
            onPrimaryContainer = hslColor(h, s * 0.8f, 0.14f),
            secondary = darkenUntilContrast(hslColor(h, s * 0.45f, 0.38f), white),
            onSecondary = white,
            secondaryContainer = hslColor(h, s * 0.4f, 0.90f),
            onSecondaryContainer = hslColor(h, s * 0.6f, 0.14f),
            tertiary = darkenUntilContrast(hslColor(h + 50f, s * 0.5f, 0.40f), white),
            onTertiary = white,
            surface = Color(0xFFFAF9FD),
            background = Color(0xFFFAF9FD),
        )
    }
}
