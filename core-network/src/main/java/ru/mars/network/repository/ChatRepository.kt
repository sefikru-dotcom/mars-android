package ru.mars.network.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ChatStartResponse
import ru.mars.network.model.ConsumedDeviceCredential
import java.io.BufferedReader
import java.io.InputStreamReader

class ChatRepository(private val client: OkHttpClient = OkHttpClient()) {
    fun sendMessage(
        endpoint: String,
        credential: ConsumedDeviceCredential,
        message: String,
        sessionId: String? = null
    ): Flow<ChatEvent> = flow {
        val base = endpoint.toHttpUrl()
        try {
            if (base.encodedPath == "/chat") {
                stream(requestStart(base, credential, message, sessionId), credential) { event -> emit(event) }
                return@flow
            }
            val startResp = client.newCall(requestStart(base, credential, message, sessionId)).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(ChatEvent.Error("HTTP ${response.code}"))
                    return@flow
                }
                extractStartResponse(response.body?.string().orEmpty())
                    ?: run { emit(ChatEvent.Error("Missing stream_id")); return@flow }
            }
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
        sessionId: String? = null
    ): Request {
        val url = if (base.encodedPath == "/chat") base else base.newBuilder().addPathSegments("api/chat/start").build()
        val jsonBody = buildString {
            append("{\"message\":").append(jsonString(message))
            if (!sessionId.isNullOrBlank()) {
                append(",\"session_id\":").append(jsonString(sessionId))
            }
            append("}")
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
                    "done", "stream_end", "cancel" -> emit(ChatEvent.Done)
                    "error", "apperror" -> emit(ChatEvent.Error(parseError(payload)))
                    "token", "delta", "chunk", "message", null -> {
                        val text = parseTokenText(payload)
                        if (text.isNotEmpty()) emit(ChatEvent.Chunk(text, id))
                    }
                    else -> {
                        // Other events (metering, tool, reasoning) are safely ignored
                    }
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
        return try {
            val obj = JSONObject(json)
            val streamId = obj.optString("stream_id").takeIf { it.isNotBlank() } ?: return null
            val sessionId = obj.optString("session_id").takeIf { it.isNotBlank() }
            ChatStartResponse(streamId, sessionId)
        } catch (_: Throwable) {
            val sid = Regex("\\\"stream_id\\\"\\s*:\\s*\\\"([^\"]+)\\\"").find(json)?.groupValues?.get(1) ?: return null
            val sess = Regex("\\\"session_id\\\"\\s*:\\s*\\\"([^\"]+)\\\"").find(json)?.groupValues?.get(1)
            ChatStartResponse(sid, sess)
        }
    }

    private fun parseTokenText(payload: String): String {
        return try {
            val obj = JSONObject(payload)
            when {
                obj.has("text") -> obj.getString("text")
                obj.has("delta") -> obj.getString("delta")
                obj.has("content") -> obj.getString("content")
                else -> ""
            }
        } catch (_: Throwable) {
            Regex("\\\"text\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").find(payload)?.groupValues?.get(1)?.replace("\\\\", "\\") ?: ""
        }
    }

    private fun parseError(payload: String): String {
        return try {
            val obj = JSONObject(payload)
            obj.optString("message").ifBlank { obj.optString("error", payload) }
        } catch (_: Throwable) {
            payload.trim()
        }
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
