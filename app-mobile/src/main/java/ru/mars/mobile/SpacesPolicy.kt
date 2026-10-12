package ru.mars.mobile

import ru.mars.network.model.WorkspaceItem
import ru.mars.network.model.WorkspacesResponse

/**
 * Фаза экрана «Пространства» (Spaces/Workspaces). Чистая JVM-модель —
 * UI-слой (SpacesActivity) только сопоставляет фазу с видимостью
 * прогресса/списка/статуса, все тексты и строки считает [SpacesPolicy].
 */
enum class SpacesPhase {
    /** Первая загрузка или refresh: список может быть пуст или показывать прошлые строки. */
    LOADING,

    /** Каталог загружен, есть хотя бы одно пространство. */
    READY,

    /** Каталог загружен, но сервер не отдал ни одного пространства. */
    EMPTY,

    /** Каталог недоступен (сеть/сервер). Возможны offline-safe строки из хранилища. */
    ERROR,

    /** Нет сохранённой пары (credential): загрузка невозможна до входа. */
    NO_AUTH,
}

/**
 * Одна строка списка пространств. [key] — стабильный идентификатор для клика
 * ([SpacesPolicy.AUTO_KEY] для строки «Авто», иначе путь workspace).
 * [isActive] — текущий эффективный выбор (явный пик пользователя),
 * [isServerDefault] — серверный `last` (пространство по умолчанию).
 */
data class SpaceRowUi(
    val key: String,
    val title: String,
    val subtitle: String,
    val isActive: Boolean,
    val isServerDefault: Boolean,
    val isAuto: Boolean,
)

/**
 * Полное состояние экрана: фаза, строки, внеполосное [notice] (например,
 * «нет сети — показан сохранённый выбор»), центральный [message] для фаз без
 * списка и флаг [canRetry] (кнопка «Повторить»).
 */
data class SpacesUiState(
    val phase: SpacesPhase,
    val rows: List<SpaceRowUi>,
    val notice: String? = null,
    val message: String? = null,
    val canRetry: Boolean = false,
)

/**
 * Чистая политика экрана Spaces (UI -> state/domain -> network): тексты,
 * строки и разметка активного выбора. Не знает ни про Android, ни про сеть —
 * данные приходят из [ru.mars.network.repository.WorkspaceRepository] через
 * [SpacesPresenter], хранение выбора — [WorkspaceSelection].
 *
 * Паритет с WebUI: без явного выбора пользователя активно «Авто» (серверный
 * `last`), явный пик подсвечивает конкретную строку и переживает перезапуск.
 */
object SpacesPolicy {

    /** Ключ строки «Авто» (серверный workspace по умолчанию, без persisted-пика). */
    const val AUTO_KEY = "__auto__"

    const val MSG_LOADING = "Загрузка пространств…"
    const val MSG_EMPTY = "На сервере пока нет рабочих пространств"
    const val MSG_ERROR = "Каталог пространств недоступен. Проверьте соединение и повторите."
    const val MSG_ERROR_OFFLINE = "Нет сети: каталог пространств недоступен"
    const val MSG_NO_AUTH = "Нужен вход: откройте приложение и пройдите пару"
    const val NOTICE_OFFLINE = "Нет сети"
    const val NOTICE_OFFLINE_SAVED = "Нет сети — показан сохранённый выбор"

    const val AUTO_TITLE = "Авто"
    const val AUTO_SUBTITLE = "Пространство сервера по умолчанию"
    const val RETRY_LABEL = "Повторить"

    /**
     * Подпись пути для subtitle: убирает дублирующийся хвостовой `/`
     * (кроме корня), ничего не выдумывает для пустой строки.
     */
    fun pathLabel(path: String): String {
        if (path.isBlank()) return path
        if (path == "/") return path
        return path.trimEnd('/')
    }

    /** Строка «Авто»: активна, пока нет явного persisted-выбора. */
    fun autoRow(explicitPickPath: String?): SpaceRowUi = SpaceRowUi(
        key = AUTO_KEY,
        title = AUTO_TITLE,
        subtitle = AUTO_SUBTITLE,
        isActive = explicitPickPath.isNullOrBlank(),
        isServerDefault = false,
        isAuto = true,
    )

    /**
     * Строки каталога в серверном порядке: «Активно» — только явный пик
     * ([explicitPickPath]); серверный `last` помечается [SpaceRowUi.isServerDefault]
     * и получает бейдж «По умолчанию» (в режиме «Авто» активна строка [autoRow]).
     */
    fun rows(response: WorkspacesResponse, explicitPickPath: String?): List<SpaceRowUi> =
        response.workspaces.map { item ->
            SpaceRowUi(
                key = item.path,
                title = item.name.ifBlank { pathLabel(item.path) },
                subtitle = pathLabel(item.path),
                isActive = explicitPickPath != null && item.path == explicitPickPath,
                isServerDefault = (response.last != null && item.path == response.last) || item.isDefault,
                isAuto = false,
            )
        }

    /**
     * Offline-safe строка из сохранённого выбора (каталог недоступен, но
     * persisted-пик есть — экран не пустует и показывает актуальный выбор).
     */
    fun localRow(item: WorkspaceItem): SpaceRowUi = SpaceRowUi(
        key = item.path,
        title = item.name.ifBlank { pathLabel(item.path) },
        subtitle = pathLabel(item.path),
        isActive = true,
        isServerDefault = item.isDefault,
        isAuto = false,
    )

    /** Заголовок экрана (паритет с остальными экранами: короткий, без глифов). */
    const val SCREEN_TITLE = "Пространства"
}
