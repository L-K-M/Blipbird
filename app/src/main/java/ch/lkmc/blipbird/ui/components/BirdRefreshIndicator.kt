package ch.lkmc.blipbird.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Bird-flight pull-to-refresh (REVIEW.md I2): Blipbird's silhouette rides the
 * standard pull-to-refresh container. While the user drags, the wings flap
 * along the pull arc (direct manipulation — the pull position IS the flap
 * phase); on release the bird does a little swoop and keeps flapping on its
 * own until the refresh completes. With system animations off, the release
 * pose is a still glide.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirdRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReducedMotion()

    // Haptic tick the instant the pull passes the trigger threshold, so you feel
    // "far enough" without watching the bird (V3 — pull-to-refresh haptic). Fires
    // once per crossing (re-armed only after the pull relaxes below threshold);
    // the system honors the user's global haptic setting. Lives here so both the
    // list and detail pull-to-refresh get it from the one shared indicator.
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state) {
        var armed = false
        snapshotFlow { state.distanceFraction >= 1f }.collect { pastThreshold ->
            if (pastThreshold) {
                if (!armed) {
                    armed = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            } else {
                armed = false
            }
        }
    }

    // Release swoop: a quick dive-and-recover the moment the refresh starts.
    // Settled value is 1 so the bird sits level while idle or pulling.
    val swoop = remember { Animatable(1f) }
    LaunchedEffect(isRefreshing, reduceMotion) {
        if (isRefreshing && !reduceMotion) {
            swoop.snapTo(0f)
            swoop.animateTo(1f, tween(durationMillis = 650, easing = FastOutSlowInEasing))
        } else {
            swoop.snapTo(1f)
        }
    }

    val tint = MaterialTheme.colorScheme.primary
    // IndicatorBox supplies the stock container behavior — ride the drag
    // offset, scale in, elevated round surface — with the bird as content.
    PullToRefreshDefaults.IndicatorBox(
        state = state,
        isRefreshing = isRefreshing,
        modifier = modifier,
        containerColor = PullToRefreshDefaults.indicatorContainerColor,
    ) {
        if (isRefreshing) {
            // Autonomous cruise flap — composed only while refreshing so the
            // infinite animation never runs for an indicator parked offscreen.
            val flap = rememberInfiniteTransition(label = "bird-flap")
            val phase by flap.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(520, easing = LinearEasing)),
                label = "bird-flap-phase",
            )
            BirdCanvas(
                wingLift = { if (reduceMotion) 0.3f else sin(phase * 2f * PI.toFloat()) },
                dip = { sin(PI.toFloat() * (1f - swoop.value)) },
                tint = tint,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            // Flap along the pull arc: ~1.5 wingbeats from rest to threshold,
            // amplitude growing with the pull so the bird wakes up gradually.
            BirdCanvas(
                wingLift = {
                    val pull = state.distanceFraction
                    sin(pull * 3f * PI.toFloat()) * pull.coerceAtMost(1f)
                },
                dip = { 0f },
                tint = tint,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** Lambdas defer the animated reads to the draw phase — dragging redraws, never recomposes. */
@Composable
private fun BirdCanvas(
    wingLift: () -> Float,
    dip: () -> Float,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(26.dp)) {
        val s = size.minDimension
        val dipNow = dip()
        translate(top = dipNow * s * 0.30f) {
            rotate(degrees = -10f * dipNow, pivot = center) {
                drawBird(wingLift(), tint)
            }
        }
    }
}

/**
 * Stylized front-view swift: head + body + forked tail on the vertical axis,
 * two tapered wings whose tips ride [wingLift] (−1 downstroke … +1 upstroke).
 */
private fun DrawScope.drawBird(wingLift: Float, color: Color) {
    val s = size.minDimension
    val cx = s / 2f
    val cy = s * 0.52f

    // head + body
    drawCircle(color, radius = s * 0.085f, center = Offset(cx, cy - s * 0.21f))
    drawCircle(color, radius = s * 0.125f, center = Offset(cx, cy))

    // forked tail
    val tail = Path().apply {
        moveTo(cx - s * 0.07f, cy + s * 0.08f)
        lineTo(cx - s * 0.10f, cy + s * 0.34f)
        lineTo(cx, cy + s * 0.24f)
        lineTo(cx + s * 0.10f, cy + s * 0.34f)
        lineTo(cx + s * 0.07f, cy + s * 0.08f)
        close()
    }
    drawPath(tail, color)

    // wings — mirrored, tips lifted by the flap phase
    for (dir in intArrayOf(-1, 1)) {
        val tipY = cy - s * 0.05f - wingLift * s * 0.28f
        val wing = Path().apply {
            moveTo(cx + dir * s * 0.06f, cy - s * 0.10f)
            quadraticTo(
                cx + dir * s * 0.26f, cy - s * 0.16f - wingLift * s * 0.16f,
                cx + dir * s * 0.46f, tipY,
            )
            quadraticTo(
                cx + dir * s * 0.25f, cy + s * 0.02f - wingLift * s * 0.06f,
                cx + dir * s * 0.07f, cy + s * 0.06f,
            )
            close()
        }
        drawPath(wing, color)
    }
}
