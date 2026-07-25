package ch.lkmc.blipbird

import ch.lkmc.blipbird.core.data.FlightOperationLocks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun secondHolderOfTheSameFlightWaitsForTheFirst() = runTest {
        val locks = FlightOperationLocks()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var secondEntered = false

        val first = launch {
            locks.withFlight(7) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch { locks.withFlight(7) { secondEntered = true } }

        yield()
        assertFalse(secondEntered)
        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertTrue(secondEntered)
    }

    /**
     * Concurrent *first* touches of one key must intern the same Mutex. With a
     * non-atomic get-then-put the racers get separate Mutexes, the critical
     * section runs in parallel, and these unsynchronized increments lose updates.
     * Correct interning makes the count exact, so this cannot fail spuriously.
     */
    @Test
    fun concurrentFirstTouchStillSerializes() = runTest {
        val locks = FlightOperationLocks()
        var unguarded = 0
        withContext(Dispatchers.Default) {
            repeat(200) { id ->
                coroutineScope {
                    // A fresh id each round, so every round races the first touch.
                    (0 until 8).map {
                        async { locks.withFlight(id.toLong()) { unguarded++ } }
                    }.awaitAll()
                }
            }
        }
        assertEquals(200 * 8, unguarded)
    }
}
