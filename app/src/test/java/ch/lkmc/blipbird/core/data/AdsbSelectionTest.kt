package ch.lkmc.blipbird.core.data

import ch.lkmc.blipbird.core.network.AdsbAircraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdsbSelectionTest {
    @Test
    fun `hex query selects matching record rather than first positioned record`() {
        val wrong = aircraft(hex = "aaaaaa", seenPos = 1.0)
        val match = aircraft(hex = " ABC123 ", seenPos = 3.0)

        assertEquals(match, selectAdsbAircraft(PositionProvider.Query.Hex("abc123"), listOf(wrong, match)))
    }

    @Test
    fun `registration and callsign queries match normalized identities`() {
        val byRegistration = aircraft(registration = " hb-jna ")
        val byCallsign = aircraft(callsign = "  SWR123  ")

        assertEquals(
            byRegistration,
            selectAdsbAircraft(PositionProvider.Query.Registration("HB-JNA"), listOf(byRegistration)),
        )
        assertEquals(
            byCallsign,
            selectAdsbAircraft(PositionProvider.Query.Callsign(" SWR123 "), listOf(byCallsign)),
        )
    }

    @Test
    fun `selector rejects missing identity malformed positions and stale fixes`() {
        val query = PositionProvider.Query.Hex("abc123")

        assertNull(selectAdsbAircraft(query, listOf(aircraft(hex = null))))
        assertNull(selectAdsbAircraft(query, listOf(aircraft(hex = "abc123", lat = Double.NaN))))
        assertNull(selectAdsbAircraft(query, listOf(aircraft(hex = "abc123", lat = 91.0))))
        assertNull(selectAdsbAircraft(query, listOf(aircraft(hex = "abc123", lon = 181.0))))
        assertNull(selectAdsbAircraft(query, listOf(aircraft(hex = "abc123", seenPos = null))))
        assertNull(selectAdsbAircraft(query, listOf(aircraft(hex = "abc123", seenPos = -1.0))))
        assertNull(
            selectAdsbAircraft(
                query,
                listOf(aircraft(hex = "abc123", seenPos = MAX_ADSB_FIX_AGE_SECONDS + 1.0)),
            ),
        )
    }

    @Test
    fun `selector chooses freshest valid matching record`() {
        val older = aircraft(hex = "abc123", seenPos = 20.0)
        val newer = aircraft(hex = "abc123", seenPos = 2.0)

        assertEquals(newer, selectAdsbAircraft(PositionProvider.Query.Hex("ABC123"), listOf(older, newer)))
    }

    @Test
    fun `current identities precede cache and callsign`() {
        assertEquals(
            listOf(
                PositionProvider.Query.Hex("abc123"),
                PositionProvider.Query.Registration("HB-JNA"),
                PositionProvider.Query.Callsign("SWR123"),
            ),
            positionQueries("abc123", "HB-JNA", "def456", "SWR123"),
        )
    }

    @Test
    fun `cached hex remains a fallback without current registration`() {
        assertEquals(
            listOf(
                PositionProvider.Query.Hex("abc123"),
                PositionProvider.Query.Hex("def456"),
                PositionProvider.Query.Callsign("SWR123"),
            ),
            positionQueries("abc123", null, "def456", "SWR123"),
        )
    }

    private fun aircraft(
        hex: String? = null,
        registration: String? = null,
        callsign: String? = null,
        lat: Double? = 47.0,
        lon: Double? = 8.0,
        seenPos: Double? = 1.0,
    ) = AdsbAircraft(
        hex = hex,
        r = registration,
        flight = callsign,
        lat = lat,
        lon = lon,
        seenPos = seenPos,
    )
}
