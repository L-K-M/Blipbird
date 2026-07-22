package ch.lkmc.blipbird.domain

/**
 * Plain-language rendering of the essentials of a METAR. Deliberately partial:
 * anything unrecognized stays visible in the raw string, which the UI always
 * offers one tap away.
 */
object MetarDecoder {

    private val WIND = Regex("\\b(\\d{3}|VRB)(\\d{2,3})(?:G(\\d{2,3}))?KT\\b")
    private val VIS_M = Regex("\\b(\\d{4})\\b")
    private val TEMP = Regex("\\b(M?\\d{2})/(M?\\d{2})\\b")
    private val CLOUD = Regex("\\b(FEW|SCT|BKN|OVC)(\\d{3})\\b")
    private val WX = mapOf(
        "RA" to "rain", "-RA" to "light rain", "+RA" to "heavy rain",
        "SN" to "snow", "-SN" to "light snow", "+SN" to "heavy snow",
        "TS" to "thunderstorms", "TSRA" to "thunderstorms with rain",
        "FG" to "fog", "BR" to "mist", "HZ" to "haze", "DZ" to "drizzle",
        "SH" to "showers", "SHRA" to "rain showers", "GR" to "hail",
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
        val parts = mutableListOf<String>()
        var tempC: Double? = null
        var windDir: Int? = null
        var windKt: Int? = null
        var gustKt: Int? = null

        if (raw.contains("CAVOK")) parts += "clear skies and good visibility"

        CLOUD.findAll(raw).lastOrNull()?.let { m ->
            val kind = CLOUD_NAME[m.groupValues[1]] ?: return@let
            val feet = m.groupValues[2].toInt() * 100
            parts += "$kind at ${"%,d".format(feet)} ft"
        }

        for ((code, desc) in WX) {
            if (Regex("(^| )$code( |$)").containsMatchIn(raw)) { parts += desc; break }
        }

        WIND.find(raw)?.let { m ->
            windDir = m.groupValues[1].toIntOrNull()
            windKt = m.groupValues[2].toInt()
            gustKt = m.groupValues[3].toIntOrNull()
            parts += buildString {
                append("wind ${windKt} kt")
                gustKt?.let { append(" gusting $it") }
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
