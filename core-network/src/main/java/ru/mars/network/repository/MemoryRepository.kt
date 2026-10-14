package ru.mars.network.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.mars.network.http.HttpClients
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.MemoryContent

/**
 * Исход записи секции памяти (`POST /api/memory/write`, api/route_memory.py
 * `_handle_memory_write`). [Saved.section] — echo сервера (`memory|user|soul`);
 * [Failed.message] — `error` из тела ответа (400/403/404/…) либо `HTTP <code>`.
 */
sealed interface MemoryWriteResult {
    data class Saved(val section: String) : MemoryWriteResult
    data class Failed(val message: String) : MemoryWriteResult
}

/**
 * Memory sections endpoint of the WebUI backend (contract from api/routes.py):
 * - GET /api/memory — `{"memory": "...", "user": "...", "soul": "...", "project_context": "..."}`
 *
 * Sections are plain markdown/text blobs; any of them may be missing or null on
 * the wire and collapses to "" client-side. Executes on [Dispatchers.IO];
 * failures map to null (memory unavailable) instead of throwing into UI
 * coroutines — same convention as [WorkspaceRepository].
 */
class MemoryRepository(private val client: OkHttpClient = HttpClients.rest) {

    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val moshi = Moshi.Builder().build()

    suspend fun getMemory(baseUrl: String, credential: ConsumedDeviceCredential): MemoryContent? =
        withContext(Dispatchers.IO) {
            val url = baseUrl.toHttpUrl().newBuilder().addPathSegments("api/memory").build().toString()
            val text = try {
                client.newCall(
                    Request.Builder().url(url)
                        .header("Authorization", "Bearer ${credential.accessToken}")
                        .get().build()
                ).execute().use { if (it.isSuccessful) it.body?.string() else null }
            } catch (_: Exception) {
                null
            } ?: return@withContext null
            parse(text)
        }

    /** Pure response parser — public for unit tests without a socket. */
    fun parse(payload: String): MemoryContent? {
        val root = parseMap(payload) ?: return null
        return MemoryContent(
            memory = stringField(root, "memory"),
            user = stringField(root, "user"),
            soul = stringField(root, "soul"),
            projectContext = stringField(root, "project_context"),
        )
    }

    // --- Запись секции: POST /api/memory/write (route_memory.py `_handle_memory_write`) --------

    /**
     * Полная перезапись секции: `{"section": ..., "content": ...}`. Сервер принимает только
     * `memory|user|soul` (иначе 400), пустой content — тоже 400 (`require`, helpers.py:41),
     * поэтому blank-ввод не уходит в сеть. Ответ 200 `{"ok": true, "section": ...}`;
     * 403 (секция отключена конфигом / symlink / ro-файл) и прочие — [MemoryWriteResult.Failed]
     * с серверным `error`.
     */
    suspend fun writeSection(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        section: String,
        content: String,
    ): MemoryWriteResult {
        if (section.isBlank() || content.isBlank()) {
            return MemoryWriteResult.Failed("Missing required field(s): section, content")
        }
        val response = post(baseUrl, credential, "api/memory/write", writeBody(section, content))
            ?: return MemoryWriteResult.Failed("Network error")
        val root = response.second?.let(::parseMap)
        val error = root?.get("error")?.toString()?.takeIf(String::isNotBlank)
        return if (response.first in 200..299 && root?.get("ok") == true) {
            MemoryWriteResult.Saved(root["section"]?.toString().orEmpty().ifBlank { section })
        } else {
            MemoryWriteResult.Failed(error ?: "HTTP ${response.first}")
        }
    }

    /** Чистая сборка тела `POST /api/memory/write` — public для unit-тестов без сокета. */
    fun writeBody(section: String, content: String): String =
        "{\"section\":${JsonStringEncoding.escape(section)},\"content\":${JsonStringEncoding.escape(content)}}"

    /** Чистый парсер ответа мутации (`{"ok": true, ...}`) — public для unit-тестов. */
    fun parseWriteOk(payload: String): Boolean = parseMap(payload)?.get("ok") == true

    /** Чистый парсер `{"error": ...}` — public для unit-тестов. */
    fun parseError(payload: String): String? =
        parseMap(payload)?.get("error")?.toString()?.takeIf(String::isNotBlank)

    /** POST с JSON-телом; null — IOException. Возвращает (HTTP-код, тело). */
    private suspend fun post(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        path: String,
        body: String,
    ): Pair<Int, String?>? = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegments(path).build().toString()
        try {
            client.newCall(
                Request.Builder().url(url)
                    .header("Authorization", "Bearer ${credential.accessToken}")
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().use { it.code to it.body?.string() }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseMap(json: String): Map<String, Any?>? =
        try {
            moshi.adapter<Map<String, Any?>>(mapType).fromJson(json)
        } catch (_: Exception) {
            null
        }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** Missing/null/blank sections collapse to "" — the model never carries nulls. */
        fun stringField(root: Map<String, Any?>, key: String): String =
            root[key]?.toString()?.takeIf(String::isNotBlank).orEmpty()
    }
}
