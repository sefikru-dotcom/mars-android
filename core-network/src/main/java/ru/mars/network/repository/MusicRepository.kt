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
import ru.mars.network.model.LyricsResult
import ru.mars.network.model.MusicPlayerState
import ru.mars.network.model.MusicTrack
import ru.mars.network.model.PlaybackStatus
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Mars Music's HTTP search/charts and Mars Connect WebSocket client. */
class MusicRepository(
    private val baseUrl: String = "https://marscore.ru/music",
    private val client: OkHttpClient = OkHttpClient(),
    private val deviceId: String = "android-client",
    private val restoreTimeoutMs: Long = DEFAULT_RESTORE_TIMEOUT_MS,
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val envelopeAdapter = moshi.adapter(SessionEnvelope::class.java)
    private val listAdapter = moshi.adapter(TrackListEnvelope::class.java)

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

    fun getStreamUrl(trackId: String): String = "$baseUrl/api/stream/$trackId"

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

    /** POSTs a JSON body to [path] and maps the successful response through [parse]. */
    private suspend fun <T> postJson(path: String, json: String, parse: (ResponseBody) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url("$baseUrl/$path")
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
            val sent = webSocket.send("{\"type\":\"REGISTER\",\"id\":\"$deviceId\",\"name\":\"Android\",\"deviceType\":\"mobile\",\"isActive\":false,\"state\":{}}")
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
    private data class TrackDto(val id: String, val title: String, val artist: String, val duration: String? = null, val thumbnail: String? = null) {
        // Favorites rows recorded without metadata carry an empty duration string.
        fun toModel(): MusicTrack = MusicTrack(id, title, artist, duration?.takeIf { it.isNotBlank() } ?: "--:--", thumbnail)
    }

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
    @JsonClass(generateAdapter = false)
    private data class Session(val track: TrackDto? = null, val queue: List<TrackDto> = emptyList(), val currentIndex: Int = 0, val currentTime: Double = 0.0, val volume: Double = 0.8, val isPlaying: Boolean = false, val activeDeviceId: String? = null) {
        fun toModel() = MusicPlayerState(track?.toModel(), currentTime, durationSeconds(track?.duration), if (isPlaying) PlaybackStatus.PLAYING else if (track != null) PlaybackStatus.PAUSED else PlaybackStatus.STOPPED, volume, queue.map { it.toModel() }, currentIndex, activeDeviceId)
    }
    companion object {
        private const val NORMAL_CLOSURE = 1000
        private const val SESSION_RESTORE = "SESSION_RESTORE"
        internal const val DEFAULT_RESTORE_TIMEOUT_MS = 10_000L

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
