package ru.mars.network.model

/**
 * Один профиль из `GET /api/profiles` (сервер: `api/profiles.py`,
 * `list_profiles_api` / `_build_profile_rows_fast`).
 *
 * Поля повторяют ровно те ключи, что сериализует сервер, и все необязательные
 * несут дефолт: усечённый payload (строка только с `name`, отсутствующий
 * `single_profile_mode`, `model: null`) обязан разбираться, а не падать.
 * `visible == false` — профиль скрыт из чата (сервер помечает так
 * meta-флагом), а не отсутствует: строка остаётся в списке.
 *
 * [MarsProfile.displayName] может быть null → показывается техническое [name]
 * (паритет с WebUI: `esc(p.display_name || p.name)`).
 */
data class MarsProfile(
    val name: String,
    val displayName: String? = null,
    val path: String? = null,
    val isDefault: Boolean = false,
    val isActive: Boolean = false,
    val gatewayRunning: Boolean = false,
    val model: String? = null,
    val provider: String? = null,
    val hasEnv: Boolean = false,
    val visible: Boolean = true,
    val enabledSkills: Int = 0,
    val totalSkills: Int = 0,
) {
    /** Подпись профиля для списка: `display_name` сервера, иначе техническое имя. */
    val label: String get() = displayName?.takeIf(String::isNotBlank) ?: name

    /**
     * Модель без провайдерского префикса (`vendor/model` → `model`) — WebUI
     * показывает в строке профиля именно хвост (`p.model.split('/').pop()`).
     */
    val shortModel: String?
        get() = model?.substringAfterLast('/')?.trim()?.takeIf(String::isNotBlank)
}

/**
 * Разобранный ответ `GET /api/profiles`:
 * `{"profiles": [...], "active": "<имя>", "single_profile_mode": bool}`.
 *
 * [active] — имя активного профиля по версии сервера (может отсутствовать в
 * усечённом payload); [singleProfileMode] — изолированный однопользовательский
 * режим WebUI (`_is_isolated_profile_mode`), в нём сервер отдаёт один профиль.
 */
data class ProfilesResponse(
    val profiles: List<MarsProfile> = emptyList(),
    val active: String? = null,
    val singleProfileMode: Boolean = false,
)

/**
 * Ответ `GET /api/profile/active`:
 * `{name, display_name, path, is_default, default_workspace}`.
 *
 * Отдельная модель, а не [MarsProfile]: у этого эндпоинта нет счётчиков
 * скиллов и флага `gateway_running`, зато есть рабочее пространство профиля.
 */
data class ActiveProfile(
    val name: String,
    val displayName: String? = null,
    val path: String? = null,
    val isDefault: Boolean = false,
    val defaultWorkspace: String? = null,
) {
    /** Подпись профиля: `display_name` сервера, иначе техническое имя. */
    val label: String get() = displayName?.takeIf(String::isNotBlank) ?: name
}
