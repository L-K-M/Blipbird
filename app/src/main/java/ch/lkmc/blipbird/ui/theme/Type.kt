@file:OptIn(ExperimentalTextApi::class)

package ch.lkmc.blipbird.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import ch.lkmc.blipbird.R

/**
 * Type system (V1 / PLAN §10.2): Inter everywhere — the closest open face to
 * SF Pro, so the app reads "expensive iOS" from the display countdowns down to
 * the labels. Bundled as one OFL variable font wired through weight-specific
 * FontVariation settings. Every style carries `"tnum"` (tabular figures) so
 * ticking digits stay put instead of wiggling. (Space Grotesk was tried for
 * display text first and dropped by owner preference, July 2026.)
 */
private fun variable(resId: Int, weight: FontWeight) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Inter = FontFamily(
    variable(R.font.inter, FontWeight.Normal),
    variable(R.font.inter, FontWeight.Medium),
    variable(R.font.inter, FontWeight.SemiBold),
    variable(R.font.inter, FontWeight.Bold),
)

private const val TABULAR = "tnum"

private fun TextStyle.inter() = copy(fontFamily = Inter, fontFeatureSettings = TABULAR)

private val defaults = Typography()

val BlipbirdTypography = Typography(
    displayLarge = defaults.displayLarge.inter(),
    displayMedium = defaults.displayMedium.inter(),
    displaySmall = defaults.displaySmall.inter(),
    headlineLarge = defaults.headlineLarge.inter(),
    headlineMedium = defaults.headlineMedium.inter(),
    headlineSmall = defaults.headlineSmall.inter(),
    titleLarge = defaults.titleLarge.inter(),
    titleMedium = defaults.titleMedium.inter(),
    titleSmall = defaults.titleSmall.inter(),
    bodyLarge = defaults.bodyLarge.inter(),
    bodyMedium = defaults.bodyMedium.inter(),
    bodySmall = defaults.bodySmall.inter(),
    labelLarge = defaults.labelLarge.inter(),
    labelMedium = defaults.labelMedium.inter(),
    labelSmall = defaults.labelSmall.inter(),
)
