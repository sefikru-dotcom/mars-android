package ru.mars.network.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.SkillDetail
import ru.mars.network.model.SkillItem

/**
 * Skills catalog of the WebUI backend (contract from api/routes.py):
 * - GET /api/skills — `{"skills": [{"name": ..., "description": ..., "category": ...}, ...]}`
 *   (optionally narrowed with `?category=...`)
 * - GET /api/skills/content?name=... — `{"name": ..., "content": ...}` (full SKILL.md body)
 *
 * Entries without a usable `name` are skipped; display metadata defaults to "".
 * Executes on [Dispatchers.IO]; failures map to an empty catalog / null detail
 * instead of throwing into UI coroutines — same convention as
 * [WorkspaceRepository].
 */
class SkillsRepository(private val client: OkHttpClient = OkHttpClient()) {

    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val moshi = Moshi.Builder().build()

    /** GET /api/skills; a non-blank [category] becomes the `category` query parameter. */
    suspend fun getSkills(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        category: String? = null,
    ): List<SkillItem> {
        val query = listOfNotNull(category?.takeIf(String::isNotBlank)?.let { "category" to it })
        val text = fetch(baseUrl, credential, "api/skills", query) ?: return emptyList()
        return parseSkills(text)
    }

    /** GET /api/skills/content?name=[name]; a blank [name] never hits the network. */
    suspend fun getSkillContent(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        name: String,
    ): SkillDetail? {
        val skill = name.takeIf(String::isNotBlank) ?: return null
        val text = fetch(baseUrl, credential, "api/skills/content", listOf("name" to skill)) ?: return null
        return parseSkillDetail(text)
    }

    /** Pure `/api/skills` parser — public for unit tests without a socket. */
    fun parseSkills(payload: String): List<SkillItem> {
        val root = parseMap(payload) ?: return emptyList()
        return (root["skills"] as? List<*>)?.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val name = map["name"]?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            SkillItem(
                name = name,
                description = map["description"]?.toString()?.takeIf(String::isNotBlank).orEmpty(),
                category = map["category"]?.toString()?.takeIf(String::isNotBlank).orEmpty(),
            )
        }.orEmpty()
    }

    /** Pure `/api/skills/content` parser — public for unit tests without a socket. */
    fun parseSkillDetail(payload: String): SkillDetail? {
        val root = parseMap(payload) ?: return null
        val name = root["name"]?.toString()?.takeIf(String::isNotBlank) ?: return null
        return SkillDetail(name = name, content = root["content"]?.toString()?.takeIf(String::isNotBlank).orEmpty())
    }

    private suspend fun fetch(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        path: String,
        query: List<Pair<String, String>>,
    ): String? = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments(path)
            .apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build().toString()
        try {
            client.newCall(
                Request.Builder().url(url)
                    .header("Authorization", "Bearer ${credential.accessToken}")
                    .get().build()
            ).execute().use { if (it.isSuccessful) it.body?.string() else null }
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
}
