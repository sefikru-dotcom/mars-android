package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TvAutoScrollPolicy] — pure JVM, no Android framework.
 * Covers the pin state machine of the TV chat transcript: initial pin, release when
 * the reader scrolls away from the live edge (jump control appears), re-pin on return
 * / on the jump action / on an outgoing message, and the px geometry helpers
 * (distance to the bottom, clamped scrollTo target — never negative, never overscroll).
 */
class TvAutoScrollPolicyTest {

    private val threshold = 100

    @Test
    fun `starts pinned so a fresh transcript follows the stream`() {
        val policy = TvAutoScrollPolicy()
        assertTrue(policy.pinnedToBottom)
        assertTrue(policy.shouldAutoScroll())
    }

    @Test
    fun `scrolling away from the bottom releases the pin and shows the jump control`() {
        val policy = TvAutoScrollPolicy()
        val jumpVisible = policy.onScroll(distanceFromBottomPx = threshold + 1, thresholdPx = threshold)
        assertTrue("away from the live edge -> «К последнему» appears", jumpVisible)
        assertFalse(policy.pinnedToBottom)
        assertFalse("a reading user is never yanked back by a chunk", policy.shouldAutoScroll())
    }

    @Test
    fun `within the threshold the transcript stays pinned`() {
        val policy = TvAutoScrollPolicy()
        assertFalse(policy.onScroll(distanceFromBottomPx = threshold, thresholdPx = threshold))
        assertTrue(policy.shouldAutoScroll())
        assertFalse(policy.onScroll(distanceFromBottomPx = 0, thresholdPx = threshold))
        assertTrue(policy.shouldAutoScroll())
    }

    @Test
    fun `returning to the bottom re-pins and hides the jump control`() {
        val policy = TvAutoScrollPolicy()
        policy.onScroll(distanceFromBottomPx = 500, thresholdPx = threshold)
        assertFalse(policy.shouldAutoScroll())
        assertFalse(policy.onScroll(distanceFromBottomPx = 10, thresholdPx = threshold))
        assertTrue(policy.shouldAutoScroll())
    }

    @Test
    fun `pinToBottom re-pins unconditionally and always hides the jump control`() {
        val policy = TvAutoScrollPolicy()
        policy.onScroll(distanceFromBottomPx = 500, thresholdPx = threshold)
        assertFalse("pinToBottom returns the jump-control visibility", policy.pinToBottom())
        assertTrue(policy.pinnedToBottom)
        assertTrue(policy.shouldAutoScroll())
    }

    @Test
    fun `a negative threshold is coerced - only the exact bottom pins`() {
        val policy = TvAutoScrollPolicy()
        assertTrue("1px away with a coerced-to-zero threshold -> unpinned", policy.onScroll(1, -5))
        assertFalse(policy.shouldAutoScroll())
        assertFalse("exactly at the bottom -> pinned again", policy.onScroll(0, -5))
        assertTrue(policy.shouldAutoScroll())
    }

    @Test
    fun `distance from bottom is the gap between content end and viewport end`() {
        assertEquals(
            200,
            TvAutoScrollPolicy.distanceFromBottom(
                contentBottomPx = 1000, scrollYPx = 300, viewportHeightPx = 500, viewportBottomPaddingPx = 0,
            ),
        )
        assertEquals(
            "bottom padding shrinks the visible viewport - the gap grows by it",
            220,
            TvAutoScrollPolicy.distanceFromBottom(
                contentBottomPx = 1000, scrollYPx = 300, viewportHeightPx = 500, viewportBottomPaddingPx = 20,
            ),
        )
    }

    @Test
    fun `content shorter than the viewport reads zero distance - always at the bottom`() {
        assertEquals(
            0,
            TvAutoScrollPolicy.distanceFromBottom(
                contentBottomPx = 200, scrollYPx = 0, viewportHeightPx = 500, viewportBottomPaddingPx = 0,
            ),
        )
    }

    @Test
    fun `scroll target for the bottom is clamped and never overscrolls`() {
        assertEquals(
            500,
            TvAutoScrollPolicy.scrollTargetForBottom(
                contentBottomPx = 1000, viewportHeightPx = 500, viewportBottomPaddingPx = 0,
            ),
        )
        assertEquals(
            "short content -> scrollTo(0), no blank overscroll area",
            0,
            TvAutoScrollPolicy.scrollTargetForBottom(
                contentBottomPx = 200, viewportHeightPx = 500, viewportBottomPaddingPx = 0,
            ),
        )
        assertEquals(
            "target equals the distance measured from scrollY=0",
            TvAutoScrollPolicy.distanceFromBottom(700, 0, 400, 10),
            TvAutoScrollPolicy.scrollTargetForBottom(700, 400, 10),
        )
    }
}
