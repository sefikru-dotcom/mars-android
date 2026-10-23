package ru.mars.network.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.mars.network.http.HttpClients
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.ProjectEntry
import ru.mars.network.model.ProjectsSnapshot
import java.io.IOException

/** Итог `GET /api/projects`. */
sealed interface ProjectsResult {
    /**
     * 2xx с разобранным списком. [rawJson] — тело ответа как есть: вызывающий
     * сохраняет в офлайн-кэш именно его (кэш не знает модели и не теряет поля).
     */
    data class Loaded(val snapshot: ProjectsSnapshot, val rawJson: String) : ProjectsResult

    /**
     * Любой отказ: транспорт (нет сети, таймаут, обрыв), не-2xx (`{"error": …}`) или
     * неразборчивое 2xx-тело. Пустой список проектов отказом НЕ является — это
     * валидный ответ сервера (см. [ProjectsSnapshot.projects]).
     */
    data class Failed(val message: String) : ProjectsResult
}

/**
 * `GET /api/projects` — список проектов WebUI-бэкенда (контракт проверен read-only
 * по `/opt/hermes-webui/api/routes.py`):
 *
 * ```
 * 200 {"projects": [{"project_id", "name", "path", "profile", "color", …}, …],
 *      "all_profiles": bool, "active_profile": str, "other_profile_count": int}
 * ```
 *
 * Маршрут разрешён для device-Bearer (`NATIVE_BEARER_EXACT_PATHS` в `api/auth.py`),
 * поэтому авторизация — обычный `Authorization: Bearer <access_token>`, как у
 * остальных read-репозиториев модуля.
 *
 * `?all_profiles=1` — необязательный агрегат по всем профилям (без него сервер
 * фильтрует по активному профилю и сообщает, сколько проектов скрыто в
 * [ProjectsSnapshot.otherProfileCount]). Параметр передаётся только когда он явно
 * запрошен — дефолт сервера не дублируем.
 *
 * Разбор терпимый ([TolerantJson]): отсутствующие/`null`/нестроковые поля дают
 * пустые значения, строка без `name` отбрасывается, а не роняет весь список. Тело,
 * которое вообще не объект, считается отказом — иначе «битый JSON» было бы
 * неотличимо от «проектов нет».
 */
class ProjectsRepository(private val client: OkHttpClient = HttpClients.rest) {

    suspend fun getProjects(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        allProfiles: Boolean = false,
    ): ProjectsResult = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/projects")
            .flagParam("all_profiles", allProfiles)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${credential.accessToken}")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val code = response.code
                if (code !in 200..299) return@use ProjectsResult.Failed(describeHttpError(code, body))
                val snapshot = parse(body)
                    ?: return@use ProjectsResult.Failed("HTTP $code: unparseable body")
                ProjectsResult.Loaded(snapshot, body)
            }
        } catch (error: IOException) {
            // Текст исключения содержит максимум host/path — заголовки (и токен) в него не попадают.
            ProjectsResult.Failed(describeNetworkError(error))
        }
    }

    private fun HttpUrl.Builder.flagParam(name: String, value: Boolean): HttpUrl.Builder =
        apply { if (value) addQueryParameter(name, "1") }

    companion object {

        /**
         * Разбор тела `GET /api/projects`; null — тело не JSON-объект (в т.ч. HTML
         * от прокси/каптивного портала) или оно пустое.
         */
        fun parse(body: String): ProjectsSnapshot? {
            val root = TolerantJson.parseMap(body) ?: return null
            return ProjectsSnapshot(
                projects = TolerantJson.listField(root, "projects").mapNotNull(::parseEntry),
                allProfiles = TolerantJson.bool(root, "all_profiles"),
                activeProfile = TolerantJson.string(root, "active_profile").orEmpty(),
                otherProfileCount = TolerantJson.int(root, "other_profile_count"),
            )
        }

        /** Строка проекта; null — не объект или нет имени (рисовать нечего). */
        private fun parseEntry(raw: Any?): ProjectEntry? {
            val map = raw as? Map<*, *> ?: return null
            val name = TolerantJson.string(map, "name") ?: return null
            return ProjectEntry(
                id = TolerantJson.string(map, "project_id").orEmpty(),
                name = name,
                path = TolerantJson.string(map, "path").orEmpty(),
                profile = TolerantJson.string(map, "profile"),
                color = TolerantJson.string(map, "color"),
            )
        }
    }
}
