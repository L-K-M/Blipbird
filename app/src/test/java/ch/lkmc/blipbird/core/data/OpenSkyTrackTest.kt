package ch.lkmc.blipbird.core.data

import ch.lkmc.blipbird.core.model.AirportRef
import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.core.model.MovementTimes
import ch.lkmc.blipbird.core.model.StatusSnapshot
import ch.lkmc.blipbird.core.network.parseOpenSkyWaypoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class OpenSkyTrackTest {

    private fun waypoint(json: String) = parseOpenSkyWaypoint(Json.parseToJsonElement(json).jsonArray)

    @Test
    fun `waypoint parses full array`() {
        val w = waypoint("[1750000000, 46.2, 6.1, 10668.0, 45.5, false]")!!
        assertEquals(1_750_000_000L, w.timeEpochSec)
        assertEquals(46.2, w.lat, 1e-9)
        assertEquals(6.1, w.lon, 1e-9)
        assertEquals(10_668.0, w.baroAltitudeM!!, 1e-9)
        assertEquals(45.5, w.trackDeg!!, 1e-9)
        assertFalse(w.onGround)
    }

    @Test
    fun `waypoint tolerates nulls in optional slots`() {
        val w = waypoint("[1750000000, 46.2, 6.1, null, null, true]")!!
        assertNull(w.baroAltitudeM)
        assertNull(w.trackDeg)
        assertTrue(w.onGround)
    }

    @Test
    fun `waypoint rejects missing or out-of-range coordinates`() {
        assertNull(waypoint("[1750000000, null, 6.1, 0, 0, false]"))
        assertNull(waypoint("[null, 46.2, 6.1, 0, 0, false]"))
        assertNull(waypoint("[1750000000, 91.0, 6.1, 0, 0, false]"))
        assertNull(waypoint("[1750000000, 46.2, 181.0, 0, 0, false]"))
        assertNull(waypoint("[1750000000]"))
    }

    // ------------------------------------------------------------- query time

    private fun snapshot(
        schedDep: Instant?,
        schedArr: Instant?,
        actDep: Instant? = null,
        actArr: Instant? = null,
    ) = StatusSnapshot(
        provider = "test",
        fetchedAt = Instant.EPOCH,
        status = FlightStatus.UNKNOWN,
        departure = AirportRef("LSGG", "GVA"),
        arrival = AirportRef("ZBAA", "PEK"),
        depTimes = MovementTimes(scheduled = schedDep, actual = actDep),
        arrTimes = MovementTimes(scheduled = schedArr, actual = actArr),
    )

    private val dep: Instant = Instant.parse("2026-07-23T02:45:00Z")
    private val arr: Instant = Instant.parse("2026-07-23T07:27:00Z")

    @Test
    fun `airborne flight queries the live track`() {
        val now = Instant.parse("2026-07-23T05:00:00Z")
        assertEquals(0L, openSkyQueryTime(snapshot(dep, arr), now))
    }

    @Test
    fun `not yet departed queries nothing`() {
        val now = Instant.parse("2026-07-23T01:00:00Z")
        assertNull(openSkyQueryTime(snapshot(dep, arr), now))
        assertNull(openSkyQueryTime(null, now))
    }

    @Test
    fun `just landed still queries live within the grace period`() {
        val now = arr.plus(Duration.ofMinutes(10))
        assertEquals(0L, openSkyQueryTime(snapshot(dep, arr), now))
    }

    @Test
    fun `completed flight queries its midpoint, not the aircraft's next leg`() {
        val now = arr.plus(Duration.ofHours(5))
        val expectedMidpoint = dep.plus(Duration.between(dep, arr).dividedBy(2)).epochSecond
        assertEquals(expectedMidpoint, openSkyQueryTime(snapshot(dep, arr), now))
    }

    @Test
    fun `flights beyond the 30-day archive query nothing`() {
        val now = arr.plus(Duration.ofDays(31))
        assertNull(openSkyQueryTime(snapshot(dep, arr), now))
    }

    @Test
    fun `unknown arrival while long past departure stays on the live track`() {
        // Without an arrival estimate there is no landed signal — live is the
        // only defensible query.
        val now = dep.plus(Duration.ofHours(20))
        assertEquals(0L, openSkyQueryTime(snapshot(dep, null), now))
    }

    // ------------------------------------------------------------- accept window

    @Test
    fun `accept window brackets the flight with slack`() {
        val now = Instant.parse("2026-07-23T05:00:00Z")
        val window = openSkyAcceptWindow(snapshot(dep, arr), now)
        assertTrue(dep.minus(Duration.ofMinutes(30)) in window)
        assertTrue(arr.plus(Duration.ofMinutes(30)) in window)
        assertFalse(dep.minus(Duration.ofHours(2)) in window)     // previous leg
        assertFalse(arr.plus(Duration.ofHours(2)) in window)      // next leg
    }

    @Test
    fun `accept window without snapshot falls back to everything up to now`() {
        val now = Instant.parse("2026-07-23T05:00:00Z")
        val window = openSkyAcceptWindow(null, now)
        assertTrue(Instant.parse("2026-07-20T00:00:00Z") in window)
        assertFalse(now.plus(Duration.ofHours(2)) in window)
    }
}
