package ch.lkmc.blipbird.core.data

import ch.lkmc.blipbird.core.model.AirportRef
import ch.lkmc.blipbird.core.model.Designator
import ch.lkmc.blipbird.core.model.FlightStatus
import ch.lkmc.blipbird.core.model.MovementTimes
import ch.lkmc.blipbird.core.model.StatusSnapshot
import ch.lkmc.blipbird.core.network.AdbFlight
import ch.lkmc.blipbird.core.network.AdbTime
import ch.lkmc.blipbird.core.network.AeroApi
import ch.lkmc.blipbird.core.network.AeroApiFlight
import ch.lkmc.blipbird.core.network.AeroDataBoxApi
import ch.lkmc.blipbird.domain.RetryAfter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** A provider either returns candidate flights, needs a key, or found nothing. */
sealed interface StatusResult {
    data class Found(val flights: List<StatusSnapshot>) : StatusResult
    data object NotFound : StatusResult
    data object NoKey : StatusResult
    data class Error(
        val message: String,
        val retryable: Boolean,
        /** Provider-requested pause (429/503 `Retry-After`), when it sent one. */
        val retryAfter: Duration? = null,
        /** True for HTTP 429 — surfaced as its own lookup outcome (G5). */
        val rateLimited: Boolean = false,
    ) : StatusResult
}

interface FlightStatusProvider {
    val name: String
    /** Cost of one lookup in the provider's own quota units. */
    val unitsPerLookup: Long
    suspend fun fetch(designator: Designator, dateLocal: LocalDate?): StatusResult
}

// ---------------------------------------------------------------------------

class AeroDataBoxProvider @Inject constructor(
    private val api: AeroDataBoxApi,
    private val keys: ProviderKeyProvider,
) : FlightStatusProvider {

    override val name = "aerodatabox"
    override val unitsPerLookup = 2L   // Tier-2 endpoint

    override suspend fun fetch(designator: Designator, dateLocal: LocalDate?): StatusResult {
        val key = keys.aeroDataBoxKey() ?: return StatusResult.NoKey
        // ADB accepts IATA or ICAO in the same parameter.
        val number = designator.iata ?: designator.icao ?: return StatusResult.NotFound
        return try {
            val flights = if (dateLocal != null)
                api.flightByNumberAndDate(key, number, dateLocal.toString())
            else
                api.flightByNumberNearest(key, number)
            if (flights.isEmpty()) StatusResult.NotFound
            else StatusResult.Found(flights.map { it.toSnapshot() })
        } catch (e: retrofit2.HttpException) {
            when (e.code()) {
                404 -> StatusResult.NotFound
                401, 403 -> StatusResult.Error("AeroDataBox key rejected", retryable = false)
                429 -> StatusResult.Error("AeroDataBox rate limited", retryable = true, retryAfter = e.retryAfterOrNull(), rateLimited = true)
                else -> StatusResult.Error("AeroDataBox HTTP ${e.code()}", retryable = e.code() >= 500, retryAfter = e.retryAfterOrNull())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            StatusResult.Error(e.message ?: "network error", retryable = true)
        }
    }

    private fun AdbFlight.toSnapshot(): StatusSnapshot {
        fun AdbTime?.instant(): Instant? = this?.utc?.let { raw ->
            // "2026-07-22 14:10Z" — normalize to ISO. ADB timestamps are minute
            // precision, which Instant.parse rejects, so the offset formatter
            // (which also accepts Z and full-second forms) goes first; the
            // Instant fallback keeps any stricter form working.
            val iso = raw.replace(" ", "T")
            runCatching { OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }.getOrNull()
                ?: runCatching { Instant.parse(iso) }.getOrNull()
        }
        val statusEnum = when (status?.lowercase()) {
            "expected", "checkin", "boarding", "gateclosed" -> FlightStatus.SCHEDULED
            "departed" -> FlightStatus.DEPARTED
            "enroute", "approaching" -> FlightStatus.EN_ROUTE
            "arrived" -> FlightStatus.ARRIVED
            "delayed" -> FlightStatus.DELAYED
            "canceled", "cancelled", "canceleduncertain" -> FlightStatus.CANCELLED
            "diverted" -> FlightStatus.DIVERTED
            else -> FlightStatus.UNKNOWN
        }
        return StatusSnapshot(
            provider = "aerodatabox",
            fetchedAt = Instant.now(),
            status = statusEnum,
            departure = departure?.airport?.let {
                AirportRef(it.icao, it.iata, it.name ?: it.shortName, it.municipalityName, it.countryCode, it.location?.lat, it.location?.lon, it.timeZone)
            },
            arrival = arrival?.airport?.let {
                AirportRef(it.icao, it.iata, it.name ?: it.shortName, it.municipalityName, it.countryCode, it.location?.lat, it.location?.lon, it.timeZone)
            },
            depTimes = MovementTimes(
                scheduled = departure?.scheduledTime.instant(),
                estimated = departure?.revisedTime.instant() ?: departure?.predictedTime.instant(),
                runwayActual = departure?.runwayTime.instant(),
            ),
            arrTimes = MovementTimes(
                scheduled = arrival?.scheduledTime.instant(),
                estimated = arrival?.revisedTime.instant() ?: arrival?.predictedTime.instant(),
                runwayActual = arrival?.runwayTime.instant(),
            ),
            depTerminal = departure?.terminal,
            depGate = departure?.gate,
            depCheckInDesk = departure?.checkInDesk,
            arrTerminal = arrival?.terminal,
            arrGate = arrival?.gate,
            baggageBelt = arrival?.baggageBelt,
            aircraftModel = aircraft?.model,
            registration = aircraft?.reg,
            icao24 = aircraft?.modeS?.lowercase(),
            operatingDesignator = if (codeshareStatus == "IsCodeshared") null else number?.replace(" ", ""),
            // ADB flags a codeshare but never names the operating flight here, so
            // there is nothing truthful to store: the queried number as its own
            // codeshare was a self-reference (glm-A). Null until enriched.
            codeshareOf = null,
            greatCircleKm = greatCircleDistance?.km,
        )
    }
}

// ---------------------------------------------------------------------------

class AeroApiProvider @Inject constructor(
    private val api: AeroApi,
    private val keys: ProviderKeyProvider,
) : FlightStatusProvider {

    override val name = "aeroapi"
    override val unitsPerLookup = 1L   // ledger unit = 1 result set ($0.005)

    override suspend fun fetch(designator: Designator, dateLocal: LocalDate?): StatusResult {
        val key = keys.aeroApiKey() ?: return StatusResult.NoKey
        val ident = designator.icao ?: designator.iata ?: return StatusResult.NotFound
        return try {
            // The requested date is departure-airport-LOCAL but this endpoint
            // filters by UTC. Pad a full day each side (offsets span UTC-12..+14)
            // and let the repository post-filter by departure-local date.
            val (start, end) = dateLocal?.let {
                "${it.minusDays(1)}T00:00:00Z" to "${it.plusDays(2)}T00:00:00Z"
            } ?: (null to null)
            val resp = api.flights(key, ident, start, end)
            if (resp.flights.isEmpty()) StatusResult.NotFound
            else StatusResult.Found(resp.flights.map { it.toSnapshot() })
        } catch (e: retrofit2.HttpException) {
            when (e.code()) {
                404 -> StatusResult.NotFound
                401, 403 -> StatusResult.Error("AeroAPI key rejected", retryable = false)
                429 -> StatusResult.Error("AeroAPI rate limited", retryable = true, retryAfter = e.retryAfterOrNull(), rateLimited = true)
                else -> StatusResult.Error("AeroAPI HTTP ${e.code()}", retryable = e.code() >= 500, retryAfter = e.retryAfterOrNull())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            StatusResult.Error(e.message ?: "network error", retryable = true)
        }
    }

    private fun AeroApiFlight.toSnapshot(): StatusSnapshot {
        fun parse(s: String?): Instant? = s?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val statusEnum = when {
            cancelled -> FlightStatus.CANCELLED
            diverted -> FlightStatus.DIVERTED
            parse(actualIn) != null -> FlightStatus.ARRIVED
            parse(actualOn) != null -> FlightStatus.LANDED
            parse(actualOff) != null || parse(actualOut) != null -> FlightStatus.EN_ROUTE
            else -> FlightStatus.SCHEDULED
        }
        return StatusSnapshot(
            provider = "aeroapi",
            fetchedAt = Instant.now(),
            status = statusEnum,
            departure = origin?.let { AirportRef(it.codeIcao, it.codeIata, it.name, it.city, null, null, null, it.timezone) },
            arrival = destination?.let { AirportRef(it.codeIcao, it.codeIata, it.name, it.city, null, null, null, it.timezone) },
            depTimes = MovementTimes(
                scheduled = parse(scheduledOut),
                estimated = parse(estimatedOut),
                actual = parse(actualOut),
                runwayEstimated = parse(estimatedOff),
                runwayActual = parse(actualOff),
            ),
            arrTimes = MovementTimes(
                scheduled = parse(scheduledIn),
                estimated = parse(estimatedIn),
                actual = parse(actualIn),
                runwayEstimated = parse(estimatedOn),
                runwayActual = parse(actualOn),
            ),
            depTerminal = terminalOrigin,
            depGate = gateOrigin,
            arrTerminal = terminalDestination,
            arrGate = gateDestination,
            baggageBelt = baggageClaim,
            aircraftModel = aircraftType,
            registration = registration,
            operatingDesignator = identIata ?: ident,
            greatCircleKm = routeDistance?.toDouble()?.times(1.852),  // nm → km when present
        )
    }
}

private fun retrofit2.HttpException.retryAfterOrNull(): Duration? =
    RetryAfter.parse(response()?.headers()?.get("Retry-After"), Instant.now())

/** Narrow key access so providers don't depend on the whole DataStore type. */
interface ProviderKeyProvider {
    suspend fun aeroDataBoxKey(): String?
    suspend fun aeroApiKey(): String?
    suspend fun openSkyClientId(): String?
    suspend fun openSkyClientSecret(): String?
}
