package ch.lkmc.blipbird.core.database

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the bundled reference CSVs (assets/reference/, produced by
 * scripts/generate_reference_data.py) into the ops database. Idempotent; runs at
 * startup when the tables are empty.
 */
@Singleton
class ReferenceImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ops: OpsDatabase,
) {
    suspend fun ensureImported() = withContext(Dispatchers.IO) {
        val dao = ops.referenceDao()
        if (dao.airportCount() > 0) return@withContext

        context.assets.open("reference/airports.csv").bufferedReader().useLines { lines ->
            val batch = ArrayList<AirportEntity>(1024)
            lines.drop(1).forEach { line ->
                val f = parseCsvLine(line)
                if (f.size >= 8) {
                    batch += AirportEntity(
                        icao = f[0].ifEmpty { null },
                        iata = f[1].ifEmpty { null },
                        name = f[2],
                        city = f[3].ifEmpty { null },
                        country = f[4].ifEmpty { null },
                        lat = f[5].toDoubleOrNull(),
                        lon = f[6].toDoubleOrNull(),
                        tz = f[7].ifEmpty { null },
                    )
                    if (batch.size == 1024) { dao.insertAirports(batch.toList()); batch.clear() }
                }
            }
            if (batch.isNotEmpty()) dao.insertAirports(batch)
        }

        context.assets.open("reference/airlines.csv").bufferedReader().useLines { lines ->
            val batch = ArrayList<AirlineEntity>(512)
            lines.drop(1).forEach { line ->
                val f = parseCsvLine(line)
                if (f.size >= 3 && f[2].isNotEmpty()) {
                    batch += AirlineEntity(
                        icao = f[0].ifEmpty { null },
                        iata = f[1].ifEmpty { null },
                        name = f[2],
                    )
                    if (batch.size == 512) { dao.insertAirlines(batch.toList()); batch.clear() }
                }
            }
            if (batch.isNotEmpty()) dao.insertAirlines(batch)
        }
    }

    /** Minimal RFC-4180 line parser (fields may be quoted and contain commas). */
    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>(8)
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
