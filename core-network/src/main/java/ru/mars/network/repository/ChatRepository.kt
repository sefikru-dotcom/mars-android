package ru.mars.network.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ChatAttachment
import ru.mars.network.model.ChatStartResponse
import ru.mars.network.model.ConsumedDeviceCredential
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Client-side mirror of the server upload cap. api/upload.py `handle_upload`
 * rejects a Content-Length above MAX_UPLOAD_BYTES (env
 * HERMES_WEBUI_MAX_UPLOAD_MB, default 20) with
 * HTTP 413 `{"error": "File too large (max 20MB)"}`.
 */
const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024

/** Thrown by [readBoundedUploadPayload] as soon as a stream exceeds the cap. */
class UploadTooLargeException(val maxBytes: Long) :
    Exception("File too large (max ${maxBytes / (1024 * 1024)}MB)")

/** Outcome of `POST /api/upload` — an explicit result instead of a silent null. */
sealed interface UploadOutcome {
    data class Success(val attachment: ChatAttachment) : UploadOutcome
    /** Payload exceeds the server limit: caught client-side pre-flight or via HTTP 413. */
    data class TooLarge(val message: String) : UploadOutcome
    /** Any other failure: HTTP status, malformed response body, network error. */
    data class Failure(val message: String) : UploadOutcome
}

/**
 * Reads at most [maxBytes] from [input] in bounded chunks — never an
 * unbounded `readBytes()` of an unknown-size stream. Throws
 * [UploadTooLargeException] as soon as the cap is crossed (detected on the
 * `maxBytes + 1`-th byte), so an oversized file is rejected without being
 * fully read or buffered.
 */
fun readBoundedUploadPayload(input: InputStream, maxBytes: Long = MAX_UPLOAD_BYTES): ByteArray {
    require(maxBytes >= 0) { "maxBytes must be >= 0" }
    val chunkSize = 64 * 1024
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(chunkSize)
    var total = 0L
    while (true) {
        val room = maxBytes + 1 - total
        if (room <= 0) throw UploadTooLargeException(maxBytes)
        val read = input.read(chunk, 0, minOf(chunkSize.toLong(), room).toInt())
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) throw UploadTooLargeException(maxBytes)
        out.write(chunk, 0, read)
    }
    return out.toByteArray()
}

class ChatRepository(private val client: OkHttpClient = OkHttpClient()) {
    fun sendMessage(
        endpoint: String,
        credential: ConsumedDeviceCredential,
        message: String,
        sessionId: String? = null,
        attachments: List<ChatAttachment> = emptyList(),
        model: String? = null,
        modelProvider: String? = null,
        explicitModelPick: Boolean = false
    ): Flow<ChatEvent> = flow {
        val base = endpoint.toHttpUrl()
        try {
            if (base.encodedPath == "/chat") {
                stream(requestStart(base, credential, message, sessionId, attachments, model, modelProvider, explicitModelPick), credential) { event -> emit(event) }
                return@flow
            }
            val startResp = client.newCall(requestStart(base, credential, message, sessionId, attachments, model, modelProvider, explicitModelPick)).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(ChatEvent.Error("HTTP ${response.code}"))
                    return@flow
                }
                extractStartResponse(response.body?.string().orEmpty())
                    ?: run { emit(ChatEvent.Error("Missing stream_id")); return@flow }
            }
            startResp.streamId.let { emit(ChatEvent.StreamStarted(it)) }
            startResp.sessionId?.let { emit(ChatEvent.SessionBound(it)) }

            val streamUrl = base.newBuilder().addPathSegments("api/chat/stream")
                .addQueryParameter("stream_id", startResp.streamId).build()
            stream(Request.Builder().url(streamUrl).header("Authorization", bearer(credential))
                .header("Accept", "text/event-stream").get().build(), credential) { event -> emit(event) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // Sudden link losses (Wi-Fi <-> LTE handover, DNS/TLS failure, read timeout)
            // must surface as a readable ChatEvent.Error — otherwise the UI hangs in the
            // "waiting for answer" state with no way out.
            emit(ChatEvent.Error(describeNetworkError(error)))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Uploads a file to `POST /api/upload` (multipart: `session_id` + `file`) and maps the
     * server contract from api/upload.py onto an explicit [UploadOutcome]:
     * 200 `{filename, path, size, mime, is_image}` → [UploadOutcome.Success];
     * 413 `{"error": "File too large (max 20MB)"}` → [UploadOutcome.TooLarge] with the server
     * message; any other non-2xx → [UploadOutcome.Failure]. Oversized payloads are rejected
     * client-side against [MAX_UPLOAD_BYTES] before any network call. Runs on Dispatchers.IO —
     * the synchronous OkHttp execute never happens on the caller's (Main) thread.
     */
    suspend fun uploadAttachment(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
        sessionId: String?
    ): UploadOutcome = withContext(Dispatchers.IO) {
        if (bytes.size.toLong() > MAX_UPLOAD_BYTES) {
            return@withContext UploadOutcome.TooLarge("File too large (max ${MAX_UPLOAD_BYTES / (1024 * 1024)}MB)")
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .apply { if (sessionId != null) addFormDataPart("session_id", sessionId) }
            .addFormDataPart("file", filename, bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        val request = Request.Builder().url(baseUrl.toHttpUrl().newBuilder().addPathSegments("api/upload").build())
            .header("Authorization", bearer(credential)).post(multipart).build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val serverMessage = StreamEventMapper.parseObject(body)
                        ?.get("error")?.toString()?.takeIf { it.isNotBlank() }
                    return@use if (response.code == 413) {
                        UploadOutcome.TooLarge(serverMessage ?: "File too large (max ${MAX_UPLOAD_BYTES / (1024 * 1024)}MB)")
                    } else {
                        UploadOutcome.Failure(serverMessage ?: "HTTP ${response.code}")
                    }
                }
                val obj = StreamEventMapper.parseObject(body)
                    ?: return@use UploadOutcome.Failure("Malformed upload response")
                val path = obj["path"]?.toString().orEmpty()
                if (path.isEmpty()) return@use UploadOutcome.Failure("Upload response missing path")
                UploadOutcome.Success(
                    ChatAttachment(
                        obj["filename"]?.toString().orEmpty(),
                        path,
                        obj["mime"]?.toString().orEmpty(),
                        (obj["size"] as? Number)?.toLong(),
                        obj["is_image"] as? Boolean
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            UploadOutcome.Failure(describeNetworkError(error))
        }
    }

    /**
     * Executes a control request (cancel/steer). A 2xx alone is NOT success: `/api/chat/steer`
     * answers 200 with `{"accepted": false, "fallback": ...}` when the steer was not applied,
     * which must be reported as failure. Responses without an `accepted` field (e.g. cancel's
     * `{"ok": true, "cancelled": ...}`) keep the legacy "HTTP status is the result" behaviour.
     */
    fun executeControl(request: Request): Boolean = client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@use false
        val body = response.body?.string().orEmpty()
        StreamEventMapper.parseObject(body)?.get("accepted")?.let { it != false } ?: true
    }

    fun cancelRequest(baseUrl: String, streamId: String, accessToken: String? = null): Request =
        Request.Builder().url(baseUrl.toHttpUrl().newBuilder().addPathSegments("api/chat/cancel")
            .addQueryParameter("stream_id", streamId).build())
            .apply { if (accessToken != null) header("Authorization", "Bearer $accessToken") }.get().build()

    /**
     * Builds `POST /api/chat/steer`. The server contract (`_handle_chat_steer`) requires JSON
     * fields `session_id` and `text` — NOT `stream_id`/`message`, which it silently rejects
     * with `{"accepted": false, "fallback": "no_cached_agent"}`.
     */
    fun steerRequest(baseUrl: String, sessionId: String, message: String, accessToken: String): Request =
        Request.Builder().url(baseUrl.toHttpUrl().newBuilder().addPathSegments("api/chat/steer").build())
            .header("Authorization", "Bearer $accessToken").header("Content-Type", "application/json")
            .post("{\"session_id\":${jsonString(sessionId)},\"text\":${jsonString(message)}}"
                .toRequestBody("application/json".toMediaType())).build()

    private fun requestStart(
        base: HttpUrl,
        credential: ConsumedDeviceCredential,
        message: String,
        sessionId: String? = null,
        attachments: List<ChatAttachment> = emptyList(),
        model: String? = null,
        modelProvider: String? = null,
        explicitModelPick: Boolean = false
    ): Request {
        val url = if (base.encodedPath == "/chat") base else base.newBuilder().addPathSegments("api/chat/start").build()
        val jsonBody = buildString {
            append("{\"message\":").append(jsonString(message))
            if (!sessionId.isNullOrBlank()) append(",\"session_id\":").append(jsonString(sessionId))
            // Server `_handle_chat_start` reads body["model"] / body["model_provider"]
            // (falling back to session state when absent) and body["explicit_model_pick"]
            // to record a deliberate user pick (#5979). Only send fields we actually have —
            // an omitted key lets the server keep its session/default routing.
            if (!model.isNullOrBlank()) append(",\"model\":").append(jsonString(model))
            if (!modelProvider.isNullOrBlank()) append(",\"model_provider\":").append(jsonString(modelProvider))
            if (!model.isNullOrBlank() && explicitModelPick) append(",\"explicit_model_pick\":true")
            append(",\"attachments\":[")
            attachments.joinTo(this, separator = ",") { attachment ->
                "{\"name\":${jsonString(attachment.name)},\"path\":${jsonString(attachment.path)},\"mime\":${jsonString(attachment.mime)},\"size\":${attachment.size?.toString() ?: "null"},\"is_image\":${attachment.isImage?.toString() ?: "null"}}"
            }
            append("]}")
        }
        return Request.Builder().url(url).header("Authorization", bearer(credential))
            .header("Accept", "text/event-stream")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
    }

    /**
     * Streams an SSE response body chunk-by-chunk through [SseChunkParser].
     *
     * Resilience contract (POCO F5 Wi-Fi/LTE handovers):
     * - the body is read in transport-sized chunks and fed to the parser verbatim: an SSE
     *   frame split across TCP packets ANYWHERE (mid-line, between `\r` and `\n`, between
     *   two `data:` lines) is reassembled from the parser's internal buffer — no partial
     *   state is ever dropped;
     * - multi-byte UTF-8 sequences split across chunks are handled by the InputStreamReader
     *   decoder (never decode a raw byte chunk to String directly);
     * - if the connection dies mid-frame, the IOException propagates to [sendMessage], which
     *   maps it via [describeNetworkError] and emits a terminal ChatEvent.Error so the UI
     *   leaves the waiting state;
     * - cancellation is checked between reads so a cancelled collector does not block on
     *   the socket read loop.
     */
    private suspend fun stream(request: Request, credential: ConsumedDeviceCredential, emit: suspend (ChatEvent) -> Unit) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) { emit(ChatEvent.Error("HTTP ${response.code}")); return }
            val byteStream = response.body?.byteStream()
            if (byteStream == null) {
                // A 200 with no body must not leave the UI waiting forever.
                emit(ChatEvent.Error("Пустой ответ сервера"))
                return
            }
            val parser = SseChunkParser()
            suspend fun emitFrames(frames: List<SseChunkParser.Frame>) {
                for (frame in frames) {
                    StreamEventMapper.map(frame.event, frame.id, frame.data)?.let { emit(it) }
                }
            }
            val reader = InputStreamReader(byteStream, Charsets.UTF_8)
            val chars = CharArray(8 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = reader.read(chars)
                if (read < 0) break
                if (read > 0) emitFrames(parser.feed(String(chars, 0, read)))
            }
            // Stream ended without the terminating blank line (server closed early, or the
            // last frame lacks it): still deliver the buffered frame.
            emitFrames(parser.end())
        }
    }

    private fun extractStartResponse(json: String): ChatStartResponse? {
        val obj = StreamEventMapper.parseObject(json) ?: return null
        val streamId = obj["stream_id"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return ChatStartResponse(streamId, obj["session_id"]?.toString()?.takeIf { it.isNotBlank() })
    }

    private fun bearer(credential: ConsumedDeviceCredential) = "Bearer ${credential.accessToken}"
    private fun jsonString(value: String) = buildString {
        append('"')
        value.forEach {
            when (it) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(it)
            }
        }
        append('"')
    }
}
