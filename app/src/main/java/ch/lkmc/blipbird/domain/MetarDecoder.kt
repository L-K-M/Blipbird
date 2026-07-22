package ch.lkmc.blipbird.domain

import java.util.Locale
import kotlin.math.roundToInt

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

    private val WIND = Regex("^(\\d{3}|VRB)(\\d{2,3})(?:G(\\d{2,3}))?(KT|MPS)$")
    private val VIS_M = Regex("^(\\d{4})$")
    private val VIS_SM = Regex("^(?:(\\d+) )?(\\d+)(?:/(\\d+))?SM$")
    private val VIS_SM_FRACTION = Regex("^\\d+/\\d+SM$")
    private const val MPS_TO_KT = 1.94384
    private val TEMP = Regex("^(M?\\d{2})/(M?\\d{2})$")
    private val CLOUD = Regex("^(FEW|SCT|BKN|OVC)(\\d{3})(CB|TCU)?$")

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
        val tokens = joinMixedNumberVisibility(raw.trim().split(Regex("\\s+")))
        val parts = mutableListOf<String>()
        var tempC: Double? = null
        var windDir: Int? = null
        var windKt: Int? = null
        var gustKt: Int? = null

        if ("CAVOK" in tokens) parts += "clear skies and good visibility"

        // Ceiling = first (lowest-reported) BKN/OVC layer — `BKN025 OVC100` is a
        // 2,500 ft ceiling, not "overcast at 10,000 ft" — else the first cloud
        // layer, with convective marker.
        val clouds = tokens.mapNotNull { CLOUD.matchEntire(it) }
        val ceiling = clouds.firstOrNull { it.groupValues[1] == "BKN" || it.groupValues[1] == "OVC" }
            ?: clouds.firstOrNull()
        ceiling?.let { m ->
            val kind = CLOUD_NAME[m.groupValues[1]] ?: return@let
            val feet = m.groupValues[2].toInt() * 100
            val convective = when (m.groupValues[3]) {
                "CB" -> " (cumulonimbus)"
                "TCU" -> " (towering cumulus)"
                else -> ""
            }
            parts += "$kind at ${"%,d".format(Locale.ROOT, feet)} ft$convective"
        }

        // Visibility — only when notable (below 10 km / 6 SM).
        val meters = tokens.firstNotNullOfOrNull { VIS_M.matchEntire(it) }
            ?.groupValues?.get(1)?.toInt()
        // METAR meter visibility steps end at 9000; 9999 means "10 km or more",
        // and near-9999 values must not round up to a misleading "10.0 km".
        if (meters != null && meters <= 9000) {
            parts += when {
                meters % 1000 == 0 -> "visibility ${meters / 1000} km"
                meters >= 1000 -> "visibility ${"%.1f".format(Locale.ROOT, meters / 1000.0)} km"
                else -> "visibility $meters m"
            }
        } else if (meters == null) {
            tokens.firstNotNullOfOrNull { VIS_SM.matchEntire(it) }?.let { m ->
                val leading = m.groupValues[1].toDoubleOrNull() ?: 0.0
                val whole = m.groupValues[2].toDouble()
                val denom = m.groupValues[3].toDoubleOrNull()
                // Hundredths keep common fractions honest (3/4 SM -> 0.75 mi)
                // while float noise still can't turn "2" into "2.0".
                val miles = ((leading + if (denom != null && denom > 0) whole / denom else whole) * 100)
                    .roundToInt() / 100.0
                if (miles < 6.0) {
                    val text = when {
                        miles % 1.0 == 0.0 -> miles.toInt().toString()
                        (miles * 10) % 1.0 == 0.0 -> "%.1f".format(Locale.ROOT, miles)
                        else -> "%.2f".format(Locale.ROOT, miles)
                    }
                    parts += "visibility $text mi"
                }
            }
        }

        // All present-weather phenomena, in report order; unknown intensity
        // variants fall back to the base code with a heavy/light prefix.
        parts += tokens.mapNotNull { token ->
            WX[token] ?: WX[token.removePrefix("+").removePrefix("-")]?.let { base ->
                when (token.firstOrNull()) {
                    '+' -> "heavy $base"
                    '-' -> "light $base"
                    else -> base
                }
            }
        }.distinct()

        tokens.firstNotNullOfOrNull { WIND.matchEntire(it) }?.let { m ->
            val dirRaw = m.groupValues[1]
            val mps = m.groupValues[4] == "MPS"
            fun toKt(v: Int): Int = if (mps) (v * MPS_TO_KT).roundToInt() else v
            windDir = dirRaw.toIntOrNull()
            windKt = toKt(m.groupValues[2].toInt())
            gustKt = m.groupValues[3].toIntOrNull()?.let(::toKt)
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

    /** Re-joins US mixed-number visibility split by whitespace: "1" + "1/2SM" → "1 1/2SM". */
    private fun joinMixedNumberVisibility(rawTokens: List<String>): List<String> {
        val out = ArrayList<String>(rawTokens.size)
        var i = 0
        while (i < rawTokens.size) {
            val cur = rawTokens[i]
            val next = rawTokens.getOrNull(i + 1)
            if (next != null && cur.toIntOrNull() != null && VIS_SM_FRACTION.matches(next)) {
                out += "$cur $next"
                i += 2
            } else {
                out += cur
                i++
            }
        }
        return out
    }
}
