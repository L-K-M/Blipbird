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
 * Type system (V1 / PLAN §10.2, owner decision July 2026): Space Grotesk for
 * display/headline/large-title text — where the big countdowns and airport
 * codes live — and Inter for body/label text. Both are bundled variable fonts
 * (OFL). Every style carries `"tnum"` (tabular figures) so ticking digits stay
 * put instead of wiggling as their widths change.
 */
private fun variable(resId: Int, weight: FontWeight) = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val SpaceGrotesk = FontFamily(
    variable(R.font.space_grotesk, FontWeight.Normal),
    variable(R.font.space_grotesk, FontWeight.Medium),
    variable(R.font.space_grotesk, FontWeight.SemiBold),
    variable(R.font.space_grotesk, FontWeight.Bold),
)

val Inter = FontFamily(
    variable(R.font.inter, FontWeight.Normal),
    variable(R.font.inter, FontWeight.Medium),
    variable(R.font.inter, FontWeight.SemiBold),
    variable(R.font.inter, FontWeight.Bold),
)

private const val TABULAR = "tnum"

private fun TextStyle.grotesk() = copy(fontFamily = SpaceGrotesk, fontFeatureSettings = TABULAR)
private fun TextStyle.inter() = copy(fontFamily = Inter, fontFeatureSettings = TABULAR)

private val defaults = Typography()

val BlipbirdTypography = Typography(
    displayLarge = defaults.displayLarge.grotesk(),
    displayMedium = defaults.displayMedium.grotesk(),
    displaySmall = defaults.displaySmall.grotesk(),
    headlineLarge = defaults.headlineLarge.grotesk(),
    headlineMedium = defaults.headlineMedium.grotesk(),
    headlineSmall = defaults.headlineSmall.grotesk(),
    titleLarge = defaults.titleLarge.grotesk(),
    titleMedium = defaults.titleMedium.inter(),
    titleSmall = defaults.titleSmall.inter(),
    bodyLarge = defaults.bodyLarge.inter(),
    bodyMedium = defaults.bodyMedium.inter(),
    bodySmall = defaults.bodySmall.inter(),
    labelLarge = defaults.labelLarge.inter(),
    labelMedium = defaults.labelMedium.inter(),
    labelSmall = defaults.labelSmall.inter(),
)
