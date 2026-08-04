package ch.lkmc.blipbird.ui.itinerary

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The measurements the journey spine (`docs/ITINERARY_PROPOSAL.md` §7.6) is laid
 * out on. Shared by the screen, the leg cards and the transition rows.
 *
 * The spine has no left rail: a flight card runs the full width it is given, and
 * the transition below it hangs off a short connector inset from the left edge.
 * A rail would have cost every card the same gutter on exactly the screens
 * (§7.16) that have the least width to give.
 */

/**
 * Below this every itinerary surface trades gutter width for content width
 * (§7.16) — the spine, the composer and the group picker alike. One constant, so
 * they cannot disagree about what "narrow" means.
 */
internal val COMPACT_WIDTH = 400.dp

internal data class SpineMetrics(
    /** Padding around the whole spine. */
    val screenPadding: Dp,
    /** Padding inside each card, and above and below a transition's text. */
    val cardPadding: Dp,
    /** How far the transition's connector sits in from the card's left edge. */
    val connectorInset: Dp,
    /** Gap between that connector and the text hanging off it. */
    val connectorGap: Dp,
    val compact: Boolean,
)

internal fun spineMetrics(compact: Boolean): SpineMetrics = if (compact) {
    SpineMetrics(
        screenPadding = 14.dp,
        cardPadding = 16.dp,
        connectorInset = 20.dp,
        connectorGap = 14.dp,
        compact = true,
    )
} else {
    SpineMetrics(
        screenPadding = 18.dp,
        cardPadding = 20.dp,
        connectorInset = 26.dp,
        connectorGap = 16.dp,
        compact = false,
    )
}
