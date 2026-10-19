package ru.mars.network.cache

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import ru.mars.network.model.MusicTrack
import ru.mars.network.model.Playlist

/**
 * Офлайн-снимок вкладок музыки: последний успешный чарт («Обзор») и последний список
 * плейлистов.
 *
 * Зачем: `MusicActivity.loadHome()` на любой сетевой сбой показывал экран ошибки
 * «Чарт недоступен», хотя чарт, загруженный вчера, остаётся осмысленным — без сети
 * вкладка просто пустела. Тот же класс проблемы уже решён для задач/проектов/знаний
 * ([TodosOfflineCache], [ProjectsOfflineCache], [KnowledgeOfflineCache]); музыка была
 * единственным экраном без такого слоя.
 *
 * Контракт (повторяет соседние кэши):
 * - Класс чистый: зависит только от порта [KeyValueStore], без Android-фреймворка и
 *   сети — полностью покрыт JVM unit-тестами; Android-частью остаётся тонкий
 *   [SharedPreferencesKeyValueStore].
 * - Ничего не бросает: битый JSON, чужая версия формата или падающее хранилище дают
 *   «данных нет» (null/false), а не crash; следующая успешная запись перетирает мусор.
 * - Объём ограничен: не более [MAX_CACHED_TRACKS] треков и [MAX_CACHED_PLAYLISTS]
 *   плейлистов в снимке, длинные строки (название, исполнитель, id, обложка) усекаются —
 *   приватные SharedPreferences не место для мегабайтов.
 * - Слотов ровно два (чарт и плейлисты), поэтому LRU-вытеснения, как в
 *   [TodosOfflineCache] с его сессионными слотами, здесь нечего вытеснять: каждый слот
 *   просто перезаписывается следующим успешным ответом.
 * - Данные приватные: снимок чарта — это фактически история прослушивания, поэтому
 *   кэш стирается при выходе из аккаунта ([clear], файл
 *   `AuthExitStores.MUSIC_OFFLINE_PREFS`).
 *
 * Не потокобезопасен: вызывать из одного места (экран), как и остальные кэши.
 */
class MusicOfflineCache(
    private val store: KeyValueStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Сохраняет снимок чарта. Пустой список — валидный снимок («сервер честно сказал,
     * что треков нет»), но политика фолбэка такой снимок не показывает: пустая вкладка
     * под видом кэша хуже честной ошибки. false — хранилище не приняло запись.
     */
    fun saveChartsSnapshot(tracks: List<MusicTrack>): Boolean = runCatching {
        store.putString(
            KEY_CHARTS,
            mapAdapter.toJson(
                mapOf(
                    "v" to CACHE_VERSION,
                    "saved_at" to clock(),
                    "tracks" to tracks.take(MAX_CACHED_TRACKS).mapNotNull { trackToRow(it) },
                ),
            ),
        )
        true
    }.getOrDefault(false)

    /**
     * Сохранённый снимок чарта; null — кэша нет или данные битые. Порядок треков
     * сохраняется как в живом ответе (это порядок чарта, а не произвольный список).
     */
    fun getChartsSnapshot(): List<MusicTrack>? = runCatching {
        val body = readEnvelope(store.getString(KEY_CHARTS)) ?: return null
        val rows = body["tracks"] as? List<*> ?: return null
        rows.mapNotNull { rowToTrack(it) }
    }.getOrNull()

    /**
     * Момент сохранения снимка чарта (epoch millis) — для подписи «снимок на 18.10 23:41».
     * null — снимка нет или данные битые.
     */
    fun getTimestamp(): Long? = readTimestamp(KEY_CHARTS)

    /** Сохраняет снимок списка плейлистов; false — хранилище не приняло запись. */
    fun savePlaylistsSnapshot(playlists: List<Playlist>): Boolean = runCatching {
        store.putString(
            KEY_PLAYLISTS,
            mapAdapter.toJson(
                mapOf(
                    "v" to CACHE_VERSION,
                    "saved_at" to clock(),
                    "playlists" to playlists.take(MAX_CACHED_PLAYLISTS).mapNotNull { playlistToRow(it) },
                ),
            ),
        )
        true
    }.getOrDefault(false)

    /** Сохранённый снимок плейлистов; null — кэша нет или данные битые. */
    fun getPlaylistsSnapshot(): List<Playlist>? = runCatching {
        val body = readEnvelope(store.getString(KEY_PLAYLISTS)) ?: return null
        val rows = body["playlists"] as? List<*> ?: return null
        rows.mapNotNull { rowToPlaylist(it) }
    }.getOrNull()

    /** Момент сохранения снимка плейлистов (epoch millis); null — снимка нет. */
    fun getPlaylistsTimestamp(): Long? = readTimestamp(KEY_PLAYLISTS)

    /** Полное стирание кэша — вызывается на всех путях выхода из авторизации. */
    fun clear() {
        runCatching { store.clearAll() }
    }

    // --- сериализация ----------------------------------------------------------------------------

    private fun readTimestamp(key: String): Long? = runCatching {
        val body = readEnvelope(store.getString(key)) ?: return null
        (body["saved_at"] as? Number)?.toLong()
    }.getOrNull()

    /** Разбор конверта; null — мусор, чужая версия или не-объект (данных нет). */
    private fun readEnvelope(raw: String?): Map<String, Any?>? {
        if (raw.isNullOrBlank()) return null
        val parsed = mapAdapter.fromJson(raw) ?: return null
        if ((parsed["v"] as? Number)?.toInt() != CACHE_VERSION) return null
        return parsed
    }

    /**
     * Строка трека. null — трек без id: такую строку нельзя ни включить, ни открыть,
     * значит кэшировать её бессмысленно (в живом списке она бы просто не заиграла).
     */
    private fun trackToRow(track: MusicTrack): Map<String, Any?>? {
        val id = track.id.trim().take(MAX_ID_CHARS)
        if (id.isEmpty()) return null
        return mapOf(
            "id" to id,
            "title" to track.title.take(MAX_TITLE_CHARS),
            "artist" to track.artist.take(MAX_ARTIST_CHARS),
            "duration" to track.duration.take(MAX_DURATION_CHARS),
            "thumbnail" to track.thumbnail?.take(MAX_THUMBNAIL_CHARS),
        )
    }

    private fun rowToTrack(row: Any?): MusicTrack? {
        val map = row as? Map<*, *> ?: return null
        val id = (map["id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return MusicTrack(
            id = id,
            title = map["title"] as? String ?: "",
            artist = map["artist"] as? String ?: "",
            duration = map["duration"] as? String ?: "--:--",
            thumbnail = (map["thumbnail"] as? String)?.takeIf { it.isNotBlank() },
        )
    }

    private fun playlistToRow(playlist: Playlist): Map<String, Any?>? {
        val id = playlist.id.trim().take(MAX_ID_CHARS)
        if (id.isEmpty()) return null
        return mapOf(
            "id" to id,
            "title" to playlist.title.take(MAX_TITLE_CHARS),
            "created_at" to playlist.createdAt.take(MAX_CREATED_AT_CHARS),
            "track_count" to playlist.trackCount,
        )
    }

    private fun rowToPlaylist(row: Any?): Playlist? {
        val map = row as? Map<*, *> ?: return null
        val id = (map["id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return Playlist(
            id = id,
            title = map["title"] as? String ?: "",
            createdAt = map["created_at"] as? String ?: "",
            trackCount = (map["track_count"] as? Number)?.toInt() ?: 0,
        )
    }

    private companion object {
        /** Версия формата: чужая версия читается как «данных нет», а не как мусор в UI. */
        const val CACHE_VERSION = 1

        /** Слоты снимков (по одному на вкладку). */
        const val KEY_CHARTS = "music_charts"
        const val KEY_PLAYLISTS = "music_playlists"

        /** Сколько треков хранится в снимке чарта (хвост отбрасывается). */
        const val MAX_CACHED_TRACKS = 100

        /** Сколько плейлистов хранится в снимке (хвост отбрасывается). */
        const val MAX_CACHED_PLAYLISTS = 100

        /** Потолки длины полей — приватное хранилище не должно раздуваться. */
        const val MAX_TITLE_CHARS = 200
        const val MAX_ARTIST_CHARS = 200
        const val MAX_ID_CHARS = 128
        const val MAX_DURATION_CHARS = 16
        const val MAX_THUMBNAIL_CHARS = 512
        const val MAX_CREATED_AT_CHARS = 40

        val MAP_TYPE = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        val moshi = Moshi.Builder().build()
        val mapAdapter = moshi.adapter<Map<String, Any?>>(MAP_TYPE)
    }
}
