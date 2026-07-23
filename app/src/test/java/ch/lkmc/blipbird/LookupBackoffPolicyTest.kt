package ch.lkmc.blipbird

import ch.lkmc.blipbird.domain.LookupBackoffPolicy
import ch.lkmc.blipbird.domain.LookupOutcome
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LookupBackoffPolicyTest {
    private val now = Instant.parse("2026-07-22T12:00:00Z")

    @Test fun `not found waits at least far-future cadence`() {
        val delay = LookupBackoffPolicy.delay(LookupOutcome.NOT_FOUND, 1, null, now)
        assertTrue(delay >= Duration.ofHours(6))
    }

    @Test fun `near requested date uses six-hour negative cache`() {
        assertEquals(
            Duration.ofHours(6),
            LookupBackoffPolicy.delay(
                LookupOutcome.NOT_FOUND, 1, LocalDate.parse("2026-07-24"), now,
            ),
        )
    }

    @Test fun `far requested date is bounded to daily retry`() {
        assertEquals(
            Duration.ofHours(24),
            LookupBackoffPolicy.delay(
                LookupOutcome.NOT_FOUND, 1, LocalDate.parse("2026-08-22"), now,
            ),
        )
    }

    @Test fun `transient errors back off exponentially with cap`() {
        assertEquals(Duration.ofMinutes(30), transientDelay(1))
        assertEquals(Duration.ofHours(1), transientDelay(2))
        assertEquals(Duration.ofHours(2), transientDelay(3))
        assertEquals(Duration.ofHours(6), transientDelay(20))
    }

    @Test fun `nonretryable error gets long bounded pause`() {
        assertEquals(
            Duration.ofDays(7),
            LookupBackoffPolicy.delay(LookupOutcome.NONRETRYABLE_ERROR, 1, null, now),
        )
    }

    @Test fun `no key pauses like a nonretryable error`() {
        assertEquals(
            Duration.ofDays(7),
            LookupBackoffPolicy.delay(LookupOutcome.NO_KEY, 1, null, now),
        )
    }

    @Test fun `rate limited backs off like a transient error`() {
        assertEquals(
            Duration.ofMinutes(30),
            LookupBackoffPolicy.delay(LookupOutcome.RATE_LIMITED, 1, null, now),
        )
        assertEquals(
            Duration.ofHours(1),
            LookupBackoffPolicy.delay(LookupOutcome.RATE_LIMITED, 2, null, now),
        )
    }

    @Test fun `quota exhaustion mid-month retries daily`() {
        assertEquals(
            Duration.ofHours(24),
            LookupBackoffPolicy.delay(LookupOutcome.QUOTA_EXHAUSTED, 1, null, now),
        )
    }

    @Test fun `quota exhaustion near month rollover retries at the rollover`() {
        val nearRollover = Instant.parse("2026-07-31T20:00:00Z")
        assertEquals(
            Duration.ofHours(4),
            LookupBackoffPolicy.delay(LookupOutcome.QUOTA_EXHAUSTED, 1, null, nearRollover),
        )
        // ...with an hour floor right before the boundary.
        val justBefore = Instant.parse("2026-07-31T23:59:00Z")
        assertEquals(
            Duration.ofHours(1),
            LookupBackoffPolicy.delay(LookupOutcome.QUOTA_EXHAUSTED, 1, null, justBefore),
        )
    }

    @Test fun `worst failure follows precedence`() {
        assertEquals(
            LookupOutcome.NOT_FOUND,
            LookupOutcome.worstFailure(
                setOf(LookupOutcome.NOT_FOUND, LookupOutcome.RATE_LIMITED, LookupOutcome.NO_KEY),
            ),
        )
        assertEquals(
            LookupOutcome.RATE_LIMITED,
            LookupOutcome.worstFailure(
                setOf(LookupOutcome.RATE_LIMITED, LookupOutcome.QUOTA_EXHAUSTED),
            ),
        )
        // A *rejected* key is sharper than a missing one.
        assertEquals(
            LookupOutcome.NONRETRYABLE_ERROR,
            LookupOutcome.worstFailure(
                setOf(LookupOutcome.NO_KEY, LookupOutcome.NONRETRYABLE_ERROR),
            ),
        )
        assertEquals(
            LookupOutcome.QUOTA_EXHAUSTED,
            LookupOutcome.worstFailure(
                setOf(LookupOutcome.NO_KEY, LookupOutcome.QUOTA_EXHAUSTED),
            ),
        )
        assertEquals(LookupOutcome.NO_KEY, LookupOutcome.worstFailure(setOf(LookupOutcome.NO_KEY)))
        assertEquals(LookupOutcome.NONRETRYABLE_ERROR, LookupOutcome.worstFailure(emptySet()))
    }

    @Test fun `success is immediately eligible and resets delay`() {
        assertEquals(now, LookupBackoffPolicy.nextEligibleAt(LookupOutcome.SUCCESS, 0, null, now))
        assertEquals(
            0,
            LookupBackoffPolicy.consecutiveFailures(
                LookupOutcome.SUCCESS, LookupOutcome.TRANSIENT_ERROR, 4,
            ),
        )
    }

    @Test fun `failure count starts and increments after failures`() {
        assertEquals(
            1,
            LookupBackoffPolicy.consecutiveFailures(LookupOutcome.NOT_FOUND, null, 0),
        )
        assertEquals(
            3,
            LookupBackoffPolicy.consecutiveFailures(
                LookupOutcome.TRANSIENT_ERROR, LookupOutcome.NOT_FOUND, 2,
            ),
        )
    }

    private fun transientDelay(failures: Int): Duration = LookupBackoffPolicy.delay(
        LookupOutcome.TRANSIENT_ERROR, failures, null, now,
    )
}
