package ru.mars.mobile

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicTrack
import ru.mars.network.model.Playlist

/**
 * Тесты чистой политики офлайн-фолбэка музыки ([MusicOfflineFallbackPolicy]) — той самой
 * ветки «сеть упала → снимок есть / снимка нет», которая в Activity выглядит как
 * `if (decision.items == null) showHomeError() else showTracks(...)`.
 *
 * Зона зафиксирована (Europe/Moscow): подпись «снимок на 18.10 23:41» не должна зависеть
 * от часового пояса машины, где гоняются тесты.
 */
class MusicOfflineFallbackPolicyTest {

    private val moscow: ZoneId = ZoneId.of("Europe/Moscow")

    /** 18.10.2026 23:41 MSK — момент снимка из примера в задаче. */
    private val snapshotAt: Long =
        ZonedDateTime.of(2026, 10, 18, 23, 41, 0, 0, moscow).toInstant().toEpochMilli()

    private val charts = listOf(
        MusicTrack(id = "F9P_fIBfenk", title = "станция конечная", artist = "не меняет", duration = "1:31"),
    )

    private val playlists = listOf(
        Playlist(id = "2e0d0d47", title = "Дорога", createdAt = "2026-09-30 14:25:34", trackCount = 12),
    )

    @Test
    fun `fresh snapshot is shown with an explicit age label`() {
        val decision = MusicOfflineFallbackPolicy.decide(
            items = charts,
            snapshotTakenAtMs = snapshotAt,
            nowMs = snapshotAt + 15 * 60_000L,
            zone = moscow,
        )

        assertTrue(decision.hasSnapshot)
        assertEquals(charts, decision.items)
        assertEquals("из кэша, снимок на 18.10 23:41", decision.label)
        assertEquals(MusicOfflineFallbackPolicy.REASON_SNAPSHOT, decision.reason)
    }

    @Test
    fun `yesterday chart is still good - the reason the cache exists`() {
        // Ровно сценарий из задачи: сети нет, вчерашний чарт был загружен.
        val decision = MusicOfflineFallbackPolicy.decide(
            items = charts,
            snapshotTakenAtMs = snapshotAt,
            nowMs = snapshotAt + 23 * 60 * 60 * 1000L,
            zone = moscow,
        )

        assertTrue(decision.hasSnapshot)
        assertEquals("из кэша, снимок на 18.10 23:41", decision.label)
    }

    @Test
    fun `no snapshot means the usual error screen`() {
        val decision = MusicOfflineFallbackPolicy.decide<MusicTrack>(
            items = null,
            snapshotTakenAtMs = null,
            nowMs = snapshotAt,
            zone = moscow,
        )

        assertFalse(decision.hasSnapshot)
        assertNull(decision.items)
        assertNull(decision.label)
        assertEquals(MusicOfflineFallbackPolicy.REASON_NO_SNAPSHOT, decision.reason)
    }

    @Test
    fun `snapshot without a timestamp is treated as broken`() {
        val decision = MusicOfflineFallbackPolicy.decide(
            items = charts,
            snapshotTakenAtMs = null,
            nowMs = snapshotAt,
            zone = moscow,
        )

        assertFalse(decision.hasSnapshot)
        assertEquals(MusicOfflineFallbackPolicy.REASON_NO_TIMESTAMP, decision.reason)
    }

    @Test
    fun `empty snapshot is not shown as if it were data`() {
        val decision = MusicOfflineFallbackPolicy.decide(
            items = emptyList<MusicTrack>(),
            snapshotTakenAtMs = snapshotAt,
            nowMs = snapshotAt,
            zone = moscow,
        )

        assertFalse(decision.hasSnapshot)
        assertEquals(MusicOfflineFallbackPolicy.REASON_EMPTY_SNAPSHOT, decision.reason)
    }

    @Test
    fun `snapshot at the age limit is still shown and one millisecond later is not`() {
        val boundary = MusicOfflineFallbackPolicy.decide(
            items = charts,
            snapshotTakenAtMs = snapshotAt,
            nowMs = snapshotAt + MusicOfflineFallbackPolicy.MAX_AGE_MS,
            zone = moscow,
        )
        assertTrue(boundary.hasSnapshot)

        val stale = MusicOfflineFallbackPolicy.decide(
            items = charts,
            snapshotTakenAtMs = snapshotAt,
            nowMs = snapshotAt + MusicOfflineFallbackPolicy.MAX_AGE_MS + 1,
            zone = moscow,
        )
        assertFalse(stale.hasSnapshot)
        assertEquals(MusicOfflineFallbackPolicy.REASON_EXPIRED, stale.reason)
    }

    @Test
    fun `clock that jumped backwards does not discard a valid snapshot`() {
        val decision = MusicOfflineFallbackPolicy.decide(
            items = charts,
            snapshotTakenAtMs = snapshotAt,
            nowMs = snapshotAt - 5 * 60_000L,
            zone = moscow,
        )

        assertTrue(decision.hasSnapshot)
        assertEquals("из кэша, снимок на 18.10 23:41", decision.label)
    }

    @Test
    fun `playlists use the same decision and label`() {
        val decision = MusicOfflineFallbackPolicy.decide(
            items = playlists,
            snapshotTakenAtMs = snapshotAt,
            nowMs = snapshotAt + 60_000L,
            zone = moscow,
        )

        assertTrue(decision.hasSnapshot)
        assertEquals(playlists, decision.items)
        assertEquals(MusicOfflineFallbackPolicy.LABEL_PREFIX + "18.10 23:41", decision.label)
    }

    @Test
    fun `stamp has day month and time without a year`() {
        assertEquals("18.10 23:41", MusicOfflineFallbackPolicy.stamp(snapshotAt, moscow))
        assertEquals("из кэша, снимок на 18.10 23:41", MusicOfflineFallbackPolicy.label(snapshotAt, moscow))
    }
}
