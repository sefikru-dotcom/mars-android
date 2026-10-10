package ru.mars.network.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.HealthLevel
import ru.mars.network.repository.MidTurnRecoveryCoordinator.RecoveryDecision
import ru.mars.network.repository.MidTurnRecoveryCoordinator.StreamFailure
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Deterministic decision-table tests for [MidTurnRecoveryCoordinator]: jitter-free
 * policies, no clocks, no randomness — every branch of
 * WaitForNetwork / ReloadSession / GiveUp / ReattachAfter is pinned, including the
 * streak budget, the health reset, the network-transition immediate retry and its
 * suppression while the server probes OFFLINE.
 */
class MidTurnRecoveryCoordinatorTest {

    private fun coordinator(maxRetries: Int = 5) = MidTurnRecoveryCoordinator(
        backoff = StreamRecoveryBackoffPolicy(
            initialDelayMs = 500, maxDelayMs = 4_000, factor = 2.0,
            maxAttempts = 10, jitterFraction = 0.0,
        ),
        reconnect = AdaptiveSseReconnectPolicy(
            initialDelayMs = 500, maxDelayMs = 4_000, factor = 2.0,
            maxRetries = maxRetries, jitterFraction = 0.0,
        ),
    )

    private val io = StreamFailure(throwable = IOException("connection reset"), lastEventId = "st-1:4")

    // ---- ReattachAfter: the retryable path -------------------------------------------

    @Test
    fun `retryable IOException reattaches with the ladder delay and carries the cursor`() {
        val decision = coordinator().decide(io)
        assertEquals(RecoveryDecision.ReattachAfter(500L, attempt = 1, resumeFromEventId = "st-1:4"), decision)
        assertEquals(1, coordinator().let { c -> c.decide(io); c.consecutiveFailures() })
    }

    @Test
    fun `consecutive failures grow the delay and the attempt number`() {
        val c = coordinator()
        val decisions = List(4) { c.decide(io) }
        assertEquals(
            listOf(
                RecoveryDecision.ReattachAfter(500L, 1, "st-1:4"),
                RecoveryDecision.ReattachAfter(1_000L, 2, "st-1:4"),
                RecoveryDecision.ReattachAfter(2_000L, 3, "st-1:4"),
                RecoveryDecision.ReattachAfter(4_000L, 4, "st-1:4"), // capped at maxDelayMs
            ),
            decisions,
        )
    }

    @Test
    fun `silent stream end without a throwable is retryable`() {
        val decision = coordinator().decide(StreamFailure())
        assertTrue(decision is RecoveryDecision.ReattachAfter)
        assertNull((decision as RecoveryDecision.ReattachAfter).resumeFromEventId)
    }

    @Test
    fun `socket timeout and unknown host are retryable network buckets`() {
        val c = coordinator()
        assertTrue(c.decide(StreamFailure(throwable = SocketTimeoutException("timeout"))) is RecoveryDecision.ReattachAfter)
        assertTrue(c.decide(StreamFailure(throwable = UnknownHostException("mars.local"))) is RecoveryDecision.ReattachAfter)
    }

    @Test
    fun `message-only failure with HTTP 502 text is retryable`() {
        val decision = coordinator().decide(StreamFailure(errorMessage = "HTTP 502: bad gateway"))
        assertEquals(RecoveryDecision.ReattachAfter(500L, 1, null), decision)
    }

    @Test
    fun `message-only failure with code-less text stays retryable`() {
        val decision = coordinator().decide(StreamFailure(errorMessage = "Сервер неожиданно закрыл соединение"))
        assertTrue(decision is RecoveryDecision.ReattachAfter)
    }

    // ---- budget -----------------------------------------------------------------------

    @Test
    fun `budget exhaustion gives up with the streak count in the reason`() {
        val c = coordinator(maxRetries = 2)
        assertTrue(c.decide(io) is RecoveryDecision.ReattachAfter)
        assertTrue(c.decide(io) is RecoveryDecision.ReattachAfter)
        val giveUp = c.decide(io)
        assertTrue(giveUp is RecoveryDecision.GiveUp)
        assertTrue((giveUp as RecoveryDecision.GiveUp).reason.contains("budget"))
    }

    @Test
    fun `a healthy stream event resets the streak and the ladder`() {
        val c = coordinator()
        assertEquals(RecoveryDecision.ReattachAfter(500L, 1, "st-1:4"), c.decide(io))
        assertEquals(RecoveryDecision.ReattachAfter(1_000L, 2, "st-1:4"), c.decide(io))
        assertTrue(c.onStreamHealthy()) // non-zero streak was actually reset
        assertEquals(0, c.consecutiveFailures())
        assertEquals(RecoveryDecision.ReattachAfter(500L, 1, "st-1:4"), c.decide(io))
        // idempotent: healthy again with a zero streak reports "nothing to reset"
        c.onStreamHealthy()
        assertTrue(!c.onStreamHealthy())
    }

    @Test
    fun `manual reset forgets the streak and the health verdict`() {
        val c = coordinator(maxRetries = 1)
        c.decide(io)
        assertTrue(c.decide(io) is RecoveryDecision.GiveUp) // budget of 1 spent
        c.onServerProbe(isSuccessful = true, latencyMs = 100)
        c.reset()
        assertEquals(0, c.consecutiveFailures())
        assertNull(c.lastHealthLevel)
        assertTrue(c.decide(io) is RecoveryDecision.ReattachAfter) // fresh budget
    }

    // ---- ReloadSession: the journal is gone -------------------------------------------

    @Test
    fun `HTTP 404 stream not found reloads the session and resets the streak`() {
        val c = coordinator()
        c.decide(io) // accumulate a streak first
        val decision = c.decide(StreamFailure(errorMessage = "HTTP 404: stream not found", lastEventId = "st-1:4"))
        assertEquals(RecoveryDecision.ReloadSession, decision)
        assertEquals(0, c.consecutiveFailures())
    }

    @Test
    fun `legacy not-found markers without the HTTP prefix also reload`() {
        val c = coordinator()
        assertEquals(RecoveryDecision.ReloadSession, c.decide(StreamFailure(errorMessage = "stream not found")))
        assertEquals(RecoveryDecision.ReloadSession, c.decide(StreamFailure(errorMessage = "Ответ 404 от сервера")))
    }

    // ---- GiveUp: hopeless classes -------------------------------------------------------

    @Test
    fun `auth rejection gives up with a labeled reason`() {
        val giveUp = coordinator().decide(StreamFailure(errorMessage = "HTTP 401: unauthorized"))
        assertTrue(giveUp is RecoveryDecision.GiveUp)
        assertTrue((giveUp as RecoveryDecision.GiveUp).reason.contains("auth rejected"))
        assertTrue(giveUp.reason.contains("401"))
    }

    @Test
    fun `message-only HTTP 400 gives up as non-retryable`() {
        val giveUp = coordinator().decide(StreamFailure(errorMessage = "HTTP 400: message is required"))
        assertTrue(giveUp is RecoveryDecision.GiveUp)
        assertTrue((giveUp as RecoveryDecision.GiveUp).reason.contains("400"))
    }

    @Test
    fun `a code bug is never retried`() {
        val giveUp = coordinator().decide(StreamFailure(throwable = IllegalStateException("parse bug")))
        assertTrue(giveUp is RecoveryDecision.GiveUp)
        assertTrue((giveUp as RecoveryDecision.GiveUp).reason.contains("IllegalStateException"))
    }

    @Test
    fun `deliberate cancellation is respected, not retried`() {
        val giveUp = coordinator().decide(StreamFailure(throwable = InterruptedIOException("cancelled")))
        assertTrue(giveUp is RecoveryDecision.GiveUp)
    }

    // ---- WaitForNetwork and the one-shot immediate retry --------------------------------

    @Test
    fun `no network waits, and the recovery transition grants the immediate reattach`() {
        val c = coordinator()
        c.onNetworkChanged(false)
        assertEquals(RecoveryDecision.WaitForNetwork, c.decide(io))
        // waiting must not burn the budget
        assertEquals(0, c.consecutiveFailures())

        c.onNetworkChanged(true) // unavailable -> available arms the one-shot
        assertEquals(RecoveryDecision.ReattachAfter(0L, 1, "st-1:4"), c.decide(io))
        // the arm is consumed: the next failure uses the ladder again
        assertEquals(RecoveryDecision.ReattachAfter(1_000L, 2, "st-1:4"), c.decide(io))
    }

    @Test
    fun `an OFFLINE server suppresses the immediate retry until it recovers`() {
        val c = coordinator()
        c.onServerProbe(isSuccessful = false, latencyMs = 0, error = IOException("unreachable"))
        assertEquals(HealthLevel.OFFLINE, c.lastHealthLevel)

        c.onNetworkChanged(false)
        c.onNetworkChanged(true) // radio is back, but the backend is still down
        // -> ladder delay instead of 0 ms, and the arm SURVIVES
        assertEquals(RecoveryDecision.ReattachAfter(500L, 1, "st-1:4"), c.decide(io))

        c.onServerProbe(isSuccessful = true, latencyMs = 100)
        assertEquals(HealthLevel.HEALTHY, c.lastHealthLevel)
        // server recovered -> the preserved one-shot fires now
        assertEquals(RecoveryDecision.ReattachAfter(0L, 2, "st-1:4"), c.decide(io))
    }

    // ---- health probes ------------------------------------------------------------------

    @Test
    fun `health probes are categorized through ServerHealthPolicy`() {
        val c = coordinator()
        assertNull(c.lastHealthLevel) // nothing probed yet
        assertEquals(HealthLevel.HEALTHY, c.onServerProbe(isSuccessful = true, latencyMs = 100))
        assertEquals(HealthLevel.DEGRADED, c.onServerProbe(isSuccessful = true, latencyMs = 1_500))
        assertEquals(HealthLevel.OFFLINE, c.onServerProbe(isSuccessful = true, latencyMs = 5_000))
        assertEquals(HealthLevel.OFFLINE, c.onServerProbe(isSuccessful = false, latencyMs = 10))
        assertEquals(HealthLevel.OFFLINE, c.lastHealthLevel)
    }

    @Test
    fun `a DEGRADED server does not suppress the immediate retry`() {
        val c = coordinator()
        c.onServerProbe(isSuccessful = true, latencyMs = 1_500) // DEGRADED, not OFFLINE
        c.onNetworkChanged(false)
        c.onNetworkChanged(true)
        assertEquals(RecoveryDecision.ReattachAfter(0L, 1, "st-1:4"), c.decide(io))
    }
}
