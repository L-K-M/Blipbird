package ch.lkmc.blipbird

import ch.lkmc.blipbird.core.datastore.Accent
import ch.lkmc.blipbird.core.datastore.ThemeSpec
import ch.lkmc.blipbird.core.datastore.legacyThemeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSpecTest {

    @Test
    fun `accent serialization round trips`() {
        val accents = listOf(
            Accent.Dynamic,
            Accent.Cockpit,
            Accent.Seed(0xFF1667D9),
            Accent.Seed(0xFF000000),
            Accent.Seed(0xFFFFFFFF),
        )
        for (accent in accents) {
            assertEquals(accent, Accent.parse(accent.serialize()))
        }
    }

    @Test
    fun `seed serialization is a hex string`() {
        assertEquals("#1667D9", Accent.Seed(0xFF1667D9).serialize())
    }

    @Test
    fun `parse rejects garbage`() {
        assertNull(Accent.parse(null))
        assertNull(Accent.parse(""))
        assertNull(Accent.parse("#12345"))       // too short
        assertNull(Accent.parse("#12345G"))      // not hex
        assertNull(Accent.parse("1667D9"))       // missing #
        assertNull(Accent.parse("DAYLIGHT"))     // legacy enum name is not an accent
    }

    @Test
    fun `legacy themes map to equivalent specs`() {
        assertEquals(ThemeSpec(accent = Accent.Dynamic), legacyThemeSpec("DAYLIGHT_DYNAMIC"))
        assertEquals(ThemeSpec(accent = Accent.Seed(Accent.BRAND_SEED)), legacyThemeSpec("DAYLIGHT"))
        assertEquals(ThemeSpec(accent = Accent.Cockpit), legacyThemeSpec("COCKPIT"))
        val hc = legacyThemeSpec("HIGH_CONTRAST")!!
        assertTrue(hc.highContrast)
        assertNull(legacyThemeSpec(null))
        assertNull(legacyThemeSpec("SOMETHING_ELSE"))
    }
}
