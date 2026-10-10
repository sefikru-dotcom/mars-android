package ru.mars.network.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.random.Random

/**
 * Schedule and classification tests for [StreamRecoveryBackoffPolicy]: exponential growth
 * with a cap, jitter inside its band, deterministic with a seeded [Random], immediate
 * retry after an unavailable -> available network transition, and the shouldRetry
 * decision table (401/403/404 never, timeouts / 5xx / IOException always).
 */
class StreamRecoveryBackoffTest {

    /** Deterministic policy: no jitter, 100 ms -> 200 -> 400 … capped at 10 s. */
    private fun flatPolicy(maxAttempts: Int = 10) = StreamRecoveryBackoffPolicy(
        initialDelayMs = 100L,
        maxDelayMs = 10_000L,
        jitterFraction = 0.0,
        maxAttempts = maxAttempts,
        random = Random(1),
    )

    // ---------- delay schedule ----------

    @Test
    fun delayGrowsExponentiallyFromInitial() {
        val policy = flatPolicy()
        assertEquals(100L, policy.nextDelayMs(0))
        assertEquals(200L, policy.nextDelayMs(1))
        assertEquals(400L, policy.nextDelayMs(2))
        assertEquals(800L, policy.nextDelayMs(3))
        assertEquals(1600L, policy.nextDelayMs(4))
    }

    @Test
    fun delayIsCappedAtMaxAndNeverOverflows() {
        val policy = flatPolicy()
        assertEquals(10_000L, policy.nextDelayMs(7))
        assertEquals(10_000L, policy.nextDelayMs(30))
        // Astronomical attempt must not hang, overflow or exceed the cap.
        assertEquals(10_000L, policy.nextDelayMs(Int.MAX_VALUE))
    }

    @Test
    fun negativeAttemptIsTreatedAsZero() {
        assertEquals(100L, flatPolicy().nextDelayMs(-5))
    }

    @Test
    fun jitterStaysWithinItsBand() {
        val policy = StreamRecoveryBackoffPolicy(
            initialDelayMs = 1_000L,
            maxDelayMs = 60_000L,
            jitterFraction = 0.2,
            random = Random(7),
        )
        // Attempts 0..5: 1_000 * 2^5 = 32_000 stays below the 60 s cap, so the
        // theoretical band and the actually-applied base match.
        for (attempt in 0..5) {
            val base = 1_000L shl attempt // 2.0 factor, all below the cap
            repeat(50) {
                val delay = policy.nextDelayMs(attempt)
                assertTrue(
                    "attempt=$attempt delay=$delay outside band around base=$base",
                    delay >= (base * 0.8).toLong() && delay <= (base * 1.2).toLong() + 1,
                )
            }
        }
    }

    @Test
    fun jitteredDelayNeverLeavesZeroToMaxRange() {
        val policy = StreamRecoveryBackoffPolicy(
            initialDelayMs = 500L,
            maxDelayMs = 5_000L,
            jitterFraction = 1.0, // extreme jitter: delay may fall to ~0
            random = Random(99),
        )
        for (attempt in 0..20) {
            repeat(30) {
                val delay = policy.nextDelayMs(attempt)
                assertTrue("delay=$delay negative", delay >= 0L)
                assertTrue("delay=$delay over cap", delay <= 5_000L)
            }
        }
    }

    @Test
    fun sameSeedProducesIdenticalSchedule() {
        fun schedule(seed: Int) = StreamRecoveryBackoffPolicy(
            initialDelayMs = 300L,
            maxDelayMs = 20_000L,
            jitterFraction = 0.3,
            random = Random(seed),
        ).let { p -> (0..8).map { p.nextDelayMs(it) } }
        assertEquals(schedule(42), schedule(42))
    }

    @Test
    fun differentSeedsDiverge() {
        fun schedule(seed: Int) = StreamRecoveryBackoffPolicy(
            initialDelayMs = 300L,
            maxDelayMs = 20_000L,
            jitterFraction = 0.3,
            random = Random(seed),
        ).let { p -> (0..8).map { p.nextDelayMs(it) } }
        assertNotEquals(schedule(1), schedule(2))
    }

    // ---------- network-change awareness ----------

    @Test
    fun networkRecoveryArmsOneImmediateRetry() {
        val policy = flatPolicy()
        policy.onNetworkChanged(false) // radio died mid-stream
        policy.onNetworkChanged(true) // handover finished
        assertTrue(policy.isImmediateRetryArmed())
        assertEquals(0L, policy.nextDelayMs(5)) // retry NOW on the fresh network
        assertFalse(policy.isImmediateRetryArmed())
        assertEquals(3_200L, policy.nextDelayMs(5)) // armed shot is spent
    }

    @Test
    fun availableToAvailableDoesNotArm() {
        val policy = flatPolicy()
        policy.onNetworkChanged(true) // initial state is already "available"
        assertFalse(policy.isImmediateRetryArmed())
        assertEquals(400L, policy.nextDelayMs(2))
    }

    @Test
    fun losingNetworkAloneDoesNotArm() {
        val policy = flatPolicy()
        policy.onNetworkChanged(false)
        assertFalse(policy.isImmediateRetryArmed())
        assertEquals(400L, policy.nextDelayMs(2))
    }

    @Test
    fun repeatedAvailableCallbacksArmOnlyOneRetry() {
        val policy = flatPolicy()
        policy.onNetworkChanged(false)
        policy.onNetworkChanged(true)
        policy.onNetworkChanged(true)
        policy.onNetworkChanged(true)
        assertEquals(0L, policy.nextDelayMs(4))
        assertEquals(1_600L, policy.nextDelayMs(4))
    }

    // ---------- shouldRetry classification ----------

    @Test
    fun retriesSocketTimeout() {
        val policy = flatPolicy()
        assertTrue(policy.shouldRetry(0, SocketTimeoutException("timeout")))
        // Even wrapped, as OkHttp does.
        assertTrue(policy.shouldRetry(0, IOException("read failed", SocketTimeoutException("t"))))
    }

    @Test
    fun retriesIoNetworkErrors() {
        val policy = flatPolicy()
        assertTrue(policy.shouldRetry(0, IOException("connection reset")))
        assertTrue(policy.shouldRetry(0, UnknownHostException("mars.local")))
        assertTrue(policy.shouldRetry(0, IOException("route error", UnknownHostException("h"))))
    }

    @Test
    fun retriesServerErrors() {
        val policy = flatPolicy()
        assertTrue(policy.shouldRetry(0, IOException("HTTP 500: internal")))
        assertTrue(policy.shouldRetry(0, RuntimeException("HTTP 502: bad gateway")))
        assertTrue(policy.shouldRetry(0, RuntimeException("HTTP 503")))
    }

    @Test
    fun retriesRateLimitAndRequestTimeoutStatuses() {
        val policy = flatPolicy()
        assertTrue(policy.shouldRetry(0, RuntimeException("HTTP 429: too many requests")))
        assertTrue(policy.shouldRetry(0, RuntimeException("HTTP 408: request timeout")))
    }

    @Test
    fun doesNotRetryAuthAndGoneStreamStatuses() {
        val policy = flatPolicy()
        assertFalse(policy.shouldRetry(0, IOException("HTTP 401: unauthorized")))
        assertFalse(policy.shouldRetry(0, RuntimeException("HTTP 403: forbidden")))
        assertFalse(policy.shouldRetry(0, RuntimeException("HTTP 404: stream not found")))
    }

    @Test
    fun statusRuleWinsOverIoType() {
        // An IOException whose message carries a fatal HTTP status is still fatal:
        // the 401 verdict must beat the generic "IOException is retryable" rule.
        val policy = flatPolicy()
        assertFalse(policy.shouldRetry(0, IOException("HTTP 401: unauthorized", SocketTimeoutException("t"))))
    }

    @Test
    fun httpCodeIsFoundDeepInCauseChain() {
        val policy = flatPolicy()
        val deep = IOException("outer", IllegalStateException("middle", RuntimeException("HTTP 404: not found")))
        assertFalse(policy.shouldRetry(0, deep))
    }

    @Test
    fun doesNotRetryDeliberateInterruption() {
        // Plain InterruptedIOException = call cancelled on purpose; must be respected.
        assertFalse(flatPolicy().shouldRetry(0, InterruptedIOException("interrupted")))
    }

    @Test
    fun socketTimeoutIsNotMistakenForInterruption() {
        // SocketTimeoutException IS an InterruptedIOException (subclass) but must stay retryable.
        assertTrue(flatPolicy().shouldRetry(0, SocketTimeoutException("t")))
    }

    @Test
    fun doesNotRetryUnknownNonNetworkExceptions() {
        assertFalse(flatPolicy().shouldRetry(0, IllegalStateException("parser bug")))
        assertFalse(flatPolicy().shouldRetry(0, IllegalArgumentException()))
    }

    @Test
    fun nullThrowableIsRetried() {
        assertTrue(flatPolicy().shouldRetry(0, null))
    }

    @Test
    fun stopsRetryingAtMaxAttempts() {
        val policy = flatPolicy(maxAttempts = 3)
        assertTrue(policy.shouldRetry(0, SocketTimeoutException("t")))
        assertTrue(policy.shouldRetry(2, SocketTimeoutException("t")))
        assertFalse(policy.shouldRetry(3, SocketTimeoutException("t")))
        assertFalse(policy.shouldRetry(99, null))
    }

    @Test
    fun constructorArgumentsAreSanitized() {
        val policy = StreamRecoveryBackoffPolicy(
            initialDelayMs = -50L,
            maxDelayMs = -1L,
            factor = 0.1,
            jitterFraction = 5.0,
            random = Random(3),
        )
        assertEquals(0L, policy.initialDelayMs)
        assertTrue(policy.maxDelayMs >= policy.initialDelayMs)
        assertTrue(policy.factor >= 1.0)
        assertTrue(policy.jitterFraction in 0.0..1.0)
        assertTrue(policy.nextDelayMs(0) >= 0L)
    }
}
