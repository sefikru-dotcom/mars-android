package ru.mars.tv.spaces

import ru.mars.network.model.WorkspaceItem
import ru.mars.network.model.WorkspacesResponse

/**
 * Pure presentation policy of the TV «Пространства» screen (no Android, no I/O —
 * JVM-testable, [ru.mars.tv.todos.TvTodosPolicy] parity). Wording is mobile
 * `SpacesPolicy` parity for the read-only subset: каталог рабочих пространств
 * сервера (`GET /api/workspaces`, [ru.mars.network.repository.WorkspaceRepository])
 * без выбора/переименования — на TV экран смотрит, но не меняет (нативный
 * device-токен покрывает только список; мутации /api/workspaces остаются на
 * браузерной cookie-сессии).
 *
 * Все решения — здесь; [TvSpacesActivity] только рисует.
 */
object TvSpacesPolicy {

    /** [restoreSelection] result when there is nothing to focus. */
    const val NO_SELECTION = -1

    /** Сервер не вернул ни одного пространства (валидный пустой каталог). */
    const val EMPTY_LIST = "На сервере пока нет рабочих пространств"

    /** Бейдж пространства, которое сервер считает последним выбранным (`last`). */
    const val DEFAULT_BADGE = "По умолчанию"

    /** Заголовок-префикс каталога. */
    const val CATALOG_PREFIX = "Пространств: "

    /** Максимальный возраст кэшированного каталога — редкие изменения (24 ч). */
    const val CACHE_MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** Одна отрисованная строка каталога. */
    data class SpaceRow(
        /** Стабильный ключ фокуса между перерисовками: путь (уникален на сервере). */
        val key: String,
        val name: String,
        val path: String,
        val defaultBadge: Boolean,
    )

    /**
     * Строки каталога в серверном порядке: имя + путь + бейдж «По умолчанию» у
     * записи, чей путь совпадает с response-level `last` (выводится здесь, а не
     * из [WorkspaceItem.isDefault] — контракт провода: `path == last`).
     * Записи с пустым путём отбрасываются — строка без пути нефункциональна.
     */
    fun rows(response: WorkspacesResponse): List<SpaceRow> =
        response.workspaces
            .filter { it.path.isNotBlank() }
            .map { item: WorkspaceItem ->
                SpaceRow(
                    key = item.path,
                    name = item.name.ifBlank { item.path },
                    path = item.path,
                    defaultBadge = response.last != null && item.path == response.last,
                )
            }

    /** Live-регион: «Пространств: N» (+ плашка источника собирает Activity). */
    fun summaryLine(response: WorkspacesResponse): String = "$CATALOG_PREFIX${rows(response).size}"

    /**
     * Пустое состояние: `null` когда строки есть; [EMPTY_LIST] для пустого
     * каталога. Отличить «пусто» от «сервер недоступен» нельзя — репозиторий
     * fail-soft ([ru.mars.network.repository.WorkspaceRepository] возвращает
     * `null` при сбое, его озвучивает контроллер ошибкой), пустой список —
     * валидный ответ.
     */
    fun emptyLine(response: WorkspacesResponse): String? =
        if (rows(response).isEmpty()) EMPTY_LIST else null

    /**
     * Focus restoration after a re-render (auto-refresh): the previous index
     * clamped into the new list — edges STAY, never wrap; [NO_SELECTION] when empty.
     */
    fun restoreSelection(previous: Int, size: Int): Int =
        if (size <= 0) NO_SELECTION else previous.coerceIn(0, size - 1)
}
