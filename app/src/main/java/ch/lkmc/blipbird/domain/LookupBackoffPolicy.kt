package ch.lkmc.blipbird.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

enum class LookupOutcome {
    SUCCESS,
    NOT_FOUND,
    TRANSIENT_ERROR,
    NONRETRYABLE_ERROR,
}

/** Backoff for status lookups that did not produce a usable snapshot. */
object LookupBackoffPolicy {
    private val MIN_NOT_FOUND_DELAY: Duration = Duration.ofHours(6)
    private val MAX_NOT_FOUND_DELAY: Duration = Duration.ofHours(24)
    private val INITIAL_TRANSIENT_DELAY: Duration = Duration.ofMinutes(30)
    private val MAX_TRANSIENT_DELAY: Duration = Duration.ofHours(6)

    /**
     * Missing/rejected keys and validation errors have no provider-specific reset hook yet.
     * A manual refresh bypasses this state; background retries resume after this bounded pause.
     */
    val NONRETRYABLE_DELAY: Duration = Duration.ofDays(7)

    fun consecutiveFailures(
        outcome: LookupOutcome,
        previousOutcome: LookupOutcome?,
        previousFailures: Int,
    ): Int = when {
        outcome == LookupOutcome.SUCCESS -> 0
        previousOutcome == null || previousOutcome == LookupOutcome.SUCCESS -> 1
        else -> previousFailures.coerceAtLeast(0).let { if (it == Int.MAX_VALUE) it else it + 1 }
    }

    fun nextEligibleAt(
        outcome: LookupOutcome,
        consecutiveFailures: Int,
        requestedDate: LocalDate?,
        attemptedAt: Instant,
    ): Instant = attemptedAt.plus(delay(outcome, consecutiveFailures, requestedDate, attemptedAt))

    fun delay(
        outcome: LookupOutcome,
        consecutiveFailures: Int,
        requestedDate: LocalDate?,
        attemptedAt: Instant,
    ): Duration = when (outcome) {
        LookupOutcome.SUCCESS -> Duration.ZERO
        LookupOutcome.NOT_FOUND -> notFoundDelay(requestedDate, attemptedAt)
        LookupOutcome.TRANSIENT_ERROR -> {
            val exponent = (consecutiveFailures.coerceAtLeast(1) - 1).coerceAtMost(4)
            val delay = INITIAL_TRANSIENT_DELAY.multipliedBy(1L shl exponent)
            delay.coerceAtMost(MAX_TRANSIENT_DELAY)
        }
        LookupOutcome.NONRETRYABLE_ERROR -> NONRETRYABLE_DELAY
    }

    private fun notFoundDelay(requestedDate: LocalDate?, attemptedAt: Instant): Duration {
        if (requestedDate == null) return MIN_NOT_FOUND_DELAY

        val today = attemptedAt.atZone(ZoneOffset.UTC).toLocalDate()
        val daysUntil = ChronoUnit.DAYS.between(today, requestedDate)
        if (daysUntil < -1) return MAX_NOT_FOUND_DELAY
        if (daysUntil <= 2) return MIN_NOT_FOUND_DELAY

        // Retry at most daily until the requested date enters the normal 48-hour window.
        val windowStart = requestedDate.minusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant()
        return Duration.between(attemptedAt, windowStart)
            .coerceAtLeast(MIN_NOT_FOUND_DELAY)
            .coerceAtMost(MAX_NOT_FOUND_DELAY)
    }
}
