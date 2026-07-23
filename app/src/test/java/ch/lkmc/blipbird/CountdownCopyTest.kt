package ch.lkmc.blipbird

import ch.lkmc.blipbird.ui.components.countdownText
import ch.lkmc.blipbird.ui.components.departsInText
import ch.lkmc.blipbird.ui.components.landsInText
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class CountdownCopyTest {

    @Test fun `future targets count down`() {
        assertEquals("Departs in 1h 30m", departsInText(Duration.ofMinutes(90)))
        assertEquals("Lands in 5m", landsInText(Duration.ofMinutes(5)))
    }

    @Test fun `past-due targets switch to in-progress copy, not a frozen zero`() {
        // DS4-V20: stale data must not read "Departs in 0m" forever.
        assertEquals("Departing…", departsInText(Duration.ofMinutes(-3)))
        assertEquals("Landing…", landsInText(Duration.ofSeconds(-1)))
    }

    @Test fun `zero is still a countdown, not in-progress`() {
        assertEquals("Departs in 0m", departsInText(Duration.ZERO))
    }

    @Test fun `countdownText granularity tiers`() {
        assertEquals("2d 1h", countdownText(Duration.ofHours(49)))
        assertEquals("3h 5m", countdownText(Duration.ofMinutes(185)))
        assertEquals("45m", countdownText(Duration.ofMinutes(45)))
    }
}
