package ch.lkmc.blipbird.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * FlightAware AeroAPI v4, Personal tier (PLAN.md §4.1). $0.005 per result set;
 * query window −10 d … +2 d. Each user brings their own key.
 *
 * Date queries: a "flight date" is DEPARTURE-AIRPORT-LOCAL, but this endpoint
 * filters by UTC instants. Callers must pass a window padded by a full day on
 * each side (any local date D spans up to D−1 10:00Z … D+1 12:00Z across the
 * UTC−12…+14 offset range) and post-filter results by departure-local date.
 */
interface AeroApi {

    @GET("flights/{ident}")
    suspend fun flights(
        @Header("x-apikey") apiKey: String,
        @Path("ident") ident: String,
        @Query("start") start: String? = null,   // ISO8601
        @Query("end") end: String? = null,
        @Query("max_pages") maxPages: Int = 1,
    ): AeroApiFlightsResponse

    companion object {
        const val BASE_URL = "https://aeroapi.flightaware.com/aeroapi/"
    }
}

@Serializable
data class AeroApiFlightsResponse(val flights: List<AeroApiFlight> = emptyList())

@Serializable
data class AeroApiFlight(
    val ident: String? = null,
    @SerialName("ident_iata") val identIata: String? = null,
    @SerialName("ident_icao") val identIcao: String? = null,
    @SerialName("fa_flight_id") val faFlightId: String? = null,
    val registration: String? = null,
    @SerialName("aircraft_type") val aircraftType: String? = null,
    val origin: AeroApiAirport? = null,
    val destination: AeroApiAirport? = null,
    val cancelled: Boolean = false,
    val diverted: Boolean = false,
    @SerialName("scheduled_out") val scheduledOut: String? = null,
    @SerialName("estimated_out") val estimatedOut: String? = null,
    @SerialName("actual_out") val actualOut: String? = null,
    @SerialName("scheduled_off") val scheduledOff: String? = null,
    @SerialName("estimated_off") val estimatedOff: String? = null,
    @SerialName("actual_off") val actualOff: String? = null,
    @SerialName("scheduled_on") val scheduledOn: String? = null,
    @SerialName("estimated_on") val estimatedOn: String? = null,
    @SerialName("actual_on") val actualOn: String? = null,
    @SerialName("scheduled_in") val scheduledIn: String? = null,
    @SerialName("estimated_in") val estimatedIn: String? = null,
    @SerialName("actual_in") val actualIn: String? = null,
    @SerialName("gate_origin") val gateOrigin: String? = null,
    @SerialName("gate_destination") val gateDestination: String? = null,
    @SerialName("terminal_origin") val terminalOrigin: String? = null,
    @SerialName("terminal_destination") val terminalDestination: String? = null,
    @SerialName("baggage_claim") val baggageClaim: String? = null,
    @SerialName("operator_icao") val operatorIcao: String? = null,
    @SerialName("operator_iata") val operatorIata: String? = null,
    @SerialName("codeshares") val codeshares: List<String> = emptyList(),
    @SerialName("route_distance") val routeDistance: Int? = null,
)

@Serializable
data class AeroApiAirport(
    val code: String? = null,
    @SerialName("code_icao") val codeIcao: String? = null,
    @SerialName("code_iata") val codeIata: String? = null,
    val name: String? = null,
    val city: String? = null,
    val timezone: String? = null,
)
