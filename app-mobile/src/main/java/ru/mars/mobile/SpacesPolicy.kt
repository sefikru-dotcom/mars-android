package ru.mars.mobile

import ru.mars.network.model.WorkspaceItem
import ru.mars.network.model.WorkspacesResponse

/**
 * Фаза экрана «Пространства» (Spaces/Workspaces). Чистая JVM-модель: UI-слой
 * (SpacesActivity) только сопоставляет фазу с видимостью прогресса, списка и
 * статус-блока — все строки, метки и тексты считает [SpacesPolicy].
 *
 * Фазы намеренно названы так же, как у остальных section-экранов
 * (KanbanPresenter/ProfilesPolicy: LOADING/READY/EMPTY/ERROR/NO_AUTH), чтобы
 * экран читался одинаково в любом разделе бара.
 */
enum class SpacesPhase {
    /** Первая загрузка: показывать ещё нечего. */
    LOADING,

    /** Каталог загружен (свежий или из кэша), строки есть. */
    READY,

    /** Каталог загружен, но сервер не отдал ни одного пространства. */
    EMPTY,

    /** Каталог недоступен (сеть/сервер). Возможна offline-safe локальная строка. */
    ERROR,

    /** Нет сохранённой пары (device-credential): загрузка невозможна до входа. */
    NO_AUTH,
}

/**
 * Одна отрисованная строка каталога. [key] — идентификатор клика: путь
 * пространства (уникален на сервере) либо [SpacesPolicy.AUTO_KEY] для строки
 * «Авто».
 *
 * Разделение флагов: [isActive] — текущий эффективный выбор клиента (явный пик
 * пользователя), [isServerDefault] — пространство, которое сервер считает
 * последним (`last`). Одна и та же строка может быть обеими: пользователь мог
 * явно выбрать серверное значение по умолчанию, и это разные факты.
 */
data class SpaceRow(
    val key: String,
    val title: String,
    val subtitle: String,
    val isActive: Boolean,
    val isServerDefault: Boolean,
    val isAuto: Boolean,
) {
    /** Метки строки в порядке отображения; экран рисует их пилюлями палитры. */
    val badges: List<String>
        get() = buildList {
            if (isActive) add(SpacesPolicy.ACTIVE_BADGE)
            if (isServerDefault) add(SpacesPolicy.DEFAULT_BADGE)
        }
}

/**
 * Полное состояние экрана: фаза, строки, счётчик каталога, внеполосная
 * [notice] (например, «нет сети — каталог из кэша»), центральный [message] для
 * фаз без списка и флаг [canRetry].
 *
 * [refreshing] и [stale] отделены от фазы, чтобы обновление поверх готового
 * каталога не мигало экраном: список остаётся на месте, меняется только шапка.
 */
data class SpacesUiState(
    val phase: SpacesPhase,
    val rows: List<SpaceRow> = emptyList(),
    val summary: String? = null,
    val notice: String? = null,
    val message: String? = null,
    val canRetry: Boolean = false,
    val refreshing: Boolean = false,
    val stale: Boolean = false,
)

/**
 * Чистая политика экрана «Пространства» (UI -> state/domain -> network): строки
 * каталога, метки выбора, offline-safe локальная строка и тексты состояний.
 * Не знает ни про Android, ни про сеть: каталог приходит из
 * [ru.mars.network.repository.WorkspaceRepository] через [SpacesPresenter],
 * хранение выбора — [WorkspaceSelection] (общий `mars-workspace-choice` префс,
 * поэтому выбор сразу видит чип чата).
 *
 * Паритет с WebUI и с TV-экраном (TvSpacesPolicy): каталог — серверный список в
 * серверном порядке, серверное значение по умолчанию — поле `last` ответа.
 * Отличия mobile: строка «Авто» (без явного пика клиент не отправляет сентинел
 * `[Workspace::v1: …]` и маршрут выбирает сервер) и сам выбор пространства —
 * на TV экран только смотрит.
 */
object SpacesPolicy {

    /** Заголовок экрана и название раздела левого бара. */
    const val SCREEN_TITLE = "Пространства"

    /** Ключ строки «Авто» (серверное пространство по умолчанию, без явного пика). */
    const val AUTO_KEY = "__auto__"

    const val AUTO_TITLE = "Авто"

    /** Пояснение строки «Авто»: без пика маршрут выбирает сервер, а не клиент. */
    const val AUTO_SUBTITLE = "Пространство сервера по умолчанию"

    /** Метка текущего эффективного выбора. */
    const val ACTIVE_BADGE = "Активно"

    /** Метка пространства, которое сервер считает последним (`last`). */
    const val DEFAULT_BADGE = "По умолчанию"

    /** Префикс live-строки счётчика каталога. */
    const val CATALOG_PREFIX = "Пространств: "

    const val MSG_LOADING = "Загрузка пространств…"
    const val MSG_EMPTY = "На сервере пока нет рабочих пространств"
    const val MSG_ERROR = "Каталог пространств недоступен. Проверьте соединение и повторите."
    const val MSG_ERROR_OFFLINE = "Нет сети: каталог пространств недоступен"
    const val MSG_NO_AUTH = "Нужен вход: откройте приложение и пройдите пару"

    const val NOTICE_OFFLINE = "Нет сети"
    const val NOTICE_OFFLINE_SAVED = "Нет сети — показан сохранённый выбор"
    const val NOTICE_OFFLINE_CACHE = "Нет сети — каталог показан из кэша"
    const val NOTICE_STALE_CACHE = "Сервер недоступен — каталог показан из кэша"

    /**
     * Максимальный возраст кэша каталога (24 ч, паритет с TV). Каталог меняется
     * редко, но устаревший на сутки список честнее показывать с плашкой, чем
     * пустой экран; старше — это уже другая реальность, показываем ошибку.
     */
    const val CACHE_MAX_AGE_MS = 24L * 60 * 60 * 1000

    /**
     * Подпись пути: дублирующийся хвостовой `/` убирается (кроме корня `/`),
     * пустая строка остаётся пустой — политика ничего не выдумывает.
     */
    fun pathLabel(path: String): String {
        if (path.isBlank()) return path
        if (path == "/") return path
        return path.trimEnd('/')
    }

    /**
     * Строка «Авто»: активна, пока явного пика нет. Серверным значением по
     * умолчанию она не помечается — «Авто» не пространство, а режим «решает сервер».
     */
    fun autoRow(explicitPickPath: String?): SpaceRow = SpaceRow(
        key = AUTO_KEY,
        title = AUTO_TITLE,
        subtitle = AUTO_SUBTITLE,
        isActive = explicitPickPath.isNullOrBlank(),
        isServerDefault = false,
        isAuto = true,
    )

    /**
     * Строки каталога в серверном порядке: имя, путь, метка «Активно» у явного
     * пика ([explicitPickPath]) и «По умолчанию» у записи, чей путь совпадает с
     * response-level `last` (контракт провода: `path == last`; [WorkspaceItem.isDefault]
     * выводится репозиторием из того же поля).
     *
     * Записи с пустым путём отбрасываются (строка без пути нефункциональна:
     * выбрать её нельзя), дубли путей схлопываются — [SpaceRow.key] обязан быть
     * уникальным, иначе клик попадёт не в ту строку.
     */
    fun rows(response: WorkspacesResponse, explicitPickPath: String? = null): List<SpaceRow> =
        response.workspaces
            .filter { it.path.isNotBlank() }
            .distinctBy { it.path }
            .map { item ->
                SpaceRow(
                    key = item.path,
                    title = item.name.ifBlank { pathLabel(item.path) },
                    subtitle = pathLabel(item.path),
                    isActive = !explicitPickPath.isNullOrBlank() && item.path == explicitPickPath,
                    isServerDefault = item.path == response.last || item.isDefault,
                    isAuto = false,
                )
            }

    /**
     * Offline-safe строка из сохранённого выбора: каталог недоступен, но
     * persisted-пик есть — экран не пустует и показывает актуальный выбор
     * (иначе пользователь видел бы ошибку и не понимал, какое пространство
     * уйдёт в следующее сообщение).
     */
    fun localRow(item: WorkspaceItem): SpaceRow = SpaceRow(
        key = item.path,
        title = item.name.ifBlank { pathLabel(item.path) },
        subtitle = pathLabel(item.path),
        isActive = true,
        isServerDefault = item.isDefault,
        isAuto = false,
    )

    /** Live-строка счётчика: «Пространств: N» по числу отрисованных строк каталога. */
    fun summaryLine(response: WorkspacesResponse): String = "$CATALOG_PREFIX${rows(response).size}"

    /**
     * Пустое состояние каталога: `null`, когда строки есть. Пустой список — это
     * валидный ответ сервера (пространств ещё не создали), а не сбой: сбой
     * отличает `null` от репозитория и озвучивает [SpacesPresenter] ошибкой.
     */
    fun emptyLine(response: WorkspacesResponse): String? =
        if (rows(response).isEmpty()) MSG_EMPTY else null

    /**
     * Годится ли кэш каталога: возраст не больше [CACHE_MAX_AGE_MS]. Отрицательный
     * возраст (часы ушли назад) считается годным — это не устаревание, а сбитые
     * часы, и такой кэш не хуже свежего.
     */
    fun isCacheFresh(ageMs: Long): Boolean = ageMs <= CACHE_MAX_AGE_MS
}
