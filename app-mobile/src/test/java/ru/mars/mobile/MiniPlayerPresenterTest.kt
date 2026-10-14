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
        // Hidden strip keeps the idle action label («Воспроизвести») — the same voice
        // the music screen uses, straight from the shared MusicA11yPolicy.
        assertEquals(MusicA11yPolicy.ACTION_PLAY, s.statusLabel)
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

    // MUS-M1 parity: the play button's description is the ACTION the tap performs —
    // while playing it offers «Пауза», while paused «Воспроизвести» — resolved by the
    // shared MusicA11yPolicy, so the strip and the music screen never diverge.

    @Test fun playingTrackShowsPauseIconAndPauseAction() {
        val s = MiniPlayerPresenter.resolve(track("Ночь", "Кино"), isPlaying = true)
        assertTrue(s.showPauseIcon)
        assertEquals("Пауза", s.statusLabel)
        assertEquals(MusicA11yPolicy.playPauseAction(isPlaying = true), s.statusLabel)
    }

    @Test fun pausedTrackShowsPlayIconAndPlayAction() {
        val s = MiniPlayerPresenter.resolve(track("Ночь", "Кино"), isPlaying = false)
        assertFalse(s.showPauseIcon)
        assertEquals("Воспроизвести", s.statusLabel)
        assertEquals(MusicA11yPolicy.playPauseAction(isPlaying = false), s.statusLabel)
    }

    @Test fun formatTitleIsPureForRawInputs() {
        assertEquals("Неизвестный трек", MiniPlayerPresenter.formatTitle(null, null))
        assertEquals("a · b", MiniPlayerPresenter.formatTitle("a", "b"))
        assertEquals("b", MiniPlayerPresenter.formatTitle(null, "b"))
    }

    // --- reveal/hide motion (fade+slide, pure JVM) ---

    @Test fun showMotionIsFadeSlideWithinCardExpandBudget() {
        val m = MiniPlayerPresenter.showMotion(reducedMotion = false)
        assertFalse(m.instant)
        assertEquals(MotionPolicy.durationFor(MotionCue.CARD_EXPAND, false), m.durationMs)
        assertTrue(m.durationMs in 1..200) // never heavier than base motion
        assertEquals(0f, m.alphaFrom, 0f)
        assertEquals(1f, m.alphaTo, 0f)
        assertEquals(6f, m.translationFromDp, 0f)
        assertEquals(0f, m.translationToDp, 0f)
    }

    @Test fun showMotionUnderReducedMotionIsInstantFinalState() {
        val m = MiniPlayerPresenter.showMotion(reducedMotion = true)
        assertEquals(0, m.durationMs)
        assertTrue(m.instant)
        // from == to == the final (fully visible, unshifted) state — no animator at all
        assertEquals(1f, m.alphaFrom, 0f)
        assertEquals(1f, m.alphaTo, 0f)
        assertEquals(0f, m.translationFromDp, 0f)
        assertEquals(0f, m.translationToDp, 0f)
    }

    @Test fun hideMotionMirrorsShowMotion() {
        val m = MiniPlayerPresenter.hideMotion(reducedMotion = false)
        assertFalse(m.instant)
        assertEquals(MiniPlayerPresenter.showMotion(reducedMotion = false).durationMs, m.durationMs)
        assertEquals(1f, m.alphaFrom, 0f)
        assertEquals(0f, m.alphaTo, 0f)
        assertEquals(0f, m.translationFromDp, 0f)
        assertEquals(6f, m.translationToDp, 0f)
    }

    @Test fun hideMotionUnderReducedMotionIsInstantHiddenState() {
        val m = MiniPlayerPresenter.hideMotion(reducedMotion = true)
        assertEquals(0, m.durationMs)
        assertTrue(m.instant)
        assertEquals(0f, m.alphaFrom, 0f)
        assertEquals(0f, m.alphaTo, 0f)
        assertEquals(6f, m.translationFromDp, 0f)
        assertEquals(6f, m.translationToDp, 0f)
    }

    @Test fun revealMotionTokensMatchTheCalmStance() {
        assertEquals(6f, MiniPlayerPresenter.REVEAL_SLIDE_DP, 0f)
        assertEquals(MarsMotion.baseMs, MiniPlayerPresenter.MAX_REVEAL_MS)
    }

    // --- intended-visibility action (mid-hide race) ---

    @Test fun trackArrivingMidHideRequestsRevealNotNone() {
        // The bug: while hiding, View.visibility is still VISIBLE, so a track arriving in
        // that window used to be dropped and the hide landed GONE on a live track.
        // Intent-based resolution must ask for a reveal (which cancels the in-flight hide).
        assertEquals(
            MiniPlayerAction.REVEAL,
            MiniPlayerPresenter.motionAction(intendedVisible = false, nextVisible = true),
        )
    }

    @Test fun trackGoingAwayRequestsHide() {
        assertEquals(
            MiniPlayerAction.HIDE,
            MiniPlayerPresenter.motionAction(intendedVisible = true, nextVisible = false),
        )
    }

    @Test fun unchangedVisibilityRequestsNoMotion() {
        // Status-only updates (play/pause) must never restart the reveal fade.
        assertEquals(MiniPlayerAction.NONE, MiniPlayerPresenter.motionAction(true, true))
        assertEquals(MiniPlayerAction.NONE, MiniPlayerPresenter.motionAction(false, false))
    }

    @Test fun repeatedTrackEventsAfterRevealStayMotionless() {
        // Two consecutive states with a live track: only the first animates.
        var intended = false
        val first = MiniPlayerPresenter.motionAction(intended, nextVisible = true)
        assertEquals(MiniPlayerAction.REVEAL, first)
        intended = true
        assertEquals(MiniPlayerAction.NONE, MiniPlayerPresenter.motionAction(intended, nextVisible = true))
    }

    @Test fun hideRevealHideSequenceAlternatesWithoutLosingTheTrack() {
        var intended = false
        // reveal
        assertEquals(MiniPlayerAction.REVEAL, MiniPlayerPresenter.motionAction(intended, true))
        intended = true
        // hide starts; view still VISIBLE but intent already false
        assertEquals(MiniPlayerAction.HIDE, MiniPlayerPresenter.motionAction(intended, false))
        intended = false
        // track returns INSIDE the hide window → must reveal again, not stall
        assertEquals(MiniPlayerAction.REVEAL, MiniPlayerPresenter.motionAction(intended, true))
    }
}
