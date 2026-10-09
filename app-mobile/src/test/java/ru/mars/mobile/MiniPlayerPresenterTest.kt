package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicTrack

class MiniPlayerPresenterTest {
    private fun track(title: String, artist: String) =
        MusicTrack(id = "1", title = title, artist = artist)

    @Test fun noTrackHidesStrip() {
        val s = MiniPlayerPresenter.resolve(track = null, isPlaying = false)
        assertFalse(s.visible)
        assertEquals("", s.titleText)
        assertFalse(s.showPauseIcon)
        assertEquals(MiniPlayerPresenter.STATUS_PAUSED, s.statusLabel)
    }

    @Test fun hiddenStateIsSharedConstant() {
        assertEquals(MiniPlayerUiState.HIDDEN, MiniPlayerPresenter.resolve(null, true))
        assertFalse(MiniPlayerUiState.HIDDEN.visible)
    }

    @Test fun fullTrackFormatsTitleWithSeparator() {
        val s = MiniPlayerPresenter.resolve(track("Ночь", "Кино"), isPlaying = false)
        assertTrue(s.visible)
        assertEquals("Ночь · Кино", s.titleText)
    }

    @Test fun blankArtistFallsBackToTitleOnly() {
        assertEquals("Ночь", MiniPlayerPresenter.resolve(track("Ночь", "  "), false).titleText)
    }

    @Test fun blankTitleFallsBackToArtistOnly() {
        assertEquals("Кино", MiniPlayerPresenter.resolve(track("", "Кино"), false).titleText)
    }

    @Test fun bothBlankFallBackToUnknownTitle() {
        val s = MiniPlayerPresenter.resolve(track("   ", ""), false)
        assertTrue(s.visible)
        assertEquals(MiniPlayerPresenter.UNKNOWN_TITLE, s.titleText)
    }

    @Test fun partsAreTrimmedBeforeJoining() {
        assertEquals("Ночь · Кино", MiniPlayerPresenter.resolve(track(" Ночь ", "\tКино "), false).titleText)
    }

    @Test fun playingTrackShowsPauseIconAndPlayingStatus() {
        val s = MiniPlayerPresenter.resolve(track("Ночь", "Кино"), isPlaying = true)
        assertTrue(s.showPauseIcon)
        assertEquals(MiniPlayerPresenter.STATUS_PLAYING, s.statusLabel)
    }

    @Test fun pausedTrackShowsPlayIconAndPausedStatus() {
        val s = MiniPlayerPresenter.resolve(track("Ночь", "Кино"), isPlaying = false)
        assertFalse(s.showPauseIcon)
        assertEquals(MiniPlayerPresenter.STATUS_PAUSED, s.statusLabel)
    }

    @Test fun formatTitleIsPureForRawInputs() {
        assertEquals("Неизвестный трек", MiniPlayerPresenter.formatTitle(null, null))
        assertEquals("a · b", MiniPlayerPresenter.formatTitle("a", "b"))
        assertEquals("b", MiniPlayerPresenter.formatTitle(null, "b"))
    }
}
