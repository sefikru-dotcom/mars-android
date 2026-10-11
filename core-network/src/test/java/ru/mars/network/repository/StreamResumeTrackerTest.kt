package ru.mars.network.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WHATWG "last event ID" buffer semantics of [StreamResumeTracker]: id-less frames keep
 * the cursor, a blank `id:` clears it, non-blank ids replace it verbatim, [reset] drops it.
 */
class StreamResumeTrackerTest {

    @Test
    fun `fresh tracker has no cursor`() {
        assertNull(StreamResumeTracker().lastEventId)
    }

    @Test
    fun `non-blank id becomes the cursor`() {
        val tracker = StreamResumeTracker()
        tracker.onFrameId("st-1:42")
        assertEquals("st-1:42", tracker.lastEventId)
    }

    @Test
    fun `frame without id keeps the previous cursor`() {
        val tracker = StreamResumeTracker()
        tracker.onFrameId("st-1:1")
        tracker.onFrameId(null) // SSE frame that carried no `id:` field
        tracker.onFrameId(null)
        assertEquals("st-1:1", tracker.lastEventId)
    }

    @Test
    fun `blank id resets the cursor per spec`() {
        val tracker = StreamResumeTracker()
        tracker.onFrameId("st-1:7")
        tracker.onFrameId("") // explicit empty `id:` line
        assertNull(tracker.lastEventId)

        tracker.onFrameId("st-1:8")
        tracker.onFrameId("   ") // whitespace-only is blank too
        assertNull(tracker.lastEventId)
    }

    @Test
    fun `latest id wins across a frame sequence`() {
        val tracker = StreamResumeTracker()
        listOf("st-1:1", null, "st-1:2", "st-1:3", null).forEach(tracker::onFrameId)
        assertEquals("st-1:3", tracker.lastEventId)
    }

    @Test
    fun `reset drops the cursor so it never leaks into a new stream`() {
        val tracker = StreamResumeTracker()
        tracker.onFrameId("st-1:9")
        tracker.reset()
        assertNull(tracker.lastEventId)
        // and the tracker keeps working after a reset
        tracker.onFrameId("st-2:1")
        assertEquals("st-2:1", tracker.lastEventId)
    }

    @Test
    fun `ids are stored verbatim - opaque to the tracker`() {
        val tracker = StreamResumeTracker()
        tracker.onFrameId("weird id:with:colons и юникод/#$%")
        assertEquals("weird id:with:colons и юникод/#$%", tracker.lastEventId)
    }
}
