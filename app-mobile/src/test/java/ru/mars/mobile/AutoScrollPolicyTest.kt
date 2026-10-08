package ru.mars.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AutoScrollPolicy] — the transcript must follow the stream only while the
 * viewport is pinned to the bottom; a reader who scrolled up mid-stream keeps their position
 * across every SSE-token re-render until they scroll back down (or pin explicitly).
 */
class AutoScrollPolicyTest {

    private val policy = AutoScrollPolicy(bottomThresholdPx = 100)

    @Test
    fun `starts pinned so fresh sessions follow the stream`() {
        assertTrue(policy.pinnedToBottom)
        assertTrue(policy.shouldScrollOnContentChanged())
    }

    @Test
    fun `scrolling up beyond the threshold unpins the viewport`() {
        policy.onViewportDistanceFromBottom(480)
        assertFalse(policy.pinnedToBottom)
        assertFalse(policy.shouldScrollOnContentChanged())
    }

    @Test
    fun `distance within the threshold still counts as pinned`() {
        policy.onViewportDistanceFromBottom(100)
        assertTrue(policy.shouldScrollOnContentChanged())
        policy.onViewportDistanceFromBottom(101)
        assertFalse(policy.shouldScrollOnContentChanged())
    }

    @Test
    fun `scrolling back down to the tail re-pins the reader`() {
        policy.onViewportDistanceFromBottom(900)
        assertFalse(policy.shouldScrollOnContentChanged())
        policy.onViewportDistanceFromBottom(40)
        assertTrue(policy.shouldScrollOnContentChanged())
    }

    @Test
    fun `pinToBottom overrides a scrolled-up reader`() {
        policy.onViewportDistanceFromBottom(900)
        policy.pinToBottom()
        assertTrue(policy.pinnedToBottom)
        assertTrue(policy.shouldScrollOnContentChanged())
    }

    @Test
    fun `sse token burst never yanks a scrolled-up reader back down`() {
        // The reader scrolls up, then 20 tokens arrive — each render re-checks the policy.
        policy.onViewportDistanceFromBottom(1200)
        val scrollDecisions = (1..20).map {
            // Simulated render path: content grew, viewport did not move.
            policy.onViewportDistanceFromBottom(1200 + it * 12)
            policy.shouldScrollOnContentChanged()
        }
        assertFalse(scrollDecisions.any { it })
    }

    @Test
    fun `pinned viewport follows every token of the burst`() {
        val scrollDecisions = (1..20).map {
            policy.onViewportDistanceFromBottom(0)
            policy.shouldScrollOnContentChanged()
        }
        assertTrue(scrollDecisions.all { it })
    }
}
