package ch.lkmc.blipbird.domain

import kotlin.math.roundToInt

/**
 * Plain-language rendering of the essentials of a METAR. Deliberately partial:
 * anything unrecognized stays visible in the raw string, which the UI always
 * offers one tap away.
 */
object MetarDecoder {

    private val WIND = Regex("\\b(\\d{3}|VRB)(\\d{2,3})(?:G(\\d{2,3}))?(KT|MPS)\\b")
    private val TEMP = Regex("\\b(M?\\d{2})/(M?\\d{2})\\b")
    private val CLOUD = Regex("\\b(FEW|SCT|BKN|OVC)(\\d{3})\\b")
    private val WX = mapOf(
        "RA" to "rain", "-RA" to "light rain", "+RA" to "heavy rain",
        "SN" to "snow", "-SN" to "light snow", "+SN" to "heavy snow",
        "TS" to "thunderstorms", "TSRA" to "thunderstorms with rain",
        "FG" to "fog", "BR" to "mist", "HZ" to "haze", "DZ" to "drizzle",
        "SH" to "showers", "SHRA" to "rain showers", "GR" to "hail",
    )
    /**
     * Precompiled per-phenomenon patterns, longest code first so e.g. `TSRA` is
     * considered before `RA`. Each code is [Regex.escape]d so the `+`/`-`
     * intensity prefixes are matched literally (the old `"(^| )$code( |$)"`
     * turned `+RA` into a quantifier and never matched heavy precipitation), and
     * bounded by lookarounds so a substring inside another token (`RA` inside
     * `TSRA`) cannot match.
     */
    private val WX_PATTERNS: List<Pair<Regex, String>> = WX.entries
        .sortedByDescending { it.key.length }
        .map { (code, desc) -> Regex("(?<=^| )" + Regex.escape(code) + "(?= |$)") to desc }
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
        val parts = mutableListOf<String>()
        var tempC: Double? = null
        var windDir: Int? = null
        var windKt: Int? = null
        var gustKt: Int? = null

        if (raw.contains("CAVOK")) parts += "clear skies and good visibility"

        // Ceiling = first BKN/OVC layer; fall back to the first cloud layer.
        val clouds = CLOUD.findAll(raw).toList()
        val ceiling = clouds.firstOrNull { it.groupValues[1] == "BKN" || it.groupValues[1] == "OVC" }
            ?: clouds.firstOrNull()
        ceiling?.let { m ->
            val kind = CLOUD_NAME[m.groupValues[1]] ?: return@let
            val feet = m.groupValues[2].toInt() * 100
            parts += "$kind at ${"%,d".format(feet)} ft"
        }

        // Collect every present-weather phenomenon (the old `break` dropped all
        // but the first, so `TSRA BR` lost the mist).
        val wx = WX_PATTERNS.mapNotNull { (rx, desc) -> if (rx.containsMatchIn(raw)) desc else null }.distinct()
        parts += wx

        WIND.find(raw)?.let { m ->
            val unit = m.groupValues[4]
            val factor = if (unit == "MPS") 1.94384 else 1.0
            windDir = m.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
            windKt = (m.groupValues[2].toInt() * factor).roundToInt()
            gustKt = m.groupValues[3].toIntOrNull()?.let { (it * factor).roundToInt() }
            parts += when {
                windDir == null && windKt == 0 -> { windKt = 0; "wind calm" }
                else -> buildString {
                    append("wind ${windKt} kt")
                    gustKt?.let { append(" gusting $it") }
                }
            }
        }

        TEMP.find(raw)?.let { m ->
            fun parse(s: String) = s.replace('M', '-').toDouble()
            tempC = parse(m.groupValues[1])
            parts += "${tempC!!.toInt()}°C"
        }

        val text = if (parts.isEmpty()) "See raw report" else
            parts.joinToString(", ").replaceFirstChar { it.uppercase() }
        return Decoded(text, tempC, windDir, windKt, gustKt)
    }
}
