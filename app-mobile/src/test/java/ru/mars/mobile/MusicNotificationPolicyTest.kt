package ru.mars.mobile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.mobile.service.MusicNotificationPolicy
import ru.mars.mobile.service.MusicNotificationPolicy.ActionType

/**
 * JVM-тесты чистой политики медиа-нотификации [MusicNotificationPolicy]:
 * видимость и порядок транспортных кнопок, intent-action строки, compact-индексы,
 * правило «живого» воспроизведения и роутинг action → type.
 */
class MusicNotificationPolicyTest {

    @Test
    fun `channel id is mars_music_playback`() {
        assertEquals("mars_music_playback", MusicNotificationPolicy.CHANNEL_ID)
    }

    @Test
    fun `playing state shows prev pause next stop in shade order`() {
        val actions = MusicNotificationPolicy.actions(isPlaying = true, hasTrack = true)
        assertEquals(
            listOf(ActionType.PREV, ActionType.PAUSE, ActionType.NEXT, ActionType.STOP),
            actions.map { it.type }
        )
    }

    @Test
    fun `paused state shows play instead of pause, same positions`() {
        val actions = MusicNotificationPolicy.actions(isPlaying = false, hasTrack = true)
        assertEquals(
            listOf(ActionType.PREV, ActionType.PLAY, ActionType.NEXT, ActionType.STOP),
            actions.map { it.type }
        )
    }

    @Test
    fun `play and pause are mutually exclusive`() {
        for (playing in listOf(true, false)) {
            val types = MusicNotificationPolicy.actions(playing, true).map { it.type }
            assertTrue(types.contains(ActionType.PAUSE) != types.contains(ActionType.PLAY))
            assertEquals(if (playing) ActionType.PAUSE else ActionType.PLAY, types[1])
        }
    }

    @Test
    fun `no track hides all actions`() {
        assertTrue(MusicNotificationPolicy.actions(isPlaying = true, hasTrack = false).isEmpty())
        assertTrue(MusicNotificationPolicy.actions(isPlaying = false, hasTrack = false).isEmpty())
    }

    @Test
    fun `every action carries its stable intent string and a content description`() {
        val expected = mapOf(
            ActionType.PLAY to MusicNotificationPolicy.ACTION_PLAY,
            ActionType.PAUSE to MusicNotificationPolicy.ACTION_PAUSE,
            ActionType.NEXT to MusicNotificationPolicy.ACTION_NEXT,
            ActionType.PREV to MusicNotificationPolicy.ACTION_PREV,
            ActionType.STOP to MusicNotificationPolicy.ACTION_STOP,
        )
        val actions = MusicNotificationPolicy.actions(isPlaying = true, hasTrack = true) +
            MusicNotificationPolicy.actions(isPlaying = false, hasTrack = true)
        actions.forEach { action ->
            assertEquals(expected[action.type], action.action)
            assertTrue(action.contentDescription.isNotBlank())
        }
        // Все пять action-строк различны — иначе PendingIntent'ы схлопнутся в один.
        assertEquals(5, expected.values.toSet().size)
    }

    @Test
    fun `typeOf routes every action string back to its type`() {
        assertEquals(ActionType.PLAY, MusicNotificationPolicy.typeOf(MusicNotificationPolicy.ACTION_PLAY))
        assertEquals(ActionType.PAUSE, MusicNotificationPolicy.typeOf(MusicNotificationPolicy.ACTION_PAUSE))
        assertEquals(ActionType.NEXT, MusicNotificationPolicy.typeOf(MusicNotificationPolicy.ACTION_NEXT))
        assertEquals(ActionType.PREV, MusicNotificationPolicy.typeOf(MusicNotificationPolicy.ACTION_PREV))
        assertEquals(ActionType.STOP, MusicNotificationPolicy.typeOf(MusicNotificationPolicy.ACTION_STOP))
    }

    @Test
    fun `typeOf returns null for unknown or absent actions`() {
        assertNull(MusicNotificationPolicy.typeOf(null))
        assertNull(MusicNotificationPolicy.typeOf(""))
        assertNull(MusicNotificationPolicy.typeOf("ru.mars.mobile.service.action.NUKE"))
    }

    @Test
    fun `compact view shows prev toggle next but never stop`() {
        for (playing in listOf(true, false)) {
            val actions = MusicNotificationPolicy.actions(playing, true)
            val compact = MusicNotificationPolicy.compactActionIndices(playing, true)
            assertArrayEquals(intArrayOf(0, 1, 2), compact)
            compact.forEach { index -> assertNotEquals(ActionType.STOP, actions[index].type) }
        }
        // Без трека кнопок нет — compact пустой, MediaStyle не должен падать.
        assertEquals(0, MusicNotificationPolicy.compactActionIndices(false, false).size)
        assertEquals(0, MusicNotificationPolicy.compactActionIndices(true, false).size)
    }

    @Test
    fun `playback is alive while a track is loaded or audio plays`() {
        assertTrue(MusicNotificationPolicy.isPlaybackAlive(hasTrack = true, isPlaying = true))
        assertTrue(MusicNotificationPolicy.isPlaybackAlive(hasTrack = true, isPlaying = false))
        assertTrue(MusicNotificationPolicy.isPlaybackAlive(hasTrack = false, isPlaying = true))
        assertFalse(MusicNotificationPolicy.isPlaybackAlive(hasTrack = false, isPlaying = false))
    }

    @Test
    fun `notification id is stable and non-zero`() {
        assertTrue(MusicNotificationPolicy.NOTIFICATION_ID != 0)
        assertEquals(
            MusicNotificationPolicy.NOTIFICATION_ID,
            MusicNotificationPolicy.NOTIFICATION_ID
        )
    }
}
