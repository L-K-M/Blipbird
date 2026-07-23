package ch.lkmc.blipbird.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay

/**
 * Solari split-flap text (REVIEW.md I4): when [text] changes, each character
 * cell folds away over its horizontal midline and the new character falls in,
 * cascading left to right like a departure-board row. The first composition
 * renders instantly, and with system animations off the text just swaps.
 */
@Composable
fun SplitFlapText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    val reduceMotion = rememberReducedMotion()
    // One spoken unit — TalkBack must announce "DELAYED", never per-flap letters.
    Row(modifier.clearAndSetSemantics { contentDescription = text }) {
        text.forEachIndexed { index, char ->
            key(index) {
                FlapCell(
                    target = char,
                    index = index,
                    color = color,
                    style = style,
                    fontWeight = fontWeight,
                    reduceMotion = reduceMotion,
                )
            }
        }
    }
}

private const val FLAP_HALF_MS = 140
private const val CASCADE_STEP_MS = 45L

@Composable
private fun FlapCell(
    target: Char,
    index: Int,
    color: Color,
    style: TextStyle,
    fontWeight: FontWeight?,
    reduceMotion: Boolean,
) {
    var shown by remember { mutableStateOf(target) }
    // rotationX of the visible flap: 0 = flat, → −90 folds the old character
    // away from the viewer, then the new one falls in from +90.
    val rotation = remember { Animatable(0f) }

    LaunchedEffect(target) {
        if (shown == target) {
            rotation.snapTo(0f)
            return@LaunchedEffect
        }
        if (reduceMotion) {
            shown = target
            rotation.snapTo(0f)
            return@LaunchedEffect
        }
        delay(index * CASCADE_STEP_MS)
        rotation.animateTo(-90f, tween(FLAP_HALF_MS, easing = FastOutLinearInEasing))
        shown = target
        rotation.snapTo(90f)
        rotation.animateTo(0f, tween(FLAP_HALF_MS, easing = LinearOutSlowInEasing))
    }

    Text(
        shown.toString(),
        color = color,
        style = style,
        fontWeight = fontWeight,
        modifier = Modifier.graphicsLayer {
            rotationX = rotation.value
            // Short throw so the fold reads as a mechanical flap, not a 3D card.
            cameraDistance = 6f * density
        },
    )
}
