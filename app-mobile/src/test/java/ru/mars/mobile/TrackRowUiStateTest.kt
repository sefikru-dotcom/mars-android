package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackRowUiStateTest {
    @Test fun otherTrackRowStaysInactiveWithPlayIcon() {
        val s = TrackRowUiState.resolve(rowTrackId = "a", currentTrackId = "b", isPlaying = true)
        assertFalse(s.active)
        assertFalse(s.showPauseIcon)
        assertTrue(s.tapStartsPlayback)
    }

    @Test fun activePlayingRowShowsPauseAndTogglesOnTap() {
        val s = TrackRowUiState.resolve(rowTrackId = "a", currentTrackId = "a", isPlaying = true)
        assertTrue(s.active)
        assertTrue(s.showPauseIcon)
        assertFalse(s.tapStartsPlayback)
    }

    @Test fun activePausedRowStaysHighlightedWithPlayIcon() {
        val s = TrackRowUiState.resolve(rowTrackId = "a", currentTrackId = "a", isPlaying = false)
        assertTrue(s.active)
        assertFalse(s.showPauseIcon)
        assertFalse(s.tapStartsPlayback)
    }

    @Test fun noCurrentTrackNeverHighlights() {
        assertFalse(TrackRowUiState.resolve("a", null, isPlaying = false).active)
        assertFalse(TrackRowUiState.resolve("a", "", isPlaying = true).active)
    }

    @Test fun blankRowIdNeverMatchesCurrentTrack() {
        assertFalse(TrackRowUiState.resolve("", "", isPlaying = true).active)
        assertFalse(TrackRowUiState.resolve(null, null, isPlaying = true).active)
    }
}
