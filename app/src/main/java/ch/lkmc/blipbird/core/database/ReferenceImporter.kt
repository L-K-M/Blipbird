package ch.lkmc.blipbird.core.database

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the bundled reference CSVs (assets/reference/, produced by
 * scripts/generate_reference_data.py) into the ops database. Idempotent; runs at
 * startup when the tables are empty OR when the bundled dataset changed — the
 * lockfile fingerprint of the last import is kept so an app update with
 * regenerated CSVs re-imports instead of serving first-install data forever.
 */
@Singleton
class ReferenceImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ops: OpsDatabase,
) {
    private companion object {
        const val PREFS = "reference_import"
        const val KEY_FINGERPRINT = "lockfile_fingerprint"
        const val LOCKFILE = "reference/data-sources.lock.json"
    }

    suspend fun ensureImported() = withContext(Dispatchers.IO) {
        val dao = ops.referenceDao()
        val fingerprint = lockfileFingerprint()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (dao.airportCount() > 0 && prefs.getString(KEY_FINGERPRINT, null) == fingerprint) {
            return@withContext
        }
        // One transaction for wipe + refill: concurrent readers keep seeing the
        // old dataset until commit instead of an empty or half-filled table.
        ops.withTransaction {
            dao.clearAirports()
            dao.clearAirlines()

            context.assets.open("reference/airports.csv").bufferedReader().use { reader ->
                val batch = ArrayList<AirportEntity>(1024)
                CsvReader.records(reader).drop(1).forEach { f ->
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

            context.assets.open("reference/airlines.csv").bufferedReader().use { reader ->
                val batch = ArrayList<AirlineEntity>(512)
                CsvReader.records(reader).drop(1).forEach { f ->
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

        prefs.edit().putString(KEY_FINGERPRINT, fingerprint).apply()
    }

    /**
     * Fingerprint of the bundled dataset: an MD5 of the provenance lockfile,
     * which the generator rewrites (fetch date, sha256 per source) whenever the
     * CSVs are regenerated.
     */
    private fun lockfileFingerprint(): String = runCatching {
        val bytes = context.assets.open(LOCKFILE).readBytes()
        java.security.MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }.getOrDefault("missing-lockfile")
}
