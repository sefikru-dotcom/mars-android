package ru.mars.network.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.mars.network.http.HttpClients
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.MemoryContent

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

    private fun parseMap(json: String): Map<String, Any?>? =
        try {
            moshi.adapter<Map<String, Any?>>(mapType).fromJson(json)
        } catch (_: Exception) {
            null
        }

    private companion object {
        /** Missing/null/blank sections collapse to "" — the model never carries nulls. */
        fun stringField(root: Map<String, Any?>, key: String): String =
            root[key]?.toString()?.takeIf(String::isNotBlank).orEmpty()
    }
}
