package ru.mars.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ScrollToBottomPolicy] — the FAB must appear only when the reader clearly
 * scrolled away from the tail, survive small jitters near a single threshold (hysteresis),
 * and hide immediately when the reader is taken back to the latest message.
 */
class ScrollToBottomPolicyTest {

    private val policy = ScrollToBottomPolicy(showThresholdPx = 300, hideThresholdPx = 100)

    @Test
    fun `starts hidden so fresh pinned sessions show no button`() {
        assertFalse(policy.visible)
    }

    @Test
    fun `transcript that fits the viewport never shows the button`() {
        repeat(5) { policy.onViewportDistanceFromBottom(0) }
        assertFalse(policy.visible)
    }

    @Test
    fun `scrolling up beyond the show threshold reveals the button`() {
        policy.onViewportDistanceFromBottom(301)
        assertTrue(policy.visible)
    }

    @Test
    fun `show threshold is inclusive`() {
        policy.onViewportDistanceFromBottom(300)
        assertTrue(policy.visible)
    }

    @Test
    fun `hide threshold is inclusive when scrolling back down`() {
        policy.onViewportDistanceFromBottom(900)
        policy.onViewportDistanceFromBottom(100)
        assertFalse(policy.visible)
    }

    @Test
    fun `hysteresis keeps a visible button visible inside the band`() {
        policy.onViewportDistanceFromBottom(900)
        assertTrue(policy.visible)
        // Reader drifts down into the band but not to the tail: no flicker off.
        policy.onViewportDistanceFromBottom(200)
        assertTrue(policy.visible)
        policy.onViewportDistanceFromBottom(101)
        assertTrue(policy.visible)
    }

    @Test
    fun `hysteresis keeps a hidden button hidden inside the band`() {
        policy.onViewportDistanceFromBottom(299)
        assertFalse(policy.visible)
        // Jitter around the middle of the band must not pop the button up.
        policy.onViewportDistanceFromBottom(150)
        assertFalse(policy.visible)
        policy.onViewportDistanceFromBottom(250)
        assertFalse(policy.visible)
    }

    @Test
    fun `jitter across a single threshold does not flicker the button`() {
        // Before showing: values below the show threshold toggle — stays hidden.
        repeat(10) { i ->
            policy.onViewportDistanceFromBottom(if (i % 2 == 0) 299 else 180)
            assertFalse(policy.visible)
        }
        // After showing: values above the hide threshold toggle — stays visible.
        policy.onViewportDistanceFromBottom(900)
        repeat(10) { i ->
            policy.onViewportDistanceFromBottom(if (i % 2 == 0) 101 else 250)
            assertTrue(policy.visible)
        }
    }

    @Test
    fun `onJumpToLatest hides the button immediately`() {
        policy.onViewportDistanceFromBottom(900)
        assertTrue(policy.visible)
        policy.onJumpToLatest()
        assertFalse(policy.visible)
    }

    @Test
    fun `after a jump the button only returns past the show threshold again`() {
        policy.onViewportDistanceFromBottom(900)
        policy.onJumpToLatest()
        policy.onViewportDistanceFromBottom(200)
        assertFalse(policy.visible)
        policy.onViewportDistanceFromBottom(300)
        assertTrue(policy.visible)
    }

    @Test
    fun `sse burst while the reader is up grows the distance and keeps the button shown`() {
        policy.onViewportDistanceFromBottom(400)
        assertTrue(policy.visible)
        (1..20).forEach { token ->
            // Content grows, viewport does not move: distance increases with every token.
            policy.onViewportDistanceFromBottom(400 + token * 12)
            assertTrue(policy.visible)
        }
    }

    @Test
    fun `pinned reader following the stream keeps the button hidden through the burst`() {
        (1..20).forEach {
            policy.onViewportDistanceFromBottom(0)
            assertFalse(policy.visible)
        }
    }

    @Test
    fun `full up-then-down round trip restores the hidden state`() {
        policy.onViewportDistanceFromBottom(1500)
        assertTrue(policy.visible)
        policy.onViewportDistanceFromBottom(800)
        assertTrue(policy.visible)
        policy.onViewportDistanceFromBottom(300)
        assertTrue(policy.visible)
        policy.onViewportDistanceFromBottom(100)
        assertFalse(policy.visible)
        policy.onViewportDistanceFromBottom(0)
        assertFalse(policy.visible)
    }

    @Test
    fun `thresholds must form a valid hysteresis band`() {
        assertThrows(IllegalArgumentException::class.java) {
            ScrollToBottomPolicy(showThresholdPx = 100, hideThresholdPx = 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScrollToBottomPolicy(showThresholdPx = 100, hideThresholdPx = 300)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScrollToBottomPolicy(showThresholdPx = 100, hideThresholdPx = -1)
        }
    }

    @Test
    fun `defaults form a valid band and match the documented dp values`() {
        val defaults = ScrollToBottomPolicy()
        assertFalse(defaults.visible)
        assertTrue(ScrollToBottomPolicy.DEFAULT_SHOW_THRESHOLD_PX > ScrollToBottomPolicy.DEFAULT_HIDE_THRESHOLD_PX)
        defaults.onViewportDistanceFromBottom(ScrollToBottomPolicy.DEFAULT_SHOW_THRESHOLD_PX)
        assertTrue(defaults.visible)
        defaults.onViewportDistanceFromBottom(ScrollToBottomPolicy.DEFAULT_HIDE_THRESHOLD_PX)
        assertFalse(defaults.visible)
    }
}
