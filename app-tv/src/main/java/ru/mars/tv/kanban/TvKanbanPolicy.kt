package ru.mars.tv.kanban

import ru.mars.network.model.KanbanBoard
import ru.mars.network.model.KanbanStats
import ru.mars.network.model.KanbanTask

/**
 * Pure presentation policy of the TV «Канбан» screen (no Android, no I/O —
 * JVM-testable, [ru.mars.tv.todos.TvTodosPolicy]/[ru.mars.tv.cron.TvCronPolicy]
 * parity). Owns every DECISION — status tone, card labels, column grouping,
 * stats summary, empty states and focus restoration; [TvKanbanActivity] only
 * renders what the policy returns.
 *
 * Wording is mobile `KanbanPolicy` parity («без исполнителя», «N мин/ч/дн»,
 * «N комм.», «goal», «блокировка», «На доске пока нет колонок»). The screen is
 * STRICTLY READ-ONLY: the native device Bearer is accepted by the server only
 * for GET/HEAD on /api/kanban/... (hermes-webui `NATIVE_BEARER_READONLY_...`), so
 * the policy has no dispatch/create/edit/move wording at all.
 */
object TvKanbanPolicy {

    /** [restoreSelection] result when there is nothing to focus. */
    const val NO_SELECTION = -1

    /** Доска загружена, но сервер не отдал ни одной колонки — mobile parity. */
    const val EMPTY_BOARD = "На доске пока нет колонок"

    /** Пустая колонка: структуру доски не прячем (mobile parity). */
    const val COLUMN_EMPTY_HINT = "Пусто"

    /** Внеполосная плашка read-only — серверный Bearer допускает только GET/HEAD. */
    const val NOTICE_READ_ONLY = "Доска только для чтения"

    const val UNASSIGNED_LABEL = "без исполнителя"
    const val STATUS_UNKNOWN_LABEL = "без статуса"
    const val NO_TITLE_LABEL = "(без названия)"
    const val NO_COLUMN_LABEL = "(колонка)"
    const val GOAL_BADGE = "goal"
    const val BLOCKED_BADGE = "блокировка"

    /** Максимальный возраст снимка доски — операционные данные, окно как у «Задач» (12 ч). */
    const val CACHE_MAX_AGE_MS = 12L * 60 * 60 * 1000

    /**
     * Semantic tone of a task status — the Activity maps it to theme colors
     * (mars_status_info / mars_status_online / mars_status_error / mars_muted),
     * the policy stays resource-free. Mobile `KanbanStatusTone` parity.
     */
    enum class StatusToken { READY, RUNNING, BLOCKED, DONE, NEUTRAL }

    /** One rendered task card: everything the TV shows, nothing it computes. */
    data class CardRow(
        /** Stable focus key across re-renders: server id, else the list index. */
        val key: String,
        val title: String,
        val statusLabel: String,
        val token: StatusToken,
        /** «без исполнителя · 3 ч · P1» — parts only when real, never invented. */
        val metaLine: String,
        /** «2 комм. · 1 подзадач · блокировка · goal» — `null` when nothing to badge. */
        val badgesLine: String?,
    )

    /** One board column: server-order name + counter + cards. */
    data class ColumnGroup(
        val name: String,
        val count: Int,
        val cards: List<CardRow>,
    )

    /** Мэппинг статус->тон: неизвестные и пустые статусы — NEUTRAL (без выдумок). */
    fun toneFor(status: String): StatusToken = when (status.trim().lowercase()) {
        "ready", "queued", "todo", "open", "new" -> StatusToken.READY
        "running", "working", "claimed", "in_progress", "started" -> StatusToken.RUNNING
        "blocked" -> StatusToken.BLOCKED
        "done", "completed", "archived", "closed" -> StatusToken.DONE
        else -> StatusToken.NEUTRAL
    }

    /**
     * Возраст задачи в человекочитаемой форме: null если сервер не отдал
     * `age_seconds`; минуты до часа, часы до суток, дальше дни. Отрицательные
     * значения (рассинхрон часов) схлопываются в «только что» — mobile parity.
     */
    fun ageLabel(seconds: Long?): String? {
        if (seconds == null) return null
        if (seconds < 60) return "только что"
        val minutes = seconds / 60
        if (minutes < 60) return "$minutes мин"
        val hours = minutes / 60
        if (hours < 24) return "$hours ч"
        return "${hours / 24} дн"
    }

    /** Подпись исполнителя: имя или явное «без исполнителя» — mobile parity. */
    fun assigneeLabel(assignee: String?): String =
        assignee?.trim()?.takeIf { it.isNotEmpty() } ?: UNASSIGNED_LABEL

    /** Метка приоритета: только ненулевой (`P0` по умолчанию — шум) — mobile parity. */
    fun priorityLabel(priority: Int): String? =
        if (priority != 0) "P$priority" else null

    /** Бейдж комментариев карточки — mobile parity. */
    fun commentsLabel(count: Int): String = "$count комм."

    /** Бейдж дочерних задач карточки — mobile parity. */
    fun childrenLabel(count: Int): String = "$count подзадач"

    /** Карточка задачи: все подписи готовятся здесь, UI только рисует. */
    fun cardFor(task: KanbanTask, fallbackKey: String): CardRow {
        val blocked = task.blockKind != null
        val meta = buildList {
            add(assigneeLabel(task.assignee))
            ageLabel(task.ageSeconds)?.let { add(it) }
            priorityLabel(task.priority)?.let { add(it) }
        }.joinToString(" · ")
        val badges = buildList {
            if (task.commentCount > 0) add(commentsLabel(task.commentCount))
            if (task.childCount > 0) add(childrenLabel(task.childCount))
            if (blocked) add(BLOCKED_BADGE)
            if (task.goalMode) add(GOAL_BADGE)
        }.joinToString(" · ").takeIf { it.isNotEmpty() }
        return CardRow(
            key = task.id.takeIf { it.isNotBlank() } ?: fallbackKey,
            title = task.title.ifBlank { NO_TITLE_LABEL },
            statusLabel = task.status.trim().ifBlank { STATUS_UNKNOWN_LABEL },
            token = if (blocked) StatusToken.BLOCKED else toneFor(task.status),
            metaLine = meta,
            badgesLine = badges,
        )
    }

    /**
     * Колонки доски в серверном порядке; пустые колонки НЕ прячем (структура
     * доски — тоже информация) — mobile parity. `changed:false` не обрабатываем:
     * TV-экран всегда запрашивает полное состояние (без курсора `since`).
     */
    fun columns(board: KanbanBoard): List<ColumnGroup> =
        board.columns.mapIndexed { columnIndex, column ->
            val cards = column.tasks.mapIndexed { taskIndex, task ->
                cardFor(task, "#$columnIndex-$taskIndex")
            }
            ColumnGroup(
                name = column.name.ifBlank { NO_COLUMN_LABEL },
                count = cards.size,
                cards = cards,
            )
        }

    /** Общее число карточек на доске (для статусной строки). */
    fun taskCount(board: KanbanBoard): Int = board.columns.sumOf { it.tasks.size }

    /**
     * Чипы статистики одной строкой: канонические статусы первыми (даже с нулём —
     * структура доски стабильна), неизвестные статусы сервера — алфавитно следом;
     * «Всего N» в конце. `null` когда сервер не отдал `by_status` (stats
     * best-effort) и считать нечего — mobile `statsFor` parity.
     */
    fun statsLine(stats: KanbanStats?): String? {
        if (stats == null || stats.byStatus.isEmpty()) return null
        val canonical = listOf("ready", "running", "blocked", "done")
        val known = canonical.filter { stats.byStatus.containsKey(it) }
        val extra = stats.byStatus.keys.filter { it !in canonical }.sorted()
        val chips = (known + extra).map { status -> "$status ${stats.byStatus[status] ?: 0}" }
        val total = stats.byStatus.values.sum()
        return (chips + "Всего $total").joinToString(" · ")
    }

    /**
     * Строка-резюме под чипами: «Всего N · старейшая ready: X» — mobile parity;
     * `null` когда фактов нет (ни итога, ни возраста). Никогда не выдумываем «0».
     */
    fun statsSummary(stats: KanbanStats?): String? {
        if (stats == null) return null
        val total = stats.byStatus.values.sum()
        val oldest = ageLabel(stats.oldestReadyAgeSeconds)
        return when {
            oldest != null -> "Всего $total · старейшая ready: $oldest"
            total > 0 -> "Всего задач: $total"
            else -> null
        }
    }

    /**
     * Line shown in the data area when the board has no columns; `null` while
     * columns exist. [EMPTY_BOARD] mobile-parity: пустая доска — валидное
     * состояние, а не ошибка.
     */
    fun emptyLine(board: KanbanBoard): String? =
        if (board.columns.isEmpty()) EMPTY_BOARD else null

    /**
     * Focus restoration after a re-render (auto-refresh): the previous index
     * clamped into the new list — edges STAY, never wrap; [NO_SELECTION] when empty.
     */
    fun restoreSelection(previous: Int, size: Int): Int =
        if (size <= 0) NO_SELECTION else previous.coerceIn(0, size - 1)
}
