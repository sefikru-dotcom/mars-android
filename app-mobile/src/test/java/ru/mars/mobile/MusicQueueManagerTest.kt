package ru.mars.mobile

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicTrack

class MusicQueueManagerTest {

    private fun track(id: String) = MusicTrack(id = id, title = "Title $id", artist = "Artist $id")

    private fun tracks(count: Int): List<MusicTrack> = (1..count).map { track("t$it") }

    /** Manager with a seeded Random (deterministic shuffles) and [count] tracks, current = t1. */
    private fun seeded(count: Int = 4, seed: Int = 7): MusicQueueManager =
        MusicQueueManager(Random(seed)).apply { setQueue(tracks(count)) }

    @Test
    fun `empty manager has no current track and no navigation`() {
        val manager = MusicQueueManager()
        assertTrue(manager.isEmpty)
        assertEquals(-1, manager.currentIndex)
        assertNull(manager.currentTrack)
        assertNull(manager.next())
        assertNull(manager.previous())
        assertFalse(manager.hasNext())
        assertFalse(manager.hasPrevious())
        assertTrue(manager.upcoming().isEmpty())
    }

    @Test
    fun `setQueue selects a clamped start index`() {
        val manager = MusicQueueManager()
        manager.setQueue(tracks(3), startIndex = 99)
        assertEquals(2, manager.currentIndex)
        assertEquals("t3", manager.currentTrack?.id)

        manager.setQueue(tracks(3), startIndex = -5)
        assertEquals(0, manager.currentIndex)
        assertEquals("t1", manager.currentTrack?.id)

        manager.setQueue(emptyList())
        assertEquals(-1, manager.currentIndex)
        assertNull(manager.currentTrack)
        assertNull(manager.next())
    }

    @Test
    fun `sequential next advances and finishes the session at the end when repeat is off`() {
        val manager = seeded(3)
        assertEquals("t1", manager.currentTrack?.id)
        assertEquals(0, manager.currentIndex)
        assertEquals("t2", manager.next()?.id)
        assertEquals("t3", manager.next()?.id)
        assertNull(manager.next())
        // Exhaustion finishes the session: the stale selection is dropped, not preserved.
        assertEquals(-1, manager.currentIndex)
        assertTrue(manager.isFinished)
        assertNull(manager.currentTrack)
        assertFalse(manager.hasNext())
        assertNull(manager.nextIndex()) // repeat off: nothing follows the last track
    }

    @Test
    fun `sequential previous goes back and stops at the start when repeat is off`() {
        val manager = seeded(3)
        manager.next()
        assertEquals(1, manager.currentIndex)
        assertEquals("t1", manager.previous()?.id)
        assertNull(manager.previous())
        assertEquals(0, manager.currentIndex)
        assertFalse(manager.hasPrevious())
    }

    @Test
    fun `repeat all wraps around both ends`() {
        val manager = seeded(3)
        manager.setRepeatMode(MusicQueueManager.RepeatMode.ALL)
        manager.playAt(2)
        assertEquals("t1", manager.next()?.id)
        assertEquals(0, manager.currentIndex)
        assertEquals("t3", manager.previous()?.id)
        assertEquals(2, manager.currentIndex)
        assertTrue(manager.hasNext())
        assertTrue(manager.hasPrevious())
    }

    @Test
    fun `repeat one replays the current track in both directions`() {
        val manager = seeded(3)
        manager.next()
        assertEquals("t2", manager.currentTrack?.id)
        manager.setRepeatMode(MusicQueueManager.RepeatMode.ONE)
        assertEquals("t2", manager.next()?.id)
        assertEquals(1, manager.currentIndex)
        assertEquals("t2", manager.previous()?.id)
        assertEquals(1, manager.currentIndex)
        assertEquals(1, manager.nextIndex())
        assertEquals(1, manager.previousIndex())
    }

    @Test
    fun `cycleRepeatMode cycles off all one off`() {
        val manager = seeded(2)
        assertEquals(MusicQueueManager.RepeatMode.OFF, manager.repeatMode)
        assertEquals(MusicQueueManager.RepeatMode.ALL, manager.cycleRepeatMode())
        assertEquals(MusicQueueManager.RepeatMode.ONE, manager.cycleRepeatMode())
        assertEquals(MusicQueueManager.RepeatMode.OFF, manager.cycleRepeatMode())
    }

    @Test
    fun `shuffle visits every track exactly once before the pass ends`() {
        val manager = seeded(6)
        assertTrue(manager.toggleShuffle())
        assertTrue(manager.isShuffled)
        val visited = mutableListOf(manager.currentTrack!!.id)
        while (manager.hasNext()) visited += manager.next()!!.id
        assertEquals(tracks(6).map { it.id }.toSet(), visited.toSet())
        assertEquals("no duplicates and no skips", 6, visited.size)
        assertNull(manager.next()) // repeat off: the shuffled pass is over
    }

    @Test
    fun `shuffle with repeat all never exhausts the queue`() {
        val manager = seeded(4)
        manager.toggleShuffle()
        manager.setRepeatMode(MusicQueueManager.RepeatMode.ALL)
        repeat(12) {
            assertNotNull(manager.next())
            assertTrue(manager.currentIndex in 0 until manager.size)
        }
    }

    @Test
    fun `toggling shuffle keeps the current track selected`() {
        val manager = seeded(5)
        manager.playAt(3)
        manager.toggleShuffle()
        assertEquals(3, manager.currentIndex)
        assertEquals("t4", manager.currentTrack?.id)
        manager.toggleShuffle()
        assertEquals(3, manager.currentIndex)
        assertEquals("t4", manager.currentTrack?.id)
    }

    @Test
    fun `shuffle off restores the natural order from the current track`() {
        val manager = seeded(5)
        manager.playAt(1)
        manager.toggleShuffle()
        manager.toggleShuffle() // back to sequential
        assertEquals("t3", manager.next()?.id)
        assertEquals("t4", manager.next()?.id)
        assertEquals("t5", manager.next()?.id)
        assertNull(manager.next())
    }

    @Test
    fun `previous at the head of a shuffled pass only wraps with repeat all`() {
        val manager = seeded(4)
        manager.toggleShuffle() // current track becomes the head of the shuffled order
        assertFalse(manager.hasPrevious())
        assertNull(manager.previous())
        manager.setRepeatMode(MusicQueueManager.RepeatMode.ALL)
        assertNotNull(manager.previous())
        assertTrue(manager.currentIndex in 1..3) // wrapped into the shuffled tail
    }

    @Test
    fun `addToQueue appends after the current track and extends navigation`() {
        val manager = seeded(2)
        manager.addToQueue(track("t3"))
        assertEquals(3, manager.size)
        assertEquals(0, manager.currentIndex) // appending does not move the selection
        assertEquals("t2", manager.next()?.id)
        assertEquals("t3", manager.next()?.id)
        assertNull(manager.next())
    }

    @Test
    fun `addToQueue on an empty manager makes the first addition current`() {
        val manager = MusicQueueManager()
        manager.addToQueue(track("solo"))
        assertEquals("solo", manager.currentTrack?.id)
        assertEquals(0, manager.currentIndex)

        manager.addAllToQueue(tracks(2))
        assertEquals(3, manager.size)
        assertEquals("solo", manager.currentTrack?.id) // selection unchanged by appends
        assertEquals("t1", manager.next()?.id)
        assertEquals("t2", manager.next()?.id)
    }

    @Test
    fun `addToQueue during shuffle schedules new tracks at the end of the pass`() {
        val manager = seeded(3)
        manager.toggleShuffle()
        manager.addToQueue(track("t4"))
        val visited = mutableSetOf(manager.currentTrack!!.id)
        while (manager.hasNext()) visited += manager.next()!!.id
        assertEquals(setOf("t1", "t2", "t3", "t4"), visited)
        assertEquals(4, manager.size)
    }

    @Test
    fun `addAllToQueue with an empty list is a no-op`() {
        val manager = seeded(2)
        manager.addAllToQueue(emptyList())
        assertEquals(2, manager.size)
        assertEquals(0, manager.currentIndex)
        assertTrue(manager.isEmpty.not())
    }

    @Test
    fun `playAt jumps to any track and rejects out-of-range indexes`() {
        val manager = seeded(4)
        assertEquals("t3", manager.playAt(2)?.id)
        assertEquals(2, manager.currentIndex)
        assertNull(manager.playAt(9))
        assertNull(manager.playAt(-1))
        assertEquals(2, manager.currentIndex) // a failed jump keeps the selection
        assertEquals("t4", manager.next()?.id) // and navigation continues from it
    }

    @Test
    fun `playAt during shuffle continues along the shuffled order`() {
        val manager = seeded(5)
        manager.toggleShuffle()
        assertEquals("t5", manager.playAt(4)?.id)
        assertEquals(4, manager.currentIndex)
        manager.setRepeatMode(MusicQueueManager.RepeatMode.ALL)
        val visited = mutableSetOf("t5")
        repeat(20) { visited += manager.next()!!.id }
        assertEquals("the whole queue stays reachable after a jump", tracks(5).map { it.id }.toSet(), visited)
    }

    @Test
    fun `upcoming lists the rest of the pass in play order`() {
        val manager = seeded(4)
        assertEquals(listOf("t2", "t3", "t4"), manager.upcoming().map { it.id })
        manager.next()
        assertEquals(listOf("t3", "t4"), manager.upcoming().map { it.id })
        manager.toggleShuffle() // current t2 heads a fresh shuffled pass over everything else
        assertEquals(setOf("t1", "t3", "t4"), manager.upcoming().map { it.id }.toSet())
        assertEquals(3, manager.upcoming().size)
    }

    @Test
    fun `clear resets queue selection and order`() {
        val manager = seeded(3)
        manager.toggleShuffle()
        manager.clear()
        assertTrue(manager.isEmpty)
        assertEquals(-1, manager.currentIndex)
        assertNull(manager.currentTrack)
        assertNull(manager.next())
        assertNull(manager.previous())
        assertTrue(manager.upcoming().isEmpty())
    }

    @Test
    fun `setQueue keeps shuffle and repeat settings`() {
        val manager = seeded(3)
        manager.toggleShuffle()
        manager.cycleRepeatMode() // ALL
        manager.setQueue(tracks(5))
        assertTrue(manager.isShuffled)
        assertEquals(MusicQueueManager.RepeatMode.ALL, manager.repeatMode)
        assertEquals(0, manager.currentIndex)
        // Repeat ALL never reports the end; a bounded walk still covers the new queue.
        val visited = mutableSetOf(manager.currentTrack!!.id)
        repeat(4) { visited += manager.next()!!.id }
        assertEquals(5, visited.size)
    }

    @Test
    fun `setQueue selects the first track and next moves to the second`() {
        val manager = MusicQueueManager()
        manager.setQueue(tracks(3))
        assertEquals(0, manager.currentIndex)
        assertEquals("t2", manager.next()?.id)
    }

    @Test
    fun `removeAt rejects out-of-range indexes and keeps state`() {
        val manager = seeded(3)
        manager.playAt(1)
        assertNull(manager.removeAt(-1))
        assertNull(manager.removeAt(3))
        assertEquals(3, manager.size)
        assertEquals(1, manager.currentIndex)
        assertEquals("t2", manager.currentTrack?.id)
        assertFalse(manager.isFinished)
    }

    @Test
    fun `removeAt after the current track keeps the selection and navigation skips the removed track`() {
        val manager = seeded(4)
        assertEquals("t3", manager.removeAt(2)?.id)
        assertEquals(3, manager.size)
        assertEquals(0, manager.currentIndex)
        assertEquals("t1", manager.currentTrack?.id)
        assertEquals(listOf("t2", "t4"), manager.upcoming().map { it.id })
        assertEquals("t2", manager.next()?.id)
        assertEquals("t4", manager.next()?.id)
        assertNull(manager.next())
        assertTrue(manager.isFinished)
    }

    @Test
    fun `removeAt before the current track shifts the selection down and keeps the current track`() {
        val manager = seeded(4)
        manager.playAt(2) // current = t3
        assertEquals("t1", manager.removeAt(0)?.id)
        assertEquals(1, manager.currentIndex)
        assertEquals("t3", manager.currentTrack?.id)
        assertFalse(manager.isFinished)
        assertEquals("t4", manager.next()?.id)
        assertNull(manager.next())
    }

    @Test
    fun `removeAt of the current track selects the successor`() {
        val manager = seeded(4)
        manager.playAt(1) // current = t2
        assertEquals("t2", manager.removeAt(1)?.id)
        assertEquals(3, manager.size)
        assertEquals(1, manager.currentIndex)
        assertEquals("t3", manager.currentTrack?.id)
        assertFalse(manager.isFinished)
        assertEquals("t4", manager.next()?.id)
        assertNull(manager.next())
    }

    @Test
    fun `removeAt of the current tail track finishes the session when repeat is off`() {
        val manager = seeded(3)
        manager.playAt(2) // current = t3, the tail
        assertEquals("t3", manager.removeAt(2)?.id)
        assertEquals(2, manager.size)
        assertEquals(-1, manager.currentIndex)
        assertNull(manager.currentTrack)
        assertTrue(manager.isFinished)
        assertFalse(manager.hasNext())
        assertNull(manager.nextIndex())
        assertTrue(manager.upcoming().isEmpty())
        // The queue itself is kept for the UI.
        assertEquals(listOf("t1", "t2"), manager.queue.map { it.id })
        // A fresh addition resumes the session on the added track.
        manager.addToQueue(track("t5"))
        assertFalse(manager.isFinished)
        assertEquals("t5", manager.currentTrack?.id)
    }

    @Test
    fun `removeAt of the current tail track wraps to the start with repeat all`() {
        val manager = seeded(3)
        manager.setRepeatMode(MusicQueueManager.RepeatMode.ALL)
        manager.playAt(2) // current = t3, the tail
        assertEquals("t3", manager.removeAt(2)?.id)
        assertEquals(0, manager.currentIndex)
        assertEquals("t1", manager.currentTrack?.id)
        assertFalse(manager.isFinished)
        assertEquals("t2", manager.next()?.id)
        assertEquals("t1", manager.next()?.id) // wraps around
    }

    @Test
    fun `removeAt of the only track leaves an empty not-finished manager`() {
        val manager = MusicQueueManager()
        manager.addToQueue(track("solo"))
        assertEquals("solo", manager.removeAt(0)?.id)
        assertTrue(manager.isEmpty)
        assertEquals(-1, manager.currentIndex)
        assertNull(manager.currentTrack)
        assertFalse(manager.isFinished)
        assertNull(manager.next())
        assertNull(manager.previous())
        assertTrue(manager.upcoming().isEmpty())
        // The next addition starts a fresh session.
        manager.addToQueue(track("t1"))
        assertEquals(0, manager.currentIndex)
        assertEquals("t1", manager.currentTrack?.id)
    }

    @Test
    fun `removeAt on a finished session drops the track and keeps the session finished`() {
        val manager = seeded(2)
        manager.next() // current = t2
        assertNull(manager.next()) // exhausted -> finished
        assertTrue(manager.isFinished)
        assertEquals("t1", manager.removeAt(0)?.id)
        assertEquals(1, manager.size)
        assertEquals(-1, manager.currentIndex)
        assertTrue(manager.isFinished)
        assertNull(manager.currentTrack)
    }

    @Test
    fun `removeAt of a non-current track during shuffle keeps the current track and pass intact`() {
        val manager = seeded(5)
        manager.toggleShuffle() // current t1 heads the shuffled pass
        assertEquals("t3", manager.removeAt(2)?.id)
        assertEquals(4, manager.size)
        assertEquals("t1", manager.currentTrack?.id) // selection unchanged
        assertFalse(manager.isFinished)
        // The rest of the pass covers exactly the surviving tracks, once each.
        val visited = mutableSetOf(manager.currentTrack!!.id)
        while (manager.hasNext()) visited += manager.next()!!.id
        assertEquals(setOf("t1", "t2", "t4", "t5"), visited)
        assertEquals(4, visited.size)
    }

    @Test
    fun `removeAt before the current track during shuffle keeps the current track and pass order`() {
        val manager = seeded(4)
        manager.toggleShuffle()
        manager.playAt(3) // t4, wherever the pass happens to place it
        val upcomingBefore = manager.upcoming().map { it.id }
        assertEquals("t1", manager.removeAt(0)?.id) // not current
        assertEquals("t4", manager.currentTrack?.id)
        assertFalse(manager.isFinished)
        // The rest of the pass keeps its order and simply loses t1 (if it was scheduled).
        assertEquals(upcomingBefore.filter { it != "t1" }, manager.upcoming().map { it.id })
        val visited = mutableSetOf("t4")
        while (manager.hasNext()) visited += manager.next()!!.id
        assertEquals(setOf("t4") + upcomingBefore.filter { it != "t1" }, visited)
    }

    @Test
    fun `removeAt of the current track during shuffle moves to the next shuffled entry`() {
        val manager = seeded(5)
        manager.toggleShuffle()
        val upcomingBefore = manager.upcoming().map { it.id } // the rest of the pass, in order
        assertEquals(4, upcomingBefore.size)
        assertEquals("t1", manager.removeAt(0)?.id) // remove the current track
        assertEquals(4, manager.size)
        assertFalse(manager.isFinished)
        // The head of the remaining pass becomes the current track.
        assertEquals(upcomingBefore.first(), manager.currentTrack?.id)
        // The walk still covers every surviving track exactly once.
        val visited = mutableSetOf(manager.currentTrack!!.id)
        while (manager.hasNext()) visited += manager.next()!!.id
        assertEquals(upcomingBefore.toSet(), visited)
        assertEquals(4, visited.size)
    }

    @Test
    fun `removeAt of the last shuffled pass entry finishes the session when repeat is off`() {
        val manager = seeded(3)
        manager.toggleShuffle()
        while (manager.hasNext()) manager.next() // current = the last entry of the pass
        val removed = manager.removeAt(manager.currentIndex)
        assertNotNull(removed)
        assertEquals(2, manager.size)
        assertTrue(manager.isFinished)
        assertEquals(-1, manager.currentIndex)
        assertNull(manager.currentTrack)
        assertFalse(manager.hasNext())
    }

    @Test
    fun `removeAt of the last shuffled pass entry wraps with repeat all`() {
        val manager = seeded(3)
        manager.toggleShuffle()
        manager.setRepeatMode(MusicQueueManager.RepeatMode.ALL)
        manager.next()
        manager.next() // current = the last entry of the first pass
        val removed = manager.removeAt(manager.currentIndex)
        assertNotNull(removed)
        assertEquals(2, manager.size)
        assertFalse(manager.isFinished)
        assertNotNull(manager.currentTrack) // wrapped to the head of the shuffled order
        // Both survivors stay reachable from the wrapped position.
        val visited = mutableSetOf(manager.currentTrack!!.id)
        visited += manager.next()!!.id
        assertEquals(2, visited.size)
        assertEquals((manager.queue.map { it.id }).toSet(), visited)
    }

    @Test
    fun `removeAt keeps the queue consistent across mixed removals`() {
        val manager = seeded(5)
        manager.playAt(2) // current = t3
        assertEquals("t1", manager.removeAt(0)?.id) // before current
        assertEquals(listOf("t2", "t3", "t4", "t5"), manager.queue.map { it.id })
        assertEquals("t3", manager.currentTrack?.id)
        assertEquals(1, manager.currentIndex)
        assertEquals("t5", manager.removeAt(3)?.id) // after current
        assertEquals(listOf("t2", "t3", "t4"), manager.queue.map { it.id })
        assertEquals("t3", manager.currentTrack?.id)
        assertEquals(listOf("t4"), manager.upcoming().map { it.id })
        assertEquals("t3", manager.removeAt(1)?.id) // current itself
        assertEquals(listOf("t2", "t4"), manager.queue.map { it.id })
        assertEquals("t4", manager.currentTrack?.id)
        assertEquals(1, manager.currentIndex)
        assertFalse(manager.isFinished)
    }
}
