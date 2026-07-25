package ch.lkmc.blipbird.core.data

import ch.lkmc.blipbird.core.database.AirportEntity
import ch.lkmc.blipbird.core.database.ReferenceDao
import ch.lkmc.blipbird.core.model.AirportRef
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fills the gaps a status provider leaves in an [AirportRef] from the bundled
 * airport table (PLAN.md §4.3) — AeroAPI omits coordinates, several providers omit
 * the time zone, and everything downstream (daylight, body clock, local times,
 * the map) needs both.
 *
 * Provider values always win; this only fills nulls. Hits are cached because the
 * same handful of airports is re-resolved on every snapshot emission.
 */
@Singleton
class AirportEnricher @Inject constructor(
    private val referenceDao: ReferenceDao,
) {
    private val cache = ConcurrentHashMap<String, AirportEntity>()

    suspend fun enrich(ref: AirportRef?): AirportRef? {
        if (ref == null) return null
        if (ref.complete) return ref
        val key = ref.iata ?: ref.icao ?: return ref
        val row = cache[key] ?: lookup(ref)?.also { cache[key] = it } ?: return ref
        return AirportRef(
            icao = ref.icao ?: row.icao,
            iata = ref.iata ?: row.iata,
            name = ref.name ?: row.name,
            city = ref.city ?: row.city,
            country = ref.country ?: row.country,
            lat = ref.lat ?: row.lat,
            lon = ref.lon ?: row.lon,
            tz = ref.tz ?: row.tz,
        )
    }

    private suspend fun lookup(ref: AirportRef): AirportEntity? =
        ref.iata?.let { referenceDao.airportByIata(it) }
            ?: ref.icao?.let { referenceDao.airportByIcao(it) }

    private val AirportRef.complete: Boolean
        get() = lat != null && lon != null && tz != null && city != null && name != null
}
