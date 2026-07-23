package ch.lkmc.blipbird.domain

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Parser for the HTTP `Retry-After` header (RFC 9110 §10.2.3): either
 * delta-seconds or an HTTP-date. Bounded so a hostile or buggy header can't
 * wedge lookups for days.
 */
object RetryAfter {

    /** Upper bound on an honored Retry-After. */
    val MAX: Duration = Duration.ofHours(24)

    fun parse(value: String?, now: Instant): Duration? {
        val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val delay = v.toLongOrNull()?.let { Duration.ofSeconds(it) }
            ?: runCatching {
                val at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                Duration.between(now, at)
            }.getOrNull()
            ?: return null
        return delay.coerceAtLeast(Duration.ZERO).coerceAtMost(MAX)
    }
}
