package ch.lkmc.blipbird.core.model

import java.time.Instant
import java.time.LocalDate

/**
 * A flight designator in both code systems, when known.
 * IATA: 2 alphanumeric airline chars (≥1 letter) + 1-4 digits (+ optional letter suffix), e.g. CA861.
 * ICAO: 3 letters + same number part, e.g. CCA861.
 */
data class Designator(
    val airlineIata: String?,
    val airlineIcao: String?,
    val number: String,
    val suffix: String? = null,
) {
    val iata: String? get() = airlineIata?.let { "$it$number${suffix.orEmpty()}" }
    val icao: String? get() = airlineIcao?.let { "$it$number${suffix.orEmpty()}" }
    val display: String get() = iata ?: icao ?: number
    /** The form used as an ATC callsign guess (ICAO airline code + number). */
    val callsignGuess: String? get() = icao
}

enum class FlightStatus { UNKNOWN, SCHEDULED, ON_TIME, DELAYED, DEPARTED, EN_ROUTE, APPROACHING, LANDED, ARRIVED, CANCELLED, DIVERTED }

enum class TimeCertainty { SCHEDULED, ESTIMATED, ACTUAL, DERIVED, USER_ENTERED }

/** One departure-or-arrival side of a status snapshot. */
data class MovementTimes(
    val scheduled: Instant? = null,
    val estimated: Instant? = null,
    val actual: Instant? = null,
    val runwayEstimated: Instant? = null,
    val runwayActual: Instant? = null,
) {
    val best: Instant? get() = actual ?: estimated ?: scheduled
    val bestRunway: Instant? get() = runwayActual ?: runwayEstimated
}

data class AirportRef(
    val icao: String?,
    val iata: String?,
    val name: String? = null,
    val city: String? = null,
    val country: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val tz: String? = null,
) {
    val code: String get() = iata ?: icao ?: "???"
}

data class AirlineRef(val icao: String?, val iata: String?, val name: String?)

/** Normalized flight-status snapshot from any provider. */
data class StatusSnapshot(
    val provider: String,
    val fetchedAt: Instant,
    val status: FlightStatus,
    val departure: AirportRef?,
    val arrival: AirportRef?,
    val depTimes: MovementTimes,
    val arrTimes: MovementTimes,
    val depTerminal: String? = null,
    val depGate: String? = null,
    val depCheckInDesk: String? = null,
    val arrTerminal: String? = null,
    val arrGate: String? = null,
    val baggageBelt: String? = null,
    val aircraftModel: String? = null,
    val registration: String? = null,
    val icao24: String? = null,
    val operatingDesignator: String? = null,
    val codeshareOf: String? = null,
    val greatCircleKm: Double? = null,
)

/** Live position fix from an ADS-B source. */
data class PositionFix(
    val at: Instant,
    val lat: Double,
    val lon: Double,
    val baroAltitudeFt: Double?,   // may be null when source reports "ground"
    val onGround: Boolean,
    val groundSpeedKt: Double?,
    val trackDeg: Double?,
    val verticalRateFpm: Double?,
    val seenPosAgeSec: Double,
    val icao24: String?,
    val callsign: String?,
    val registration: String?,
    val source: String,
)

enum class LightBand { DAY, CIVIL_TWILIGHT, NAUTICAL_TWILIGHT, ASTRONOMICAL_TWILIGHT, NIGHT }

data class RouteSample(
    val fraction: Double,
    val at: Instant,
    val lat: Double,
    val lon: Double,
    val solarElevationDeg: Double,
    val band: LightBand,
)

enum class SunEventType { SUNRISE, SUNSET }

data class SunEvent(
    val type: SunEventType,
    val at: Instant,
    val lat: Double,
    val lon: Double,
    /** North-based solar azimuth at the event, for the window-side callout. */
    val azimuthDeg: Double,
    /** True when computed with the cruise-altitude horizon-dip threshold. */
    val cabinVisible: Boolean,
)

data class WeatherSample(
    val fraction: Double,
    val at: Instant,
    val lat: Double,
    val lon: Double,
    val weatherCode: Int?,
    val cloudCoverPct: Int?,
    val precipProbabilityPct: Int?,
    val temperatureC: Double?,
    val cruiseWindSpeedKt: Double?,
    val cruiseWindDirDeg: Double?,
)

data class AirportWeather(
    val stationId: String,
    val observedAt: Instant?,
    val rawMetar: String,
    val decoded: String,
    val temperatureC: Double?,
    val windDirDeg: Int?,
    val windSpeedKt: Int?,
    val windGustKt: Int?,
)

/** What the user asked to track. */
data class TrackRequest(
    val designator: Designator,
    val date: LocalDate?,      // departure-airport local date; null = next occurrence
    val alias: String? = null,
)
