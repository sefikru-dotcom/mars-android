package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TvPlaybackModePolicy] — pure JVM, no Android framework, no MediaPlayer.
 * Covers the full decision surface: repeat-mode ring cycling, auto-advance on track
 * completion for every mode (including wrap-around and exhaustion), D-pad next/previous
 * navigation with boundaries and wrap-around, and degenerate playlists (empty, invalid
 * index) where the policy must stop rather than guess.
 */
class TvPlaybackModePolicyTest {

    private fun result(index: Int?, stop: Boolean, reason: String) = TvAdvanceResult(index, stop, reason)

    // --- repeat-mode ring -------------------------------------------------------------------------

    @Test
    fun `repeat mode cycles OFF to ALL to ONE to OFF`() {
        assertEquals(TvRepeatMode.ALL, TvPlaybackModePolicy.nextRepeatMode(TvRepeatMode.OFF))
        assertEquals(TvRepeatMode.ONE, TvPlaybackModePolicy.nextRepeatMode(TvRepeatMode.ALL))
        assertEquals(TvRepeatMode.OFF, TvPlaybackModePolicy.nextRepeatMode(TvRepeatMode.ONE))
    }

    @Test
    fun `repeat mode ring returns to the start after three cycles`() {
        var mode = TvRepeatMode.OFF
        repeat(3) { mode = TvPlaybackModePolicy.nextRepeatMode(mode) }
        assertEquals(TvRepeatMode.OFF, mode)
    }

    // --- advanceOnCompletion: degenerate playlists --------------------------------------------------

    @Test
    fun `completion on an empty playlist stops for every mode`() {
        for (mode in TvRepeatMode.values()) {
            assertEquals(
                "mode=$mode", result(null, true, "empty_playlist"),
                TvPlaybackModePolicy.advanceOnCompletion(0, 0, mode),
            )
        }
    }

    @Test
    fun `completion on a negative playlist size stops as empty`() {
        assertEquals(
            result(null, true, "empty_playlist"),
            TvPlaybackModePolicy.advanceOnCompletion(-3, 0, TvRepeatMode.ALL),
        )
    }

    @Test
    fun `completion with an invalid index stops for every mode`() {
        for (mode in TvRepeatMode.values()) {
            assertEquals("mode=$mode index=size", result(null, true, "invalid_index"),
                TvPlaybackModePolicy.advanceOnCompletion(3, 3, mode))
            assertEquals("mode=$mode index=-1", result(null, true, "invalid_index"),
                TvPlaybackModePolicy.advanceOnCompletion(3, -1, mode))
            assertEquals("mode=$mode index=far", result(null, true, "invalid_index"),
                TvPlaybackModePolicy.advanceOnCompletion(3, 17, mode))
        }
    }

    // --- advanceOnCompletion: OFF -------------------------------------------------------------------

    @Test
    fun `OFF completion in the middle advances to the successor`() {
        assertEquals(result(1, false, "next_track"), TvPlaybackModePolicy.advanceOnCompletion(3, 0, TvRepeatMode.OFF))
        assertEquals(result(2, false, "next_track"), TvPlaybackModePolicy.advanceOnCompletion(3, 1, TvRepeatMode.OFF))
    }

    @Test
    fun `OFF completion of the last track exhausts the playlist instead of wrapping`() {
        val actual = TvPlaybackModePolicy.advanceOnCompletion(3, 2, TvRepeatMode.OFF)
        assertEquals(result(null, true, "playlist_exhausted"), actual)
        assertTrue(actual.shouldStop)
    }

    @Test
    fun `OFF completion of a single-track playlist exhausts immediately`() {
        assertEquals(result(null, true, "playlist_exhausted"),
            TvPlaybackModePolicy.advanceOnCompletion(1, 0, TvRepeatMode.OFF))
    }

    // --- advanceOnCompletion: ALL -------------------------------------------------------------------

    @Test
    fun `ALL completion in the middle advances to the successor`() {
        assertEquals(result(1, false, "repeat_all_cycle"), TvPlaybackModePolicy.advanceOnCompletion(3, 0, TvRepeatMode.ALL))
        assertEquals(result(2, false, "repeat_all_cycle"), TvPlaybackModePolicy.advanceOnCompletion(3, 1, TvRepeatMode.ALL))
    }

    @Test
    fun `ALL completion of the last track wraps around to the first`() {
        val actual = TvPlaybackModePolicy.advanceOnCompletion(3, 2, TvRepeatMode.ALL)
        assertEquals(result(0, false, "repeat_all_cycle"), actual)
        assertFalse("wrap-around must never stop playback", actual.shouldStop)
    }

    @Test
    fun `ALL completion of a single-track playlist replays that track`() {
        assertEquals(result(0, false, "repeat_all_cycle"), TvPlaybackModePolicy.advanceOnCompletion(1, 0, TvRepeatMode.ALL))
    }

    // --- advanceOnCompletion: ONE -------------------------------------------------------------------

    @Test
    fun `ONE completion keeps the current index wherever it is`() {
        assertEquals(result(0, false, "repeat_one"), TvPlaybackModePolicy.advanceOnCompletion(3, 0, TvRepeatMode.ONE))
        assertEquals(result(1, false, "repeat_one"), TvPlaybackModePolicy.advanceOnCompletion(3, 1, TvRepeatMode.ONE))
        assertEquals(result(2, false, "repeat_one"), TvPlaybackModePolicy.advanceOnCompletion(3, 2, TvRepeatMode.ONE))
    }

    @Test
    fun `ONE completion never stops and never moves`() {
        val actual = TvPlaybackModePolicy.advanceOnCompletion(5, 4, TvRepeatMode.ONE)
        assertFalse(actual.shouldStop)
        assertEquals(4, actual.targetIndex)
    }

    // --- advanceOnUserNext ----------------------------------------------------------------------------

    @Test
    fun `user next on an empty playlist has no target`() {
        for (mode in TvRepeatMode.values()) {
            assertNull("mode=$mode", TvPlaybackModePolicy.advanceOnUserNext(0, 0, mode))
            assertNull("mode=$mode negative size", TvPlaybackModePolicy.advanceOnUserNext(-2, 0, mode))
        }
    }

    @Test
    fun `user next with an invalid index has no target`() {
        for (mode in TvRepeatMode.values()) {
            assertNull("mode=$mode index=size", TvPlaybackModePolicy.advanceOnUserNext(3, 3, mode))
            assertNull("mode=$mode index=-1", TvPlaybackModePolicy.advanceOnUserNext(3, -1, mode))
        }
    }

    @Test
    fun `user next in OFF steps forward and dies at the end`() {
        assertEquals(1, TvPlaybackModePolicy.advanceOnUserNext(3, 0, TvRepeatMode.OFF))
        assertEquals(2, TvPlaybackModePolicy.advanceOnUserNext(3, 1, TvRepeatMode.OFF))
        assertNull("OFF must never wrap on an explicit skip", TvPlaybackModePolicy.advanceOnUserNext(3, 2, TvRepeatMode.OFF))
    }

    @Test
    fun `user next in ALL and ONE wraps around at the end`() {
        assertEquals(1, TvPlaybackModePolicy.advanceOnUserNext(3, 0, TvRepeatMode.ALL))
        assertEquals(0, TvPlaybackModePolicy.advanceOnUserNext(3, 2, TvRepeatMode.ALL))
        assertEquals(1, TvPlaybackModePolicy.advanceOnUserNext(3, 0, TvRepeatMode.ONE))
        assertEquals(0, TvPlaybackModePolicy.advanceOnUserNext(3, 2, TvRepeatMode.ONE))
    }

    @Test
    fun `user next on a single-track playlist wraps in repeating modes and dies in OFF`() {
        assertEquals(0, TvPlaybackModePolicy.advanceOnUserNext(1, 0, TvRepeatMode.ALL))
        assertEquals(0, TvPlaybackModePolicy.advanceOnUserNext(1, 0, TvRepeatMode.ONE))
        assertNull(TvPlaybackModePolicy.advanceOnUserNext(1, 0, TvRepeatMode.OFF))
    }

    // --- advanceOnUserPrevious ------------------------------------------------------------------------

    @Test
    fun `user previous on an empty playlist has no target`() {
        for (mode in TvRepeatMode.values()) {
            assertNull("mode=$mode", TvPlaybackModePolicy.advanceOnUserPrevious(0, 0, mode))
            assertNull("mode=$mode negative size", TvPlaybackModePolicy.advanceOnUserPrevious(-2, 0, mode))
        }
    }

    @Test
    fun `user previous with an invalid index has no target`() {
        for (mode in TvRepeatMode.values()) {
            assertNull("mode=$mode index=size", TvPlaybackModePolicy.advanceOnUserPrevious(3, 3, mode))
            assertNull("mode=$mode index=-1", TvPlaybackModePolicy.advanceOnUserPrevious(3, -1, mode))
        }
    }

    @Test
    fun `user previous inside the list steps back in every mode`() {
        for (mode in TvRepeatMode.values()) {
            assertEquals("mode=$mode", 1, TvPlaybackModePolicy.advanceOnUserPrevious(3, 2, mode))
            assertEquals("mode=$mode", 0, TvPlaybackModePolicy.advanceOnUserPrevious(3, 1, mode))
        }
    }

    @Test
    fun `user previous at index zero wraps to the tail only in ALL`() {
        assertEquals(2, TvPlaybackModePolicy.advanceOnUserPrevious(3, 0, TvRepeatMode.ALL))
        assertEquals("ONE must not loop backwards", 0, TvPlaybackModePolicy.advanceOnUserPrevious(3, 0, TvRepeatMode.ONE))
        assertEquals("OFF must not loop backwards", 0, TvPlaybackModePolicy.advanceOnUserPrevious(3, 0, TvRepeatMode.OFF))
    }

    @Test
    fun `user previous on a single-track playlist always holds at zero`() {
        for (mode in TvRepeatMode.values()) {
            assertEquals("mode=$mode", 0, TvPlaybackModePolicy.advanceOnUserPrevious(1, 0, mode))
        }
    }

    // --- result data class ---------------------------------------------------------------------------

    @Test
    fun `advance result exposes its fields and equality`() {
        val r = TvAdvanceResult(targetIndex = 2, shouldStop = false, reason = "next_track")
        assertEquals(2, r.targetIndex)
        assertFalse(r.shouldStop)
        assertEquals("next_track", r.reason)
        assertEquals(r, TvAdvanceResult(2, false, "next_track"))
        assertEquals(r, r.copy())
    }
}
