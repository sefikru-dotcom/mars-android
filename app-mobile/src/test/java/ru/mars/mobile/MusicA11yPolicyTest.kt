package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MUS-M1/M3: the music a11y labels are ACTION-based — TalkBack reads what the tap
 * will do, not the state the control happens to be in (parity with the TV transport
 * captions, TvMiniPlayerSpec.toggleLabel).
 */
class MusicA11yPolicyTest {

    // --- play/pause (MUS-M1) ---

    @Test fun playingTransportOffersPauseAction() {
        assertEquals("Пауза", MusicA11yPolicy.playPauseAction(isPlaying = true))
        assertEquals(MusicA11yPolicy.ACTION_PAUSE, MusicA11yPolicy.playPauseAction(true))
    }

    @Test fun pausedTransportOffersPlayAction() {
        assertEquals("Воспроизвести", MusicA11yPolicy.playPauseAction(isPlaying = false))
        assertEquals(MusicA11yPolicy.ACTION_PLAY, MusicA11yPolicy.playPauseAction(false))
    }

    // --- shuffle / repeat (MUS-M3) ---

    @Test fun shuffleLabelReadsTheToggleAction() {
        assertEquals("Включить перемешивание", MusicA11yPolicy.shuffleAction(shuffleOn = false))
        assertEquals("Выключить перемешивание", MusicA11yPolicy.shuffleAction(shuffleOn = true))
    }

    @Test fun repeatLabelReadsTheNextModeOfTheCycle() {
        // The tap cycles OFF→ALL→ONE→OFF, so each label names the mode it switches to.
        assertEquals("Включить повтор списка", MusicA11yPolicy.repeatAction(MusicQueueManager.RepeatMode.OFF))
        assertEquals(
            "Включить повтор одного трека",
            MusicA11yPolicy.repeatAction(MusicQueueManager.RepeatMode.ALL),
        )
        assertEquals("Выключить повтор", MusicA11yPolicy.repeatAction(MusicQueueManager.RepeatMode.ONE))
    }

    @Test fun everyModeHasItsOwnRepeatLabel() {
        val labels = MusicQueueManager.RepeatMode.entries.map { MusicA11yPolicy.repeatAction(it) }
        assertEquals(3, labels.size)
        assertEquals(3, labels.distinct().size)
        assertTrue(labels.all { it.isNotBlank() })
    }

    // --- heart (MUS-M3) ---

    @Test fun heartLabelReadsTheToggleAction() {
        assertEquals("Добавить в избранное", MusicA11yPolicy.heartAction(isFavorite = false))
        assertEquals("Убрать из избранного", MusicA11yPolicy.heartAction(isFavorite = true))
    }

    // --- tabs (MUS-M3) ---

    @Test fun activeTabAnnouncesItsStateInactiveStaysPlain() {
        assertEquals("Чарт — активная вкладка", MusicA11yPolicy.tabDescription("Чарт", active = true))
        assertEquals("Очередь", MusicA11yPolicy.tabDescription("Очередь", active = false))
        assertNotEquals(
            MusicA11yPolicy.tabDescription("Чарт", active = true),
            MusicA11yPolicy.tabDescription("Чарт", active = false),
        )
    }
}
