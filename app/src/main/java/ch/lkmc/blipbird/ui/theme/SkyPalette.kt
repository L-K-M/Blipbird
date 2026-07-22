package ch.lkmc.blipbird.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Sky colors as a function of true solar elevation at the departure airport at
 * departure time — so a card's color/darkness reads as "when does this flight
 * leave": deep navy red-eye, orange sunrise, bright blue noon, purple dusk.
 *
 * Anchor elevations follow the USNO band edges used by DaylightEngine; between
 * anchors the three gradient stops lerp, so 07:00 and 09:00 departures differ.
 * Content is always white — cards add a bottom scrim for the light daytime skies.
 */
object SkyPalette {

    data class Sky(val top: Color, val mid: Color, val bottom: Color) {
        val content: Color get() = Color.White
        val contentDim: Color get() = Color(0xC3FFFFFF)
    }

    private class Anchor(val elevation: Double, val top: Color, val mid: Color, val bottom: Color)

    // night → astronomical → nautical → civil dusk → sunrise/sunset → golden → day → noon
    private val ANCHORS = listOf(
        Anchor(-90.0, Color(0xFF050A18), Color(0xFF0A1128), Color(0xFF101735)),
        Anchor(-18.0, Color(0xFF071026), Color(0xFF0D1734), Color(0xFF17224A)),
        Anchor(-12.0, Color(0xFF0D1B3E), Color(0xFF1D2A5C), Color(0xFF2E3A73)),
        Anchor(-6.0, Color(0xFF1C3164), Color(0xFF454687), Color(0xFF8D5E85)),
        Anchor(-0.833, Color(0xFF2C4E86), Color(0xFF8E6598), Color(0xFFEE8F62)),
        Anchor(6.0, Color(0xFF3C74B8), Color(0xFF82A9D6), Color(0xFFF2BE83)),
        Anchor(20.0, Color(0xFF3B82CC), Color(0xFF6FADE3), Color(0xFFA8D2F0)),
        Anchor(90.0, Color(0xFF2E86DC), Color(0xFF62A9E8), Color(0xFF98CCF4)),
    )

    /** Neutral slate when the departure position or time is unknown. */
    val UNKNOWN = Sky(Color(0xFF39445A), Color(0xFF313C51), Color(0xFF2A3447))

    fun forElevation(elevationDeg: Double?): Sky {
        if (elevationDeg == null) return UNKNOWN
        val e = elevationDeg.coerceIn(-90.0, 90.0)
        val i = ANCHORS.indexOfLast { it.elevation <= e }.coerceIn(0, ANCHORS.size - 2)
        val a = ANCHORS[i]
        val b = ANCHORS[i + 1]
        val f = ((e - a.elevation) / (b.elevation - a.elevation)).toFloat().coerceIn(0f, 1f)
        return Sky(lerp(a.top, b.top, f), lerp(a.mid, b.mid, f), lerp(a.bottom, b.bottom, f))
    }
}
