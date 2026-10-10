package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TvStreamRecoveryPolicy] and [TvStreamRecoveryTracker] — pure JVM, no
 * sockets, no timers. Covers exponential backoff math (growth, cap, clamped attempts,
 * deterministic jitter bounds), the plan decision surface per health verdict, session
 * and playback-position preservation on every plan, give-up after max attempts, and the
 * tracker contract: attempt counting, reset-on-recovery with session survival.
 */
class TvStreamRecoveryPolicyTest {

    private val noJitter = 0.5 // maps to zero perturbation with any jitterFraction

    // --- backoff math ---------------------------------------------------------------------------------

    @Test
    fun `backoff doubles per attempt from the base delay`() {
        val config = TvBackoffConfig()
        assertEquals(1_000L, TvStreamRecoveryPolicy.backoffDelayMs(1, config, noJitter))
        assertEquals(2_000L, TvStreamRecoveryPolicy.backoffDelayMs(2, config, noJitter))
        assertEquals(4_000L, TvStreamRecoveryPolicy.backoffDelayMs(3, config, noJitter))
        assertEquals(8_000L, TvStreamRecoveryPolicy.backoffDelayMs(4, config, noJitter))
    }

    @Test
    fun `backoff never exceeds the max delay cap`() {
        val config = TvBackoffConfig(baseDelayMs = 1_000L, maxDelayMs = 5_000L)
        assertEquals(4_000L, TvStreamRecoveryPolicy.backoffDelayMs(3, config, noJitter))
        assertEquals(5_000L, TvStreamRecoveryPolicy.backoffDelayMs(4, config, noJitter))
        assertEquals(5_000L, TvStreamRecoveryPolicy.backoffDelayMs(50, config, noJitter))
    }

    @Test
    fun `non-positive attempts are clamped to the first attempt`() {
        val config = TvBackoffConfig()
        assertEquals(1_000L, TvStreamRecoveryPolicy.backoffDelayMs(0, config, noJitter))
        assertEquals(1_000L, TvStreamRecoveryPolicy.backoffDelayMs(-7, config, noJitter))
    }

    @Test
    fun `huge attempts do not overflow into negative delays`() {
        val delay = TvStreamRecoveryPolicy.backoffDelayMs(Int.MAX_VALUE, TvBackoffConfig(), noJitter)
        assertTrue("delay=$delay", delay in 0L..TvBackoffConfig().maxDelayMs)
    }

    @Test
    fun `jitter stays within plus-minus the jitter fraction`() {
        val config = TvBackoffConfig(baseDelayMs = 10_000L, jitterFraction = 0.2)
        val low = TvStreamRecoveryPolicy.backoffDelayMs(1, config, jitter = 0.0)
        val mid = TvStreamRecoveryPolicy.backoffDelayMs(1, config, jitter = 0.5)
        val high = TvStreamRecoveryPolicy.backoffDelayMs(1, config, jitter = 1.0)
        assertEquals(8_000L, low)
        assertEquals(10_000L, mid)
        assertEquals(12_000L, high)
        // out-of-range draws are clamped into the same band
        assertEquals(low, TvStreamRecoveryPolicy.backoffDelayMs(1, config, jitter = -3.0))
        assertEquals(high, TvStreamRecoveryPolicy.backoffDelayMs(1, config, jitter = 9.0))
    }

    @Test
    fun `jittered delay is still capped at maxDelay`() {
        val config = TvBackoffConfig(baseDelayMs = 29_000L, maxDelayMs = 30_000L, jitterFraction = 0.5)
        val delay = TvStreamRecoveryPolicy.backoffDelayMs(1, config, jitter = 1.0)
        assertEquals(30_000L, delay)
    }

    @Test
    fun `zero jitter fraction means deterministic delays`() {
        val config = TvBackoffConfig(jitterFraction = 0.0)
        for (jitter in listOf(0.0, 0.3, 0.5, 1.0)) {
            assertEquals(2_000L, TvStreamRecoveryPolicy.backoffDelayMs(2, config, jitter))
        }
    }

    // --- planNextAttempt: no-op verdicts -----------------------------------------------------------------

    @Test
    fun `healthy stream plans nothing`() {
        val plan = TvStreamRecoveryPolicy.planNextAttempt(
            attempt = 3, health = TvStreamHealth.HEALTHY, hasSession = true, playbackPositionMs = 1_000L,
        )
        assertEquals(TvRecoveryAction.NONE, plan.action)
        assertEquals(0L, plan.delayMs)
        assertEquals("stream_healthy", plan.reason)
    }

    @Test
    fun `stall warning holds the stream instead of tearing it down`() {
        val plan = TvStreamRecoveryPolicy.planNextAttempt(
            attempt = 1, health = TvStreamHealth.STALL_WARNING, hasSession = true, playbackPositionMs = null,
        )
        assertEquals(TvRecoveryAction.NONE, plan.action)
        assertEquals("stall_warning_hold", plan.reason)
    }

    // --- planNextAttempt: give-up -------------------------------------------------------------------------

    @Test
    fun `attempt past the max gives up with zero delay but keeps state`() {
        val config = TvBackoffConfig(maxAttempts = 3)
        val plan = TvStreamRecoveryPolicy.planNextAttempt(
            attempt = 4, health = TvStreamHealth.STALLED, hasSession = true,
            playbackPositionMs = 42_000L, config = config,
        )
        assertEquals(TvRecoveryAction.GIVE_UP, plan.action)
        assertEquals(0L, plan.delayMs)
        assertEquals("max_attempts_exceeded", plan.reason)
        assertTrue("session must survive the give-up for a manual retry", plan.keepSessionId)
        assertTrue("playback position must survive the give-up", plan.keepPlaybackPosition)
    }

    @Test
    fun `non-positive attempt is treated as the first one`() {
        val plan = TvStreamRecoveryPolicy.planNextAttempt(
            attempt = 0, health = TvStreamHealth.NETWORK_LOST, hasSession = false, playbackPositionMs = null,
            jitter = noJitter,
        )
        assertEquals(1, plan.attempt)
        assertEquals(1_000L, plan.delayMs)
    }

    // --- planNextAttempt: network loss ----------------------------------------------------------------------

    @Test
    fun `network loss waits for the radio with backoff delay`() {
        val plan = TvStreamRecoveryPolicy.planNextAttempt(
            attempt = 2, health = TvStreamHealth.NETWORK_LOST, hasSession = true,
            playbackPositionMs = null, jitter = noJitter,
        )
        assertEquals(TvRecoveryAction.WAIT_FOR_NETWORK, plan.action)
        assertEquals(2_000L, plan.delayMs)
        assertEquals(2, plan.attempt)
        assertEquals("network_lost_backoff", plan.reason)
        assertTrue(plan.keepSessionId)
        assertFalse(plan.keepPlaybackPosition)
    }

    // --- planNextAttempt: stall -----------------------------------------------------------------------------

    @Test
    fun `stall with session and position resumes playback`() {
        val plan = TvStreamRecoveryPolicy.planNextAttempt(
            attempt = 1, health = TvStreamHealth.STALLED, hasSession = true,
            playbackPositionMs = 90_000L, jitter = noJitter,
        )
        assertEquals(TvRecoveryAction.RESUME_PLAYBACK, plan.action)
        assertEquals("stalled_resume_playback", plan.reason)
        assertTrue(plan.keepSessionId)
        assertTrue(plan.keepPlaybackPosition)
        assertEquals(1_000L, plan.delayMs)
    }

    @Test
    fun `stall with session but no position reconnects keeping the session`() {
        val plan = TvStreamRecoveryPolicy.planNextAttempt(
            attempt = 1, health = TvStreamHealth.STALLED, hasSession = true,
            playbackPositionMs = null, jitter = noJitter,
        )
        assertEquals(TvRecoveryAction.RECONNECT_KEEP_SESSION, plan.action)
        assertEquals("stalled_reconnect_keep_session", plan.reason)
        assertTrue(plan.keepSessionId)
        assertFalse(plan.keepPlaybackPosition)
    }

    @Test
    fun `stall without a session reconnects fresh`() {
        val plan = TvStreamRecoveryPolicy.planNextAttempt(
            attempt = 1, health = TvStreamHealth.STALLED, hasSession = false,
            playbackPositionMs = null, jitter = noJitter,
        )
        assertEquals(TvRecoveryAction.RECONNECT_KEEP_SESSION, plan.action)
        assertEquals("stalled_reconnect_fresh", plan.reason)
        assertFalse(plan.keepSessionId)
    }

    @Test
    fun `plan delays grow across attempts until the cap`() {
        val config = TvBackoffConfig()
        val delays = (1..config.maxAttempts).map {
            TvStreamRecoveryPolicy.planNextAttempt(
                attempt = it, health = TvStreamHealth.STALLED, hasSession = true,
                playbackPositionMs = null, config = config, jitter = noJitter,
            ).delayMs
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L), delays)
    }

    // --- tracker ------------------------------------------------------------------------------------------

    @Test
    fun `tracker counts consecutive failed attempts`() {
        val tracker = TvStreamRecoveryTracker()
        tracker.bindSession("s-1")
        assertEquals(0, tracker.attempt)
        tracker.planNext(TvStreamHealth.STALLED, jitter = noJitter)
        assertEquals(1, tracker.attempt)
        tracker.planNext(TvStreamHealth.NETWORK_LOST, jitter = noJitter)
        assertEquals(2, tracker.attempt)
        tracker.planNext(TvStreamHealth.HEALTHY)
        assertEquals("healthy verdicts must not bump the counter", 2, tracker.attempt)
    }

    @Test
    fun `tracker keeps the session across recovery cycles`() {
        val tracker = TvStreamRecoveryTracker()
        tracker.bindSession("session-42")
        tracker.planNext(TvStreamHealth.STALLED, playbackPositionMs = 5_000L, jitter = noJitter)
        tracker.onRecovered()
        assertEquals(0, tracker.attempt)
        assertEquals("session-42", tracker.sessionId)
        assertTrue(tracker.hasSession)
    }

    @Test
    fun `tracker resumes playback position through a stalled music recovery`() {
        val tracker = TvStreamRecoveryTracker()
        tracker.bindSession("music-session")
        val plan = tracker.planNext(TvStreamHealth.STALLED, playbackPositionMs = 123_456L, jitter = noJitter)
        assertEquals(TvRecoveryAction.RESUME_PLAYBACK, plan.action)
        assertTrue(plan.keepSessionId)
        assertTrue(plan.keepPlaybackPosition)
    }

    @Test
    fun `tracker gives up after max attempts and stays given up`() {
        val tracker = TvStreamRecoveryTracker(TvBackoffConfig(maxAttempts = 3))
        repeat(3) {
            val plan = tracker.planNext(TvStreamHealth.STALLED, jitter = noJitter)
            assertEquals(TvRecoveryAction.RECONNECT_KEEP_SESSION, plan.action)
        }
        val fourth = tracker.planNext(TvStreamHealth.STALLED, jitter = noJitter)
        assertEquals(TvRecoveryAction.GIVE_UP, fourth.action)
        val fifth = tracker.planNext(TvStreamHealth.STALLED, jitter = noJitter)
        assertEquals("give-up is sticky until a manual reset", TvRecoveryAction.GIVE_UP, fifth.action)
        tracker.onRecovered()
        val afterManual = tracker.planNext(TvStreamHealth.STALLED, jitter = noJitter)
        assertEquals(TvRecoveryAction.RECONNECT_KEEP_SESSION, afterManual.action)
    }

    @Test
    fun `tracker reset clears both attempt and session`() {
        val tracker = TvStreamRecoveryTracker()
        tracker.bindSession("s")
        tracker.planNext(TvStreamHealth.STALLED, jitter = noJitter)
        tracker.reset()
        assertEquals(0, tracker.attempt)
        assertNull(tracker.sessionId)
        assertFalse(tracker.hasSession)
    }

    @Test
    fun `tracker ignores null and blank session bindings`() {
        val tracker = TvStreamRecoveryTracker()
        tracker.bindSession(null)
        assertNull(tracker.sessionId)
        tracker.bindSession("   ")
        assertNull(tracker.sessionId)
        assertFalse(tracker.hasSession)
        tracker.bindSession("real")
        assertEquals("real", tracker.sessionId)
        tracker.bindSession("")
        assertNull("empty binding unbinds", tracker.sessionId)
    }

    @Test
    fun `tracker without a session plans fresh reconnects`() {
        val tracker = TvStreamRecoveryTracker()
        val plan = tracker.planNext(TvStreamHealth.STALLED, jitter = noJitter)
        assertEquals(TvRecoveryAction.RECONNECT_KEEP_SESSION, plan.action)
        assertEquals("stalled_reconnect_fresh", plan.reason)
        assertFalse(plan.keepSessionId)
    }

    // --- data classes ---------------------------------------------------------------------------------------

    @Test
    fun `config and plan expose fields and equality`() {
        val config = TvBackoffConfig(baseDelayMs = 500L, multiplier = 3.0, maxDelayMs = 9_000L, maxAttempts = 4, jitterFraction = 0.1)
        assertEquals(500L, config.baseDelayMs)
        assertEquals(config, config.copy())
        val plan = TvRecoveryPlan(TvRecoveryAction.WAIT_FOR_NETWORK, 1_500L, 2, true, false, "network_lost_backoff")
        assertEquals(TvRecoveryAction.WAIT_FOR_NETWORK, plan.action)
        assertEquals(1_500L, plan.delayMs)
        assertEquals(2, plan.attempt)
        assertTrue(plan.keepSessionId)
        assertFalse(plan.keepPlaybackPosition)
        assertEquals("network_lost_backoff", plan.reason)
        assertEquals(plan, plan.copy())
    }
}
