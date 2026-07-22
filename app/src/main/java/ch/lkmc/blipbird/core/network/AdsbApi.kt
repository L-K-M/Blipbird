package ch.lkmc.blipbird.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Url

/**
 * The readsb/ADSBExchange-v2 aggregator family (PLAN.md §4.2): adsb.lol (primary,
 * ODbL), airplanes.live and adsb.fi (fallbacks; response schema identical, URL
 * paths differ — see [AdsbProviderSpec]).
 */
interface AdsbApi {
    @GET
    suspend fun byUrl(@Url url: String): AdsbV2Response
}

data class AdsbProviderSpec(
    val name: String,
    val base: String,
    val callsignPath: (String) -> String,
    val regPath: (String) -> String,
    val hexPath: (String) -> String,
) {
    companion object {
        val ADSB_LOL = AdsbProviderSpec(
            name = "adsb.lol",
            base = "https://api.adsb.lol",
            callsignPath = { "/v2/callsign/$it" },
            regPath = { "/v2/reg/$it" },
            hexPath = { "/v2/icao/$it" },
        )
        val AIRPLANES_LIVE = AdsbProviderSpec(
            name = "airplanes.live",
            base = "https://api.airplanes.live",
            callsignPath = { "/v2/callsign/$it" },
            regPath = { "/v2/reg/$it" },
            hexPath = { "/v2/hex/$it" },
        )
        val ADSB_FI = AdsbProviderSpec(
            name = "adsb.fi",
            base = "https://opendata.adsb.fi/api",
            callsignPath = { "/v2/callsign/$it" },
            regPath = { "/v2/registration/$it" },   // path differs from the others
            hexPath = { "/v2/hex/$it" },
        )
    }
}

@Serializable
data class AdsbV2Response(
    val ac: List<AdsbAircraft> = emptyList(),
    val now: Long? = null,
    val total: Int? = null,
    val msg: String? = null,
)

@Serializable
data class AdsbAircraft(
    val hex: String? = null,
    val type: String? = null,
    val flight: String? = null,        // callsign, space-padded
    val r: String? = null,             // registration
    val t: String? = null,             // ICAO type code
    /** Barometric altitude in ft, or the string "ground". */
    @SerialName("alt_baro") val altBaro: JsonElement? = null,
    @SerialName("alt_geom") val altGeom: Double? = null,
    val gs: Double? = null,
    val track: Double? = null,
    @SerialName("baro_rate") val baroRate: Double? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val seen: Double? = null,
    @SerialName("seen_pos") val seenPos: Double? = null,
    val squawk: String? = null,
    val category: String? = null,
) {
    val onGround: Boolean
        get() = (altBaro as? JsonPrimitive)?.content == "ground"

    val baroAltitudeFt: Double?
        get() = (altBaro as? JsonPrimitive)?.doubleOrNull

    val callsignTrimmed: String? get() = flight?.trim()?.ifEmpty { null }
}
