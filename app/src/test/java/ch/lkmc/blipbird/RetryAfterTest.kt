package ch.lkmc.blipbird

import ch.lkmc.blipbird.domain.RetryAfter
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetryAfterTest {

    private val now = Instant.parse("2026-07-22T12:00:00Z")

    @Test
    fun `delta seconds form`() {
        assertEquals(Duration.ofSeconds(120), RetryAfter.parse("120", now))
        assertEquals(Duration.ofSeconds(120), RetryAfter.parse(" 120 ", now))
    }

    @Test
    fun `http date form`() {
        assertEquals(
            Duration.ofMinutes(30),
            RetryAfter.parse("Wed, 22 Jul 2026 12:30:00 GMT", now),
        )
    }

    @Test
    fun `past date clamps to zero`() {
        assertEquals(
            Duration.ZERO,
            RetryAfter.parse("Wed, 22 Jul 2026 11:00:00 GMT", now),
        )
    }

    @Test
    fun `bounded to a day`() {
        assertEquals(RetryAfter.MAX, RetryAfter.parse("9999999", now))
    }

    @Test
    fun `garbage and blanks are null`() {
        assertNull(RetryAfter.parse("soon", now))
        assertNull(RetryAfter.parse("", now))
        assertNull(RetryAfter.parse("   ", now))
        assertNull(RetryAfter.parse(null, now))
    }
}
