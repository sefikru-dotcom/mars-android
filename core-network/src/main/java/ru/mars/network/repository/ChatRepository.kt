package ru.mars.network.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ConsumedDeviceCredential
import java.io.BufferedReader
import java.io.InputStreamReader

class ChatRepository(
    private val client: OkHttpClient = OkHttpClient()
) {
    fun sendMessage(endpoint: String, credential: ConsumedDeviceCredential, message: String): Flow<ChatEvent> = flow {
        val request = Request.Builder()
            .url(endpoint.toHttpUrl())
            .header("Authorization", "Bearer ${credential.accessToken}")
            .header("Accept", "text/event-stream")
            .post("{\"message\":${jsonString(message)}}".toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(ChatEvent.Error("HTTP ${response.code}"))
                    return@use
                }
                val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: return@use, Charsets.UTF_8))
                var eventName: String? = null
                val data = StringBuilder()
                suspend fun dispatch() {
                    if (data.isEmpty()) return
                    val payload = data.toString().removeSuffix("\n")
                    when (eventName) {
                        "done", "complete" -> emit(ChatEvent.Done)
                        "error" -> emit(ChatEvent.Error(payload))
                        else -> emit(ChatEvent.Chunk(parseText(payload)))
                    }
                    eventName = null
                    data.clear()
                }
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) { dispatch(); continue }
                    when {
                        line.startsWith("event:") -> eventName = line.substringAfter(':').trim()
                        line.startsWith("data:") -> data.append(line.substringAfter(':').trimStart()).append('\n')
                        line.startsWith(":") -> Unit
                    }
                }
                dispatch()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emit(ChatEvent.Error(error.message ?: "Network error"))
        }
    }.flowOn(Dispatchers.IO)

    private fun parseText(payload: String): String {
        val trimmed = payload.trim()
        return if (trimmed.startsWith("{") && trimmed.contains("\"text\"")) {
            Regex("\\\"text\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").find(trimmed)?.groupValues?.get(1)
                ?.replace("\\\\", "\\").orEmpty()
        } else trimmed
    }

    private fun jsonString(value: String) = buildString {
        append('"')
        value.forEach { when (it) { '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t"); else -> append(it) } }
        append('"')
    }
}
