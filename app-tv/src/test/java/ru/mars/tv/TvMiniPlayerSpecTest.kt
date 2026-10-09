package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicTrack

/**
 * Unit tests for [TvMiniPlayerSpec] — pure JVM, no Android framework. Covers the
 * chat-header mini player rules: the plate is shown only while a track is bound to
 * the global transport, the caption is the compact "title — artist" line (artist
 * omitted when blank) and the toggle caption matches the playing state.
 */
class TvMiniPlayerSpecTest {

    private fun track(title: String = "Sunset", artist: String = "Mars Band") =
        MusicTrack(id = "t1", title = title, artist = artist, duration = "3:20")

    @Test
    fun `plate is visible only while a track is bound`() {
        assertTrue(TvMiniPlayerSpec.visible(track()))
        assertFalse("idle transport must hide the plate", TvMiniPlayerSpec.visible(null))
    }

    @Test
    fun `line shows title and artist`() {
        assertEquals("Sunset — Mars Band", TvMiniPlayerSpec.line(track()))
    }

    @Test
    fun `blank artist collapses the line to the title alone`() {
        assertEquals("Sunset", TvMiniPlayerSpec.line(track(artist = "")))
        assertEquals("Sunset", TvMiniPlayerSpec.line(track(artist = "   ")))
    }

    @Test
    fun `idle transport has an empty line`() {
        assertEquals("", TvMiniPlayerSpec.line(null))
    }

    @Test
    fun `toggle offers pause while playing and play otherwise`() {
        assertEquals("Пауза", TvMiniPlayerSpec.toggleLabel(isPlaying = true))
        assertEquals("Играть", TvMiniPlayerSpec.toggleLabel(isPlaying = false))
    }
}
