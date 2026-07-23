package ch.lkmc.blipbird.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

enum class LookupOutcome {
    SUCCESS,
    NOT_FOUND,
    /** A provider answered 429 (the sharpest transient signal we can surface). */
    RATE_LIMITED,
    TRANSIENT_ERROR,
    /** Every keyed provider was skipped by the local quota ledger's soft stop. */
    QUOTA_EXHAUSTED,
    /** No provider has a key configured at all. */
    NO_KEY,
    NONRETRYABLE_ERROR,
    ;

    companion object {
        /**
         * Failure precedence for a chain pass that consulted several providers:
         * a definitive "no such flight" beats infrastructure noise; among the
         * rest, the most specific/most-retryable signal wins, and "no key" only
         * surfaces when nothing sharper (e.g. a *rejected* key) was seen.
         */
        private val FAILURE_PRECEDENCE = listOf(
            NOT_FOUND, RATE_LIMITED, TRANSIENT_ERROR,
            NONRETRYABLE_ERROR, QUOTA_EXHAUSTED, NO_KEY,
        )

        fun worstFailure(seen: Collection<LookupOutcome>): LookupOutcome =
            FAILURE_PRECEDENCE.firstOrNull { it in seen } ?: NONRETRYABLE_ERROR
    }
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
        LookupOutcome.RATE_LIMITED, LookupOutcome.TRANSIENT_ERROR -> {
            val exponent = (consecutiveFailures.coerceAtLeast(1) - 1).coerceAtMost(4)
            val delay = INITIAL_TRANSIENT_DELAY.multipliedBy(1L shl exponent)
            delay.coerceAtMost(MAX_TRANSIENT_DELAY)
        }
        LookupOutcome.QUOTA_EXHAUSTED -> {
            // Ledger periods are UTC calendar months (QuotaLedger.periodKey):
            // retry at the rollover, bounded to daily so a freed budget or a
            // newly added key isn't missed for weeks, with an hour floor.
            val rollover = attemptedAt.atZone(ZoneOffset.UTC).toLocalDate()
                .withDayOfMonth(1).plusMonths(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant()
            Duration.between(attemptedAt, rollover)
                .coerceIn(Duration.ofHours(1), Duration.ofHours(24))
        }
        LookupOutcome.NO_KEY -> NONRETRYABLE_DELAY
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
