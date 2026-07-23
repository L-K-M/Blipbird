package ch.lkmc.blipbird

import androidx.compose.ui.graphics.Color
import ch.lkmc.blipbird.ui.components.contrastRatio
import ch.lkmc.blipbird.ui.components.statusContentColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusContentColorTest {

    private val themeStatusBackgrounds = mapOf(
        "daylight on time" to Color(0xFF2E7D32),
        "daylight delayed" to Color(0xFFB26A00),
        "daylight cancelled" to Color(0xFFC62828),
        "daylight en route" to Color(0xFF1667D9),
        "daylight neutral" to Color(0xFF5F6368),
        "cockpit on time" to Color(0xFF53F2A0),
        "cockpit delayed" to Color(0xFFFFB454),
        "cockpit cancelled" to Color(0xFFFF6B6B),
        "cockpit en route" to Color(0xFF53D2F2),
        "cockpit neutral" to Color(0xFF7BA38C),
        "high contrast dark on time" to Color(0xFF7CFF9B),
        "high contrast dark delayed" to Color(0xFFFFD37C),
        "high contrast dark cancelled" to Color(0xFFFF8C8C),
        "high contrast dark en route" to Color(0xFF9CC7FF),
        "high contrast dark neutral" to Color(0xFFCCCCCC),
        "high contrast light on time" to Color(0xFF005E20),
        "high contrast light delayed" to Color(0xFF7A4A00),
        "high contrast light cancelled" to Color(0xFF9E0000),
        "high contrast light en route" to Color(0xFF003FA3),
        "high contrast light neutral" to Color(0xFF333333),
    )

    @Test
    fun `status foreground meets WCAG AA against every theme background`() {
        themeStatusBackgrounds.forEach { (name, background) ->
            val foreground = statusContentColor(background)
            assertTrue(contrastRatio(foreground, background) >= 4.5, name)
        }
    }

    @Test
    fun `status foreground deterministically chooses the higher contrast neutral`() {
        themeStatusBackgrounds.values.forEach { background ->
            val expected = if (contrastRatio(Color.Black, background) >= contrastRatio(Color.White, background)) {
                Color.Black
            } else {
                Color.White
            }
            assertEquals(expected, statusContentColor(background))
        }
    }
}
