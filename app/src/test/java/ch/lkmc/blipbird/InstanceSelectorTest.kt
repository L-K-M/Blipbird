package ch.lkmc.blipbird

import ch.lkmc.blipbird.core.model.AirportRef
import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.core.model.MovementTimes
import ch.lkmc.blipbird.core.model.StatusSnapshot
import ch.lkmc.blipbird.domain.InstanceSelector
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression tests for the "departs in 22 h but it already left" bug: a dateless
 * lookup returning yesterday's active instance plus tomorrow's must pick the
 * active one.
 */
class InstanceSelectorTest {

    private val now: Instant = Instant.parse("2026-07-22T20:16:00Z")

    private fun instance(
        schedDep: Instant,
        schedArr: Instant = schedDep.plus(Duration.ofHours(11)),
        actDep: Instant? = null,
        actArr: Instant? = null,
        codeshareOf: String? = null,
    ) = StatusSnapshot(
        provider = "test",
        fetchedAt = now,
        status = FlightStatus.SCHEDULED,
        departure = AirportRef("ZBAA", "PEK", tz = "Asia/Shanghai"),
        arrival = AirportRef("LSGG", "GVA", tz = "Europe/Zurich"),
        depTimes = MovementTimes(scheduled = schedDep, actual = actDep),
        arrTimes = MovementTimes(scheduled = schedArr, actual = actArr),
        codeshareOf = codeshareOf,
    )

    @Test fun `confirmed airborne wins over tomorrow's instance`() {
        val airborne = instance(schedDep = now.minus(Duration.ofHours(1)), actDep = now.minus(Duration.ofMinutes(55)))
        val tomorrow = instance(schedDep = now.plus(Duration.ofHours(23)))
        assertEquals(airborne, InstanceSelector.select(listOf(tomorrow, airborne), now))
    }

    @Test fun `schedule-wise in-flight wins without actuals`() {
        // The exact user scenario: departed ~1 h ago per schedule, no actual
        // timestamps in the feed, next instance ~23 h out. Must pick the airborne one.
        val current = instance(schedDep = now.minus(Duration.ofHours(1)))
        val tomorrow = instance(schedDep = now.plus(Duration.ofHours(23)))
        assertEquals(current, InstanceSelector.select(listOf(tomorrow, current), now))
    }

    @Test fun `recently landed beats tomorrow`() {
        val landed = instance(
            schedDep = now.minus(Duration.ofHours(12)),
            schedArr = now.minus(Duration.ofHours(1)),
            actDep = now.minus(Duration.ofHours(12)),
            actArr = now.minus(Duration.ofHours(1)),
        )
        val tomorrow = instance(schedDep = now.plus(Duration.ofHours(12)))
        assertEquals(landed, InstanceSelector.select(listOf(tomorrow, landed), now))
    }

    @Test fun `landed long ago loses to upcoming`() {
        val oldLanded = instance(
            schedDep = now.minus(Duration.ofDays(1)),
            schedArr = now.minus(Duration.ofHours(13)),
            actDep = now.minus(Duration.ofDays(1)),
            actArr = now.minus(Duration.ofHours(13)),
        )
        val upcoming = instance(schedDep = now.plus(Duration.ofHours(11)))
        assertEquals(upcoming, InstanceSelector.select(listOf(oldLanded, upcoming), now))
    }

    @Test fun `earliest upcoming wins among futures`() {
        val later = instance(schedDep = now.plus(Duration.ofHours(30)))
        val sooner = instance(schedDep = now.plus(Duration.ofHours(6)))
        assertEquals(sooner, InstanceSelector.select(listOf(later, sooner), now))
    }

    @Test fun `operating record preferred over codeshare`() {
        val marketing = instance(schedDep = now.plus(Duration.ofHours(6)), codeshareOf = "LX1234")
        val operating = instance(schedDep = now.plus(Duration.ofHours(6)))
        assertEquals(operating, InstanceSelector.select(listOf(marketing, operating), now))
    }

    @Test fun `about to board (departure minus 20m) counts as upcoming not past`() {
        val boarding = instance(schedDep = now.plus(Duration.ofMinutes(20)))
        assertEquals(boarding, InstanceSelector.select(listOf(boarding), now))
    }

    @Test fun `empty list yields null`() {
        assertNull(InstanceSelector.select(emptyList(), now))
    }
}
