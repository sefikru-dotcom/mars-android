package ru.mars.network.repository

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import ru.mars.network.model.MusicPlayerState
import ru.mars.network.model.MusicTrack
import ru.mars.network.model.PlaybackStatus
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Mars Music's HTTP search/charts and Mars Connect WebSocket client. */
class MusicRepository(
    private val baseUrl: String = "https://marscore.ru/music",
    private val client: OkHttpClient = OkHttpClient(),
    private val deviceId: String = "android-client",
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

    private sealed interface WsAction {
        object Status : WsAction
        data class Command(val action: String, val payload: String) : WsAction
    }

    /**
     * Registers over Mars Connect and waits for the server's `SESSION_RESTORE`.
     * - Status: resumes with the restored session state.
     * - Command: sends `CONTROL_CMD` targeted at the session's `activeDeviceId`
     *   (broadcasting to `all` would toggle every paired device), then closes the
     *   socket gracefully with code 1000 instead of cancelling mid-flight.
     */
    private suspend fun connect(action: WsAction): MusicPlayerState {
        val wsUrl = baseUrl.toHttpUrl().newBuilder().addPathSegments("api/connect/ws").build()
            .toString().replaceFirst("^http", "ws")
        return suspendCancellableCoroutine { continuation ->
            lateinit var socket: WebSocket
            socket = client.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    webSocket.send("{\"type\":\"REGISTER\",\"id\":\"$deviceId\",\"name\":\"Android\",\"deviceType\":\"mobile\",\"isActive\":false,\"state\":{}}")
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val envelope = try { envelopeAdapter.fromJson(text) } catch (e: Throwable) { null } ?: return
                    val session = envelope.session ?: envelope.globalSession ?: return
                    when (action) {
                        WsAction.Status -> {
                            if (continuation.isActive) continuation.resume(session.toModel())
                            webSocket.close(NORMAL_CLOSURE, "done")
                        }
                        is WsAction.Command -> {
                            val target = session.activeDeviceId?.takeIf { it.isNotBlank() } ?: "all"
                            webSocket.send("{\"type\":\"CONTROL_CMD\",\"targetDeviceId\":\"$target\",\"action\":\"${action.action}\",\"payload\":${action.payload}}")
                            if (continuation.isActive) continuation.resume(session.toModel())
                            webSocket.close(NORMAL_CLOSURE, "done")
                        }
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // Server closed first: echo the close frame so the handshake completes.
                    webSocket.close(NORMAL_CLOSURE, null)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) { if (continuation.isActive) continuation.resumeWithException(t) }
            })
            continuation.invokeOnCancellation { socket.cancel() }
        }
    }

    @JsonClass(generateAdapter = false)
    private data class TrackListEnvelope(val success: Boolean? = null, val results: List<TrackDto> = emptyList(), val error: String? = null)
    @JsonClass(generateAdapter = false)
    private data class SessionEnvelope(val session: Session? = null, val globalSession: Session? = null)
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
        private fun durationSeconds(value: String?): Double = value?.split(':')?.takeIf { it.size == 2 }?.let { it[0].toDoubleOrNull()?.times(60)?.plus(it[1].toDoubleOrNull() ?: 0.0) } ?: 0.0
    }
}
