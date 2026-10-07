package ru.mars.network.repository

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
                            continuation.resume(parsed.results.map { item -> item.toModel() })
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

    @JsonClass(generateAdapter = false)
    private data class TrackListEnvelope(val success: Boolean? = null, val results: List<TrackDto> = emptyList(), val error: String? = null)
    @JsonClass(generateAdapter = false)
    private data class SessionEnvelope(val type: String? = null, val session: Session? = null, val globalSession: Session? = null)
    @JsonClass(generateAdapter = false)
    private data class TrackDto(val id: String, val title: String, val artist: String, val duration: String? = null, val thumbnail: String? = null) {
        fun toModel() = MusicTrack(id, title, artist, duration ?: "--:--", thumbnail)
    }
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
