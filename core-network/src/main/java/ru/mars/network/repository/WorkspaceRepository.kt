package ru.mars.network.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import ru.mars.network.http.HttpClients
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.WorkspaceItem
import ru.mars.network.model.WorkspacesResponse

/**
 * Workspace catalog of the WebUI backend (verified against api/routes.py):
 * - GET /api/workspaces — `{"workspaces": [{"path": ..., "name": ...}, ...], "last": "...", ...}`
 *
 * `last` is the server-side active workspace path; entries are marked
 * [WorkspaceItem.isDefault] when their path matches it (the wire format has no
 * per-entry default flag). Executes on [Dispatchers.IO]; failures map to null
 * (catalog unavailable) instead of throwing into UI coroutines.
 */
class WorkspaceRepository(private val client: OkHttpClient = HttpClients.rest) {

    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val moshi = Moshi.Builder().build()

    suspend fun getWorkspaces(baseUrl: String, credential: ConsumedDeviceCredential): WorkspacesResponse? =
        withContext(Dispatchers.IO) {
            val url = baseUrl.toHttpUrl().newBuilder().addPathSegments("api/workspaces").build().toString()
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
    fun parse(payload: String): WorkspacesResponse? {
        val root = parseMap(payload) ?: return null
        val last = root["last"]?.toString()?.takeIf { it.isNotBlank() }
        val items = (root["workspaces"] as? List<*>)?.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val path = map["path"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // Server labels entries with `name`; fall back to the path itself.
            val name = map["name"]?.toString()?.takeIf { it.isNotBlank() } ?: path
            WorkspaceItem(name = name, path = path, isDefault = path == last)
        }.orEmpty()
        return WorkspacesResponse(workspaces = items, last = last)
    }

    private fun parseMap(json: String): Map<String, Any?>? =
        try {
            moshi.adapter<Map<String, Any?>>(mapType).fromJson(json)
        } catch (_: Exception) {
            null
        }
}
