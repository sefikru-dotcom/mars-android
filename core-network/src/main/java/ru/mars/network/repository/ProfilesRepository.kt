package ru.mars.network.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.mars.network.http.HttpClients
import ru.mars.network.model.ActiveProfile
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.MarsProfile
import ru.mars.network.model.ProfilesResponse

/**
 * Профили сервера (WebUI backend, контракт из `api/routes.py`):
 * - `GET /api/profiles` → `{"profiles": [...], "active": "<имя>", "single_profile_mode": bool}`;
 * - `GET /api/profile/active` → `{"name", "display_name", "path", "is_default", "default_workspace"}`.
 *
 * Только чтение: у репозитория нет ни одного POST/PUT/DELETE. Переключение
 * профиля — мутация чужого состояния (меняет `HERMES_HOME` процесса WebUI),
 * поэтому клиент её не выполняет; экран показывает список и активный профиль.
 *
 * Разбор терпимый ([TolerantJson]): элементы `profiles` без `name` отбрасываются
 * (профиль без имени нельзя ни показать, ни сопоставить с активным), остальные
 * необязательные поля получают дефолты — усечённый payload не роняет разбор.
 * Сетевые/HTTP-сбои возвращают null (профили недоступны), а не бросают исключение
 * в UI-корутину — конвенция [MemoryRepository] и [WorkspaceRepository].
 */
class ProfilesRepository(private val client: OkHttpClient = HttpClients.rest) {

    suspend fun getProfiles(baseUrl: String, credential: ConsumedDeviceCredential): ProfilesResponse? =
        get(baseUrl, credential, "api/profiles")?.let(::parse)

    suspend fun getActiveProfile(baseUrl: String, credential: ConsumedDeviceCredential): ActiveProfile? =
        get(baseUrl, credential, "api/profile/active")?.let(::parseActive)

    /** Чистый разбор `GET /api/profiles` — public для unit-тестов без сокета. */
    fun parse(payload: String): ProfilesResponse? {
        val root = TolerantJson.parseMap(payload) ?: return null
        val profiles = TolerantJson.listField(root, "profiles").mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val name = TolerantJson.string(map, "name") ?: return@mapNotNull null
            MarsProfile(
                name = name,
                displayName = TolerantJson.string(map, "display_name"),
                path = TolerantJson.string(map, "path"),
                isDefault = TolerantJson.bool(map, "is_default"),
                isActive = TolerantJson.bool(map, "is_active"),
                gatewayRunning = TolerantJson.bool(map, "gateway_running"),
                model = TolerantJson.string(map, "model"),
                provider = TolerantJson.string(map, "provider"),
                hasEnv = TolerantJson.bool(map, "has_env"),
                // Сервер опускает ключ у видимых профилей; скрытый помечен явным false.
                visible = TolerantJson.bool(map, "visible", default = true),
                enabledSkills = TolerantJson.int(map, "enabled_skills"),
                totalSkills = TolerantJson.int(map, "total_skills"),
            )
        }
        return ProfilesResponse(
            profiles = profiles,
            active = TolerantJson.string(root, "active"),
            singleProfileMode = TolerantJson.bool(root, "single_profile_mode"),
        )
    }

    /** Чистый разбор `GET /api/profile/active` — public для unit-тестов без сокета. */
    fun parseActive(payload: String): ActiveProfile? {
        val root = TolerantJson.parseMap(payload) ?: return null
        val name = TolerantJson.string(root, "name") ?: return null
        return ActiveProfile(
            name = name,
            displayName = TolerantJson.string(root, "display_name"),
            path = TolerantJson.string(root, "path"),
            isDefault = TolerantJson.bool(root, "is_default"),
            defaultWorkspace = TolerantJson.string(root, "default_workspace"),
        )
    }

    /** GET с device-Bearer; null — транспортная ошибка или не-2xx. */
    private suspend fun get(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        path: String,
    ): String? = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegments(path).build().toString()
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
}
