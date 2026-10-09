package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicTrack

/**
 * Unit tests for [TvPlaylistPolicy] — pure JVM, no Android framework, no MediaPlayer.
 * Covers the continuous-playback decisions of the TV transport: start-index
 * sanitization, sequential auto-advance on completion (stop at the end, never loop),
 * track-level successor resolution and the dead-stream skip circuit breaker
 * (mobile MarsMusicPlayer parity: one full failed pass stops the retrying).
 */
class TvPlaylistPolicyTest {

    private fun track(id: String) = MusicTrack(id = id, title = "Track $id", artist = "Artist $id", duration = "3:20")

    private val playlist = listOf(track("a"), track("b"), track("c"))

    // --- start index ------------------------------------------------------------------------------

    @Test
    fun `valid start index is accepted as-is`() {
        assertEquals(0, TvPlaylistPolicy.startIndex(playlist.size, 0))
        assertEquals(2, TvPlaylistPolicy.startIndex(playlist.size, 2))
    }

    @Test
    fun `out-of-range start index is rejected`() {
        assertNull(TvPlaylistPolicy.startIndex(playlist.size, 3))
        assertNull(TvPlaylistPolicy.startIndex(playlist.size, -1))
    }

    @Test
    fun `empty playlist has no start index`() {
        assertNull(TvPlaylistPolicy.startIndex(0, 0))
    }

    // --- auto-advance on completion ----------------------------------------------------------------

    @Test
    fun `completion in the middle advances to the successor`() {
        assertEquals(1, TvPlaylistPolicy.nextIndex(playlist.size, 0))
        assertEquals(2, TvPlaylistPolicy.nextIndex(playlist.size, 1))
    }

    @Test
    fun `completion of the last track ends playback instead of looping`() {
        assertNull("exhausted playlist must go idle, never wrap", TvPlaylistPolicy.nextIndex(playlist.size, 2))
    }

    @Test
    fun `single-track playlist ends after its track`() {
        assertNull(TvPlaylistPolicy.nextIndex(1, 0))
    }

    @Test
    fun `invalid current index has no successor`() {
        assertNull(TvPlaylistPolicy.nextIndex(playlist.size, -1))
        assertNull(TvPlaylistPolicy.nextIndex(playlist.size, 3))
        assertNull(TvPlaylistPolicy.nextIndex(0, 0))
    }

    @Test
    fun `next track resolves the successor track`() {
        assertEquals(playlist[1], TvPlaylistPolicy.nextTrack(playlist, 0))
        assertEquals(playlist[2], TvPlaylistPolicy.nextTrack(playlist, 1))
    }

    @Test
    fun `next track is null at the end of the playlist`() {
        assertNull(TvPlaylistPolicy.nextTrack(playlist, 2))
        assertNull(TvPlaylistPolicy.nextTrack(emptyList(), 0))
    }

    @Test
    fun `walking the whole playlist visits every track exactly once then stops`() {
        val visited = mutableListOf(0)
        var index = 0
        while (true) {
            index = TvPlaylistPolicy.nextIndex(playlist.size, index) ?: break
            visited += index
        }
        assertEquals(listOf(0, 1, 2), visited)
    }

    // --- dead-stream skip circuit breaker -----------------------------------------------------------

    @Test
    fun `first failures skip to the successor`() {
        assertTrue(TvPlaylistPolicy.skipAfterFailure(1, playlist.size))
        assertTrue(TvPlaylistPolicy.skipAfterFailure(2, playlist.size))
    }

    @Test
    fun `a full failed pass trips the breaker and stops the retrying`() {
        assertFalse(TvPlaylistPolicy.skipAfterFailure(3, playlist.size))
        assertFalse(TvPlaylistPolicy.skipAfterFailure(4, playlist.size))
    }

    @Test
    fun `single-track playlist never skips - there is no successor`() {
        assertFalse(TvPlaylistPolicy.skipAfterFailure(1, 1))
    }

    @Test
    fun `empty playlist never skips`() {
        assertFalse(TvPlaylistPolicy.skipAfterFailure(1, 0))
    }

    @Test
    fun `zero or negative failure streak never skips`() {
        assertFalse(TvPlaylistPolicy.skipAfterFailure(0, playlist.size))
        assertFalse(TvPlaylistPolicy.skipAfterFailure(-1, playlist.size))
    }
}
