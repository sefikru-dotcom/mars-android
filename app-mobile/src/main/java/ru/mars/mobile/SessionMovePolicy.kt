package ru.mars.mobile

import ru.mars.network.model.SessionItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Чистая политика переноса диалога в другого родителя (`POST /api/session/move`).
 *
 * Переносить имеет смысл только дочернюю строку: субагента (`read_only`) или форк
 * с `parent_session_id`. У корневой строки переносить некуда — она и есть верхний
 * уровень, поэтому пункт меню ей не показывается ([canMove]).
 *
 * Кандидаты в цели ([targets]) — КОРНЕВЫЕ диалоги из уже загруженного списка:
 * - сам переносимый диалог исключён (перенос в себя — бессмыслица);
 * - его потомки исключены: перенос в собственного потомка сделал бы цикл в дереве,
 *   который сервер всё равно отклонит, — предлагать такой вариант в UI нельзя;
 * - текущий родитель исключён: перенос «куда уже стою» — no-op, лишний пункт;
 * - субагенты и архивные строки исключены: первые не могут быть контейнером
 *   (у них `read_only`), вторые вне активного списка.
 *
 * Подписи целей — заголовок и дата, без сырого id: id нужен только запросу.
 * Без Android-импортов — покрывается JVM unit-тестами.
 */
object SessionMovePolicy {

    /** Подпись пункта меню. */
    const val LABEL = "Перенести"

    /** Заголовок диалога выбора цели. */
    const val DIALOG_TITLE = "Перенести в…"

    /** Целей нет (все диалоги — потомки переносимого или список пуст). */
    const val NO_TARGETS = "Нет диалога, в который можно перенести"

    /** Заголовок строки-цели, если сервер не отдал название. */
    const val UNTITLED = "Новый диалог"

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /**
     * True для строк, которые вообще можно перенести: субагент или форк с родителем.
     * Пустой id отсекается — такую строку нечем адресовать на сервере.
     */
    fun canMove(session: SessionItem): Boolean =
        session.id.isNotBlank() &&
            (session.isSubagent || !session.parentSessionId.isNullOrBlank())

    /**
     * Цели переноса для [moving] из загруженного списка [sessions], в порядке списка
     * (сервер отдаёт свежие сверху). Пустой результат — переносить некуда.
     */
    fun targets(sessions: List<SessionItem>, moving: SessionItem): List<SessionItem> {
        if (!canMove(moving)) return emptyList()
        val excluded = descendantsOf(sessions, moving.id).toMutableSet().apply { add(moving.id) }
        moving.parentSessionId?.takeIf { it.isNotBlank() }?.let { excluded.add(it) }
        return sessions
            .filter { it.id.isNotBlank() && it.id !in excluded }
            .filter { it.parentSessionId.isNullOrBlank() }
            .filter { !it.isSubagent }
            .filter { !it.archived }
            .distinctBy { it.id }
    }

    /**
     * Подпись цели в диалоге: «Заголовок · 14.10.2026». Дата — из `updated_at`
     * (свежесть диалога), при её отсутствии из `created_at`; нераспознанная метка
     * даёт подпись без даты, а не «01.01.1970».
     */
    fun targetLabel(target: SessionItem, zone: ZoneId = ZoneId.systemDefault()): String {
        val title = target.title?.trim()?.takeIf { it.isNotEmpty() } ?: UNTITLED
        val date = dateText(target, zone) ?: return title
        return "$title · $date"
    }

    /** Дата диалога в виде «14.10.2026»; null — метки нет или она не распознана. */
    fun dateText(target: SessionItem, zone: ZoneId = ZoneId.systemDefault()): String? {
        val millis = MessageTimePolicy.parseServerTimestamp(target.updatedAt ?: target.createdAt)
        if (millis <= MessageTimePolicy.UNKNOWN_TIMESTAMP) return null
        return DATE_FORMAT.format(Instant.ofEpochMilli(millis).atZone(zone))
    }

    /** Тост успеха: сервер подтвердил перенос в [target]. */
    fun successMessage(target: SessionItem): String {
        val title = target.title?.trim()?.takeIf { it.isNotEmpty() } ?: UNTITLED
        return "Диалог перенесён: $title"
    }

    /**
     * Тост отказа. Текст сервера («Session not found», «Project not found») показываем
     * как есть — это состояние сервера, а не догадка клиента; пустой ответ даёт
     * нейтральную формулировку.
     */
    fun failureMessage(serverMessage: String?): String {
        val text = serverMessage?.trim()?.takeIf { it.isNotEmpty() } ?: return "Не удалось перенести диалог"
        return "Не удалось перенести: $text"
    }

    /** Все потомки [rootId] по `parent_session_id` — транзитивно (BFS по списку). */
    private fun descendantsOf(sessions: List<SessionItem>, rootId: String): Set<String> {
        val found = mutableSetOf<String>()
        val queue = ArrayDeque<String>().apply { add(rootId) }
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            sessions.forEach { candidate ->
                val candidateParent = candidate.parentSessionId
                if (!candidateParent.isNullOrBlank() && candidateParent == parent && found.add(candidate.id)) {
                    queue.add(candidate.id)
                }
            }
        }
        return found
    }
}
