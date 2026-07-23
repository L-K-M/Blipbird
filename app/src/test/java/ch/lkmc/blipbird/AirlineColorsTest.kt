package ch.lkmc.blipbird

import androidx.compose.ui.graphics.Color
import ch.lkmc.blipbird.ui.components.contrastRatio
import ch.lkmc.blipbird.ui.components.monogramColor
import ch.lkmc.blipbird.ui.components.monogramContentColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AirlineColorsTest {

    @Test fun `known carriers resolve to their brand color, not the hash palette`() {
        assertEquals(Color(0xFF05164D), monogramColor("LH"))
        assertEquals(Color(0xFFE30614), monogramColor("LX"))
        assertEquals(Color(0xFF0078D2), monogramColor("AA"))
        assertEquals(Color(0xFF011E41), monogramColor("SQ"))
    }

    @Test fun `lookup is case- and whitespace-insensitive`() {
        assertEquals(monogramColor("LX"), monogramColor("lx"))
        assertEquals(monogramColor("LX"), monogramColor(" lx "))
    }

    @Test fun `unknown codes fall back to a deterministic palette color`() {
        val first = monogramColor("ZZ9")
        val second = monogramColor("ZZ9")
        assertEquals(first, second)
        // and different unknown codes can differ (sanity that it's not one constant)
        assertNotEquals(monogramColor("Q0"), monogramColor("Q3"))
    }

    @Test fun `monogram foreground meets WCAG AA on every brand and fallback color`() {
        // Brand table entries (sampled across the palette extremes: near-black
        // United, Spirit yellow, airBaltic lime) plus arbitrary fallback codes.
        val codes = listOf(
            "UA", "NK", "BT", "VY", "NZ", "G4", "TR", "5J", "AR", "KE", "S7",
            "LH", "LX", "AA", "DL", "EK", "QF", "SQ", "W6", "U2",
            "ZZ", "Q1", "Q2", "Q3", "Q4", "Q5", "Q6", "Q7", "Q8", "Q9", "XX", "YY",
        )
        codes.forEach { code ->
            val ratio = contrastRatio(monogramContentColor(code), monogramColor(code))
            assertTrue(ratio >= 4.5, "monogram foreground on $code has contrast $ratio")
        }
    }
}
