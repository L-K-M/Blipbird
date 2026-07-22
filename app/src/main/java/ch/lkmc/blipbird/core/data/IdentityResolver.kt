package ch.lkmc.blipbird.core.data

import ch.lkmc.blipbird.core.database.ReferenceDao
import ch.lkmc.blipbird.core.model.Designator
import ch.lkmc.blipbird.domain.DesignatorParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Completes a parsed designator against the bundled airline table (PLAN.md §5
 * step 2): CA861 → CCA861 and vice versa. Ambiguous 3-char prefixes (IATA-with-
 * digit vs ICAO) resolve via table membership, IATA first.
 */
@Singleton
class IdentityResolver @Inject constructor(
    private val referenceDao: ReferenceDao,
) {
    suspend fun resolveToken(token: String): Designator? {
        val parses = DesignatorParser.parseToken(token)
        if (parses.isEmpty()) return null
        val merged = DesignatorParser.mergePair(parses) ?: return null
        return complete(merged)
    }

    suspend fun complete(d: Designator): Designator {
        var iata = d.airlineIata
        var icao = d.airlineIcao
        if (iata != null && icao == null) {
            icao = referenceDao.airlinesByIata(iata).firstOrNull { it.icao?.length == 3 }?.icao
        }
        if (icao != null && iata == null) {
            iata = referenceDao.airlinesByIcao(icao).firstOrNull { it.iata?.length == 2 }?.iata
        }
        return d.copy(airlineIata = iata, airlineIcao = icao)
    }

    suspend fun airlineName(d: Designator): String? =
        d.airlineIcao?.let { referenceDao.airlinesByIcao(it).firstOrNull()?.name }
            ?: d.airlineIata?.let { referenceDao.airlinesByIata(it).firstOrNull()?.name }
}
