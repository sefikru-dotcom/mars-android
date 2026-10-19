package ru.mars.network.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicTrack
import ru.mars.network.model.Playlist

/**
 * JVM-тесты офлайн-снимка музыки ([MusicOfflineCache]): round-trip чарта и плейлистов,
 * независимость двух слотов, лимиты (число строк, длина полей), timestamp для подписи
 * «снимок на …», устойчивость к битым данным и падающему хранилищу, гигиена очистки.
 *
 * Данные в фикстурах — реальная форма ответа музыки: id видео, site-relative обложка
 * (`/music/api/thumb?v=…`), длительность строкой «1:31», плейлист с 8-hex id и
 * серверной меткой создания.
 */
class MusicOfflineCacheTest {

    /** In-memory [KeyValueStore]; [failing] имитирует падающее хранилище (диск/IO). */
    private class FakeStore(var failing: Boolean = false) : KeyValueStore {
        val map = HashMap<String, String>()
        override fun getString(key: String): String? {
            if (failing) throw IllegalStateException("store down")
            return map[key]
        }

        override fun putString(key: String, value: String) {
            if (failing) throw IllegalStateException("store down")
            map[key] = value
        }

        override fun remove(key: String) {
            if (failing) throw IllegalStateException("store down")
            map.remove(key)
        }

        override fun keys(): Set<String> {
            if (failing) throw IllegalStateException("store down")
            return map.keys.toSet()
        }

        override fun clearAll() {
            if (failing) throw IllegalStateException("store down")
            map.clear()
        }
    }

    private var now = 1_760_000_000_000L

    private fun newCache(store: KeyValueStore = FakeStore()) =
        MusicOfflineCache(store, clock = { now })

    private fun track(
        id: String = "F9P_fIBfenk",
        title: String = "станция конечная",
        artist: String = "не меняет",
        duration: String = "1:31",
        thumbnail: String? = "/music/api/thumb?v=F9P_fIBfenk",
    ) = MusicTrack(id = id, title = title, artist = artist, duration = duration, thumbnail = thumbnail)

    private fun playlist(
        id: String = "2e0d0d47",
        title: String = "Дорога",
        createdAt: String = "2026-09-30 14:25:34",
        trackCount: Int = 12,
    ) = Playlist(id = id, title = title, createdAt = createdAt, trackCount = trackCount)

    // --- round-trip ----------------------------------------------------------------------------

    @Test
    fun `charts snapshot survives save and load with all fields`() {
        val cache = newCache()
        val tracks = listOf(
            track(),
            track(id = "abc123", title = "второй", artist = "кто-то", duration = "3:05", thumbnail = null),
        )

        assertTrue(cache.saveChartsSnapshot(tracks))

        val loaded = cache.getChartsSnapshot()
        assertEquals(tracks, loaded)
        // Порядок чарта — часть данных: он не должен сортироваться/переставляться.
        assertEquals(listOf("F9P_fIBfenk", "abc123"), loaded?.map { it.id })
        assertNull("null-обложка остаётся null, а не пустой строкой", loaded?.get(1)?.thumbnail)
    }

    @Test
    fun `playlists snapshot survives save and load with all fields`() {
        val cache = newCache()
        val playlists = listOf(
            playlist(),
            playlist(id = "deadbeef", title = "Ночь", createdAt = "2026-10-01 09:00:00", trackCount = 0),
        )

        assertTrue(cache.savePlaylistsSnapshot(playlists))

        assertEquals(playlists, cache.getPlaylistsSnapshot())
    }

    @Test
    fun `empty snapshots round trip as empty lists not as absent data`() {
        val cache = newCache()

        assertTrue(cache.saveChartsSnapshot(emptyList()))
        assertTrue(cache.savePlaylistsSnapshot(emptyList()))

        // Пустой снимок — валидные данные («сервер сказал: пусто»); решение показать
        // его или нет принимает MusicOfflineFallbackPolicy, а не кэш.
        assertEquals(emptyList<MusicTrack>(), cache.getChartsSnapshot())
        assertEquals(emptyList<Playlist>(), cache.getPlaylistsSnapshot())
    }

    @Test
    fun `chart and playlist slots do not overwrite each other`() {
        val cache = newCache()

        cache.saveChartsSnapshot(listOf(track()))
        now += 60_000
        cache.savePlaylistsSnapshot(listOf(playlist()))

        assertEquals(1, cache.getChartsSnapshot()?.size)
        assertEquals(1, cache.getPlaylistsSnapshot()?.size)
        assertEquals("метки времени слотов независимы", 1_760_000_000_000L, cache.getTimestamp())
        assertEquals(1_760_000_060_000L, cache.getPlaylistsTimestamp())
    }

    // --- timestamp ------------------------------------------------------------------------------

    @Test
    fun `timestamp is the save moment of the snapshot`() {
        val cache = newCache()
        now = 1_760_123_456_789L

        cache.saveChartsSnapshot(listOf(track()))
        assertEquals(1_760_123_456_789L, cache.getTimestamp())

        now = 1_760_999_999_999L
        cache.savePlaylistsSnapshot(listOf(playlist()))
        assertEquals(1_760_999_999_999L, cache.getPlaylistsTimestamp())
    }

    @Test
    fun `timestamp is null when there is no snapshot`() {
        val cache = newCache()

        assertNull(cache.getTimestamp())
        assertNull(cache.getPlaylistsTimestamp())
    }

    // --- лимиты ---------------------------------------------------------------------------------

    @Test
    fun `charts snapshot keeps at most the configured number of tracks`() {
        val cache = newCache()
        val many = (1..150).map { track(id = "v$it", title = "трек $it") }

        assertTrue(cache.saveChartsSnapshot(many))

        val loaded = cache.getChartsSnapshot()
        assertEquals(100, loaded?.size)
        // Отбрасывается хвост: первые строки чарта — самые важные.
        assertEquals("v1", loaded?.first()?.id)
        assertEquals("v100", loaded?.last()?.id)
    }

    @Test
    fun `playlists snapshot keeps at most the configured number of playlists`() {
        val cache = newCache()
        val many = (1..120).map { playlist(id = "p$it", title = "плейлист $it") }

        assertTrue(cache.savePlaylistsSnapshot(many))

        val loaded = cache.getPlaylistsSnapshot()
        assertEquals(100, loaded?.size)
        assertEquals("p1", loaded?.first()?.id)
        assertEquals("p100", loaded?.last()?.id)
    }

    @Test
    fun `long strings are truncated so the prefs file cannot grow unbounded`() {
        val cache = newCache()
        val hugeTitle = "ж".repeat(5_000)
        val hugeArtist = "а".repeat(5_000)
        val hugeThumb = "/music/api/thumb?v=" + "x".repeat(5_000)

        assertTrue(
            cache.saveChartsSnapshot(
                listOf(track(id = "v1", title = hugeTitle, artist = hugeArtist, thumbnail = hugeThumb)),
            ),
        )

        val loaded = cache.getChartsSnapshot()?.single()
        assertEquals(200, loaded?.title?.length)
        assertEquals(200, loaded?.artist?.length)
        assertEquals(512, loaded?.thumbnail?.length)
        // Хранимая запись не должна быть больше пары килобайт даже при гигантских полях.
        assertTrue(cache.saveChartsSnapshot(listOf(track(id = "v2", title = hugeTitle))))
    }

    @Test
    fun `track without a playable id is not cached`() {
        val cache = newCache()

        assertTrue(cache.saveChartsSnapshot(listOf(track(id = "  "), track(id = "ok"))))

        val loaded = cache.getChartsSnapshot()
        assertEquals(listOf("ok"), loaded?.map { it.id })
    }

    // --- устойчивость ---------------------------------------------------------------------------

    @Test
    fun `broken json reads as no data`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["music_charts"] = "{не json"
        store.map["music_playlists"] = ""

        assertNull(cache.getChartsSnapshot())
        assertNull(cache.getTimestamp())
        assertNull(cache.getPlaylistsSnapshot())
        assertNull(cache.getPlaylistsTimestamp())
    }

    @Test
    fun `foreign cache version reads as no data`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["music_charts"] = """{"v":99,"saved_at":5,"tracks":[{"id":"v1"}]}"""

        assertNull(cache.getChartsSnapshot())
        assertNull("метка чужой версии тоже не считается", cache.getTimestamp())
    }

    @Test
    fun `envelope without the rows array reads as no data`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["music_playlists"] = """{"v":1,"saved_at":5}"""

        assertNull(cache.getPlaylistsSnapshot())
        // Конверт цел — метка времени доступна (политика решит, что делать с пустотой).
        assertEquals(5L, cache.getPlaylistsTimestamp())
    }

    @Test
    fun `malformed rows are dropped but valid ones survive`() {
        val store = FakeStore()
        val cache = newCache(store)
        store.map["music_charts"] = """
            {"v":1,"saved_at":7,"tracks":[
                42,
                "строка",
                {"id":"v1","title":"живой","artist":"артист","duration":"2:00","thumbnail":"/t.jpg"},
                {"title":"без id"},
                {"id":"v2"}
            ]}
        """.trimIndent()

        val loaded = cache.getChartsSnapshot()
        assertEquals(listOf("v1", "v2"), loaded?.map { it.id })
        // Отсутствующие поля получают дефолты модели, а не роняют чтение.
        assertEquals(MusicTrack(id = "v2", title = "", artist = "", duration = "--:--", thumbnail = null), loaded?.get(1))
        assertEquals(7L, cache.getTimestamp())
    }

    @Test
    fun `failing store gives false on save and null on reads without throwing`() {
        val store = FakeStore(failing = true)
        val cache = newCache(store)

        assertFalse(cache.saveChartsSnapshot(listOf(track())))
        assertFalse(cache.savePlaylistsSnapshot(listOf(playlist())))
        assertNull(cache.getChartsSnapshot())
        assertNull(cache.getPlaylistsSnapshot())
        assertNull(cache.getTimestamp())
        assertNull(cache.getPlaylistsTimestamp())
        cache.clear() // не бросает
    }

    // --- гигиена --------------------------------------------------------------------------------

    @Test
    fun `clear wipes both snapshots`() {
        val store = FakeStore()
        val cache = newCache(store)
        cache.saveChartsSnapshot(listOf(track()))
        cache.savePlaylistsSnapshot(listOf(playlist()))

        cache.clear()

        assertTrue(store.map.isEmpty())
        assertNull(cache.getChartsSnapshot())
        assertNull(cache.getPlaylistsSnapshot())
        assertNull(cache.getTimestamp())
        assertNull(cache.getPlaylistsTimestamp())
    }
}
