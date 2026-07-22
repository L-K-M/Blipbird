package ch.lkmc.blipbird.core.network

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * AeroDataBox via RapidAPI (PLAN.md §4.1). Single-date status lookup is a Tier-2
 * endpoint (2 units). Flight numbers may be IATA or ICAO, any case.
 */
interface AeroDataBoxApi {

    @GET("flights/number/{number}/{dateLocal}")
    suspend fun flightByNumberAndDate(
        @Header("X-RapidAPI-Key") apiKey: String,
        @Path("number") number: String,
        @Path("dateLocal") dateLocal: String,          // yyyy-MM-dd
        @Query("dateLocalRole") dateLocalRole: String = "Departure",
        @Query("withAircraftImage") withImage: Boolean = false,
        @Query("withLocation") withLocation: Boolean = false,
    ): List<AdbFlight>

    @GET("flights/number/{number}")
    suspend fun flightByNumberNearest(
        @Header("X-RapidAPI-Key") apiKey: String,
        @Path("number") number: String,
    ): List<AdbFlight>

    companion object {
        const val BASE_URL = "https://aerodatabox.p.rapidapi.com/"
        const val RAPIDAPI_HOST = "aerodatabox.p.rapidapi.com"
    }
}

@Serializable
data class AdbFlight(
    val number: String? = null,
    val callSign: String? = null,
    val status: String? = null,
    val codeshareStatus: String? = null,
    val isCargo: Boolean = false,
    val departure: AdbMovement? = null,
    val arrival: AdbMovement? = null,
    val aircraft: AdbAircraft? = null,
    val airline: AdbAirline? = null,
    val greatCircleDistance: AdbDistance? = null,
)

@Serializable
data class AdbMovement(
    val airport: AdbAirport? = null,
    val scheduledTime: AdbTime? = null,
    val revisedTime: AdbTime? = null,
    val predictedTime: AdbTime? = null,
    val runwayTime: AdbTime? = null,
    val terminal: String? = null,
    val gate: String? = null,
    val checkInDesk: String? = null,
    val baggageBelt: String? = null,
    val quality: List<String> = emptyList(),
)

@Serializable
data class AdbAirport(
    val icao: String? = null,
    val iata: String? = null,
    val name: String? = null,
    val shortName: String? = null,
    val municipalityName: String? = null,
    val countryCode: String? = null,
    val timeZone: String? = null,
    val location: AdbLocation? = null,
)

@Serializable
data class AdbLocation(val lat: Double? = null, val lon: Double? = null)

/** AeroDataBox times: {"utc":"2026-07-22 14:10Z","local":"2026-07-22 16:10+02:00"} */
@Serializable
data class AdbTime(val utc: String? = null, val local: String? = null)

@Serializable
data class AdbAircraft(val reg: String? = null, val modeS: String? = null, val model: String? = null)

@Serializable
data class AdbAirline(val name: String? = null, val iata: String? = null, val icao: String? = null)

@Serializable
data class AdbDistance(val km: Double? = null)
