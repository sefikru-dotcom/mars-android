package ru.mars.network.model

/**
 * Одна строка `projects[]` из `GET /api/projects` (контракт проверен по
 * hermes-webui `api/routes.py`: `load_projects()` отдаёт список словарей
 * `{project_id, name, color, profile, created_at, …}`).
 *
 * Поля читаются терпимо (см. [ru.mars.network.repository.ProjectsRepository]):
 * обязательное только `name` — строка без имени не рисуется ни в одном UI, поэтому
 * отбрасывается; остальные поля деградируют в пустые значения, а не роняют разбор.
 *
 * `path` — серверный путь проекта (не URL сервиса): ссылку «Открыть сервис» из него
 * не строим, иначе приложение открывало бы `file://`/каталог как веб-страницу.
 */
data class ProjectEntry(
    val id: String,
    val name: String,
    val path: String = "",
    val profile: String? = null,
    val color: String? = null,
)

/**
 * Разобранный ответ `GET /api/projects`:
 * - [projects] — проекты в порядке сервера (уже отфильтрованные по активному профилю,
 *   либо все при `?all_profiles=1`);
 * - [activeProfile] — профиль, по которому сервер фильтровал (`active_profile`);
 * - [allProfiles] — был ли запрос агрегатным (`all_profiles`);
 * - [otherProfileCount] — сколько проектов скрыто фильтром профиля (сервер считает сам).
 */
data class ProjectsSnapshot(
    val projects: List<ProjectEntry> = emptyList(),
    val allProfiles: Boolean = false,
    val activeProfile: String = "",
    val otherProfileCount: Int = 0,
)
