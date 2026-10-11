package ru.mars.network.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wire-and-marker contract of [RetryAfterPolicy]: delta-seconds and IMF-fixdate
 * `Retry-After` parsing, the cap, and the anchored "(retry-after <N>s)" message marker
 * the recovery coordinator reads back. Deterministic: the clock is injected.
 */
class RetryAfterPolicyTest {

    private val now = 1_760_000_000L // fixed Unix time for HTTP-date math

    // ---- delta-seconds ----

    @Test fun `delta-seconds parse with whitespace tolerance`() {
        assertEquals(30L, RetryAfterPolicy.parseSeconds("30", now))
        assertEquals(0L, RetryAfterPolicy.parseSeconds("0", now))
        assertEquals(120L, RetryAfterPolicy.parseSeconds("  120 ", now))
    }

    @Test fun `delta-seconds are clamped to the cap`() {
        assertEquals(
            RetryAfterPolicy.MAX_RETRY_AFTER_SECONDS,
            RetryAfterPolicy.parseSeconds("3600", now),
        )
    }

    @Test fun `invalid or absent directives parse to null`() {
        assertNull(RetryAfterPolicy.parseSeconds(null, now))
        assertNull(RetryAfterPolicy.parseSeconds("", now))
        assertNull(RetryAfterPolicy.parseSeconds("   ", now))
        assertNull(RetryAfterPolicy.parseSeconds("soon", now))
        assertNull(RetryAfterPolicy.parseSeconds("-5", now))
        assertNull(RetryAfterPolicy.parseSeconds("30.5", now))
    }

    // ---- HTTP-date (IMF-fixdate, GMT) ----

    @Test fun `http-date in the future becomes delta seconds`() {
        // now = 1760000000 -> Thu, 09 Oct 2025 08:53:20 GMT; +45 s:
        val date = "Thu, 09 Oct 2025 08:54:05 GMT"
        assertEquals(45L, RetryAfterPolicy.parseSeconds(date, now))
    }

    @Test fun `http-date already in the past floors to zero`() {
        assertEquals(0L, RetryAfterPolicy.parseSeconds("Thu, 09 Oct 2025 08:52:20 GMT", now))
    }

    @Test fun `a far-future http-date is clamped to the cap`() {
        assertEquals(
            RetryAfterPolicy.MAX_RETRY_AFTER_SECONDS,
            RetryAfterPolicy.parseSeconds("Fri, 21 Sep 2035 06:13:20 GMT", now),
        )
    }

    @Test fun `malformed http-date parses to null`() {
        assertNull(RetryAfterPolicy.parseSeconds("Sun, 32 XYZ 2025 99:99:99 GMT", now))
        assertNull(RetryAfterPolicy.parseSeconds("yesterday", now))
    }

    // ---- message marker round-trip ----

    @Test fun `marker formats the anchored shape and round-trips`() {
        assertEquals("(retry-after 30s)", RetryAfterPolicy.marker(30))
        assertEquals(30L, RetryAfterPolicy.parseMarkerSeconds("(retry-after 30s)"))
        assertEquals(
            7L,
            RetryAfterPolicy.parseMarkerSeconds("HTTP 429: slow down (retry-after 7s)"),
        )
    }

    @Test fun `marker values are clamped on both ends`() {
        assertEquals("(retry-after 600s)", RetryAfterPolicy.marker(3_600))
        assertEquals("(retry-after 0s)", RetryAfterPolicy.marker(-5))
        assertEquals(
            RetryAfterPolicy.MAX_RETRY_AFTER_SECONDS,
            RetryAfterPolicy.parseMarkerSeconds("(retry-after 999999999s)"),
        )
    }

    @Test fun `messages without the marker parse to null`() {
        assertNull(RetryAfterPolicy.parseMarkerSeconds(null))
        assertNull(RetryAfterPolicy.parseMarkerSeconds("HTTP 502: bad gateway"))
        assertNull(RetryAfterPolicy.parseMarkerSeconds("retry after 30 seconds"))
        // Near-misses of the anchored shape must not match:
        assertNull(RetryAfterPolicy.parseMarkerSeconds("(retry-after 30)"))
        assertNull(RetryAfterPolicy.parseMarkerSeconds("(retry-after xs)"))
    }

    @Test fun `the longest directive wins when messages stack`() {
        assertEquals(
            120L,
            RetryAfterPolicy.parseMarkerSeconds(
                "wrapped: HTTP 429 (retry-after 30s) via HTTP 503 (retry-after 120s)"
            ),
        )
    }
}
