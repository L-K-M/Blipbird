package ch.lkmc.blipbird.core.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Serializes user lifecycle, identity, refresh, and platform work by stable ID. */
@Singleton
class FlightOperationLocks @Inject constructor() {
    private val flightLocks = ConcurrentHashMap<Long, Mutex>()
    private val itineraryLocks = ConcurrentHashMap<Long, Mutex>()
    private val creationLocks = ConcurrentHashMap<String, Mutex>()
    private val itineraryMutationLock = Mutex()

    /**
     * Interns one [Mutex] per key. Must be `computeIfAbsent`, not the stdlib
     * `getOrPut`: the latter is a non-atomic get-then-put, so two callers racing
     * on the first touch of a key each walk away with their *own* Mutex and the
     * mutual exclusion this class exists to provide silently doesn't happen.
     */
    private fun <K : Any> ConcurrentHashMap<K, Mutex>.lockFor(key: K): Mutex =
        computeIfAbsent(key) { Mutex() }

    suspend fun <T> withFlight(id: Long, block: suspend () -> T): T =
        flightLocks.lockFor(id).withLock { block() }

    suspend fun <T> withFlights(ids: Collection<Long>, block: suspend () -> T): T {
        val ordered = ids.distinct().sorted()
        suspend fun acquire(index: Int): T = if (index == ordered.size) {
            block()
        } else {
            flightLocks.lockFor(ordered[index]).withLock { acquire(index + 1) }
        }
        return acquire(0)
    }

    suspend fun <T> withItinerary(id: Long, block: suspend () -> T): T =
        itineraryMutationLock.withLock {
            itineraryLocks.lockFor(id).withLock { block() }
        }

    suspend fun <T> withCreationRequest(id: String, block: suspend () -> T): T =
        itineraryMutationLock.withLock {
            creationLocks.lockFor(id).withLock { block() }
        }

    suspend fun <T> withAllItineraries(block: suspend () -> T): T =
        itineraryMutationLock.withLock { block() }
}
