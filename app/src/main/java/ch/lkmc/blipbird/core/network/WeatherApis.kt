package ch.lkmc.blipbird.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * NOAA Aviation Weather Center data API (PLAN.md §4.4): worldwide METAR/TAF,
 * no key; ≤100 req/min, batch station IDs, custom User-Agent.
 */
interface AviationWeatherApi {

    @GET("api/data/metar")
    suspend fun metar(
        @Query("ids") ids: String,          // comma-separated ICAO station IDs
        @Query("format") format: String = "json",
    ): List<AwcMetar>

    companion object {
        const val BASE_URL = "https://aviationweather.gov/"
    }
}

@Serializable
data class AwcMetar(
    @SerialName("icaoId") val icaoId: String? = null,
    @SerialName("rawOb") val rawOb: String? = null,
    @SerialName("obsTime") val obsTime: Long? = null,   // epoch seconds
    val temp: Double? = null,
    val wdir: kotlinx.serialization.json.JsonElement? = null,  // int or "VRB"
    val wspd: Int? = null,
    val wgst: Int? = null,
)

/**
 * Open-Meteo forecast API (PLAN.md §4.4): free non-commercial, no key, CC-BY 4.0
 * attribution ("Weather data by Open-Meteo.com"). One request carries the whole
 * route's sample points as comma-separated coordinate lists.
 */
interface OpenMeteoApi {

    @GET("v1/forecast")
    suspend fun forecast(
        @Query("latitude") latitudes: String,      // "52.52,48.85,…"
        @Query("longitude") longitudes: String,
        @Query("hourly") hourly: String =
            "weather_code,cloud_cover,precipitation_probability,temperature_2m," +
                "wind_speed_250hPa,wind_direction_250hPa",
        @Query("timeformat") timeFormat: String = "unixtime",
        @Query("timezone") timezone: String = "UTC",
        @Query("forecast_days") forecastDays: Int = 14,
        @Query("wind_speed_unit") windSpeedUnit: String = "kn",
    ): kotlinx.serialization.json.JsonElement
    // The response is an object for one point and an array for many; parsed manually.

    companion object {
        const val BASE_URL = "https://api.open-meteo.com/"
    }
}

@Serializable
data class OpenMeteoPoint(
    val latitude: Double,
    val longitude: Double,
    val hourly: OpenMeteoHourly? = null,
)

@Serializable
data class OpenMeteoHourly(
    val time: List<Long> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
    @SerialName("cloud_cover") val cloudCover: List<Int?> = emptyList(),
    @SerialName("precipitation_probability") val precipitationProbability: List<Int?> = emptyList(),
    @SerialName("temperature_2m") val temperature2m: List<Double?> = emptyList(),
    @SerialName("wind_speed_250hPa") val windSpeed250: List<Double?> = emptyList(),
    @SerialName("wind_direction_250hPa") val windDirection250: List<Double?> = emptyList(),
)
