package ru.mars.mobile

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicTrack

/**
 * Unit tests for the pure playback-retry decision core ([PlaybackRetryPolicy]): error
 * classification from raw MediaPlayer codes, per-track retry budgets with capped exponential
 * backoff, mid-track resume positions, the queue-wide circuit breaker, exception mapping and
 * a player-free simulation of full failure streaks against [MusicQueueManager].
 */
class PlaybackRetryPolicyTest {

    private val policy = PlaybackRetryPolicy()

    private fun track(id: String) = MusicTrack(id = id, title = "Title $id", artist = "Artist $id")
    private fun tracks(count: Int): List<MusicTrack> = (1..count).map { track("t$it") }

    private val io = PlaybackRetryPolicy.ERROR_IO
    private val malformed = PlaybackRetryPolicy.ERROR_MALFORMED

    // --- Classification -----------------------------------------------------

    @Test
    fun `network-flavored codes classify as transient`() {
        assertEquals(PlaybackErrorKind.TRANSIENT_NETWORK, policy.classify(io, 0))
        assertEquals(PlaybackErrorKind.TRANSIENT_NETWORK, policy.classify(PlaybackRetryPolicy.ERROR_TIMED_OUT, 0))
        assertEquals(PlaybackErrorKind.TRANSIENT_NETWORK, policy.classify(PlaybackRetryPolicy.ERROR_SERVER_DIED, 0))
        // The classic prepareAsync network shape: unknown `what`, IO carried in `extra`.
        assertEquals(
            PlaybackErrorKind.TRANSIENT_NETWORK,
            policy.classify(PlaybackRetryPolicy.ERROR_UNKNOWN, io)
        )
    }

    @Test
    fun `broken-stream codes classify as permanent`() {
        assertEquals(PlaybackErrorKind.PERMANENT, policy.classify(malformed, 0))
        assertEquals(PlaybackErrorKind.PERMANENT, policy.classify(PlaybackRetryPolicy.ERROR_UNSUPPORTED, 0))
        assertEquals(PlaybackErrorKind.PERMANENT, policy.classify(PlaybackRetryPolicy.ERROR_NOT_VALID_FOR_PROGRESSIVE, 0))
        assertEquals(PlaybackErrorKind.PERMANENT, policy.classify(PlaybackRetryPolicy.ERROR_UNKNOWN, malformed))
    }

    @Test
    fun `permanent marker wins over a transient one`() {
        assertEquals(PlaybackErrorKind.PERMANENT, policy.classify(io, malformed))
    }

    @Test
    fun `unrecognized codes classify as unknown`() {
        assertEquals(PlaybackErrorKind.UNKNOWN, policy.classify(PlaybackRetryPolicy.ERROR_UNKNOWN, 0))
        assertEquals(PlaybackErrorKind.UNKNOWN, policy.classify(42, 99))
    }

    // --- Transient retries with backoff --------------------------------------

    @Test
    fun `transient failure retries the same track with exponential backoff then skips`() {
        val first = policy.decide(io, 0, queueSize = 3)
        assertTrue(first is RetryDecision.RetryTrack)
        assertEquals(PlaybackRetryPolicy.DEFAULT_BASE_DELAY_MS, (first as RetryDecision.RetryTrack).delayMs)
        assertEquals(1, first.attempt)

        val second = policy.decide(io, 0, queueSize = 3)
        assertTrue(second is RetryDecision.RetryTrack)
        assertEquals(2_000L, (second as RetryDecision.RetryTrack).delayMs)
        assertEquals(2, second.attempt)
        assertEquals(2, policy.trackRetries)

        // Default budget (2 retries per track) is spent: the track is skipped.
        assertEquals(RetryDecision.SkipTrack, policy.decide(io, 0, queueSize = 3))
        assertEquals(1, policy.consecutiveSkips)
    }

    @Test
    fun `navigation gives the next track a fresh retry budget but keeps the streak`() {
        policy.decide(io, 0, queueSize = 3) // t1: retry 1 consumed
        policy.onTrackSelected()
        assertEquals(0, policy.trackRetries)

        val decision = policy.decide(io, 0, queueSize = 3)
        assertTrue(decision is RetryDecision.RetryTrack)
        assertEquals("budget restarts at attempt 1", 1, (decision as RetryDecision.RetryTrack).attempt)

        // Burn this track's budget -> skip #1; the streak survives navigation.
        policy.decide(io, 0, queueSize = 3) // retry 2
        assertEquals(RetryDecision.SkipTrack, policy.decide(io, 0, queueSize = 3))
        policy.onTrackSelected()
        policy.decide(io, 0, queueSize = 3) // retry 1
        policy.decide(io, 0, queueSize = 3) // retry 2
        assertEquals(RetryDecision.SkipTrack, policy.decide(io, 0, queueSize = 3)) // skip #2
        policy.onTrackSelected()
        policy.decide(io, 0, queueSize = 3) // retry 1
        policy.decide(io, 0, queueSize = 3) // retry 2
        // Skip #3 with queueSize 3: a full failed pass -> circuit breaker.
        assertEquals(RetryDecision.Stop, policy.decide(io, 0, queueSize = 3))
    }

    @Test
    fun `successful playback clears both counters`() {
        policy.decide(io, 0, queueSize = 5)
        policy.decide(io, 0, queueSize = 5)
        policy.decide(io, 0, queueSize = 5) // SkipTrack, streak = 1
        policy.onPlaybackReady()
        assertEquals(0, policy.trackRetries)
        assertEquals(0, policy.consecutiveSkips)
        // A later permanent failure only skips — the streak no longer trips the breaker.
        assertEquals(RetryDecision.SkipTrack, policy.decide(malformed, 0, queueSize = 5))
        assertEquals(1, policy.consecutiveSkips)
    }

    // --- Permanent and unknown kinds ------------------------------------------

    @Test
    fun `permanent failure never retries the same stream`() {
        assertEquals(RetryDecision.SkipTrack, policy.decide(malformed, 0, queueSize = 3))
        assertEquals(0, policy.trackRetries)
        assertEquals(1, policy.consecutiveSkips)
    }

    @Test
    fun `unknown failure gets a single retry then skips`() {
        val first = policy.decide(PlaybackRetryPolicy.ERROR_UNKNOWN, 0, queueSize = 3)
        assertTrue(first is RetryDecision.RetryTrack)
        assertEquals(1, (first as RetryDecision.RetryTrack).attempt)
        assertEquals(RetryDecision.SkipTrack, policy.decide(PlaybackRetryPolicy.ERROR_UNKNOWN, 0, queueSize = 3))
    }

    // --- Circuit breaker ------------------------------------------------------

    @Test
    fun `single-track queue stops as soon as its budget is spent`() {
        assertTrue(policy.decide(io, 0, queueSize = 1) is RetryDecision.RetryTrack)
        assertTrue(policy.decide(io, 0, queueSize = 1) is RetryDecision.RetryTrack)
        assertEquals(RetryDecision.Stop, policy.decide(io, 0, queueSize = 1))
    }

    @Test
    fun `empty queue stops on the first exhausted track`() {
        assertEquals(RetryDecision.Stop, policy.decide(malformed, 0, queueSize = 0))
    }

    @Test
    fun `stop is self-clearing so a resumed session starts fresh`() {
        assertEquals(RetryDecision.Stop, policy.decide(malformed, 0, queueSize = 1))
        assertEquals(0, policy.consecutiveSkips)
        assertEquals(0, policy.trackRetries)
        val next = policy.decide(io, 0, queueSize = 3)
        assertTrue("fresh cycle after stop", next is RetryDecision.RetryTrack)
        assertEquals(1, (next as RetryDecision.RetryTrack).attempt)
    }

    // --- Backoff configuration -------------------------------------------------

    @Test
    fun `backoff doubles per attempt and never exceeds the cap`() {
        val p = PlaybackRetryPolicy(maxRetriesPerTrack = 5, baseDelayMs = 1_000, maxDelayMs = 8_000)
        val delays = (1..5).map {
            val d = p.decide(io, 0, queueSize = 99)
            (d as RetryDecision.RetryTrack).delayMs
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L), delays)
    }

    @Test
    fun `zero retries skips transient failures immediately`() {
        val p = PlaybackRetryPolicy(maxRetriesPerTrack = 0)
        assertEquals(RetryDecision.SkipTrack, p.decide(io, 0, queueSize = 3))
        assertEquals(RetryDecision.SkipTrack, p.decide(PlaybackRetryPolicy.ERROR_UNKNOWN, 0, queueSize = 3))
    }

    // --- Mid-track resume -------------------------------------------------------

    @Test
    fun `mid-track failure carries the resume position`() {
        val decision = policy.decide(io, 0, queueSize = 3, positionMs = 60_000, durationMs = 180_000)
        assertTrue(decision is RetryDecision.RetryTrack)
        assertEquals(60_000, (decision as RetryDecision.RetryTrack).resumeFromMs)
    }

    @Test
    fun `failures without a meaningful position start over`() {
        // Never reached prepared (duration unknown) — no resume.
        assertEquals(0, (policy.decide(io, 0, 3, positionMs = 45_000, durationMs = 0) as RetryDecision.RetryTrack).resumeFromMs)
        policy.onTrackSelected()
        // Position zero.
        assertEquals(0, (policy.decide(io, 0, 3, positionMs = 0, durationMs = 180_000) as RetryDecision.RetryTrack).resumeFromMs)
        policy.onTrackSelected()
        // At/past the end or negative — restart instead of seeking into the void.
        assertEquals(0, (policy.decide(io, 0, 3, positionMs = 180_000, durationMs = 180_000) as RetryDecision.RetryTrack).resumeFromMs)
        policy.onTrackSelected()
        assertEquals(0, (policy.decide(io, 0, 3, positionMs = -5, durationMs = 180_000) as RetryDecision.RetryTrack).resumeFromMs)
    }

    // --- Exception mapping --------------------------------------------------------

    @Test
    fun `setDataSource exceptions map onto the same code space`() {
        assertEquals(PlaybackRetryPolicy.ERROR_IO, PlaybackRetryPolicy.codeFor(IOException("timeout")))
        assertEquals(PlaybackRetryPolicy.ERROR_UNSUPPORTED, PlaybackRetryPolicy.codeFor(IllegalArgumentException("bad uri")))
        assertEquals(PlaybackRetryPolicy.ERROR_UNSUPPORTED, PlaybackRetryPolicy.codeFor(SecurityException()))
        assertEquals(PlaybackRetryPolicy.ERROR_UNKNOWN, PlaybackRetryPolicy.codeFor(IllegalStateException()))
    }

    @Test
    fun `mapped IO exception retries like a network failure`() {
        val what = PlaybackRetryPolicy.codeFor(IOException("ECONNRESET"))
        assertTrue(policy.decide(what, 0, queueSize = 3) is RetryDecision.RetryTrack)
    }

    // --- Player-free streak simulations -------------------------------------------

    /** Drives the policy the way MarsMusicPlayer does: skip navigates, stop finishes. */
    private fun simulate(manager: MusicQueueManager, p: PlaybackRetryPolicy, what: Int, extra: Int = 0): List<RetryDecision> {
        val decisions = mutableListOf<RetryDecision>()
        var guard = 0
        while (guard++ < 100) {
            val decision = p.decide(what, extra, manager.size)
            decisions += decision
            when (decision) {
                is RetryDecision.RetryTrack -> Unit // same track re-prepared after backoff
                RetryDecision.SkipTrack -> {
                    if (manager.next() == null) {
                        manager.markFinished()
                        break
                    }
                    p.onTrackSelected() // startCurrentTrack does this after navigation
                }
                RetryDecision.Stop -> {
                    manager.markFinished()
                    break
                }
            }
        }
        return decisions
    }

    @Test
    fun `simulated outage - permanent failures skip one pass and stop`() {
        val manager = MusicQueueManager().apply { setQueue(tracks(3)) }
        val decisions = simulate(manager, PlaybackRetryPolicy(), malformed)
        assertEquals(
            listOf(RetryDecision.SkipTrack, RetryDecision.SkipTrack, RetryDecision.Stop),
            decisions
        )
        assertTrue("session must be finished, not looping", manager.isFinished)
    }

    @Test
    fun `simulated outage - transient failures retry each track, then skip, then stop`() {
        val manager = MusicQueueManager().apply { setQueue(tracks(3)) }
        val decisions = simulate(manager, PlaybackRetryPolicy(), io)
        // Per track: retry(1s), retry(2s), skip; the third skip trips the breaker.
        assertEquals(9, decisions.size)
        assertEquals(6, decisions.count { it is RetryDecision.RetryTrack })
        assertEquals(2, decisions.count { it == RetryDecision.SkipTrack })
        assertEquals(RetryDecision.Stop, decisions.last())
        assertEquals(
            listOf(1_000L, 2_000L, 1_000L, 2_000L, 1_000L, 2_000L),
            decisions.filterIsInstance<RetryDecision.RetryTrack>().map { it.delayMs }
        )
        assertTrue(manager.isFinished)
    }

    @Test
    fun `simulated recovery - a prepared stream clears the failure history`() {
        val manager = MusicQueueManager().apply { setQueue(tracks(3)) }
        val p = PlaybackRetryPolicy()
        // Track 1 drops twice (backoff retries), then prepares successfully.
        assertTrue(p.decide(io, 0, manager.size) is RetryDecision.RetryTrack)
        assertTrue(p.decide(io, 0, manager.size) is RetryDecision.RetryTrack)
        p.onPlaybackReady() // what the prepared listener does
        assertEquals("t2", manager.next()?.id) // completion listener advances
        p.onTrackSelected()
        // Tracks 2 and 3 are permanently broken: skipped without tripping the breaker,
        // because the recovered track 1 cleared the streak.
        assertEquals(RetryDecision.SkipTrack, p.decide(malformed, 0, manager.size))
        assertEquals("t3", manager.next()?.id)
        p.onTrackSelected()
        assertEquals(RetryDecision.SkipTrack, p.decide(malformed, 0, manager.size))
        assertEquals(2, p.consecutiveSkips)
        // The queue then ends naturally (nothing behind t3) — not via the circuit breaker.
        assertNull(manager.next())
        assertTrue(manager.isFinished)
    }
}
