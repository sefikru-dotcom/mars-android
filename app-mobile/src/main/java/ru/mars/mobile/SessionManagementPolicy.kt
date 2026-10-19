package ru.mars.mobile

import ru.mars.network.model.SessionItem

/**
 * Пункты меню управления диалогом (контекстное меню карточки в [ChatSessionsActivity]).
 *
 * Список строится ДО открытия меню и зависит от состояния строки: у закреплённого
 * диалога пункт называется «Открепить», у архивного — «Вернуть из архива».
 * Никаких разрушающих действий над `read_only`-строками (субагенты) меню не
 * предлагает — ту же авторитетность, что и у rename/delete, несёт
 * [SessionRowActions.canManage]. Единственное исключение — «Перенести»: перенос
 * дочерней строки в другого родителя сервер разрешает и для `read_only`
 * ([SessionMovePolicy]).
 */
enum class SessionActionKind {
    PIN,
    ARCHIVE,
    EXPORT,

    /** Публичная ссылка: `POST /api/share/create` (ручка разрешена device-токену). */
    SHARE,

    /** Перенос дочерней строки в другого родителя ([SessionMovePolicy]). */
    MOVE,
}

/**
 * Чистая политика действий над диалогом: состав меню, подписи, имя файла экспорта
 * и доступность «поделиться».
 *
 * «Поделиться» — это ПУБЛИЧНАЯ ССЫЛКА сервера (`POST /api/share/create`, отзыв —
 * `POST /api/share/revoke`): обе ручки входят в device-Bearer allowlist
 * (`hermes-webui/api/auth.py`, `NATIVE_BEARER_EXACT_PATHS`), поэтому клиент зовёт
 * их напрямую. Прежнее утверждение «на device-токене эти ручки отвечают 401»
 * было серверным блокером и снято — ссылка делается через сервер, а локальный
 * Markdown-экспорт ([ru.mars.network.export.SessionMarkdownExporter]) остаётся
 * отдельным пунктом для файла.
 */
object SessionManagementPolicy {

    /**
     * Состав меню для строки: управляемые строки получают pin/archive/export/share,
     * дочерние (субагент/форк) — «Перенести». Оба набора складываются: у субагента
     * `read_only` отсекает pin/archive/export/share, но перенос в другого родителя
     * сервер разрешает, поэтому пункт [SessionActionKind.MOVE] остаётся.
     */
    fun menu(session: SessionItem): List<SessionActionKind> {
        val manageable = SessionRowActions.canManage(session)
        val movable = SessionMovePolicy.canMove(session)
        return buildList {
            if (manageable) {
                addAll(
                    listOf(
                        SessionActionKind.PIN,
                        SessionActionKind.ARCHIVE,
                        SessionActionKind.EXPORT,
                        SessionActionKind.SHARE,
                    )
                )
            }
            if (movable) add(SessionActionKind.MOVE)
        }
    }

    /** Подпись пункта публичной ссылки. */
    fun shareLabel(): String = "Поделиться ссылкой"

    /** Подпись пункта отзыва ссылки (в диалоге ссылки). */
    fun revokeLabel(): String = "Отозвать ссылку"

    /** Подпись пункта переноса. */
    fun moveLabel(): String = SessionMovePolicy.LABEL

    /** Подпись пункта pin с учётом текущего состояния строки. */
    fun pinLabel(session: SessionItem): String = if (session.pinned) "Открепить" else "Закрепить"

    /** Подпись пункта archive с учётом текущего состояния строки. */
    fun archiveLabel(session: SessionItem): String = if (session.archived) "Вернуть из архива" else "В архив"

    /** Целевое значение флага pin для отправки в `POST /api/session/pin`. */
    fun nextPinned(session: SessionItem): Boolean = !session.pinned

    /** Целевое значение флага archive для отправки в `POST /api/session/archive`. */
    fun nextArchived(session: SessionItem): Boolean = !session.archived

    /**
     * Имя файла экспорта — из [ru.mars.network.export.SessionExportPolicy], чтобы
     * имена совпадали с уже покрытым тестами форматом (`<заголовок>-<id8>.md`).
     */
    fun exportFileName(session: SessionItem): String =
        ru.mars.network.export.SessionExportPolicy.fileNameFor(session.id, session.title)

    /**
     * Куда положить подпись «данные на …» для архивных строк в списке: архивная
     * строка — не ошибка и не свежая активность, её подпись объясняет, почему
     * диалог ушёл из основного списка.
     */
    fun archivedNote(session: SessionItem): String? = if (session.archived) "в архиве" else null
}
