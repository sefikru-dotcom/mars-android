package ru.mars.network.repository

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import ru.mars.network.http.HttpClients
import ru.mars.network.model.HistoryEntry
import ru.mars.network.model.LyricsResult
import ru.mars.network.model.MusicPlayerState
import ru.mars.network.model.MusicShelf
import ru.mars.network.model.MusicTrack
import ru.mars.network.model.PlaybackStatus
import ru.mars.network.model.Playlist
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Страница исполнителя из `GET /api/artist/{id_or_name}`: имя, канал, подписчики,
 * аватар и готовый список песен. Пустые строки сервера приведены к null, чтобы UI
 * отличал «поля нет» от «поле пустое».
 */
data class MusicArtist(
    val name: String,
    val channelId: String? = null,
    val radioId: String? = null,
    val description: String? = null,
    val subscribers: String? = null,
    val thumbnail: String? = null,
    val songs: List<MusicTrack> = emptyList(),
)

/** Альбом из `GET /api/album/{browseId}`: обложка, год и треки в порядке нумерации. */
data class MusicAlbum(
    val browseId: String,
    val title: String,
    val artist: String,
    val year: String? = null,
    val trackCount: Int = 0,
    val thumbnail: String? = null,
    val description: String? = null,
    val tracks: List<MusicTrack> = emptyList(),
)

/**
 * Карточка альбома из `GET /api/search?type=albums` — ровно то, что печатает
 * строка списка, без треков.
 *
 * Почему отдельная модель, а не [MusicAlbum]: список альбомов исполнителя не
 * должен тянуть `/api/album/{browseId}` на каждую карточку (это N запросов и
 * N разборов треков). Треки приходят только при открытии конкретного альбома.
 *
 * [year] — как в ответе (`"2024"`), может отсутствовать; [thumbnail] уже
 * абсолютный: сервер отдаёт относительный путь, который резолвится от корня
 * сайта, а не от `baseUrl` музыкального API.
 */
data class MusicAlbumSummary(
    val browseId: String,
    val title: String,
    val artist: String,
    val year: String? = null,
    val thumbnail: String? = null,
)

/**
 * Итог запроса альбома. Отказ «неверный browseId» — это ответ сервера по контракту
 * (`must start with MPRE`), а не сбой, поэтому он несёт собственный вариант
 * [InvalidBrowseId] и не превращается в исключение.
 */
sealed interface AlbumResult {
    /** Альбом найден и разобран. */
    data class Loaded(val album: MusicAlbum) : AlbumResult

    /** browseId не в формате `MPRE…` — такой страницы у сервера нет. */
    data object InvalidBrowseId : AlbumResult

    /** Сеть или сервер: [message] — техническая деталь, текст для UI даёт политика. */
    data class Failed(val message: String) : AlbumResult
}

/** Mars Music's HTTP search/charts and Mars Connect WebSocket client. */
class MusicRepository(
    private val baseUrl: String = "https://marscore.ru/music",
    private val client: OkHttpClient = HttpClients.rest,
    /** Mars Connect device id sent in REGISTER frames; also the echo-suppression key. */
    val deviceId: String = "android-client",
    private val restoreTimeoutMs: Long = DEFAULT_RESTORE_TIMEOUT_MS,
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val envelopeAdapter = moshi.adapter(SessionEnvelope::class.java)
    private val listAdapter = moshi.adapter(TrackListEnvelope::class.java)
    private val albumListAdapter = moshi.adapter(AlbumSummaryListEnvelope::class.java)

    suspend fun getStatus(): MusicPlayerState = connect(WsAction.Status)
    suspend fun play() { command("playPause") }
    suspend fun pause() { command("playPause") }
    suspend fun next() { command("next") }
    suspend fun previous() { command("prev") }
    suspend fun setVolume(volume: Double) {
        require(volume in 0.0..1.0) { "volume must be between 0 and 1" }
        command("setVolume", "{\"volume\":$volume}")
    }
    suspend fun getQueue(): List<MusicTrack> = getStatus().queue

    /**
     * Stream URL for the media player: `{base}/api/stream/{trackId}`.
     *
     * Built through the OkHttp URL builder like every other call in this class —
     * a raw `"$baseUrl/..."` concatenation leaks a DOUBLE SLASH onto the wire for a
     * trailing-slash base (`https://…/music/` → `music//api/stream/…`), which
     * strict routers answer with 404. `addPathSegment` also percent-encodes the
     * id, so a malformed id can never bend the path.
     */
    fun getStreamUrl(trackId: String): String = baseUrl.toHttpUrl().newBuilder()
        .addPathSegments("api/stream")
        .addPathSegment(trackId)
        .build()
        .toString()

    /** Real `/api/charts` payload is the same `{success,results}` envelope as `/api/search`. */
    suspend fun getCharts(): List<MusicTrack> = fetchTracks("api/charts")

    /**
     * GET /api/favorites — the user's favorite tracks. The live service wraps them in a
     * `{"favorites": [...]}` envelope (same track shape as charts/home).
     */
    suspend fun getFavorites(): List<MusicTrack> = fetchTracks("api/favorites")

    /**
     * GET /api/flow — the personalized track feed. Same track shape as charts; the
     * live service may answer with `{results}`, `{favorites}` or `{tracks}`.
     */
    suspend fun getFlow(): List<MusicTrack> = fetchTracks("api/flow")

    /**
     * GET /api/home → `shelf.sections[]` — персональная лента из четырёх полок
     * («Продолжить слушать», «Новинки», «Для тебя», «Похожие на …»). Пустые полки
     * возвращаются как есть (пустой список items); отсутствие поля `shelf` или
     * `sections` даёт пустой результат, а не исключение. Сбой сети или
     * `success:false` выбрасывается наружу.
     */
    suspend fun getHome(): List<MusicShelf> {
        val body = getJson("api/home") { it.source().readUtf8() }
        return parseHomeShelves(body)
    }

    /**
     * GET /api/suggestions?q=[query] — живые подсказки поиска (до 8 строк).
     * Пустой запрос, сбой сети или любой не-200 ответ дают пустой список,
     * потому что подсказки — вспомогательный UX и никогда не должны блокировать
     * ввод пользователя.
     */
    suspend fun getSuggestions(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        return try {
            val body = getJson("api/suggestions", listOf("q" to trimmed)) { it.source().readUtf8() }
            parseSuggestions(body)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Отмена coroutine (дебаунс заменил запрос) — не «сбой подсказок».
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * GET /api/radio/{videoId} — a radio queue seeded from [videoId].
     */
    suspend fun getRadio(videoId: String): List<MusicTrack> {
        require(videoId.isNotBlank()) { "videoId must not be blank" }
        return fetchTracks("api/radio/$videoId")
    }

    /**
     * GET /api/lyrics/{videoId} — lyrics for a track. Optional [title]/[artist] are
     * forwarded as query parameters to help the server match the right lyrics.
     */
    suspend fun getLyrics(videoId: String, title: String? = null, artist: String? = null): LyricsResult {
        require(videoId.isNotBlank()) { "videoId must not be blank" }
        val query = buildList {
            title?.let { add("title" to it) }
            artist?.let { add("artist" to it) }
        }
        return getJson("api/lyrics/$videoId", query) { body ->
            moshi.adapter(LyricsResult::class.java).fromJson(body.source()) ?: LyricsResult(null, null, null)
        }
    }

    /**
     * GET /api/artist/{id_or_name} — страница исполнителя вместе с его песнями
     * (`songs[]` уже приходят в ответе, отдельный запрос за ними не нужен).
     *
     * [query] — имя исполнителя ИЛИ его channelId (`UC…`): сервер различает их по
     * префиксу `UC` и во втором случае идёт прямо в канал. Значение уходит ОДНИМ
     * сегментом пути, поэтому имя со слэшем (`AC/DC`) не превращается в два сегмента
     * и не получает 404.
     *
     * null — сервер ответил 404 (`Artist not found`): исполнителя нет, это ответ по
     * контракту, а не сбой. Сеть и прочие коды — исключение, как у [getRadio].
     */
    suspend fun getArtist(query: String): MusicArtist? {
        val target = query.trim()
        require(target.isNotEmpty()) { "artist query must not be blank" }
        val response = getRaw("api/artist", target)
        if (response.code == HTTP_NOT_FOUND) return null
        if (response.code !in HTTP_OK_RANGE) {
            throw IllegalStateException("HTTP ${response.code} for /api/artist")
        }
        val dto = moshi.adapter(ArtistDto::class.java).fromJson(response.body)
            ?: throw IOException("Empty artist response")
        return dto.toModel(fallbackName = target)
    }

    /**
     * GET /api/album/{browseId} — альбом и его треки.
     *
     * browseId альбома — это НЕ имя: сервис принимает только `MPRE…`, на всё
     * остальное отвечает `{"error":"Invalid album browseId provided, must start with
     * MPRE."}` с кодом 500. Такой отказ — часть контракта, поэтому он возвращается
     * вариантом [AlbumResult.InvalidBrowseId], а не исключением; неверный префикс
     * отсекается до запроса. Исключений метод не бросает вовсе: транспортная ошибка
     * приходит как [AlbumResult.Failed] (отмена coroutine пробрасывается как есть).
     */
    suspend fun getAlbum(browseId: String): AlbumResult {
        val target = browseId.trim()
        if (!target.startsWith(ALBUM_BROWSE_ID_PREFIX)) return AlbumResult.InvalidBrowseId
        val response = try {
            getRaw("api/album", target)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return AlbumResult.Failed(error.message ?: "Album request failed")
        }
        if (response.code !in HTTP_OK_RANGE) {
            return if (response.body.contains(INVALID_BROWSE_ID_MARKER, ignoreCase = true)) {
                AlbumResult.InvalidBrowseId
            } else {
                AlbumResult.Failed(errorText(response.body) ?: "HTTP ${response.code}")
            }
        }
        val parsed = runCatching { moshi.adapter(AlbumEnvelope::class.java).fromJson(response.body) }
            .getOrNull() ?: return AlbumResult.Failed("Malformed album response")
        if (parsed.success == false) {
            return AlbumResult.Failed(parsed.error ?: "Album request failed")
        }
        val album = parsed.album ?: return AlbumResult.Failed("Empty album response")
        return AlbumResult.Loaded(album.toModel(target))
    }

    /**
     * POST /api/favorite/toggle — adds or removes [videoId] from favorites and returns the
     * server's new `favorited` state, or null when the server could not determine it
     * (e.g. `{"status":"error"}`). [track] metadata is forwarded so the service can store
     * title/artist/thumbnail alongside the id (it inserts empty strings otherwise).
     */
    suspend fun toggleFavorite(videoId: String, track: MusicTrack? = null): Boolean? {
        require(videoId.isNotBlank()) { "videoId must not be blank" }
        val json = moshi.adapter(FavoriteToggleRequest::class.java).toJson(
            FavoriteToggleRequest(
                id = videoId,
                toggleVideoId = videoId,
                title = track?.title,
                artist = track?.artist,
                duration = track?.duration,
                thumbnail = track?.thumbnail,
            ),
        )
        val parsed = postJson("api/favorite/toggle", json) { body ->
            moshi.adapter(FavoriteToggleResponse::class.java).fromJson(body.source())
        }
        return parsed?.favorited
    }

    // ---------------------------------------------------------------- playlists

    /**
     * GET /api/playlists — every server-side playlist of the account.
     *
     * Response is `{"success":true,"playlists":[{"id":"2e0d0d47","title":"Street",
     * "created_at":"2026-09-30 14:25:34","track_count":1}]}`; `success:false` (with
     * `error`) is an exception, never an empty list, so the screen can tell «нет
     * плейлистов» from «сервис отказал».
     */
    suspend fun getPlaylists(): List<Playlist> {
        val body = getJson("api/playlists") { it.source().readUtf8() }
        val parsed = moshi.adapter(PlaylistListEnvelope::class.java).fromJson(body)
            ?: throw IOException("Empty playlists response")
        if (parsed.success == false) throw IllegalStateException(parsed.error ?: "Music request failed")
        return parsed.playlists.map { it.toModel() }
    }

    /**
     * POST /api/playlist/create with the single body field `title`. The service answers
     * `{"success":true,"id":"<8 hex>","title":"…"}`; an empty/blank title comes back as
     * HTTP 400 + `{"success":false,"error":"Title required"}` and surfaces here as that
     * very message (see [postText], which keeps the error body on non-2xx).
     *
     * A blank title is deliberately NOT rejected locally: the service's own wording is
     * the message the user sees, and a client-side check would replace it with a
     * generic one the screen cannot translate into «укажите название».
     */
    suspend fun createPlaylist(title: String): Playlist {
        val body = postText("api/playlist/create", moshi.adapter(CreatePlaylistRequest::class.java).toJson(CreatePlaylistRequest(title)))
        val parsed = moshi.adapter(PlaylistCreatedResponse::class.java).fromJson(body)
            ?: throw IOException("Empty playlist response")
        if (parsed.success == false) throw IllegalStateException(parsed.error ?: "Playlist not created")
        val id = parsed.id?.takeIf { it.isNotBlank() } ?: throw IOException("Playlist response without id")
        // A fresh playlist has no tracks and the endpoint returns no created_at.
        return Playlist(id, parsed.title?.takeIf { it.isNotBlank() } ?: title, "", 0)
    }

    /** DELETE /api/playlist/{id}; a `success:false`/`status:error` answer throws. */
    suspend fun deletePlaylist(id: String) {
        require(id.isNotBlank()) { "id must not be blank" }
        val body = sendJson(pathWithSegment("api/playlist", id), "", "DELETE")
        requireSuccess("api/playlist/$id", moshi.adapter(MutationResponse::class.java).fromJson(body) ?: MutationResponse())
    }

    /**
     * GET /api/playlist/{id} — the playlist's tracks. Same envelope as charts, so
     * [fetchTracks] (results/favorites/tracks keys, `success:false` = exception) is reused.
     */
    suspend fun getPlaylistTracks(id: String): List<MusicTrack> {
        require(id.isNotBlank()) { "id must not be blank" }
        return fetchTracks(pathWithSegment("api/playlist", id))
    }

    /**
     * POST /api/playlist/{id}/add — puts [videoId] into the playlist. The video id goes
     * in as its own path segment (percent-encoded), so an id with `/` cannot bend the path.
     */
    suspend fun addTrackToPlaylist(id: String, videoId: String) {
        require(id.isNotBlank()) { "id must not be blank" }
        require(videoId.isNotBlank()) { "videoId must not be blank" }
        mutate(pathWithSegment("api/playlist", id) + "/add", moshi.adapter(PlaylistTrackRequest::class.java).toJson(PlaylistTrackRequest(videoId)))
    }

    /** POST /api/playlist/{id}/remove — takes [videoId] back out of the playlist. */
    suspend fun removeTrackFromPlaylist(id: String, videoId: String) {
        require(id.isNotBlank()) { "id must not be blank" }
        require(videoId.isNotBlank()) { "videoId must not be blank" }
        mutate(pathWithSegment("api/playlist", id) + "/remove", moshi.adapter(PlaylistTrackRequest::class.java).toJson(PlaylistTrackRequest(videoId)))
    }

    // ------------------------------------------------------------------ history

    /**
     * GET /api/history — the server listening history, newest first.
     *
     * Each element carries the VIDEO id under the key `id` (not `video_id`) plus
     * `title`, `artist`, `duration` («2:38»), a site-relative `thumbnail`
     * (`/music/api/thumb?v=…`) and `played_at` (`2026-10-12 09:37:59`).
     */
    suspend fun getHistory(): List<HistoryEntry> {
        val body = getJson("api/history") { it.source().readUtf8() }
        // The service documents an object envelope, but a bare array is tolerated.
        val normalized = if (body.trimStart().startsWith("[")) "{\"history\":$body}" else body
        val parsed = moshi.adapter(HistoryEnvelope::class.java).fromJson(normalized)
            ?: throw IOException("Empty history response")
        if (parsed.success == false) throw IllegalStateException(parsed.error ?: "Music request failed")
        return parsed.history.map { it.toModel() }
    }

    /**
     * POST /api/history/record — writes one played track into the server history. The
     * body is `{id,title,artist,duration,thumbnail}` (the service answers `status:ok`;
     * a body without `id` is a 400 + `error:"Missing id"`). Callers treat a failure as
     * non-fatal: playback must never break because the history write did.
     */
    suspend fun recordHistory(track: MusicTrack) {
        require(track.id.isNotBlank()) { "track id must not be blank" }
        mutate(
            "api/history/record",
            moshi.adapter(HistoryRecordRequest::class.java).toJson(
                HistoryRecordRequest(
                    id = track.id,
                    title = track.title,
                    artist = track.artist,
                    duration = track.duration,
                    thumbnail = track.thumbnail,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------- lyrics

    /**
     * POST /api/lyrics/translate — Russian translation of a song text. The request body
     * is the single field `text`; the answer is `{"success":true,"translated":"…"}`.
     * An empty input is answered with `translated:""` (not an error), so the empty
     * string is returned as-is and the caller decides what to show.
     */
    suspend fun translateLyrics(text: String): String {
        val body = postText("api/lyrics/translate", moshi.adapter(TranslateRequest::class.java).toJson(TranslateRequest(text)))
        val parsed = moshi.adapter(TranslateResponse::class.java).fromJson(body)
            ?: throw IOException("Empty translation response")
        if (parsed.success == false) throw IllegalStateException(parsed.error ?: "Translation failed")
        return parsed.translated.orEmpty()
    }

    /**
     * Shared path builder for `/…/{segment}` calls: [addPathSegment] percent-encodes the
     * id, so a malformed one can never introduce an extra path level (same rule as
     * [getStreamUrl]). `pathWithSegment("api/playlist", "2e0d0d47")` → `api/playlist/2e0d0d47`.
     */
    private fun pathWithSegment(path: String, segment: String): String =
        baseUrl.toHttpUrl().newBuilder().addPathSegments(path).addPathSegment(segment).build()
            .encodedPath.removePrefix(baseUrl.toHttpUrl().encodedPath).trimStart('/')

    /** Runs a JSON mutation and throws the server's own `error` text on a rejected one. */
    private suspend fun mutate(path: String, json: String) {
        val body = postText(path, json)
        requireSuccess(path, moshi.adapter(MutationResponse::class.java).fromJson(body) ?: MutationResponse())
    }

    /** `success:false` or `status:error` is a failure; both shapes are in the live service. */
    private fun requireSuccess(path: String, response: MutationResponse) {
        if (response.success == false || response.status == "error") {
            throw IllegalStateException(response.error ?: "Music request failed for /$path")
        }
    }

    /**
     * POSTs [json] to [path] and keeps the RAW body even for a non-2xx answer.
     *
     * The music service reports validation problems in the body (400 +
     * `{"success":false,"error":"Title required"}`), and that text is the only thing
     * worth showing to the user — [postJson] would replace it with a bare «HTTP 400».
     * Transport failures still throw.
     */
    private suspend fun postText(path: String, json: String): String = sendJson(path, json, "POST")

    /** One JSON request/response round trip for any method; returns the body text. */
    private suspend fun sendJson(path: String, json: String, method: String): String =
        suspendCancellableCoroutine { continuation ->
            val builder = Request.Builder().url(baseUrl.toHttpUrl().newBuilder().addPathSegments(path).build())
            val request = when (method) {
                "DELETE" -> builder.delete().build()
                else -> builder.post(json.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            }
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val body = resp.body?.string()
                        when {
                            body == null -> continuation.resumeWithException(IOException("Empty body from /$path"))
                            else -> continuation.resume(body)
                        }
                    }
                }
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            })
        }

    /** POSTs a JSON body to [path] and maps the successful response through [parse]. */
    private suspend fun <T> postJson(path: String, json: String, parse: (ResponseBody) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                // HttpUrl builder (parity with getJson/fetchTracks): a string
                // concatenation double-slashes the path for a trailing-slash base.
                .url(baseUrl.toHttpUrl().newBuilder().addPathSegments(path).build())
                .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val body = resp.body
                        when {
                            !resp.isSuccessful -> continuation.resumeWithException(
                                IllegalStateException("HTTP ${resp.code} for /$path"),
                            )
                            body == null -> continuation.resumeWithException(
                                IOException("Empty body from /$path"),
                            )
                            else -> {
                                val result = runCatching { parse(body) }
                                if (result.isSuccess) {
                                    continuation.resume(result.getOrThrow())
                                } else {
                                    continuation.resumeWithException(
                                        IOException("Malformed response from /$path", result.exceptionOrNull()),
                                    )
                                }
                            }
                        }
                    }
                }
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            })
        }

    /** Код и тело ответа без истолкования: нужен там, где отказ сервера — часть контракта. */
    private data class RawResponse(val code: Int, val body: String)

    /**
     * GET `{base}/{path}/{segment}` — ровно один закодированный сегмент пути.
     *
     * В отличие от [getJson], любой HTTP-код возвращается вызывающему как данные:
     * отказ «неверный browseId» приходит с кодом 500, и его надо разобрать по телу,
     * а не превратить в исключение. Сбой транспорта по-прежнему исключение.
     */
    private suspend fun getRaw(path: String, segment: String): RawResponse =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(
                    baseUrl.toHttpUrl().newBuilder()
                        .addPathSegments(path)
                        .addPathSegment(segment)
                        .build(),
                )
                .get()
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val text = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                        if (continuation.isActive) continuation.resume(RawResponse(resp.code, text))
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            })
        }

    /** Текст серверной ошибки из `{"error": "…"}`; null — тело не об этом. */
    private fun errorText(body: String): String? = runCatching {
        moshi.adapter(ErrorEnvelope::class.java).fromJson(body)?.error?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Абсолютный URL обложки альбома из серверного значения.
     *
     * Сервер музыки отдаёт относительный путь `/music/api/thumb?url=…`, который уже
     * содержит префикс `/music`. Приклеивать его к [baseUrl] (`…/music`) нельзя —
     * на выходе `/music/music/api/thumb`, сервер такого маршрута не знает. Поэтому
     * относительный путь приклеивается к корню сайта ([SITE_ORIGIN]).
     *
     * Абсолютные http(s) и протокол-относительные (`//cdn…`) значения сохраняются;
     * любые прочие схемы (`data:`, `file:`, `javascript:`…) отбрасываются — в
     * загрузчик картинок не должна уезжать произвольная схема.
     */
    private fun resolveAlbumThumbnail(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)) {
            return value
        }
        if (value.startsWith("//")) return "https:$value"
        if (ANY_SCHEME.containsMatchIn(value)) return null
        return SITE_ORIGIN + if (value.startsWith("/")) value else "/$value"
    }

    /** GETs [path] with optional [query] parameters and maps the successful response through [parse]. */
    private suspend fun <T> getJson(path: String, query: List<Pair<String, String>> = emptyList(), parse: (ResponseBody) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(baseUrl.toHttpUrl().newBuilder().addPathSegments(path).apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build())
                .get()
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val body = resp.body
                        when {
                            !resp.isSuccessful -> continuation.resumeWithException(
                                IllegalStateException("HTTP ${resp.code} for /$path"),
                            )
                            body == null -> continuation.resumeWithException(
                                IOException("Empty body from /$path"),
                            )
                            else -> {
                                val result = runCatching { parse(body) }
                                if (result.isSuccess) {
                                    continuation.resume(result.getOrThrow())
                                } else {
                                    continuation.resumeWithException(
                                        IOException("Malformed response from /$path", result.exceptionOrNull()),
                                    )
                                }
                            }
                        }
                    }
                }
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            })
        }

    suspend fun search(query: String): List<MusicTrack> {
        require(query.isNotBlank()) { "query must not be blank" }
        return fetchTracks("api/search", "q" to query)
    }

    /**
     * GET /api/search?q=…&type=albums — альбомы по запросу (в UI: альбомы исполнителя).
     *
     * Почему поиск, а не `/api/artist/{…}`: страница исполнителя отдаёт только
     * `name, channelId, radioId, description, subscribers, thumbnail, songs` —
     * альбомов в ней нет (проверено на живом сервисе 2026-10-19). Ключ
     * `type=albums` переключает выдачу на карточки
     * `{type:"album", browseId:"MPRE…", title, artist, year, thumbnail}`.
     *
     * `thumbnail` в этой выдаче относительный (`/music/api/thumb?url=…`) и уже
     * содержит префикс `/music`, поэтому резолвится от КОРНЯ сайта, а не от
     * [baseUrl]: от baseUrl получился бы двойной `/music/music/api/thumb` и
     * битая обложка.
     *
     * Строки без `browseId` и с чужим префиксом отбрасываются — такой альбом
     * нельзя открыть ([getAlbum] ответит [AlbumResult.InvalidBrowseId]).
     *
     * Ошибки — как у [search]: исключение, а не пустой список, чтобы экран
     * отличал «альбомов нет» от «сервис отказал» и мог предложить «Повторить».
     */
    suspend fun searchAlbums(query: String): List<MusicAlbumSummary> {
        require(query.isNotBlank()) { "query must not be blank" }
        return fetchAlbumSummaries("api/search", listOf("q" to query, "type" to ALBUM_SEARCH_TYPE))
    }

    /**
     * Разбор выдачи альбомов: тот же конверт `{success, results}`, что у [fetchTracks],
     * но элемент — карточка альбома, а не трек. Отдельный хелпер, чтобы не менять
     * существующую сигнатуру [fetchTracks] (её читают другие экраны).
     */
    private suspend fun fetchAlbumSummaries(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
    ): List<MusicAlbumSummary> {
        val request = Request.Builder()
            .url(
                baseUrl.toHttpUrl().newBuilder().addPathSegments(path).apply {
                    query.forEach { (key, value) -> addQueryParameter(key, value) }
                }.build(),
            )
            .get().build()
        return suspendCancellableCoroutine { continuation ->
            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.use {
                        try {
                            if (!it.isSuccessful) error("HTTP ${it.code}")
                            val parsed = albumListAdapter.fromJson(it.body?.string().orEmpty())
                                ?: error("Empty album search response")
                            if (parsed.success == false) error(parsed.error ?: "Music request failed")
                            continuation.resume(
                                parsed.results.mapNotNull { dto -> dto.toModel() }
                                    .map { album -> album.copy(thumbnail = resolveAlbumThumbnail(album.thumbnail)) },
                            )
                        } catch (e: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                }
            })
        }
    }

    private suspend fun fetchTracks(path: String, query: Pair<String, String>? = null): List<MusicTrack> {
        val request = Request.Builder()
            .url(baseUrl.toHttpUrl().newBuilder().addPathSegments(path).apply { query?.let { addQueryParameter(it.first, it.second) } }.build())
            .get().build()
        return suspendCancellableCoroutine { continuation ->
            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.use {
                        try {
                            if (!it.isSuccessful) error("HTTP ${it.code}")
                            val parsed = listAdapter.fromJson(it.body?.string().orEmpty()) ?: error("Empty music response")
                            if (parsed.success == false) error(parsed.error ?: "Music request failed")
                            val items = parsed.results.ifEmpty { parsed.favorites }.ifEmpty { parsed.tracks }
                            continuation.resume(items.map { item -> item.toModel() })
                        } catch (e: Throwable) { if (continuation.isActive) continuation.resumeWithException(e) }
                    }
                }
            })
        }
    }

    private suspend fun command(action: String, payload: String = "{}") {
        connect(WsAction.Command(action, payload))
    }

    /**
     * Opens a PERSISTENT Mars Connect socket and reports the real remote music event
     * stream: the initial `SESSION_RESTORE` snapshot plus every later `DEVICES_UPDATE`
     * broadcast carrying a session (`globalSession`). Frames are parsed by the same
     * envelope/session mapping as [connect] — this subscription adds no second parser.
     *
     * Robustness (parity with [ConnectListener]): malformed JSON, session-less
     * `DEVICES_UPDATE`, and any other frame type (`EXEC_CMD`, …) are ignored without
     * killing the socket, and a throwing [onUpdate] receiver cannot tear the reader down.
     *
     * [onUpdate] fires on OkHttp's WebSocket reader thread — receivers that touch UI or
     * player state must marshal to their own thread. The returned [Closeable] cancels the
     * socket; afterwards no further updates are delivered. There is deliberately no
     * auto-reconnect: a dropped socket goes silent, and the next subscription (e.g. the
     * next MusicActivity session) restores the stream.
     */
    fun subscribeRemoteState(onUpdate: (MusicPlayerState) -> Unit): Closeable {
        val wsUrl = webSocketUrl(
            baseUrl.toHttpUrl().newBuilder().addPathSegments("api/connect/ws").build().toString(),
        )
        val socket = client.newWebSocket(Request.Builder().url(wsUrl).build(), RemoteStateListener(onUpdate))
        return Closeable { socket.cancel() }
    }

    /** Persistent counterpart of [ConnectListener]: every session-bearing frame, no continuation, no close. */
    internal inner class RemoteStateListener(
        private val onUpdate: (MusicPlayerState) -> Unit,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            webSocket.send(registerFrame())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val envelope = try { envelopeAdapter.fromJson(text) } catch (e: Throwable) { null } ?: return
            val session = when (envelope.type) {
                // This client's initial snapshot, sent once by the server on register.
                SESSION_RESTORE -> envelope.session ?: envelope.globalSession
                // Every later global change is broadcast as DEVICES_UPDATE(globalSession).
                DEVICES_UPDATE -> envelope.globalSession ?: envelope.session
                else -> null
            } ?: return
            val state = try { session.toModel() } catch (e: Throwable) { null } ?: return
            try { onUpdate(state) } catch (e: Throwable) { /* a bad receiver must not kill the socket reader */ }
        }
    }

    /** The one REGISTER frame shape this client sends (shared by both WS listeners). */
    private fun registerFrame(): String =
        "{\"type\":\"REGISTER\",\"id\":\"$deviceId\",\"name\":\"Android\",\"deviceType\":\"mobile\",\"isActive\":false,\"state\":{}}"

    internal sealed interface WsAction {
        object Status : WsAction
        data class Command(val action: String, val payload: String) : WsAction
    }

    /**
     * Registers over Mars Connect and waits for the server's `SESSION_RESTORE`.
     * - Status: resumes with the restored session state.
     * - Command: sends `CONTROL_CMD` targeted at the session's `activeDeviceId`
     *   (broadcasting to `all` would toggle every paired device); without an active
     *   device the call fails instead of broadcasting. The socket is then closed
     *   gracefully with code 1000 instead of cancelling mid-flight.
     *
     * The wait is bounded by [restoreTimeoutMs]: a server that closes the socket
     * before `SESSION_RESTORE` fails the call immediately, a timeout cancels it,
     * and cancellation tears the socket down.
     */
    private suspend fun connect(action: WsAction): MusicPlayerState = withTimeout(restoreTimeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val wsUrl = webSocketUrl(
                baseUrl.toHttpUrl().newBuilder().addPathSegments("api/connect/ws").build().toString(),
            )
            val socket = client.newWebSocket(Request.Builder().url(wsUrl).build(), ConnectListener(action, continuation))
            continuation.invokeOnCancellation { socket.cancel() }
        }
    }

    /**
     * Handles the register/restore handshake. Only frames whose `type` is exactly
     * `SESSION_RESTORE` are accepted — the server also broadcasts `DEVICES_UPDATE`
     * frames carrying `globalSession`, which must never be mistaken for the
     * restore — and the restore is processed exactly once so duplicate frames
     * cannot re-resume the continuation or fire a second `CONTROL_CMD`.
     */
    internal inner class ConnectListener(
        private val action: WsAction,
        private val continuation: CancellableContinuation<MusicPlayerState>,
    ) : WebSocketListener() {
        private val restoreHandled = AtomicBoolean(false)

        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            val sent = webSocket.send(registerFrame())
            if (!sent) fail(webSocket, IOException("music socket rejected REGISTER"))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val envelope = try { envelopeAdapter.fromJson(text) } catch (e: Throwable) { null } ?: return
            if (envelope.type != SESSION_RESTORE) return
            val session = envelope.session ?: envelope.globalSession ?: return
            if (!restoreHandled.compareAndSet(false, true)) return // duplicate restore
            when (action) {
                WsAction.Status -> resumeAndClose(webSocket, session)
                is WsAction.Command -> {
                    val target = session.activeDeviceId?.takeIf { it.isNotBlank() }
                    if (target == null) {
                        fail(
                            webSocket,
                            IllegalStateException("no activeDeviceId in the music session; refusing to broadcast CONTROL_CMD to all devices"),
                        )
                        return
                    }
                    val sent = webSocket.send("{\"type\":\"CONTROL_CMD\",\"targetDeviceId\":\"$target\",\"action\":\"${action.action}\",\"payload\":${action.payload}}")
                    if (!sent) {
                        fail(webSocket, IOException("music socket rejected CONTROL_CMD (${action.action})"))
                        return
                    }
                    resumeAndClose(webSocket, session)
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Server closed first: echo the close frame so the handshake completes.
            webSocket.close(NORMAL_CLOSURE, null)
            // Closed before the restore arrived: fail instead of waiting forever.
            if (continuation.isActive) {
                continuation.resumeWithException(IOException("music socket closed (code=$code) before SESSION_RESTORE"))
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (continuation.isActive) {
                continuation.resumeWithException(IOException("music socket closed before SESSION_RESTORE"))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) { if (continuation.isActive) continuation.resumeWithException(t) }

        private fun resumeAndClose(webSocket: WebSocket, session: Session) {
            if (continuation.isActive) continuation.resume(session.toModel())
            webSocket.close(NORMAL_CLOSURE, "done")
        }

        private fun fail(webSocket: WebSocket, t: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(t)
            webSocket.cancel()
        }
    }

    /**
     * `results` is the charts/search envelope key; `favorites` is what `/api/favorites`
     * uses and `tracks` is what flow/radio may answer with. `success == false`
     * (with `error`) surfaces as an exception.
     */
    @JsonClass(generateAdapter = false)
    private data class TrackListEnvelope(
        val success: Boolean? = null,
        val results: List<TrackDto> = emptyList(),
        val favorites: List<TrackDto> = emptyList(),
        val tracks: List<TrackDto> = emptyList(),
        val error: String? = null,
    )
    @JsonClass(generateAdapter = false)
    private data class SessionEnvelope(val type: String? = null, val session: Session? = null, val globalSession: Session? = null)
    @JsonClass(generateAdapter = false)
    private data class TrackDto(
        val id: String,
        val title: String,
        val artist: String,
        val duration: String? = null,
        val thumbnail: String? = null,
        val album: AlbumRefDto? = null,
    ) {
        // Favorites rows recorded without metadata carry an empty duration string.
        fun toModel(): MusicTrack = MusicTrack(
            id = id,
            title = title,
            artist = artist,
            duration = duration?.takeIf { it.isNotBlank() } ?: "--:--",
            thumbnail = thumbnail,
            albumTitle = album?.name?.takeIf { it.isNotBlank() },
            albumBrowseId = album?.browseId?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Поле `album` из выдачи сервера — один и тот же объект во всех выдачах
     * (`GET /api/search?type=songs`, страница исполнителя, чарты).
     *
     * Сервер нормализует две формы ytmusicapi (голое имя и `{name,id}`) в этот
     * контракт, поэтому клиенту достаточно одной модели. `browseId` может быть
     * пустым, если у трека нет альбома: такую карточку нельзя открыть, но имя
     * альбома всё равно показывается в строке трека.
     */
    @JsonClass(generateAdapter = false)
    private data class AlbumRefDto(
        val name: String? = null,
        @Json(name = "browseId") val browseId: String? = null,
    )

    /**
     * The live service reads `id` (server.py handle_toggle_favorite) while the documented
     * contract also mentions `video_id`, so both keys are sent and each server picks the
     * one it knows. Null metadata fields are omitted from the JSON by Moshi.
     */
    @JsonClass(generateAdapter = false)
    private data class FavoriteToggleRequest(
        val id: String,
        @Json(name = "video_id") val toggleVideoId: String,
        val title: String? = null,
        val artist: String? = null,
        val duration: String? = null,
        val thumbnail: String? = null,
    )

    @JsonClass(generateAdapter = false)
    private data class FavoriteToggleResponse(val status: String? = null, val favorited: Boolean? = null)

    // ---------------------------------------------------------- playlists DTOs

    /** GET /api/playlists envelope; `playlists` is the live key (server handle_get_playlists). */
    @JsonClass(generateAdapter = false)
    private data class PlaylistListEnvelope(
        val success: Boolean? = null,
        val playlists: List<PlaylistDto> = emptyList(),
        val error: String? = null,
    )

    @JsonClass(generateAdapter = false)
    private data class PlaylistDto(
        val id: String,
        val title: String,
        @Json(name = "created_at") val createdAt: String? = null,
        @Json(name = "track_count") val trackCount: Int = 0,
    ) {
        fun toModel(): Playlist = Playlist(id, title, createdAt.orEmpty(), trackCount)
    }

    /** POST /api/playlist/create body — the handler reads exactly one field, `title`. */
    @JsonClass(generateAdapter = false)
    private data class CreatePlaylistRequest(val title: String)

    @JsonClass(generateAdapter = false)
    private data class PlaylistCreatedResponse(val success: Boolean? = null, val id: String? = null, val title: String? = null, val error: String? = null)

    /**
     * The field is `id`, not `videoId`: server handle_add_playlist_track and
     * handle_remove_playlist_track both do `data.get("id")`, and a body without it is a
     * 400 `Invalid params`.
     */
    @JsonClass(generateAdapter = false)
    private data class PlaylistTrackRequest(val id: String)

    /** Shared answer of the mutating music endpoints: `success`/`error` or `status`/`error`. */
    @JsonClass(generateAdapter = false)
    private data class MutationResponse(val success: Boolean? = null, val status: String? = null, val error: String? = null)

    // ------------------------------------------------------------ history DTOs

    @JsonClass(generateAdapter = false)
    private data class HistoryEnvelope(
        val history: List<HistoryDto> = emptyList(),
        val success: Boolean? = null,
        val error: String? = null,
    )

    /** `id` here is the video id — the API key is `id`, never `video_id`. */
    @JsonClass(generateAdapter = false)
    private data class HistoryDto(
        val id: String,
        val title: String,
        val artist: String? = null,
        val duration: String? = null,
        val thumbnail: String? = null,
        @Json(name = "played_at") val playedAt: String? = null,
    ) {
        fun toModel(): HistoryEntry = HistoryEntry(
            id = id,
            title = title,
            artist = artist.orEmpty(),
            duration = duration?.takeIf { it.isNotBlank() } ?: "--:--",
            thumbnail = thumbnail,
            playedAt = playedAt.orEmpty(),
        )
    }

    /** POST /api/history/record body; without `id` the service answers 400 «Missing id». */
    @JsonClass(generateAdapter = false)
    private data class HistoryRecordRequest(
        val id: String,
        val title: String,
        val artist: String,
        val duration: String,
        val thumbnail: String? = null,
    )

    // ------------------------------------------------------ home/suggestions

    /**
     * Типизированный разбор полок GET /api/home. Живой сервер держит персональные
     * полки в ключе `shelf` (`{sections, generatedAt}`, server.py build_personal_shelf);
     * документированный контракт допускает те же полки прямо в верхнеуровневом
     * `sections` — приоритет у `shelf`, верхний уровень служит фолбэком.
     * `success:false` — ошибка (исключение); отсутствие секций — пустой список.
     */
    private fun parseHomeShelves(body: String): List<MusicShelf> {
        val parsed = moshi.adapter(HomeEnvelope::class.java).fromJson(body)
            ?: throw IOException("Empty home response")
        if (parsed.success == false) throw IllegalStateException(parsed.error ?: "Music request failed")
        val sections = parsed.shelf?.sections ?: parsed.sections
        return sections.map { it.toModel() }
    }

    /**
     * Разбор ответа GET /api/suggestions: `{"suggestions":["…",…]}` (сервер отдаёт
     * не больше 8 и пустой массив при любом сбое). Пустые/пробельные строки
     * отбрасываются, список ограничивается [MAX_SUGGESTIONS] со стороны клиента.
     */
    private fun parseSuggestions(body: String): List<String> {
        val parsed = moshi.adapter(SuggestionsEnvelope::class.java).fromJson(body)
            ?: throw IOException("Empty suggestions response")
        if (parsed.success == false) throw IllegalStateException(parsed.error ?: "Music request failed")
        return parsed.suggestions
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .take(MAX_SUGGESTIONS)
    }

    /**
     * GET /api/home envelope: персональные полки лежат в `shelf`, а верхнеуровневый
     * `sections` — кураторские миксы обратной совместимости (без `id`). Разбор
     * терпим к обоим формам; [ShelfDto.toModel] для миксов без `id` использует
     * заголовок как ключ.
     */
    @JsonClass(generateAdapter = false)
    private data class HomeEnvelope(
        val success: Boolean? = null,
        val shelf: ShelfPageDto? = null,
        val sections: List<ShelfDto> = emptyList(),
        val error: String? = null,
    )

    @JsonClass(generateAdapter = false)
    private data class ShelfPageDto(
        val sections: List<ShelfDto> = emptyList(),
        val generatedAt: Long? = null,
    )

    /** Одна полка; строки разбираются терпимо (те же фолбэки, что в [TrackDto.toModel]). */
    @JsonClass(generateAdapter = false)
    private data class ShelfDto(
        val id: String? = null,
        val title: String? = null,
        val items: List<ShelfTrackDto> = emptyList(),
    ) {
        fun toModel(): MusicShelf = MusicShelf(
            id = id?.takeIf { it.isNotBlank() } ?: title.orEmpty(),
            title = title.orEmpty(),
            items = items.mapNotNull { it.toModel() },
        )
    }

    /**
     * Трек полки с полностью необязательными полями: сервер собирает полки из БД и
     * радио-выдачи, где отдельные ключи могут отсутствовать. Строка без `id`
     * неиграбельна и отбрасывается ([toModel] → null), пустые поля получают те же
     * подписи-фолбэки, что и в [MusicChartLoader.parseHomePayload] мобильного модуля.
     */
    @JsonClass(generateAdapter = false)
    private data class ShelfTrackDto(
        val id: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val duration: String? = null,
        val thumbnail: String? = null,
        /**
         * Тот же `album`, что и в остальных выдачах ([AlbumRefDto]). Необязателен:
         * полки собираются из БД и радио, где ключа может не быть вовсе, — но если
         * сервер его отдал, трек не должен терять альбом только потому, что пришёл
         * не через [TrackDto].
         */
        val album: AlbumRefDto? = null,
    ) {
        fun toModel(): MusicTrack? {
            val trackId = id?.takeIf { it.isNotBlank() } ?: return null
            return MusicTrack(
                id = trackId,
                title = title?.takeIf { it.isNotBlank() } ?: "Без названия",
                artist = artist?.takeIf { it.isNotBlank() } ?: "Неизвестный артист",
                duration = duration?.takeIf { it.isNotBlank() } ?: "--:--",
                thumbnail = thumbnail?.takeIf { it.isNotBlank() },
                albumTitle = album?.name?.takeIf { it.isNotBlank() },
                albumBrowseId = album?.browseId?.takeIf { it.isNotBlank() },
            )
        }
    }

    /** Ответ GET /api/suggestions: `{"suggestions":["…",…]}`; `success:false` — отказ. */
    @JsonClass(generateAdapter = false)
    private data class SuggestionsEnvelope(
        val success: Boolean? = null,
        val suggestions: List<String> = emptyList(),
        val error: String? = null,
    )

    // ------------------------------------------------------------- lyrics DTOs

    /** POST /api/lyrics/translate body — one field, `text`. */
    @JsonClass(generateAdapter = false)
    private data class TranslateRequest(val text: String)

    /** An empty input is answered with `translated:""` and no `success` field at all. */
    @JsonClass(generateAdapter = false)
    private data class TranslateResponse(val success: Boolean? = null, val translated: String? = null, val error: String? = null)

    // -------------------------------------------------------- artist/album DTOs

    /**
     * GET /api/artist/{id_or_name} отвечает объектом БЕЗ конверта `success`:
     * `{name, channelId, radioId, description, subscribers, thumbnail, songs[]}`.
     * Песни — та же строка трека, что у поиска, поэтому разбираются терпимым
     * [ShelfTrackDto] (пустая `duration` становится «--:--»).
     */
    @JsonClass(generateAdapter = false)
    private data class ArtistDto(
        val name: String? = null,
        val channelId: String? = null,
        val radioId: String? = null,
        val description: String? = null,
        val subscribers: String? = null,
        val thumbnail: String? = null,
        val songs: List<ShelfTrackDto> = emptyList(),
    ) {
        fun toModel(fallbackName: String): MusicArtist = MusicArtist(
            name = name?.takeIf { it.isNotBlank() } ?: fallbackName,
            channelId = channelId?.takeIf { it.isNotBlank() },
            radioId = radioId?.takeIf { it.isNotBlank() },
            description = description?.takeIf { it.isNotBlank() },
            subscribers = subscribers?.takeIf { it.isNotBlank() },
            thumbnail = thumbnail?.takeIf { it.isNotBlank() },
            songs = songs.mapNotNull { it.toModel() },
        )
    }

    /** GET /api/album/{browseId}: `{"success":true,"album":{…,"tracks":[…]}}`. */
    @JsonClass(generateAdapter = false)
    private data class AlbumEnvelope(
        val success: Boolean? = null,
        val album: AlbumDto? = null,
        val error: String? = null,
    )

    /**
     * Тело альбома. `year` приходит строкой, но Moshi приводит к строке и число —
     * поэтому тип не ломается на `2024`. `trackCount` при отсутствии считается по
     * фактическому числу треков, чтобы счётчик в UI не врал нулём.
     */
    @JsonClass(generateAdapter = false)
    private data class AlbumDto(
        val title: String? = null,
        val artist: String? = null,
        val year: String? = null,
        val trackCount: Int? = null,
        val duration: String? = null,
        val thumbnail: String? = null,
        val description: String? = null,
        val tracks: List<ShelfTrackDto> = emptyList(),
    ) {
        fun toModel(browseId: String): MusicAlbum {
            val mapped = tracks.mapNotNull { it.toModel() }
            return MusicAlbum(
                browseId = browseId,
                title = title?.takeIf { it.isNotBlank() } ?: "Без названия",
                artist = artist?.takeIf { it.isNotBlank() } ?: "Неизвестный артист",
                year = year?.trim()?.takeIf { it.isNotEmpty() },
                trackCount = trackCount?.takeIf { it > 0 } ?: mapped.size,
                thumbnail = thumbnail?.takeIf { it.isNotBlank() },
                description = description?.takeIf { it.isNotBlank() },
                tracks = mapped,
            )
        }
    }

    /**
     * Ответ GET /api/search?type=albums: тот же конверт `{success, results}`, что у
     * поиска треков, но элемент — карточка альбома. `success:false` (с `error`) —
     * исключение, а не пустой список.
     */
    @JsonClass(generateAdapter = false)
    private data class AlbumSummaryListEnvelope(
        val success: Boolean? = null,
        val results: List<AlbumSummaryDto> = emptyList(),
        val error: String? = null,
    )

    /**
     * Карточка альбома из выдачи поиска:
     * `{type:"album", browseId:"MPREb_…", title, artist, year:"2024", thumbnail:"/music/api/thumb?…"}`.
     *
     * Все поля, кроме `browseId`, необязательны: сервер собирает выдачу из разных
     * источников. Строка без `browseId` с префиксом `MPRE` неиграбельна и
     * отбрасывается ([toModel] → null), пустые подписи получают те же фолбэки, что
     * и в остальных моделях музыки. `type` проверяется только когда он есть:
     * `type=albums` уже отфильтрован сервером, но карточка чужого типа в списке
     * альбомов — это мусор, который не должен открываться как альбом.
     */
    @JsonClass(generateAdapter = false)
    private data class AlbumSummaryDto(
        val type: String? = null,
        val browseId: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val year: String? = null,
        val thumbnail: String? = null,
    ) {
        fun toModel(): MusicAlbumSummary? {
            val id = browseId?.trim().orEmpty()
            if (!id.startsWith(ALBUM_BROWSE_ID_PREFIX)) return null
            val kind = type?.trim()?.lowercase()
            if (kind != null && kind.isNotEmpty() && kind != ALBUM_CARD_TYPE) return null
            return MusicAlbumSummary(
                browseId = id,
                title = title?.takeIf { it.isNotBlank() } ?: "Без названия",
                artist = artist?.takeIf { it.isNotBlank() } ?: "Неизвестный артист",
                year = year?.trim()?.takeIf { it.isNotEmpty() },
                // Резолвится в MusicRepository.fetchAlbumSummaries: origin живёт в
                // companion, а не в DTO — так правило одно на весь модуль.
                thumbnail = thumbnail?.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * Тело серверной ошибки: `{"error": "…"}` (его же отдаёт отказ по browseId).
     */
    @JsonClass(generateAdapter = false)
    private data class ErrorEnvelope(val error: String? = null)

    @JsonClass(generateAdapter = false)
    private data class Session(val track: TrackDto? = null, val queue: List<TrackDto> = emptyList(), val currentIndex: Int = 0, val currentTime: Double = 0.0, val volume: Double = 0.8, val isPlaying: Boolean = false, val activeDeviceId: String? = null, val timestamp: Long? = null) {
        fun toModel() = MusicPlayerState(track?.toModel(), currentTime, durationSeconds(track?.duration), if (isPlaying) PlaybackStatus.PLAYING else if (track != null) PlaybackStatus.PAUSED else PlaybackStatus.STOPPED, volume, queue.map { it.toModel() }, currentIndex, activeDeviceId, timestamp)
    }
    companion object {
        private const val NORMAL_CLOSURE = 1000
        private const val SESSION_RESTORE = "SESSION_RESTORE"
        private const val DEVICES_UPDATE = "DEVICES_UPDATE"
        internal const val DEFAULT_RESTORE_TIMEOUT_MS = 10_000L

        /** Живой сервер отдаёт не больше 8 подсказок; клиент дублирует предел. */
        internal const val MAX_SUGGESTIONS = 8

        /** browseId альбома обязан начинаться с этого префикса (иначе сервер отвечает отказом). */
        const val ALBUM_BROWSE_ID_PREFIX = "MPRE"

        /** Значение `type` у карточки альбома в выдаче `/api/search`. */
        private const val ALBUM_CARD_TYPE = "album"

        /** Значение query-параметра, переключающего поиск на альбомы. */
        private const val ALBUM_SEARCH_TYPE = "albums"

        /**
         * Корень сайта музыки: от него резолвятся относительные thumbnail
         * (`/music/api/thumb?…`), которые уже содержат префикс `/music`.
         */
        private const val SITE_ORIGIN = "https://marscore.ru"

        /** Любая схема URL (`data:`, `file:`, `javascript:`…) — такие обложки отбрасываются. */
        private val ANY_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

        /** Маркер серверного отказа «неверный browseId» в теле ответа. */
        private const val INVALID_BROWSE_ID_MARKER = "must start with MPRE"

        private const val HTTP_NOT_FOUND = 404

        /** Успешный диапазон кодов: 2xx. */
        private val HTTP_OK_RANGE = 200..299

        /**
         * Maps the HTTP(S) base URL onto the WebSocket scheme. Kotlin's
         * `String.replaceFirst(String, String)` is literal, not regex — the old
         * `"^http"` pattern never matched — so the schemes are mapped explicitly.
         */
        internal fun webSocketUrl(httpUrl: String): String = when {
            httpUrl.startsWith("http://") -> "ws://" + httpUrl.removePrefix("http://")
            httpUrl.startsWith("https://") -> "wss://" + httpUrl.removePrefix("https://")
            else -> httpUrl
        }

        private fun durationSeconds(value: String?): Double = value?.split(':')?.takeIf { it.size == 2 }?.let { it[0].toDoubleOrNull()?.times(60)?.plus(it[1].toDoubleOrNull() ?: 0.0) } ?: 0.0
    }
}
