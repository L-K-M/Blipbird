package ch.lkmc.blipbird

import androidx.compose.ui.graphics.Color
import ch.lkmc.blipbird.ui.components.contrastRatio
import ch.lkmc.blipbird.ui.theme.accentColorScheme
import ch.lkmc.blipbird.ui.theme.hslColor
import ch.lkmc.blipbird.ui.theme.toHsl
import ch.lkmc.blipbird.ui.theme.toHsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The accent engine must produce readable schemes for ANY seed the picker can
 * emit — including the pathological ones (pure yellow, black, white, neon).
 */
class AccentSchemeTest {

    private val seeds = listOf(
        0xFF1667D9, // brand blue
        0xFFFFEB3B, // lemon yellow (worst case on white)
        0xFF39FF14, // neon green
        0xFF000000, // black
        0xFFFFFFFF, // white
        0xFF808080, // pure grey
        0xFFD6336C, // rose
        0xFF00FFFF, // cyan
        0xFFFF0000, // red
        0xFF5C6B7A, // slate
    )

    @Test
    fun `light primary always readable under white text`() {
        for (seed in seeds) {
            val scheme = accentColorScheme(seed, dark = false)
            val ratio = contrastRatio(scheme.onPrimary, scheme.primary)
            assertTrue("seed %06X: onPrimary/primary ratio $ratio".format(seed and 0xFFFFFF), ratio >= 4.5)
        }
    }

    @Test
    fun `light secondary and tertiary always readable under white text`() {
        for (seed in seeds) {
            val scheme = accentColorScheme(seed, dark = false)
            assertTrue(contrastRatio(scheme.onSecondary, scheme.secondary) >= 4.5)
            assertTrue(contrastRatio(scheme.onTertiary, scheme.tertiary) >= 4.5)
        }
    }

    @Test
    fun `container pairs stay readable in both modes`() {
        for (seed in seeds) {
            for (dark in listOf(false, true)) {
                val scheme = accentColorScheme(seed, dark)
                val ratio = contrastRatio(scheme.onPrimaryContainer, scheme.primaryContainer)
                assertTrue(
                    "seed %06X dark=$dark container ratio $ratio".format(seed and 0xFFFFFF),
                    ratio >= 4.5,
                )
            }
        }
    }

    @Test
    fun `dark primary readable against its onPrimary`() {
        for (seed in seeds) {
            val scheme = accentColorScheme(seed, dark = true)
            assertTrue(contrastRatio(scheme.onPrimary, scheme.primary) >= 4.5)
        }
    }

    @Test
    fun `hsl round trip preserves color`() {
        for (seed in seeds) {
            val original = Color(seed)
            val hsl = original.toHsl()
            val restored = hslColor(hsl.h, hsl.s, hsl.l)
            assertEquals(original.red, restored.red, 0.01f)
            assertEquals(original.green, restored.green, 0.01f)
            assertEquals(original.blue, restored.blue, 0.01f)
        }
    }

    @Test
    fun `hsv round trip via compose hsv builder preserves color`() {
        for (seed in seeds) {
            val original = Color(seed)
            val (h, s, v) = original.toHsv()
            val restored = Color.hsv(h, s, v)
            assertEquals(original.red, restored.red, 0.01f)
            assertEquals(original.green, restored.green, 0.01f)
            assertEquals(original.blue, restored.blue, 0.01f)
        }
    }

    @Test
    fun `greyscale seeds stay hueless`() {
        val hsl = Color(0xFF808080).toHsl()
        assertEquals(0f, hsl.s, 1e-6f)
        assertTrue(abs(Color(0xFF808080).toHsv().second) < 1e-6f)
    }
}
