package ru.mars.tv.todos

import ru.mars.network.model.TodoItem
import ru.mars.network.model.TodoSnapshot

/**
 * Pure presentation policy of the TV «Задачи» screen (no Android, no I/O — JVM-testable,
 * [ru.mars.tv.TvSessionBrowserPolicy]/[ru.mars.tv.TvQuickChipsPolicy] parity). Every
 * DECISION — status wording, filtering, summary, empty states and focus restoration —
 * lives here; [TvTodosActivity] only renders what the policy returns.
 *
 * Status wording is mobile `TodosActivity` parity: completed reads «Готово» (struck
 * through), in_progress «В работе» (emphasized), cancelled «Отменено», anything else
 * «Ожидает». Unknown statuses survive at the data layer verbatim ([TodoItem] contract)
 * and render through the pending fallback.
 *
 * A snapshot of `null` and an EMPTY snapshot are different states and must read
 * differently ([summaryLine]): the first means the session never wrote todos, the
 * second means the latest write cleared the list ([TodoSnapshot] contract).
 */
object TvTodosPolicy {

    /** [restoreSelection] result when there is nothing to focus. */
    const val NO_SELECTION = -1

    /** Header when no candidate session was resolved (empty list or server unreachable). */
    const val NO_SESSION_HEADER = "Текущая сессия не найдена"

    /** Honest summary for the no-session state: the repository fails soft on both causes. */
    const val NO_SESSION_SUMMARY = "Список сессий пуст или сервер недоступен"

    /** Session exists but never wrote a `todo_state` snapshot — mobile parity. */
    const val NEVER_WROTE_SUMMARY = "В текущей сессии задачи не ставились"

    /** A valid EMPTY snapshot (the latest write cleared the list) — mobile parity. */
    const val EMPTY_LIST_SUMMARY = "Задачи не запланированы"

    /** The filtered view of a non-empty list came out empty. */
    const val EMPTY_FILTER = "Нет задач с выбранным статусом"

    /** Header prefix of a resolved session. */
    const val SESSION_PREFIX = "Сессия: "

    /**
     * Status filter row, in D-pad order (left to right). Mobile `TodosActivity.StatusFilter`
     * parity: ALL shows everything (cancelled included), the other three match the exact
     * server status token.
     */
    enum class TodosFilter(val label: String) {
        ALL("Все"),
        IN_PROGRESS("В работе"),
        COMPLETED("Выполнены"),
        PENDING("В очереди"),
    }

    /**
     * Semantic dot token of a todo row — the Activity maps it to theme colors
     * (mars_status_online / mars_status_info / mars_muted), the policy stays resource-free.
     */
    enum class StatusToken { DONE, ACTIVE, WAITING, CANCELLED }

    /** One rendered todo row: everything the TV shows, nothing it computes. */
    data class TodoRow(
        /** Stable focus key across re-renders: server id when present, else the list index. */
        val key: String,
        val content: String,
        val badge: String,
        val token: StatusToken,
        val struckThrough: Boolean,
        val emphasized: Boolean,
    )

    /** Status badge text — mobile `getStatusBadge` parity (unknown tokens read «Ожидает»). */
    fun statusBadge(status: String): String = when (status) {
        "completed" -> "Готово"
        "in_progress" -> "В работе"
        "cancelled" -> "Отменено"
        else -> "Ожидает"
    }

    /** Dot token of a status: done is green, in-progress blue, waiting/cancelled muted. */
    fun statusToken(status: String): StatusToken = when (status) {
        "completed" -> StatusToken.DONE
        "in_progress" -> StatusToken.ACTIVE
        "cancelled" -> StatusToken.CANCELLED
        else -> StatusToken.WAITING
    }

    /** Whether [item] passes [filter] — exact status match, ALL passes everything (mobile parity). */
    fun matches(item: TodoItem, filter: TodosFilter): Boolean = when (filter) {
        TodosFilter.ALL -> true
        TodosFilter.IN_PROGRESS -> item.status == "in_progress"
        TodosFilter.COMPLETED -> item.status == "completed"
        TodosFilter.PENDING -> item.status == "pending"
    }

    /**
     * Display rows of a snapshot under [filter], server order preserved. Blank-content
     * todos are dropped — an empty card is noise on a 10-foot screen. [TodoRow.key]
     * falls back to the list index when the server sent no id (focus must stay stable).
     */
    fun rows(todos: List<TodoItem>, filter: TodosFilter): List<TodoRow> =
        todos.withIndex()
            .filter { (_, item) -> item.content.isNotBlank() && matches(item, filter) }
            .map { (index, item) ->
                TodoRow(
                    key = item.id?.takeIf { it.isNotBlank() } ?: "#$index",
                    content = item.content,
                    badge = statusBadge(item.status),
                    token = statusToken(item.status),
                    struckThrough = item.status == "completed",
                    emphasized = item.status == "in_progress",
                )
            }

    /** Header line: the resolved session title, or the explicit no-session state. */
    fun headerLine(sessionTitle: String?): String =
        sessionTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { "$SESSION_PREFIX$it" } ?: NO_SESSION_HEADER

    /**
     * Summary line (live region): progress over a resolved snapshot, or the honest
     * no-session / never-wrote / cleared-list states. Percent math is mobile parity:
     * `done * 100 / total.coerceAtLeast(1)`, counted from the SAME list that renders.
     */
    fun summaryLine(sessionTitle: String?, snapshot: TodoSnapshot?): String {
        if (sessionTitle.isNullOrBlank() && snapshot == null) return NO_SESSION_SUMMARY
        if (snapshot == null) return NEVER_WROTE_SUMMARY
        val todos = snapshot.todos
        if (todos.isEmpty()) return EMPTY_LIST_SUMMARY
        val done = todos.count { it.status == "completed" }
        val percent = done * 100 / todos.size.coerceAtLeast(1)
        return "Выполнено $done из ${todos.size} задач ($percent%)"
    }

    /**
     * Line shown in the data area when the rendered list is empty; `null` while rows
     * exist. A filtered-to-empty list reads [EMPTY_FILTER]; a list-less state (no
     * session / never wrote / cleared snapshot) repeats [summaryLine] verbatim — one
     * source of truth, no divergent copy between header and body.
     */
    fun emptyStateLine(sessionTitle: String?, snapshot: TodoSnapshot?, filter: TodosFilter): String? {
        val todos = snapshot?.todos.orEmpty()
        return when {
            rows(todos, filter).isNotEmpty() -> null
            todos.isEmpty() -> summaryLine(sessionTitle, snapshot)
            else -> EMPTY_FILTER
        }
    }

    /**
     * Focus restoration after a re-render (filter switch, auto-refresh): the previous
     * index clamped into the new list — edges STAY, never wrap (a jumping focus reads
     * as a broken remote on a TV); [NO_SELECTION] when the list is empty.
     */
    fun restoreSelection(previous: Int, size: Int): Int =
        if (size <= 0) NO_SELECTION else previous.coerceIn(0, size - 1)
}
