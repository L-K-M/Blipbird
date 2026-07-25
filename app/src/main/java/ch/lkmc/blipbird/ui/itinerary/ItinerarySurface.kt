package ch.lkmc.blipbird.ui.itinerary

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.lkmc.blipbird.ui.theme.LocalHighContrast

@Composable
fun itinerarySurface(alpha: Float = 0.45f): Color =
    if (LocalHighContrast.current) MaterialTheme.colorScheme.surface
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)

@Composable
fun Modifier.itineraryBorder(radius: Dp): Modifier =
    if (LocalHighContrast.current) {
        border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(radius))
    } else {
        this
    }

@Composable
fun contrastAware(color: Color, normalAlpha: Float): Color =
    if (LocalHighContrast.current) color else color.copy(alpha = normalAlpha)
