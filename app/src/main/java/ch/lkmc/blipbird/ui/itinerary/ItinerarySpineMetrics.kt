package ch.lkmc.blipbird.ui.itinerary

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The measurements the journey spine (`docs/ITINERARY_PROPOSAL.md` §7.6) is laid
 * out on. Shared by the screen, the leg rows and the transition rows so a leg
 * card and the transition card below it line up on the same gutter.
 */

internal val COMPACT_WIDTH = 400.dp

internal data class SpineMetrics(
    /** Padding around the whole spine. */
    val screenPadding: Dp,
    /** Width of the left gutter that carries the leg number and the connector. */
    val gutter: Dp,
    /** Gap between the gutter and the cards. */
    val railGap: Dp,
    /** Padding inside each card. */
    val cardPadding: Dp,
    val compact: Boolean,
)

internal fun spineMetrics(compact: Boolean): SpineMetrics = if (compact) {
    SpineMetrics(screenPadding = 12.dp, gutter = 30.dp, railGap = 8.dp, cardPadding = 13.dp, compact = true)
} else {
    SpineMetrics(screenPadding = 16.dp, gutter = 40.dp, railGap = 12.dp, cardPadding = 16.dp, compact = false)
}
