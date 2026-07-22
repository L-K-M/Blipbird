package ch.lkmc.blipbird.core.data

import ch.lkmc.blipbird.core.model.AirportWeather
import ch.lkmc.blipbird.core.model.WeatherSample
import ch.lkmc.blipbird.core.network.AviationWeatherApi
import ch.lkmc.blipbird.core.network.OpenMeteoApi
import ch.lkmc.blipbird.core.network.OpenMeteoPoint
import ch.lkmc.blipbird.domain.MetarDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeatherRepository @Inject constructor(
    private val awc: AviationWeatherApi,
    private val openMeteo: OpenMeteoApi,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** METAR-now for one or two airports in a single batched request. */
    suspend fun airportWeather(icaoIds: List<String>): List<AirportWeather> {
        if (icaoIds.isEmpty()) return emptyList()
        return try {
            awc.metar(icaoIds.joinToString(",")).mapNotNull { m ->
                val raw = m.rawOb ?: return@mapNotNull null
                val decoded = MetarDecoder.decode(raw)
                AirportWeather(
                    stationId = m.icaoId ?: "",
                    observedAt = m.obsTime?.let { Instant.ofEpochSecond(it) },
                    rawMetar = raw,
                    decoded = decoded.text,
                    temperatureC = m.temp ?: decoded.temperatureC,
                    windDirDeg = (m.wdir as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: decoded.windDirDeg,
                    windSpeedKt = m.wspd ?: decoded.windSpeedKt,
                    windGustKt = m.wgst ?: decoded.windGustKt,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * En-route weather: one multi-point Open-Meteo request; each sample is read at
     * the hour nearest its overflight time. Returns empty on failure or when a
     * sample's time is beyond the forecast horizon ("available closer to departure").
     */
    suspend fun routeWeather(points: List<Triple<Double, Double, Instant>>): List<WeatherSample> {
        if (points.isEmpty()) return emptyList()
        return try {
            // Locale.ROOT: device locales with comma decimal separators would
            // otherwise produce an invalid coordinate list.
            val lats = points.joinToString(",") { "%.3f".format(Locale.ROOT, it.first) }
            val lons = points.joinToString(",") { "%.3f".format(Locale.ROOT, it.second) }
            val element = openMeteo.forecast(lats, lons)
            val parsed: List<OpenMeteoPoint> = when (element) {
                is JsonArray -> element.map { json.decodeFromJsonElement(OpenMeteoPoint.serializer(), it) }
                is JsonObject -> listOf(json.decodeFromJsonElement(OpenMeteoPoint.serializer(), element))
                else -> emptyList()
            }
            points.mapIndexedNotNull { i, (lat, lon, at) ->
                val pt = parsed.getOrNull(i) ?: return@mapIndexedNotNull null
                val hourly = pt.hourly ?: return@mapIndexedNotNull null
                val target = at.epochSecond
                val idx = hourly.time.withIndex().minByOrNull { (_, t) -> kotlin.math.abs(t - target) }?.index
                    ?: return@mapIndexedNotNull null
                if (kotlin.math.abs(hourly.time[idx] - target) > 3 * 3600) return@mapIndexedNotNull null
                WeatherSample(
                    fraction = i.toDouble() / (points.size - 1).coerceAtLeast(1),
                    at = at,
                    lat = lat,
                    lon = lon,
                    weatherCode = hourly.weatherCode.getOrNull(idx),
                    cloudCoverPct = hourly.cloudCover.getOrNull(idx),
                    precipProbabilityPct = hourly.precipitationProbability.getOrNull(idx),
                    temperatureC = hourly.temperature2m.getOrNull(idx),
                    cruiseWindSpeedKt = hourly.windSpeed250.getOrNull(idx),
                    cruiseWindDirDeg = hourly.windDirection250.getOrNull(idx),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
