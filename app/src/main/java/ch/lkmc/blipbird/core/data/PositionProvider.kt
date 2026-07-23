package ch.lkmc.blipbird.core.data

import ch.lkmc.blipbird.core.model.PositionFix
import ch.lkmc.blipbird.core.network.AdsbAircraft
import ch.lkmc.blipbird.core.network.AdsbApi
import ch.lkmc.blipbird.core.network.AdsbProviderSpec
import java.time.Instant
import java.util.Locale
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
        val queryValue = query.normalizedIdentity() ?: return null
        for (spec in chain) {
            val url = spec.base + when (query) {
                is Query.Callsign -> spec.callsignPath(queryValue)
                is Query.Registration -> spec.regPath(queryValue)
                is Query.Hex -> spec.hexPath(queryValue)
            }
            try {
                val resp = api.byUrl(url)
                val ac = selectAdsbAircraft(query, resp.ac) ?: continue
                return ac.toFix(spec.name)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // fall through to the next provider
            }
        }
        return null
    }

    private fun AdsbAircraft.toFix(source: String): PositionFix {
        val age = seenPos!!
        return PositionFix(
            at = Instant.now().minusMillis((age * 1000).toLong()),
            lat = lat!!,
            lon = lon!!,
            baroAltitudeFt = baroAltitudeFt,
            onGround = onGround,
            groundSpeedKt = gs,
            trackDeg = track,
            verticalRateFpm = baroRate,
            seenPosAgeSec = age,
            icao24 = hex?.trim()?.lowercase(Locale.ROOT),
            callsign = callsignTrimmed,
            registration = r?.trim(),
            source = source,
        )
    }
}

internal const val MAX_ADSB_FIX_AGE_SECONDS = 300.0

internal fun selectAdsbAircraft(
    query: PositionProvider.Query,
    aircraft: List<AdsbAircraft>,
): AdsbAircraft? {
    val expected = query.normalizedIdentity() ?: return null
    return aircraft.asSequence()
        .filter { candidate -> candidate.matches(query, expected) && candidate.hasValidPosition() }
        .minByOrNull { it.seenPos!! }
}

private fun PositionProvider.Query.normalizedIdentity(): String? = when (this) {
    is PositionProvider.Query.Hex -> icao24.trim().lowercase(Locale.ROOT)
        .takeIf { it.matches(Regex("[0-9a-f]{6}")) }
    is PositionProvider.Query.Registration -> registration.trim().uppercase(Locale.ROOT)
        .ifEmpty { null }
    is PositionProvider.Query.Callsign -> callsign.trim().uppercase(Locale.ROOT)
        .ifEmpty { null }
}

private fun AdsbAircraft.matches(query: PositionProvider.Query, expected: String): Boolean = when (query) {
    is PositionProvider.Query.Hex -> hex?.trim()?.lowercase(Locale.ROOT) == expected
    is PositionProvider.Query.Registration -> r?.trim()?.uppercase(Locale.ROOT) == expected
    is PositionProvider.Query.Callsign -> callsignTrimmed?.uppercase(Locale.ROOT) == expected
}

private fun AdsbAircraft.hasValidPosition(): Boolean =
    lat != null && lat.isFinite() && lat in -90.0..90.0 &&
        lon != null && lon.isFinite() && lon in -180.0..180.0 &&
        seenPos != null && seenPos.isFinite() && seenPos in 0.0..MAX_ADSB_FIX_AGE_SECONDS
