package ch.lkmc.blipbird.core.data

import ch.lkmc.blipbird.core.model.PositionFix
import ch.lkmc.blipbird.core.model.StatusSnapshot
import ch.lkmc.blipbird.core.network.OpenSkyApi
import ch.lkmc.blipbird.core.network.OpenSkyWaypoint
import ch.lkmc.blipbird.core.network.parseOpenSkyWaypoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backfills the exact flown path from OpenSky's trajectory endpoint. Entirely
 * optional: without a user-supplied OpenSky API client the provider is inert and
 * the map keeps its great-circle guide plus self-collected ADS-B breadcrumbs.
 */
@Singleton
class OpenSkyTrackProvider @Inject constructor(
    private val api: OpenSkyApi,
    private val keys: ProviderKeyProvider,
) {
    private val tokenMutex = Mutex()
    private var cachedToken: Pair<String, Instant>? = null   // value → hard expiry

    suspend fun isConfigured(): Boolean =
        keys.openSkyClientId() != null && keys.openSkyClientSecret() != null

    /**
     * The full trajectory for [icao24] at [timeEpochSec] (0 = the live flight),
     * or null when unconfigured or the fetch failed. Altitude is converted to
     * feet to match every other [PositionFix] source.
     */
    suspend fun fetchTrack(icao24: String, timeEpochSec: Long, now: Instant = Instant.now()): List<PositionFix>? {
        val clientId = keys.openSkyClientId() ?: return null
        val clientSecret = keys.openSkyClientSecret() ?: return null
        val token = token(clientId, clientSecret, now) ?: return null
        return try {
            val resp = api.track("${OpenSkyApi.TRACK_URL}?icao24=$icao24&time=$timeEpochSec", "Bearer $token")
            resp.path.mapNotNull { parseOpenSkyWaypoint(it)?.toFix(icao24, resp.callsign?.trim(), now) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A dead token (revoked client, early expiry) also lands here; drop
            // the cache so the next attempt re-authenticates instead of failing
            // for the rest of the token's nominal lifetime.
            tokenMutex.withLock { cachedToken = null }
            null
        }
    }

    private suspend fun token(clientId: String, clientSecret: String, now: Instant): String? =
        tokenMutex.withLock {
            cachedToken?.takeIf { it.second.isAfter(now.plusSeconds(TOKEN_SLACK_SEC)) }?.let { return it.first }
            try {
                val resp = api.token(OpenSkyApi.TOKEN_URL, clientId, clientSecret)
                val access = resp.accessToken ?: return null
                cachedToken = access to now.plusSeconds(resp.expiresInSec ?: 1800)
                access
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    private fun OpenSkyWaypoint.toFix(icao24: String, callsign: String?, now: Instant): PositionFix {
        val at = Instant.ofEpochSecond(timeEpochSec)
        return PositionFix(
            at = at,
            lat = lat,
            lon = lon,
            baroAltitudeFt = baroAltitudeM?.let { it * FEET_PER_METER },
            onGround = onGround,
            groundSpeedKt = null,          // the trajectory endpoint carries no speed
            trackDeg = trackDeg,
            verticalRateFpm = null,
            seenPosAgeSec = Duration.between(at, now).seconds.coerceAtLeast(0).toDouble(),
            icao24 = icao24,
            callsign = callsign?.ifEmpty { null },
            registration = null,
            source = SOURCE,
        )
    }

    companion object {
        const val SOURCE = "opensky"
        private const val FEET_PER_METER = 3.28084
        private const val TOKEN_SLACK_SEC = 60L
    }
}

/**
 * The `time` parameter for a trajectory query, or null when querying is
 * pointless. OpenSky semantics: 0 returns the aircraft's LIVE track, any other
 * value must fall inside the wanted flight — after landing, 0 would return the
 * aircraft's NEXT leg, so a completed flight is queried at its midpoint instead.
 */
internal fun openSkyQueryTime(snapshot: StatusSnapshot?, now: Instant): Long? {
    if (snapshot == null) return null
    val up = snapshot.depTimes.bestRunway ?: snapshot.depTimes.best ?: return null
    val down = snapshot.arrTimes.bestRunway ?: snapshot.arrTimes.best
    if (now.isBefore(up)) return null                                  // nothing flown yet
    if (down != null && now.isAfter(down.plus(LANDED_GRACE))) {
        if (now.isAfter(down.plus(OPENSKY_HISTORY_LIMIT))) return null // beyond OpenSky's 30-day archive
        if (!down.isAfter(up)) return null                             // corrupt times; midpoint undefined
        return up.plus(Duration.between(up, down).dividedBy(2)).epochSecond
    }
    return 0L
}

/**
 * Instant range a returned waypoint must fall in to be attached to this flight.
 * Guards against OpenSky handing back a neighboring leg of the same airframe.
 */
internal fun openSkyAcceptWindow(snapshot: StatusSnapshot?, now: Instant): ClosedRange<Instant> {
    val up = snapshot?.depTimes?.bestRunway ?: snapshot?.depTimes?.best
    val down = snapshot?.arrTimes?.bestRunway ?: snapshot?.arrTimes?.best
    return (up?.minus(WINDOW_SLACK) ?: Instant.EPOCH)..((down ?: now).plus(WINDOW_SLACK))
}

private val LANDED_GRACE: Duration = Duration.ofMinutes(30)
private val WINDOW_SLACK: Duration = Duration.ofMinutes(45)
private val OPENSKY_HISTORY_LIMIT: Duration = Duration.ofDays(30)
