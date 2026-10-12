package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPolicyTest {

    @Test
    fun `tokens match the calm motion stance`() {
        assertEquals(120, MarsMotion.fastMs)
        assertEquals(200, MarsMotion.baseMs)
        assertEquals(300, MarsMotion.slowMs)
    }

    @Test
    fun `decelerate is the default curve and bounce or overshoot are forbidden`() {
        assertEquals("decelerate", MarsMotion.INTERPOLATOR_DECELERATE)
        assertTrue(MarsMotion.isAllowedInterpolator(MarsMotion.INTERPOLATOR_DECELERATE))
        assertFalse(MarsMotion.isAllowedInterpolator(MarsMotion.INTERPOLATOR_BOUNCE))
        assertFalse(MarsMotion.isAllowedInterpolator(MarsMotion.INTERPOLATOR_OVERSHOOT))
    }

    @Test
    fun `scale 1f is not reduced motion`() {
        assertFalse(MotionPolicy.isReducedMotion(1f))
    }

    @Test
    fun `scale 0f is reduced motion`() {
        assertTrue(MotionPolicy.isReducedMotion(0f))
    }

    @Test
    fun `negative and NaN scales are reduced motion`() {
        assertTrue(MotionPolicy.isReducedMotion(-1f))
        assertTrue(MotionPolicy.isReducedMotion(Float.NaN))
    }

    @Test
    fun `reduced motion collapses every event to zero`() {
        MotionEvent.values().forEach { event ->
            assertEquals(
                "event $event must be instant under reduced motion",
                0,
                MotionPolicy.durationFor(event, reducedMotion = true),
            )
        }
    }

    @Test
    fun `full motion maps base events to baseMs`() {
        assertEquals(MarsMotion.baseMs, MotionPolicy.durationFor(MotionEvent.MESSAGE_REVEAL, false))
        assertEquals(MarsMotion.baseMs, MotionPolicy.durationFor(MotionEvent.CARD_EXPAND, false))
        assertEquals(MarsMotion.baseMs, MotionPolicy.durationFor(MotionEvent.NETWORK_BANNER, false))
    }

    @Test
    fun `full motion maps fast events to fastMs`() {
        assertEquals(MarsMotion.fastMs, MotionPolicy.durationFor(MotionEvent.SCROLL_TO_BOTTOM_SHOW, false))
        assertEquals(MarsMotion.fastMs, MotionPolicy.durationFor(MotionEvent.MICRO_TAP, false))
    }

    @Test
    fun `full motion maps drawer to slowMs`() {
        assertEquals(MarsMotion.slowMs, MotionPolicy.durationFor(MotionEvent.DRAWER, false))
    }

    @Test
    fun `every duration is non-negative for both motion modes`() {
        MotionEvent.values().forEach { event ->
            val full = MotionPolicy.durationFor(event, reducedMotion = false)
            val reduced = MotionPolicy.durationFor(event, reducedMotion = true)
            assertTrue("event $event full duration must be positive, was $full", full > 0)
            assertTrue("event $event reduced duration must not be negative, was $reduced", reduced >= 0)
        }
    }

    @Test
    fun `no event exceeds the slowest token`() {
        MotionEvent.values().forEach { event ->
            val duration = MotionPolicy.durationFor(event, reducedMotion = false)
            assertTrue("event $event duration $duration exceeds slowMs", duration <= MarsMotion.slowMs)
        }
    }
}
