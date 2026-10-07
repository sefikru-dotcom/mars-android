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
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
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
            val obj = parseObject(response.body?.string().orEmpty()) ?: return null
            ChatAttachment(obj["filename"]?.toString().orEmpty(), obj["path"]?.toString().orEmpty(), obj["mime"]?.toString().orEmpty(), (obj["size"] as? Number)?.toLong(), obj["is_image"] as? Boolean)
        }
    }

    fun executeControl(request: Request): Boolean = client.newCall(request).execute().use { it.isSuccessful }

    fun cancelRequest(baseUrl: String, streamId: String, accessToken: String? = null): Request =
        Request.Builder().url(baseUrl.toHttpUrl().newBuilder().addPathSegments("api/chat/cancel")
            .addQueryParameter("stream_id", streamId).build())
            .apply { if (accessToken != null) header("Authorization", "Bearer $accessToken") }.get().build()

    fun steerRequest(baseUrl: String, streamId: String, message: String, accessToken: String): Request =
        Request.Builder().url(baseUrl.toHttpUrl().newBuilder().addPathSegments("api/chat/steer").build())
            .header("Authorization", "Bearer $accessToken").header("Content-Type", "application/json")
            .post("{\"stream_id\":${jsonString(streamId)},\"message\":${jsonString(message)}}"
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
                when (event) {
                    "session", "session_bound" -> parseSessionId(payload)?.let { emit(ChatEvent.SessionBound(it)) }
                    "done", "stream_end", "cancel" -> emit(ChatEvent.Done)
                    "error", "apperror" -> emit(ChatEvent.Error(parseError(payload)))
                    "token", "delta", "chunk", "message", null -> {
                        val text = parseTokenText(payload)
                        if (text.isNotEmpty()) emit(ChatEvent.Chunk(text, id))
                    }
                    "reasoning", "thought", "thinking" -> parseText(payload)?.let { emit(ChatEvent.Thought(it)) }
                    "tool_call", "tool" -> parseToolCall(payload)?.let { emit(it) }
                    "tool_result" -> parseToolResult(payload)?.let { emit(it) }
                    "status", "phase" -> parsePhase(payload)?.let { emit(it) }
                    else -> Unit
                }
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
        val obj = parseObject(json) ?: return null
        val streamId = obj["stream_id"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        return ChatStartResponse(streamId, obj["session_id"]?.toString()?.takeIf { it.isNotBlank() })
    }

    private fun parseTokenText(payload: String): String {
        val obj = parseObject(payload)
        return listOf("text", "delta", "content").firstNotNullOfOrNull { obj?.get(it)?.toString() } ?: ""
    }

    private fun parseError(payload: String): String {
        val obj = parseObject(payload)
        return obj?.get("message")?.toString()?.takeIf { it.isNotBlank() } ?: obj?.get("error")?.toString()?.takeIf { it.isNotBlank() } ?: payload.trim()
    }

    private fun parseSessionId(payload: String): String? = parseObject(payload)?.let { m ->
        (m["session_id"] ?: m["id"])?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun parseText(payload: String): String? = parseObject(payload)?.let { m ->
        listOf("text", "content", "message", "thought", "reasoning").firstNotNullOfOrNull { k -> m[k]?.toString()?.takeIf { it.isNotBlank() } }
    } ?: payload.takeIf { it.isNotBlank() }

    private fun parseToolCall(payload: String): ChatEvent.ToolCall? = parseObject(payload)?.let { m ->
        ChatEvent.ToolCall(m["id"]?.toString(), (m["name"] ?: m["tool"])?.toString().orEmpty(), jsonValue(m["arguments"] ?: m["args"]))
    }

    private fun parseToolResult(payload: String): ChatEvent.ToolResult? = parseObject(payload)?.let { m ->
        ChatEvent.ToolResult(m["id"]?.toString(), (m["name"] ?: m["tool"])?.toString().orEmpty(), jsonValue(m["result"] ?: m["content"]))
    }

    private fun parsePhase(payload: String): ChatEvent.Phase? = parseObject(payload)?.let { m ->
        ChatEvent.Phase((m["name"] ?: m["phase"] ?: m["status"])?.toString().orEmpty().ifBlank { payload })
    } ?: payload.takeIf { it.isNotBlank() }?.let(ChatEvent::Phase)

    private fun parseObject(payload: String): Map<String, Any?>? = try {
        @Suppress("UNCHECKED_CAST")
        Moshi.Builder().build().adapter<Map<String, Any?>>(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)).fromJson(payload)
    } catch (_: Throwable) { null }

    private fun jsonValue(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        else -> Moshi.Builder().build().adapter(Any::class.java).toJson(value)
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
