package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayPauseIconPolicyTest {

    @Test fun crossfadeIsTheFastTokenUnderNormalMotion() {
        assertEquals(MarsMotion.fastMs, PlayPauseIconPolicy.durationMs(reducedMotion = false))
        assertEquals(120, PlayPauseIconPolicy.durationMs(reducedMotion = false))
    }

    @Test fun crossfadeCollapsesToInstantSwapUnderReducedMotion() {
        assertEquals(0, PlayPauseIconPolicy.durationMs(reducedMotion = true))
    }

    @Test fun crossfadeNeverExceedsTheFastToken() {
        assertTrue(PlayPauseIconPolicy.durationMs(reducedMotion = false) <= MarsMotion.fastMs)
        assertTrue(PlayPauseIconPolicy.durationMs(reducedMotion = true) <= MarsMotion.fastMs)
    }

    @Test fun firstRenderIsInstantBecauseThereIsNothingToFadeFrom() {
        assertFalse(PlayPauseIconPolicy.shouldCrossfade(currentResId = null, targetResId = 2, durationMs = 120))
    }

    @Test fun unchangedGlyphNeverAnimates() {
        assertFalse(PlayPauseIconPolicy.shouldCrossfade(currentResId = 1, targetResId = 1, durationMs = 120))
    }

    @Test fun realGlyphChangeCrossfadesWhileAnimationIsAllowed() {
        assertTrue(PlayPauseIconPolicy.shouldCrossfade(currentResId = 1, targetResId = 2, durationMs = 120))
    }

    @Test fun reducedMotionSwapsWithoutCrossfade() {
        assertFalse(PlayPauseIconPolicy.shouldCrossfade(currentResId = 1, targetResId = 2, durationMs = 0))
    }
}
