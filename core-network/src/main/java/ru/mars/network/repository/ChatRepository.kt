package ru.mars.network.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
import java.io.BufferedReader
import java.io.InputStreamReader

class ChatRepository(private val client: OkHttpClient = OkHttpClient()) {
    fun sendMessage(
        endpoint: String,
        credential: ConsumedDeviceCredential,
        message: String,
        sessionId: String? = null,
        attachments: List<ChatAttachment> = emptyList()
    ): Flow<ChatEvent> = flow {
        val base = endpoint.toHttpUrl()
        try {
            if (base.encodedPath == "/chat") {
                stream(requestStart(base, credential, message, sessionId, attachments), credential) { event -> emit(event) }
                return@flow
            }
            val startResp = client.newCall(requestStart(base, credential, message, sessionId, attachments)).execute().use { response ->
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
            emit(ChatEvent.Error(error.message ?: "Network error"))
        }
    }.flowOn(Dispatchers.IO)

    fun uploadAttachment(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
        sessionId: String?
    ): ChatAttachment? {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .apply { if (sessionId != null) addFormDataPart("session_id", sessionId) }
            .addFormDataPart("file", filename, bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        val request = Request.Builder().url(baseUrl.toHttpUrl().newBuilder().addPathSegments("api/upload").build())
            .header("Authorization", bearer(credential)).post(multipart).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val obj = StreamEventMapper.parseObject(response.body?.string().orEmpty()) ?: return null
            ChatAttachment(obj["filename"]?.toString().orEmpty(), obj["path"]?.toString().orEmpty(), obj["mime"]?.toString().orEmpty(), (obj["size"] as? Number)?.toLong(), obj["is_image"] as? Boolean)
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
        attachments: List<ChatAttachment> = emptyList()
    ): Request {
        val url = if (base.encodedPath == "/chat") base else base.newBuilder().addPathSegments("api/chat/start").build()
        val jsonBody = buildString {
            append("{\"message\":").append(jsonString(message))
            if (!sessionId.isNullOrBlank()) append(",\"session_id\":").append(jsonString(sessionId))
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

    private suspend fun stream(request: Request, credential: ConsumedDeviceCredential, emit: suspend (ChatEvent) -> Unit) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) { emit(ChatEvent.Error("HTTP ${response.code}")); return }
            val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: return, Charsets.UTF_8))
            var event: String? = null
            var id: String? = null
            val data = StringBuilder()

            suspend fun dispatch() {
                if (data.isEmpty()) return
                val payload = data.toString().removeSuffix("\n")
                StreamEventMapper.map(event, id, payload)?.let { emit(it) }
                event = null; id = null; data.clear()
            }

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) { dispatch(); continue }
                when {
                    line.startsWith("event:") -> event = line.substringAfter(':').trim()
                    line.startsWith("id:") -> id = line.substringAfter(':').trim()
                    line.startsWith("data:") -> data.append(line.substringAfter(':').trimStart()).append('\n')
                }
            }
            dispatch()
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
