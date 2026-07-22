package ch.lkmc.blipbird.core.data

import ch.lkmc.blipbird.core.model.PositionFix
import ch.lkmc.blipbird.core.network.AdsbAircraft
import ch.lkmc.blipbird.core.network.AdsbApi
import ch.lkmc.blipbird.core.network.AdsbProviderSpec
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live positions from the readsb aggregator family with ordered failover
 * (PLAN.md §4.2): adsb.lol → airplanes.live → adsb.fi. All three share the
 * response schema; adsb.fi's registration path differs (handled by the spec).
 */
@Singleton
class PositionProvider @Inject constructor(
    private val api: AdsbApi,
) {
    private val chain = listOf(
        AdsbProviderSpec.ADSB_LOL,
        AdsbProviderSpec.AIRPLANES_LIVE,
        AdsbProviderSpec.ADSB_FI,
    )

    sealed interface Query {
        data class Callsign(val callsign: String) : Query
        data class Registration(val registration: String) : Query
        data class Hex(val icao24: String) : Query
    }

    suspend fun fetch(query: Query): PositionFix? {
        for (spec in chain) {
            val url = spec.base + when (query) {
                is Query.Callsign -> spec.callsignPath(query.callsign)
                is Query.Registration -> spec.regPath(query.registration)
                is Query.Hex -> spec.hexPath(query.icao24)
            }
            try {
                val resp = api.byUrl(url)
                val ac = resp.ac.firstOrNull { it.lat != null && it.lon != null } ?: continue
                return ac.toFix(spec.name)
            } catch (_: Exception) {
                // fall through to the next provider
            }
        }
        return null
    }

    private fun AdsbAircraft.toFix(source: String): PositionFix = PositionFix(
        at = Instant.now().minusMillis(((seenPos ?: 0.0) * 1000).toLong()),
        lat = lat!!,
        lon = lon!!,
        baroAltitudeFt = baroAltitudeFt,
        onGround = onGround,
        groundSpeedKt = gs,
        trackDeg = track,
        verticalRateFpm = baroRate,
        seenPosAgeSec = seenPos ?: 0.0,
        icao24 = hex?.lowercase(),
        callsign = callsignTrimmed,
        registration = r,
        source = source,
    )
}
