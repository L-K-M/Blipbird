package ch.lkmc.blipbird

import ch.lkmc.blipbird.core.data.requireValidGraph
import ch.lkmc.blipbird.core.database.ItineraryLegEntity
import ch.lkmc.blipbird.core.database.ItineraryTransitionEntity
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ItineraryGraphTest {
    private val legs = listOf(
        ItineraryLegEntity(id = 10, itineraryId = 1, trackedFlightId = 100, ordinal = 0),
        ItineraryLegEntity(id = 11, itineraryId = 1, trackedFlightId = 101, ordinal = 1),
        ItineraryLegEntity(id = 12, itineraryId = 1, trackedFlightId = 102, ordinal = 2),
    )
    private val transitions = listOf(
        transition(20, 10, 11),
        transition(21, 11, 12),
    )

    @Test
    fun acceptsDenseLinearGraph() {
        requireValidGraph(legs, transitions)
    }

    @Test
    fun rejectsMissingOrNonAdjacentEdges() {
        assertFailsWith<IllegalArgumentException> { requireValidGraph(legs, transitions.take(1)) }
        assertFailsWith<IllegalArgumentException> {
            requireValidGraph(legs, listOf(transition(20, 10, 12), transition(21, 11, 12)))
        }
    }

    @Test
    fun rejectsSparseOrdinals() {
        assertFailsWith<IllegalArgumentException> {
            requireValidGraph(legs.mapIndexed { index, leg -> leg.copy(ordinal = index * 2) }, transitions)
        }
    }

    private fun transition(id: Long, inbound: Long, outbound: Long) =
        ItineraryTransitionEntity(
            id = id,
            itineraryId = 1,
            inboundLegId = inbound,
            outboundLegId = outbound,
            updatedAt = 1,
        )
}
