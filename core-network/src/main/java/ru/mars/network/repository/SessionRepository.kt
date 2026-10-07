package ru.mars.network.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.SessionDetail
import ru.mars.network.model.SessionItem
import ru.mars.network.model.SessionMessage

class SessionRepository(private val client: OkHttpClient = OkHttpClient()) {
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val moshi = Moshi.Builder().build()

    fun getSessions(baseUrl: String, credential: ConsumedDeviceCredential): List<SessionItem> {
        val root = parseMap(execute(baseUrl.toHttpUrl().newBuilder().addPathSegments("api/sessions").build().toString(), credential) ?: return emptyList())
        val items = (root?.get("sessions") ?: root?.get("items")) as? List<*> ?: return emptyList()
        return items.mapNotNull { (it as? Map<*, *>)?.let(::sessionItem) }
    }
    fun getSessionDetail(baseUrl: String, credential: ConsumedDeviceCredential, sessionId: String): SessionDetail? {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegments("api/session").addQueryParameter("session_id", sessionId).build().toString()
        val root = parseMap(execute(url, credential) ?: return null) ?: return null
        val s = (root["session"] as? Map<*, *>) ?: root
        return SessionDetail(string(s, "id") ?: string(s, "session_id") ?: sessionId, string(s, "title"), string(s, "created_at", "createdAt"), string(s, "updated_at", "updatedAt"), (s["messages"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(::message) }.orEmpty())
    }
    fun searchSessions(baseUrl: String, credential: ConsumedDeviceCredential, query: String): List<SessionItem> =
        getSessions(baseUrl, credential).filter { item ->
            item.title?.contains(query, ignoreCase = true) == true || item.preview?.contains(query, ignoreCase = true) == true
        }

    fun renameSession(baseUrl: String, credential: ConsumedDeviceCredential, sessionId: String, newTitle: String): Boolean {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegments("api/session/title").build()
        val body = okhttp3.RequestBody.create("application/json; charset=utf-8".toMediaType(), "{\"session_id\":${moshi.adapter(String::class.java).toJson(sessionId)},\"title\":${moshi.adapter(String::class.java).toJson(newTitle)}}")
        return try { client.newCall(Request.Builder().url(url).header("Authorization", bearer(credential)).post(body).build()).execute().use { it.isSuccessful } } catch (_: Exception) { false }
    }

    fun deleteSession(baseUrl: String, credential: ConsumedDeviceCredential, sessionId: String): Boolean {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegments("api/session").addQueryParameter("session_id", sessionId).build()
        return try { client.newCall(Request.Builder().url(url).header("Authorization", bearer(credential)).delete().build()).execute().use { it.isSuccessful } } catch (_: Exception) { false }
    }
    private fun execute(url: String, c: ConsumedDeviceCredential): String? = try { client.newCall(Request.Builder().url(url).header("Authorization", bearer(c)).get().build()).execute().use { if (it.isSuccessful) it.body?.string() else null } } catch (_: Exception) { null }
    private fun parseMap(json: String): Map<String, Any?>? = try { moshi.adapter<Map<String, Any?>>(mapType).fromJson(json) } catch (_: Exception) { null }
    private fun sessionItem(o: Map<*, *>) = SessionItem(string(o, "id", "session_id").orEmpty(), string(o, "title"), string(o, "preview", "last_message", "summary"), string(o, "created_at", "createdAt"), string(o, "updated_at", "updatedAt"), (o["message_count"] as? Number)?.toInt())
    private fun message(o: Map<*, *>) = SessionMessage(string(o, "id", "message_id"), o["role"]?.toString().orEmpty(), when (val v = o["content"]) { is String -> v; is List<*> -> v.mapNotNull { (it as? Map<*, *>)?.get("text")?.toString() }.joinToString(""); else -> o["text"]?.toString().orEmpty() }, string(o, "created_at", "createdAt"), string(o, "event_id"))
    private fun string(o: Map<*, *>, vararg keys: String) = keys.firstNotNullOfOrNull { o[it]?.toString()?.takeIf(String::isNotBlank) }
    private fun bearer(c: ConsumedDeviceCredential) = "Bearer ${c.accessToken}"
}
