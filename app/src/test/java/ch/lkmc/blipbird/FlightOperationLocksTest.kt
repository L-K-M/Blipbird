package ch.lkmc.blipbird

import ch.lkmc.blipbird.core.data.FlightOperationLocks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlightOperationLocksTest {
    @Test
    fun itineraryCreationAndExistingMutationShareAggregateLock() = runTest {
        val locks = FlightOperationLocks()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var creationEntered = false

        val existingMutation = launch {
            locks.withItinerary(1) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val creation = launch {
            locks.withCreationRequest("draft") { creationEntered = true }
        }

        yield()
        assertFalse(creationEntered)
        releaseFirst.complete(Unit)
        existingMutation.join()
        creation.join()
        assertTrue(creationEntered)
    }
}
