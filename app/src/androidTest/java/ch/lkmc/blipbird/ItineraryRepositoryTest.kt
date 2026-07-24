package ch.lkmc.blipbird

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.blipbird.core.data.FlightOperationLocks
import ch.lkmc.blipbird.core.data.IdentityResolver
import ch.lkmc.blipbird.core.data.ItineraryRepository
import ch.lkmc.blipbird.core.data.ItineraryValidationException
import ch.lkmc.blipbird.core.database.OpsDatabase
import ch.lkmc.blipbird.core.database.UserDatabase
import ch.lkmc.blipbird.core.model.ItineraryDraftLegInput
import ch.lkmc.blipbird.core.model.SaveItineraryRequest
import ch.lkmc.blipbird.core.model.TransitionIntent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ItineraryRepositoryTest {
    private lateinit var userDb: UserDatabase
    private lateinit var opsDb: OpsDatabase
    private lateinit var repository: ItineraryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        userDb = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).build()
        opsDb = Room.inMemoryDatabaseBuilder(context, OpsDatabase::class.java).build()
        repository = ItineraryRepository(
            userDb = userDb,
            identity = IdentityResolver(opsDb.referenceDao()),
            operationLocks = FlightOperationLocks(),
        )
    }

    @After
    fun tearDown() {
        userDb.close()
        opsDb.close()
    }

    @Test
    fun staleDeleteIsNotAcknowledgedAndLastPairDissolves() = runBlocking {
        val created = repository.save(
            SaveItineraryRequest(
                creationRequestId = "create-1",
                itineraryId = null,
                name = "Trip",
                legs = listOf(newLeg("LX100"), newLeg("LX200"), newLeg("LX300")),
            )
        )
        val loaded = repository.itinerary(created.itineraryId)
        assertNotNull(loaded)
        val original = checkNotNull(loaded)
        val first = original.legs[0].flight.id
        val second = original.legs[1].flight.id
        val third = original.legs[2].flight.id

        val keepRemoval = SaveItineraryRequest(
            creationRequestId = "edit-keep",
            itineraryId = original.id,
            name = original.name,
            legs = listOf(existingLeg(first), existingLeg(second)),
        )
        repository.save(keepRemoval)
        assertNotNull(userDb.trackedFlightDao().byId(third))

        try {
            repository.save(
                keepRemoval.copy(
                    creationRequestId = "edit-delete-stale",
                    deleteRemovedFlightIds = listOf(third),
                )
            )
            fail("A stale destructive draft must not report success")
        } catch (_: ItineraryValidationException) {
        }
        assertNotNull(userDb.trackedFlightDao().byId(third))

        val dissolved = repository.save(
            SaveItineraryRequest(
                creationRequestId = "edit-dissolve",
                itineraryId = original.id,
                name = original.name,
                legs = listOf(existingLeg(first)),
            )
        )
        assertTrue(dissolved.dissolved)
        assertNull(repository.itinerary(original.id))
        assertNotNull(userDb.trackedFlightDao().byId(first))
        assertNotNull(userDb.trackedFlightDao().byId(second))
        assertNotNull(userDb.trackedFlightDao().byId(third))
    }

    private fun newLeg(designator: String) = ItineraryDraftLegInput(
        existingFlightId = null,
        designator = designator,
        departureLocalDate = LocalDate.of(2026, 9, 18),
        transitionAfter = TransitionIntent.UNKNOWN,
    )

    private fun existingLeg(id: Long) = ItineraryDraftLegInput(
        existingFlightId = id,
        designator = "",
        departureLocalDate = LocalDate.of(2026, 9, 18),
        transitionAfter = TransitionIntent.UNKNOWN,
    )
}
