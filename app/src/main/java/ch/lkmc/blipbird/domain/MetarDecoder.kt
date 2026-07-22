package ch.lkmc.blipbird.domain

import java.util.Locale

/**
 * Plain-language rendering of the essentials of a METAR. Deliberately partial:
 * anything unrecognized stays visible in the raw string, which the UI always
 * offers alongside.
 *
 * Works token-by-token (METAR groups are space-separated), so intensity
 * prefixes like "+RA" match exactly instead of being lost to regex escaping,
 * and multiple phenomena are all reported.
 */
object MetarDecoder {

    private val WIND = Regex("^(\\d{3}|VRB)(\\d{2,3})(?:G(\\d{2,3}))?KT$")
    private val VIS_M = Regex("^(\\d{4})$")
    private val VIS_SM = Regex("^(\\d+)(?:/(\\d+))?SM$")
    private val TEMP = Regex("^(M?\\d{2})/(M?\\d{2})$")
    private val CLOUD = Regex("^(FEW|SCT|BKN|OVC)(\\d{3})(?:CB|TCU)?$")

    /** Exact-token lookup; order in the map is irrelevant. */
    private val WX = mapOf(
        "+TSRA" to "heavy thunderstorms with rain",
        "-TSRA" to "light thunderstorms with rain",
        "TSRA" to "thunderstorms with rain",
        "TS" to "thunderstorms",
        "+RA" to "heavy rain", "-RA" to "light rain", "RA" to "rain",
        "+SN" to "heavy snow", "-SN" to "light snow", "SN" to "snow",
        "+SHRA" to "heavy rain showers", "-SHRA" to "light rain showers",
        "SHRA" to "rain showers", "SH" to "showers",
        "FZRA" to "freezing rain", "FZDZ" to "freezing drizzle",
        "-DZ" to "light drizzle", "DZ" to "drizzle",
        "FG" to "fog", "BR" to "mist", "HZ" to "haze", "GR" to "hail",
    )
    private val CLOUD_NAME = mapOf(
        "FEW" to "few clouds", "SCT" to "scattered clouds",
        "BKN" to "broken clouds", "OVC" to "overcast",
    )

    data class Decoded(
        val text: String,
        val temperatureC: Double?,
        val windDirDeg: Int?,
        val windSpeedKt: Int?,
        val windGustKt: Int?,
    )

    fun decode(raw: String): Decoded {
        val tokens = raw.trim().split(Regex("\\s+"))
        val parts = mutableListOf<String>()
        var tempC: Double? = null
        var windDir: Int? = null
        var windKt: Int? = null
        var gustKt: Int? = null

        if ("CAVOK" in tokens) parts += "clear skies and good visibility"

        // Most significant (last reported) cloud layer.
        tokens.mapNotNull { CLOUD.matchEntire(it) }.lastOrNull()?.let { m ->
            val kind = CLOUD_NAME[m.groupValues[1]] ?: return@let
            val feet = m.groupValues[2].toInt() * 100
            parts += "$kind at ${"%,d".format(Locale.ROOT, feet)} ft"
        }

        // All present-weather phenomena, in report order.
        parts += tokens.mapNotNull { WX[it] }.distinct()

        // Visibility — only when notable (below 10 km / 6 SM).
        tokens.firstNotNullOfOrNull { VIS_M.matchEntire(it) }?.let { m ->
            val meters = m.groupValues[1].toInt()
            if (meters < 9999) {
                parts += when {
                    meters % 1000 == 0 -> "visibility ${meters / 1000} km"
                    meters >= 1000 -> "visibility ${"%.1f".format(Locale.ROOT, meters / 1000.0)} km"
                    else -> "visibility $meters m"
                }
            }
        } ?: tokens.firstNotNullOfOrNull { VIS_SM.matchEntire(it) }?.let { m ->
            val whole = m.groupValues[1].toDouble()
            val denom = m.groupValues[2].toDoubleOrNull()
            val miles = if (denom != null && denom > 0) whole / denom else whole
            if (miles < 6.0) {
                parts += if (miles < 1.0) "visibility ${"%.1f".format(Locale.ROOT, miles)} mi"
                else "visibility ${miles.toInt()} mi"
            }
        }

        tokens.firstNotNullOfOrNull { WIND.matchEntire(it) }?.let { m ->
            val dirRaw = m.groupValues[1]
            windDir = dirRaw.toIntOrNull()
            windKt = m.groupValues[2].toInt()
            gustKt = m.groupValues[3].toIntOrNull()
            parts += buildString {
                when {
                    windKt == 0 -> append("calm wind")
                    dirRaw == "VRB" -> append("variable wind $windKt kt")
                    else -> append("wind $windDir° $windKt kt")
                }
                gustKt?.let { append(" gusting $it") }
            }
        }

        tokens.firstNotNullOfOrNull { TEMP.matchEntire(it) }?.let { m ->
            fun parse(s: String) = s.replace('M', '-').toDouble()
            tempC = parse(m.groupValues[1])
            parts += "${tempC!!.toInt()}°C"
        }

        val text = if (parts.isEmpty()) "See raw report" else
            parts.joinToString(", ").replaceFirstChar { it.uppercase() }
        return Decoded(text, tempC, windDir, windKt, gustKt)
    }
}
