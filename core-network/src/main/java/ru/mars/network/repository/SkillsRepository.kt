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
import ru.mars.network.model.SkillDetail
import ru.mars.network.model.SkillItem

/** Исход мутации каталога скиллов (`POST /api/skills/save|delete|toggle`, api/route_skills.py). */
sealed interface SkillMutationResult {
    /** Сервер ответил `{"ok": true, ...}` — состояние каталога изменилось. */
    data object Ok : SkillMutationResult
    /** 4xx/5xx, `ok:false`, битый JSON или сеть; [message] — `error` с сервера либо `HTTP <code>`. */
    data class Failed(val message: String) : SkillMutationResult
}

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
class SkillsRepository(private val client: OkHttpClient = HttpClients.rest) {

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
                // route_skills.py:251 — «скилл выключен в конфиге профиля»; не-boolean → false.
                disabled = map["disabled"] as? Boolean ?: false,
            )
        }.orEmpty()
    }

    /** Pure `/api/skills/content` parser — public for unit tests without a socket. */
    fun parseSkillDetail(payload: String): SkillDetail? {
        val root = parseMap(payload) ?: return null
        val name = root["name"]?.toString()?.takeIf(String::isNotBlank) ?: return null
        return SkillDetail(name = name, content = root["content"]?.toString()?.takeIf(String::isNotBlank).orEmpty())
    }

    // --- Мутации каталога: POST /api/skills/save|delete|toggle (api/route_skills.py) ------------

    /**
     * `POST /api/skills/save` — `_handle_skill_save` (:464): `{name, content, category?}`;
     * сервер нормализует имя (strip/lowercase/пробелы→дефисы), пустая категория допустима —
     * ключ тогда не отправляется. Ответ 200 `{"ok": true, "name", "path"}`.
     */
    suspend fun saveSkill(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        name: String,
        content: String,
        category: String? = null,
    ): SkillMutationResult {
        if (name.isBlank() || content.isBlank()) {
            return SkillMutationResult.Failed("Missing required field(s): name, content")
        }
        return mutate(baseUrl, credential, "api/skills/save", saveBody(name, content, category))
    }

    /**
     * `POST /api/skills/delete` — `_handle_skill_delete` (:495): `{name}`; необратимый rmtree
     * на сервере, поэтому UI обязан требовать явное подтверждение. 404 `{"error": "Skill not found"}`.
     */
    suspend fun deleteSkill(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        name: String,
    ): SkillMutationResult {
        if (name.isBlank()) return SkillMutationResult.Failed("Missing required field(s): name")
        return mutate(baseUrl, credential, "api/skills/delete", nameBody(name))
    }

    /**
     * `POST /api/skills/toggle` — `_handle_skill_toggle` (:536): `{name, enabled}`;
     * `enabled=false` валиден (require пропускает False как 0). Ответ `{"ok": true, "name", "enabled"}`.
     */
    suspend fun toggleSkill(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        name: String,
        enabled: Boolean,
    ): SkillMutationResult {
        if (name.isBlank()) return SkillMutationResult.Failed("Missing required field(s): name")
        return mutate(baseUrl, credential, "api/skills/toggle", toggleBody(name, enabled))
    }

    /** Чистая сборка тела save: category включается только непустой — public для unit-тестов. */
    fun saveBody(name: String, content: String, category: String?): String = buildString {
        append("{\"name\":").append(JsonStringEncoding.escape(name))
        append(",\"content\":").append(JsonStringEncoding.escape(content))
        category?.takeIf(String::isNotBlank)?.let {
            append(",\"category\":").append(JsonStringEncoding.escape(it))
        }
        append('}')
    }

    /** Чистая сборка тела delete — public для unit-тестов. */
    fun nameBody(name: String): String =
        "{\"name\":${JsonStringEncoding.escape(name)}}"

    /** Чистая сборка тела toggle — public для unit-тестов. */
    fun toggleBody(name: String, enabled: Boolean): String =
        "{\"name\":${JsonStringEncoding.escape(name)},\"enabled\":$enabled}"

    /**
     * Чистый разбор ответа мутации: успех — 2xx и `ok == true`; иначе `error` с сервера
     * либо `HTTP <code>`. public для unit-тестов без сокета.
     */
    fun parseMutation(code: Int, payload: String): SkillMutationResult {
        val root = parseMap(payload)
        val error = root?.get("error")?.toString()?.takeIf(String::isNotBlank)
        return if (code in 200..299 && root?.get("ok") == true) {
            SkillMutationResult.Ok
        } else {
            SkillMutationResult.Failed(error ?: "HTTP $code")
        }
    }

    private suspend fun mutate(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        path: String,
        body: String,
    ): SkillMutationResult = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegments(path).build().toString()
        try {
            client.newCall(
                Request.Builder().url(url)
                    .header("Authorization", "Bearer ${credential.accessToken}")
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            ).execute().use { parseMutation(it.code, it.body?.string().orEmpty()) }
        } catch (_: Exception) {
            SkillMutationResult.Failed("Network error")
        }
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

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
