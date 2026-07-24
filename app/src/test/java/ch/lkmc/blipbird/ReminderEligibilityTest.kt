package ch.lkmc.blipbird

import ch.lkmc.blipbird.platform.reminderDeliveryEligible
import ch.lkmc.blipbird.platform.reminderDeliveryWorkName
import ch.lkmc.blipbird.platform.reminderScheduleWorkName
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReminderEligibilityTest {
    private val now = Instant.parse("2026-07-24T12:00:00Z")

    @Test
    fun postsOnlyAfterTriggerAndBeforeMilestone() {
        assertTrue(
            reminderDeliveryEligible(
                trigger = now.minusSeconds(1),
                milestone = now.plusSeconds(60),
                actual = null,
                now = now,
            )
        )
        assertFalse(
            reminderDeliveryEligible(
                trigger = now.plusSeconds(1),
                milestone = now.plusSeconds(60),
                actual = null,
                now = now,
            )
        )
        assertFalse(
            reminderDeliveryEligible(
                trigger = now.minusSeconds(60),
                milestone = now,
                actual = null,
                now = now,
            )
        )
    }

    @Test
    fun actualMovementSuppressesStaleAlarm() {
        assertFalse(
            reminderDeliveryEligible(
                trigger = now.minusSeconds(60),
                milestone = now.plusSeconds(60),
                actual = now.minusSeconds(30),
                now = now,
            )
        )
    }

    @Test
    fun firedDeliveryHasIndependentWorkIdentity() {
        assertFalse(
            reminderScheduleWorkName(42, "boarding") ==
                reminderDeliveryWorkName(42, "boarding")
        )
    }
}
