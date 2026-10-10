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

    private fun coordinator(
        maxRetries: Int = 5,
        maxReattachesPerTurn: Int = MidTurnRecoveryCoordinator.DEFAULT_MAX_REATTACHES_PER_TURN,
    ) = MidTurnRecoveryCoordinator(
        backoff = StreamRecoveryBackoffPolicy(
            initialDelayMs = 500, maxDelayMs = 4_000, factor = 2.0,
            maxAttempts = 10, jitterFraction = 0.0,
        ),
        reconnect = AdaptiveSseReconnectPolicy(
            initialDelayMs = 500, maxDelayMs = 4_000, factor = 2.0,
            maxRetries = maxRetries, jitterFraction = 0.0,
        ),
        maxReattachesPerTurn = maxReattachesPerTurn,
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
    fun `anchored gone-stream phrases without the HTTP prefix also reload`() {
        val c = coordinator()
        assertEquals(RecoveryDecision.ReloadSession, c.decide(StreamFailure(errorMessage = "stream not found")))
        assertEquals(RecoveryDecision.ReloadSession, c.decide(StreamFailure(errorMessage = "No such stream: st-1")))
        assertEquals(RecoveryDecision.ReloadSession, c.decide(StreamFailure(errorMessage = "unknown stream")))
        // bare 404 counts only together with stream context (legacy shapes)
        assertEquals(RecoveryDecision.ReloadSession, c.decide(StreamFailure(errorMessage = "stream 404")))
    }

    @Test
    fun `broad not-found text is NOT accepted as a gone stream`() {
        // Tool output / browsed web content routinely contains these shapes; misreading
        // them as an expired journal would throw away a resumable live turn.
        val c = coordinator()
        assertTrue(c.decide(StreamFailure(errorMessage = "tool 'read_file' failed: file not found"))
            is RecoveryDecision.ReattachAfter)
        assertTrue(c.decide(StreamFailure(errorMessage = "404 page not found"))
            is RecoveryDecision.ReattachAfter)
        assertTrue(c.decide(StreamFailure(errorMessage = "Ответ 404 от сервера"))
            is RecoveryDecision.ReattachAfter)
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

    // ---- server-origin seam: terminal server errors never become network retries -------

    @Test
    fun `server-side error event gives up and is not counted as a network failure`() {
        val c = coordinator()
        val giveUp = c.decide(
            StreamFailure(
                errorMessage = "tool 'terminal' failed: connection reset by peer",
                lastEventId = "st-1:4",
                origin = StreamFailure.Origin.SERVER_EVENT,
            )
        )
        assertTrue(giveUp is RecoveryDecision.GiveUp)
        assertTrue((giveUp as RecoveryDecision.GiveUp).reason.contains("server-side"))
        assertEquals(0, c.consecutiveFailures()) // the streak/budget stay untouched
    }

    @Test
    fun `server event text with HTTP codes or 404 markers stays terminal`() {
        // The message of a server `error` frame is arbitrary agent/runtime text — it is
        // never parsed for transport verdicts, whatever it happens to contain.
        val c = coordinator()
        val d1 = c.decide(StreamFailure(errorMessage = "HTTP 502: bad gateway", origin = StreamFailure.Origin.SERVER_EVENT))
        assertTrue(d1 is RecoveryDecision.GiveUp)
        val d2 = c.decide(StreamFailure(errorMessage = "HTTP 404: stream not found", origin = StreamFailure.Origin.SERVER_EVENT))
        assertTrue(d2 is RecoveryDecision.GiveUp)
        assertTrue((d2 as RecoveryDecision.GiveUp).reason.contains("server-side"))
    }

    @Test
    fun `server event is terminal even while the radio is down`() {
        val c = coordinator()
        c.onNetworkChanged(false)
        // Origin wins over connectivity: waiting for the network cannot fix an agent error.
        assertTrue(c.decide(StreamFailure(errorMessage = "agent crashed", origin = StreamFailure.Origin.SERVER_EVENT))
            is RecoveryDecision.GiveUp)
    }

    @Test
    fun `explicit TRANSPORT origin behaves like the legacy message-only failure`() {
        val c = coordinator()
        assertEquals(
            RecoveryDecision.ReattachAfter(500L, 1, null),
            c.decide(StreamFailure(errorMessage = "HTTP 502: bad gateway", origin = StreamFailure.Origin.TRANSPORT)),
        )
    }

    // ---- replay-only reconnects: cursor-guarded budget resets ---------------------------

    @Test
    fun `replayed frames at or below the cursor do not reset the reconnect budget`() {
        val c = coordinator(maxRetries = 2)
        assertTrue(c.decide(io) is RecoveryDecision.ReattachAfter) // high-water = st-1:4
        assertTrue(!c.onStreamFrame("st-1:4")) // replayed duplicate — already delivered
        assertTrue(!c.onStreamFrame("st-1:3")) // stale replay
        assertEquals(1, c.consecutiveFailures()) // streak untouched by replays
        assertTrue(c.decide(io) is RecoveryDecision.ReattachAfter)
        val giveUp = c.decide(io)
        assertTrue(giveUp is RecoveryDecision.GiveUp) // budget exhausted despite "healthy" replays
    }

    @Test
    fun `a frame beyond the cursor is verified progress and resets the budget`() {
        val c = coordinator(maxRetries = 2)
        assertTrue(c.decide(io) is RecoveryDecision.ReattachAfter)
        assertTrue(c.decide(io) is RecoveryDecision.ReattachAfter)
        assertTrue(c.onStreamFrame("st-1:5")) // genuinely new content, not a replay
        assertEquals(0, c.consecutiveFailures())
        assertEquals("st-1:5", c.highWaterEventId())
        assertEquals(RecoveryDecision.ReattachAfter(500L, 1, "st-1:4"), c.decide(io))
    }

    @Test
    fun `the failure cursor feeds the high-water mark even without onStreamFrame calls`() {
        val c = coordinator(maxRetries = 2)
        c.decide(StreamFailure(throwable = IOException("reset"), lastEventId = "st-1:9"))
        assertTrue(!c.onStreamFrame("st-1:9")) // the tracker already acked it → replay
        assertTrue(!c.onStreamFrame("st-1:2"))
        assertTrue(c.onStreamFrame("st-1:10"))
    }

    @Test
    fun `id-less liveness frames reset the streak but never re-arm the per-turn cap`() {
        val c = coordinator(maxRetries = 100, maxReattachesPerTurn = 3)
        val failure = StreamFailure(throwable = IOException("reset"))
        repeat(3) {
            assertTrue(c.decide(failure) is RecoveryDecision.ReattachAfter)
            c.onStreamHealthy() // legacy unconditional streak reset — cap must survive it
        }
        val giveUp = c.decide(failure)
        assertTrue(giveUp is RecoveryDecision.GiveUp)
        assertTrue((giveUp as RecoveryDecision.GiveUp).reason.contains("cap"))
    }

    @Test
    fun `verified progress re-arms the per-turn cap`() {
        val c = coordinator(maxRetries = 100, maxReattachesPerTurn = 2)
        val failure = StreamFailure(throwable = IOException("reset"))
        assertTrue(c.decide(failure) is RecoveryDecision.ReattachAfter)
        assertTrue(c.decide(failure) is RecoveryDecision.ReattachAfter)
        val capped = c.decide(failure)
        assertTrue(capped is RecoveryDecision.GiveUp)
        // a frame with an id newer than anything seen re-arms the safety net
        assertTrue(c.onStreamFrame("st-2:1"))
        assertEquals(0, c.reattachesSinceProgress())
        assertTrue(c.decide(failure) is RecoveryDecision.ReattachAfter)
    }

    @Test
    fun `manual reset clears the cap and the high-water cursor for the new turn`() {
        val c = coordinator(maxRetries = 100, maxReattachesPerTurn = 1)
        val failure = StreamFailure(throwable = IOException("reset"))
        assertTrue(c.decide(failure) is RecoveryDecision.ReattachAfter)
        assertTrue(c.decide(failure) is RecoveryDecision.GiveUp) // cap of 1 spent
        c.onStreamFrame("st-1:7")
        c.reset() // new user-initiated turn
        assertNull(c.highWaterEventId())
        assertEquals(0, c.reattachesSinceProgress())
        assertTrue(c.decide(failure) is RecoveryDecision.ReattachAfter)
        // old-turn ids no longer count as duplicates after the reset
        assertTrue(c.onStreamFrame("st-1:1"))
    }
}
