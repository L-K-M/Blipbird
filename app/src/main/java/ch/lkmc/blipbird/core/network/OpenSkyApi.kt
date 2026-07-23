package ch.lkmc.blipbird.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * OpenSky Network trajectory API (optional BYO API client, PLAN.md §4.2 note).
 * Auth is OAuth2 client-credentials against OpenSky's Keycloak realm — the
 * legacy basic-auth scheme was retired in 2025, so only API clients created on
 * opensky-network.org work here. The token host differs from the API host,
 * hence full @Url endpoints instead of a base URL.
 */
interface OpenSkyApi {
    @FormUrlEncoded
    @POST
    suspend fun token(
        @Url url: String,
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("grant_type") grantType: String = "client_credentials",
    ): OpenSkyTokenResponse

    @GET
    suspend fun track(
        @Url url: String,
        @Header("Authorization") bearer: String,
    ): OpenSkyTrackResponse

    companion object {
        const val TOKEN_URL =
            "https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token"
        const val TRACK_URL = "https://opensky-network.org/api/tracks/all"
    }
}

@Serializable
data class OpenSkyTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("expires_in") val expiresInSec: Long? = null,
)

@Serializable
data class OpenSkyTrackResponse(
    val icao24: String? = null,
    // Numeric, not Long: OpenSky has been observed emitting fractional epochs.
    val startTime: Double? = null,
    val endTime: Double? = null,
    val callsign: String? = null,
    /** Waypoints as positional arrays: [time, lat, lon, baro_altitude_m, true_track, on_ground]. */
    val path: List<JsonArray> = emptyList(),
)

/** One decoded trajectory waypoint; altitude still in OpenSky's native meters. */
data class OpenSkyWaypoint(
    val timeEpochSec: Long,
    val lat: Double,
    val lon: Double,
    val baroAltitudeM: Double?,
    val trackDeg: Double?,
    val onGround: Boolean,
)

/**
 * Decodes one positional waypoint array, or null when time/lat/lon are absent or
 * out of range — OpenSky pads unknown fields with JSON null.
 */
fun parseOpenSkyWaypoint(raw: JsonArray): OpenSkyWaypoint? {
    fun prim(i: Int): JsonPrimitive? = raw.getOrNull(i) as? JsonPrimitive
    val time = prim(0)?.longOrNull ?: return null
    val lat = prim(1)?.doubleOrNull?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
    val lon = prim(2)?.doubleOrNull?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
    return OpenSkyWaypoint(
        timeEpochSec = time,
        lat = lat,
        lon = lon,
        baroAltitudeM = prim(3)?.doubleOrNull?.takeIf { it.isFinite() },
        trackDeg = prim(4)?.doubleOrNull?.takeIf { it.isFinite() },
        onGround = prim(5)?.booleanOrNull ?: false,
    )
}
