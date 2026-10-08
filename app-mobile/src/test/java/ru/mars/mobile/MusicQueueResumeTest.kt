package ru.mars.mobile

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicTrack

/**
 * Queue exhaustion → enqueue regression suite: after the last track completes (or the
 * player is stopped), a new enqueue must resume playback on the new track and the old
 * track must never resurface as current. Covers the five queue states: EMPTY, ACTIVE,
 * EXHAUSTED, STOPPED and the REPEAT modes, plus the pure [QueueResumePolicy] itself.
 */
class MusicQueueResumeTest {

    private fun track(id: String) = MusicTrack(id = id, title = "Title $id", artist = "Artist $id")
    private fun tracks(count: Int): List<MusicTrack> = (1..count).map { track("t$it") }

    private fun seeded(count: Int = 3, seed: Int = 7): MusicQueueManager =
        MusicQueueManager(Random(seed)).apply { setQueue(tracks(count)) }

    /** Drives the manager to exhaustion the way the player's completion listener does. */
    private fun exhaust(manager: MusicQueueManager) {
        while (manager.next() != null) { /* walk to the end */ }
    }

    // --- EMPTY -------------------------------------------------------------

    @Test
    fun `empty queue - enqueue starts playback on the new track`() {
        val manager = MusicQueueManager()
        assertTrue(manager.isEmpty)
        assertFalse(manager.isFinished)
        val shouldStart = manager.addToQueue(track("fresh"))
        assertTrue("empty queue must start playback", shouldStart)
        assertEquals("fresh", manager.currentTrack?.id)
        assertEquals(0, manager.currentIndex)
    }

    // --- ACTIVE ------------------------------------------------------------

    @Test
    fun `active session - enqueue only appends and keeps the current track`() {
        val manager = seeded(3)
        manager.next() // t2 is current, session is active
        assertFalse(manager.isFinished)
        val shouldStart = manager.addToQueue(track("fresh"))
        assertFalse("an active session must keep playing", shouldStart)
        assertEquals("t2", manager.currentTrack?.id)
        assertEquals(1, manager.currentIndex)
        assertEquals(listOf("t3", "fresh"), manager.upcoming().map { it.id })
    }

    @Test
    fun `paused mid-queue is still an active session for the policy`() {
        // Pausing does not change queue state: not finished, not empty → append only.
        val manager = seeded(2)
        assertFalse(QueueResumePolicy.shouldStartPlayback(manager.isEmpty, manager.isFinished))
        assertFalse(manager.addToQueue(track("fresh")))
        assertEquals("t1", manager.currentTrack?.id)
    }

    // --- EXHAUSTED ---------------------------------------------------------

    @Test
    fun `exhausted queue - the finished track does not resurface`() {
        val manager = seeded(3)
        exhaust(manager)
        assertTrue(manager.isFinished)
        assertEquals(-1, manager.currentIndex)
        assertNull("stale track must not stay current", manager.currentTrack)
        assertEquals("the queue itself is kept for the UI", 3, manager.size)
        assertNull(manager.nextIndex())
        assertNull(manager.previousIndex())
        assertTrue(manager.upcoming().isEmpty())
    }

    @Test
    fun `exhausted queue - enqueue resumes on the new track`() {
        val manager = seeded(3)
        exhaust(manager)
        val shouldStart = manager.addToQueue(track("fresh"))
        assertTrue("enqueue after exhaustion must restart playback", shouldStart)
        assertFalse(manager.isFinished)
        assertEquals("fresh", manager.currentTrack?.id)
        assertEquals(3, manager.currentIndex) // appended after the old tracks
        assertEquals("old tracks stay queued ahead, never current", 4, manager.size)
        // After the new track the session ends again (repeat off, nothing behind it).
        assertNull(manager.next())
        assertTrue(manager.isFinished)
    }

    @Test
    fun `exhausted shuffled queue - enqueue resumes on the new track`() {
        val manager = seeded(4)
        manager.toggleShuffle()
        exhaust(manager)
        assertTrue(manager.isFinished)
        assertTrue(manager.addToQueue(track("fresh")))
        assertEquals("fresh", manager.currentTrack?.id)
        assertFalse(manager.isFinished)
    }

    @Test
    fun `addAllToQueue on a finished queue starts on the first added track`() {
        val manager = seeded(2)
        exhaust(manager)
        val shouldStart = manager.addAllToQueue(listOf(track("a"), track("b")))
        assertTrue(shouldStart)
        assertEquals("a", manager.currentTrack?.id)
        assertEquals("b", manager.next()?.id)
        assertNull(manager.next())
    }

    // --- STOPPED (player released without natural exhaustion) --------------

    @Test
    fun `stopped session - markFinished drops the selection and enqueue resumes`() {
        val manager = seeded(3)
        manager.next() // mid-session on t2, like a failure-streak stop
        manager.markFinished() // what MarsMusicPlayer.stopPlayback does
        assertTrue(manager.isFinished)
        assertEquals(-1, manager.currentIndex)
        assertNull(manager.currentTrack)
        assertEquals(3, manager.size)

        assertTrue(manager.addToQueue(track("fresh")))
        assertEquals("fresh", manager.currentTrack?.id)
        assertFalse(manager.isFinished)
    }

    @Test
    fun `markFinished is idempotent and finished navigation stays dead`() {
        val manager = seeded(2)
        manager.markFinished()
        manager.markFinished()
        assertTrue(manager.isFinished)
        assertNull(manager.next()) // next() on a finished session keeps it finished
        assertNull(manager.previous())
        assertTrue(manager.isFinished)
        assertNull(manager.currentTrack)
    }

    // --- REPEAT modes -------------------------------------------------------

    @Test
    fun `repeat all never exhausts so enqueue stays append-only`() {
        val manager = seeded(3)
        manager.setRepeatMode(MusicQueueManager.RepeatMode.ALL)
        repeat(10) { manager.next() } // wraps around, session stays alive
        assertFalse(manager.isFinished)
        assertFalse(manager.addToQueue(track("fresh")))
        assertTrue(manager.currentTrack != null)
        // The wrap still reaches the new track within one more full pass.
        val visited = mutableSetOf(manager.currentTrack!!.id)
        repeat(4) { visited += manager.next()!!.id }
        assertTrue("fresh" in visited)
    }

    @Test
    fun `repeat one replays the last track instead of exhausting`() {
        val manager = seeded(2)
        manager.next() // t2, the last track
        manager.setRepeatMode(MusicQueueManager.RepeatMode.ONE)
        repeat(5) { assertEquals("t2", manager.next()?.id) }
        assertFalse(manager.isFinished)
        assertFalse(manager.addToQueue(track("fresh")))
        assertEquals("t2", manager.currentTrack?.id)
        // Repeat ONE keeps replaying the current track; nothing resurrects.
        assertEquals("t2", manager.next()?.id)
    }

    @Test
    fun `repeat all enqueue after wrap keeps session active`() {
        val manager = seeded(2)
        manager.setRepeatMode(MusicQueueManager.RepeatMode.ALL)
        manager.next(); manager.next() // wrapped back to t1
        assertFalse(manager.isFinished)
        assertFalse(manager.addToQueue(track("fresh")))
        assertEquals("t1", manager.currentTrack?.id)
    }

    // --- Explicit selections clear the finished state -----------------------

    @Test
    fun `playAt after finish revives the session on the chosen track`() {
        val manager = seeded(3)
        exhaust(manager)
        assertEquals("t2", manager.playAt(1)?.id)
        assertFalse(manager.isFinished)
        assertEquals("t3", manager.next()?.id)
    }

    @Test
    fun `setQueue and clear reset the finished state`() {
        val manager = seeded(2)
        exhaust(manager)
        assertTrue(manager.isFinished)

        manager.setQueue(tracks(3))
        assertFalse(manager.isFinished)
        assertEquals("t1", manager.currentTrack?.id)

        exhaust(manager)
        manager.clear()
        assertFalse(manager.isFinished)
        assertTrue(manager.isEmpty)
        assertTrue("cleared queue is empty → next add starts playback", manager.addToQueue(track("x")))
    }

    // --- The pure policy itself ----------------------------------------------

    @Test
    fun `policy starts playback only for empty or finished queues`() {
        assertTrue(QueueResumePolicy.shouldStartPlayback(queueWasEmpty = true, queueFinished = false))
        assertTrue(QueueResumePolicy.shouldStartPlayback(queueWasEmpty = false, queueFinished = true))
        assertTrue(QueueResumePolicy.shouldStartPlayback(queueWasEmpty = true, queueFinished = true))
        assertFalse(QueueResumePolicy.shouldStartPlayback(queueWasEmpty = false, queueFinished = false))
    }

    // --- Full user-facing regression: exhausted → enqueue → play through -----

    @Test
    fun `regression - after the last track the new enqueue plays and old tracks stay dead`() {
        val manager = seeded(2)
        assertEquals("t1", manager.currentTrack?.id)
        assertEquals("t2", manager.next()?.id)
        assertNull("last track completed, repeat off", manager.next())
        assertNull("old track must not resurface as current", manager.currentTrack)

        assertTrue(manager.addToQueue(track("fresh")))
        assertEquals("fresh", manager.currentTrack?.id)
        // Playback continues only through the new material; the old pass is over.
        assertNull(manager.next())
        assertTrue(manager.isFinished)
    }
}
