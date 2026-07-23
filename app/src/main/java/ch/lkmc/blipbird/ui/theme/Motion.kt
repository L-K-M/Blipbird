package ch.lkmc.blipbird.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset

/**
 * Motion tokens (PLAN.md §10.2): durations, easings and spring specs as data,
 * not ad-hoc `tween(300)` calls. Spatial movement defaults to critically-damped
 * springs (settles fast, never oscillates); deterministic tweens are reserved
 * for fades and progress. Screen-level transitions are *named* specs so "row
 * tap → detail" is a design decision made once, here.
 *
 * Callers gate on [ch.lkmc.blipbird.ui.components.rememberReducedMotion] and
 * substitute [crossfade] (no spatial movement) when the user removed
 * animations. Component-embedded specs (the split-flap status cascade, the
 * bird refresh indicator) stay with their components; they already respect the
 * same reduced-motion gate.
 */
object BlipbirdMotion {

    // ---- durations (fades, color flips, other deterministic tweens)
    const val DURATION_SHORT_MS = 150
    // Reserved for the component-spec migration (REVIEW.md V2 remainder):
    // status-chip color flip, sheet present. Not yet wired up.
    const val DURATION_MEDIUM_MS = 300
    const val DURATION_LONG_MS = 450

    /**
     * M3 emphasized-decelerate: fast start, gentle landing. Reserved for the
     * component-spec migration (REVIEW.md V2 remainder).
     */
    val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Critically damped spatial spring for screen-scale movement. */
    fun screenSpring(): SpringSpec<IntOffset> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 380f,
        visibilityThreshold = IntOffset(1, 1),
    )

    // ---- named screen transitions (consumed by BlipbirdNav)

    /**
     * Forward navigation (row tap → detail, list → settings): the incoming
     * screen pushes in from the trailing edge and covers the outgoing one,
     * which parallax-slides a quarter width away — the iOS-style push.
     * [zIndex] is the incoming screen's stacking order; pass its navigation
     * depth so pushes cover and pops reveal.
     */
    fun push(zIndex: Float): ContentTransform = ContentTransform(
        targetContentEnter = slideInHorizontally(screenSpring()) { it },
        initialContentExit = slideOutHorizontally(screenSpring()) { -it / 4 },
        targetContentZIndex = zIndex,
    )

    /** Back navigation: the exact reverse of [push]. */
    fun pop(zIndex: Float): ContentTransform = ContentTransform(
        targetContentEnter = slideInHorizontally(screenSpring()) { -it / 4 },
        initialContentExit = slideOutHorizontally(screenSpring()) { it },
        targetContentZIndex = zIndex,
    )

    /** Reduced-motion substitute: a quick crossfade, no spatial movement. */
    fun crossfade(zIndex: Float): ContentTransform = ContentTransform(
        targetContentEnter = fadeIn(tween(DURATION_SHORT_MS)),
        initialContentExit = fadeOut(tween(DURATION_SHORT_MS)),
        targetContentZIndex = zIndex,
    )
}
