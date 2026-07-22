package ch.lkmc.blipbird.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

/**
 * The signature route progress bar: rounded track, gradient fill, endpoint dots
 * and a little plane at the head of the flown portion. Replaces the M3
 * LinearProgressIndicator (whose stop-indicator dot reads as a broken slider).
 */
@Composable
fun FlightProgressBar(
    progress: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    planeVisible: Boolean = true,
) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), animationSpec = tween(700), label = "flightProgress")

    Canvas(modifier.fillMaxWidth().height(18.dp)) {
        val w = size.width
        val cy = size.height / 2
        val inset = 9.dp.toPx()
        val x0 = inset
        val x1 = w - inset
        val stroke = 3.dp.toPx()

        // track
        drawLine(trackColor, Offset(x0, cy), Offset(x1, cy), stroke, cap = StrokeCap.Round)

        // flown portion with gradient
        val head = x0 + (x1 - x0) * p
        if (p > 0.01f) {
            drawLine(
                Brush.horizontalGradient(listOf(color.copy(alpha = 0.35f), color), startX = x0, endX = head),
                Offset(x0, cy), Offset(head, cy), stroke + 1.dp.toPx(), cap = StrokeCap.Round,
            )
        }

        // endpoint dots
        drawCircle(color, radius = 3.5.dp.toPx(), center = Offset(x0, cy))
        drawCircle(trackColor, radius = 3.5.dp.toPx(), center = Offset(x1, cy))
        drawCircle(Color.Transparent, radius = 2.dp.toPx(), center = Offset(x1, cy))

        // plane at the head, pointing along the track
        if (planeVisible && p in 0.01f..0.995f) {
            rotate(degrees = 90f, pivot = Offset(head, cy)) {
                val s = 7.dp.toPx()
                val plane = Path().apply {
                    moveTo(head, cy - s)               // nose (pre-rotation: up)
                    lineTo(head + s * 0.72f, cy + s * 0.55f)
                    lineTo(head, cy + s * 0.22f)
                    lineTo(head - s * 0.72f, cy + s * 0.55f)
                    close()
                }
                drawPath(plane, color)
            }
        }
    }
}
