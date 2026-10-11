package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicTrack

/**
 * Unit tests for [TvMusicNowPlayingSpec] — pure JVM, no Android framework.
 * Covers the header line of every transport state (idle / loading / playing /
 * error, including blank-error honesty) and the row-highlight rule.
 */
class TvMusicNowPlayingSpecTest {

    private val track = MusicTrack(id = "t1", title = "Закат", artist = "Марс", duration = "3:20")

    // --- header line -----------------------------------------------------------------------------

    @Test
    fun `idle transport renders empty line`() {
        assertEquals("", TvMusicNowPlayingSpec.nowPlayingLine(null, isPlaying = false, error = null))
    }

    @Test
    fun `idle transport with stale flags still renders empty line`() {
        assertEquals("", TvMusicNowPlayingSpec.nowPlayingLine(null, isPlaying = true, error = "oops"))
    }

    @Test
    fun `loading transport renders loading line`() {
        assertEquals(
            "Загружаю: Закат…",
            TvMusicNowPlayingSpec.nowPlayingLine(track, isPlaying = false, error = null),
        )
    }

    @Test
    fun `playing transport renders now-playing line with title and artist`() {
        assertEquals(
            "Сейчас играет: Закат — Марс",
            TvMusicNowPlayingSpec.nowPlayingLine(track, isPlaying = true, error = null),
        )
    }

    @Test
    fun `playing state wins over stale error`() {
        assertEquals(
            "Сейчас играет: Закат — Марс",
            TvMusicNowPlayingSpec.nowPlayingLine(track, isPlaying = true, error = "старая ошибка"),
        )
    }

    @Test
    fun `error transport renders explicit not-playing line with reason`() {
        assertEquals(
            "Не играет: Закат. Ошибка воспроизведения (1/-1004)",
            TvMusicNowPlayingSpec.nowPlayingLine(track, isPlaying = false, error = "Ошибка воспроизведения (1/-1004)"),
        )
    }

    @Test
    fun `blank error falls back to loading line instead of empty reason`() {
        assertEquals(
            "Загружаю: Закат…",
            TvMusicNowPlayingSpec.nowPlayingLine(track, isPlaying = false, error = "  "),
        )
    }

    // --- row highlight rule ----------------------------------------------------------------------

    @Test
    fun `row is current only for the same track id`() {
        assertTrue(TvMusicNowPlayingSpec.isCurrent("t1", "t1"))
        assertFalse(TvMusicNowPlayingSpec.isCurrent("t1", "t2"))
    }

    @Test
    fun `no row is current while transport is idle`() {
        assertFalse(TvMusicNowPlayingSpec.isCurrent("t1", null))
    }

    // --- repeat toggle label ------------------------------------------------------------------

    @Test
    fun `repeat button mirrors the transport preference`() {
        assertEquals("Повтор: выкл", TvMusicNowPlayingSpec.repeatButtonLabel(TvRepeatMode.OFF))
        assertEquals("Повтор: список", TvMusicNowPlayingSpec.repeatButtonLabel(TvRepeatMode.ALL))
        assertEquals("Повтор: трек", TvMusicNowPlayingSpec.repeatButtonLabel(TvRepeatMode.ONE))
    }

    @Test
    fun `label follows the cycle ring OFF - ALL - ONE - OFF`() {
        var mode = TvRepeatMode.OFF
        val labels = buildList {
            repeat(4) {
                add(TvMusicNowPlayingSpec.repeatButtonLabel(mode))
                mode = TvPlaybackModePolicy.nextRepeatMode(mode)
            }
        }
        assertEquals(listOf("Повтор: выкл", "Повтор: список", "Повтор: трек", "Повтор: выкл"), labels)
    }

    // --- header transport pair (play/pause + stop) -----------------------------------------------

    @Test
    fun `transport pair is hidden while the transport is idle`() {
        // No track bound — nothing to pause and nothing to stop: both buttons stay GONE
        // and stale playing/error flags cannot summon them.
        val controls = TvMusicNowPlayingSpec.transportControls(track = null, isPlaying = true)
        assertFalse(controls.visible)
    }

    @Test
    fun `bound track shows the transport pair`() {
        assertTrue(TvMusicNowPlayingSpec.transportControls(track, isPlaying = false).visible)
        assertTrue(TvMusicNowPlayingSpec.transportControls(track, isPlaying = true).visible)
    }

    @Test
    fun `playing transport offers pause with the pause icon`() {
        val controls = TvMusicNowPlayingSpec.transportControls(track, isPlaying = true)
        assertEquals("Пауза", controls.playPauseLabel)
        assertTrue(controls.showPauseIcon)
    }

    @Test
    fun `paused or failed transport offers play with the play icon`() {
        // Paused, preparing and error states alike: the toggle resumes via the same
        // transport action and must never claim «Пауза» while nothing renders.
        val controls = TvMusicNowPlayingSpec.transportControls(track, isPlaying = false)
        assertEquals("Играть", controls.playPauseLabel)
        assertFalse(controls.showPauseIcon)
    }

    @Test
    fun `music screen toggle speaks with one voice as the chat mini player`() {
        // Single source of truth: the captions are delegated to TvMiniPlayerSpec, so the
        // two surfaces can never drift apart state by state.
        for (playing in listOf(true, false)) {
            assertEquals(
                TvMiniPlayerSpec.toggleLabel(playing),
                TvMusicNowPlayingSpec.transportControls(track, isPlaying = playing).playPauseLabel,
            )
        }
        for (bound in listOf(track, null)) {
            assertEquals(
                TvMiniPlayerSpec.visible(bound),
                TvMusicNowPlayingSpec.transportControls(bound, isPlaying = false).visible,
            )
        }
    }
}
