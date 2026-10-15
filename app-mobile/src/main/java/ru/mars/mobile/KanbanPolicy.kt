package ru.mars.mobile

import ru.mars.network.model.BoardSummary
import ru.mars.network.model.KanbanBoard
import ru.mars.network.model.KanbanStats
import ru.mars.network.model.KanbanTask
import ru.mars.network.model.KanbanTaskDetail

/**
 * Фаза экрана «Канбан» (read-only доска). Чистая JVM-модель — UI-слой
 * (KanbanActivity) только сопоставляет фазу с видимостью прогресса/доски/
 * статуса; все тексты, колонки и карточки считает [KanbanPolicy].
 */
enum class KanbanPhase {
    /** Первая загрузка или refresh: доска может быть пустой или показывать прошлые колонки. */
    LOADING,

    /** Доска загружена, есть хотя бы одна колонка. */
    READY,

    /** Доска загружена, но сервер не отдал ни одной колонки. */
    EMPTY,

    /** Доска недоступна (сеть/сервер). Возможны offline-safe колонки из кэша. */
    ERROR,

    /** Нет сохранённой пары (credential): загрузка невозможна до входа. */
    NO_AUTH,
}

/**
 * Тон статуса задачи — чистая семантика для раскраски (UI мапит тон на цвета
 * MarsTheme). Порядок enum не значим; значимо соответствие статус→тон в
 * [KanbanPolicy.toneFor].
 */
enum class KanbanStatusTone {
    /** Готово к работе (ready/queued/todo/open). */
    READY,

    /** В работе (running/working/claimed/in_progress). */
    RUNNING,

    /** Заблокировано (blocked + любой block_kind). */
    BLOCKED,

    /** Завершено (done/completed/archived). */
    DONE,

    /** Неизвестный/пустой статус — нейтральная раскраска. */
    NEUTRAL,
}

/**
 * Одна карточка задачи на доске. [id] — стабильный идентификатор для клика
 * (read-only detail). Все подписи уже отформатированы [KanbanPolicy]:
 * UI ничего не вычисляет.
 */
data class KanbanTaskCardUi(
    val id: String,
    val title: String,
    val status: String,
    val tone: KanbanStatusTone,
    val assigneeLabel: String,
    val priorityLabel: String?,
    val ageLabel: String?,
    val commentCount: Int,
    val childCount: Int,
    val isBlocked: Boolean,
    val isGoalMode: Boolean,
)

/** Одна колонка доски: имя в серверном порядке + счётчик + карточки. */
data class KanbanColumnUi(
    val name: String,
    val count: Int,
    val cards: List<KanbanTaskCardUi>,
)

/** Чип статистики: «готово · 3» (тон для цвета точки/цифры). */
data class KanbanStatChipUi(
    val status: String,
    val label: String,
    val value: Int,
    val tone: KanbanStatusTone,
)

/**
 * Сводка `GET /api/kanban/stats`: чипы по статусам в каноническом порядке +
 * строка-резюме (всего задач, возраст старейшей ready). [chips] пусты, если
 * сервер не отдал `by_status` — экран обязан это пережить (stats best-effort).
 */
data class KanbanStatsUi(
    val chips: List<KanbanStatChipUi>,
    val summary: String?,
)

/**
 * Полное состояние экрана: фаза, колонки (могут быть stale при ERROR —
 * offline-safe), статистика, внеполосное [notice] («нет сети»), центральный
 * [message] для фаз без доски и флаг [canRetry] (кнопка «Повторить»).
 * [latestEventId] — курсор polling-конверта board (`since`).
 */
data class KanbanUiState(
    val phase: KanbanPhase,
    val columns: List<KanbanColumnUi> = emptyList(),
    val stats: KanbanStatsUi? = null,
    val notice: String? = null,
    val message: String? = null,
    val canRetry: Boolean = false,
    /** Серверный флаг `read_only` из board-конверта (экран в любом случае read-only). */
    val serverReadOnly: Boolean = false,
    val latestEventId: Long = 0L,
    val taskCount: Int = 0,
    /** Переключатель досок: пустой список скрывает строку (старый мост их не отдаёт). */
    val boards: List<KanbanBoardChipUi> = emptyList(),
    /** Slug выбранной доски; null = доска по умолчанию (сервер решает, какая). */
    val currentBoardSlug: String? = null,
    /** Фильтр исполнителей; пустой список — строка фильтра не рисуется. */
    val assignees: List<KanbanAssigneeChipUi> = emptyList(),
    /** Выбранный исполнитель; null = без фильтра (все задачи доски). */
    val selectedAssignee: String? = null,
    /** Есть ли живой SSE-поток канбана прямо сейчас (индикатор в шапке). */
    val live: Boolean = false,
)

/**
 * Одна плашка в переключателе досок. [label] уже готов к показу: имя, а при
 * пустом имени — slug (доска без подписи не должна стать некликабельной
 * «пустышкой», но и выдумывать ей имя нельзя).
 */
data class KanbanBoardChipUi(
    val slug: String,
    val label: String,
    val isCurrent: Boolean,
    /** «12 задач» или null, когда сервер счётчик не отдал (не рисуем «0»). */
    val countLabel: String?,
)

/**
 * Одна плашка фильтра исполнителей. [isSelected] запекается в модель, чтобы UI
 * не сверял состояние сам.
 */
data class KanbanAssigneeChipUi(
    val name: String,
    val label: String,
    val countLabel: String?,
    val isSelected: Boolean,
)

/** Одна строка read-only диалога задачи («Исполнитель: mars»). */
data class KanbanDetailRowUi(val label: String, val value: String)

/** Один комментарий задачи в диалоге. */
data class KanbanDetailCommentUi(val author: String, val body: String, val whenLabel: String?)

/**
 * Модель read-only диалога задачи (`GET /api/kanban/tasks/{id}`). Никаких
 * действий кроме «Закрыть»: канбан на мобильном строго read-only
 * (server Bearer allowlist допускает только GET/HEAD /api/kanban/).
 */
data class KanbanTaskDetailUi(
    val id: String,
    val title: String,
    val status: String,
    val tone: KanbanStatusTone,
    val rows: List<KanbanDetailRowUi>,
    val body: String?,
    val comments: List<KanbanDetailCommentUi>,
    val eventLabels: List<String>,
    val parentIds: List<String>,
    val childIds: List<String>,
)

/**
 * Чистая политика экрана «Канбан» (UI -> state/domain -> network): тексты,
 * колонки, карточки, статистика и модель detail-диалога. Не знает ни про
 * Android, ни про сеть — данные приходят из
 * [ru.mars.network.repository.KanbanRepository] через [KanbanPresenter].
 *
 * Контракт read-only: policy/presenter не содержат и не порождают никаких
 * операций dispatch/create/edit/move/delete — только отображение GET-данных.
 */
object KanbanPolicy {

    const val SCREEN_TITLE = "Канбан"

    const val MSG_LOADING = "Загрузка доски…"
    const val MSG_EMPTY = "На доске пока нет колонок"
    const val MSG_ERROR = "Доска недоступна. Проверьте соединение и повторите."
    const val MSG_ERROR_OFFLINE = "Нет сети: доска недоступна"
    const val MSG_NO_AUTH = "Нужен вход: откройте приложение и пройдите пару"
    const val NOTICE_OFFLINE = "Нет сети"
    const val NOTICE_STALE = "Нет сети — показаны последние данные"
    const val NOTICE_READ_ONLY = "Доска только для чтения"

    const val RETRY_LABEL = "Повторить"
    const val CLOSE_LABEL = "Закрыть"
    const val UNASSIGNED_LABEL = "без исполнителя"
    const val MSG_DETAIL_LOADING = "Открываем задачу…"
    const val MSG_DETAIL_ERROR = "Не удалось открыть задачу"
    const val STATUS_UNKNOWN_LABEL = "без статуса"
    const val COLUMN_EMPTY_HINT = "Пусто"
    const val GOAL_BADGE = "goal"
    const val BLOCKED_BADGE = "блокировка"

    /** Заголовки строк переключателя досок и фильтра исполнителей. */
    const val BOARDS_LABEL = "Доски"
    const val ASSIGNEES_LABEL = "Исполнители"
    const val ALL_ASSIGNEES_LABEL = "Все"
    const val LIVE_BADGE = "в эфире"
    const val MSG_LIVE_LOST = "Поток событий прерван"

    /** «12 задач» в плашке доски/исполнителя; пустой счётчик не рисуем. */
    fun taskCountLabel(count: Int): String? = when {
        count <= 0 -> null
        count % 10 == 1 && count % 100 != 11 -> "$count задача"
        count % 10 in 2..4 && count % 100 !in 12..14 -> "$count задачи"
        else -> "$count задач"
    }

    /**
     * Переключатель досок. Пусто -> пустой список: экран не рисует строку вообще
     * (старые мосты `/api/kanban/boards` не имеют, и пустая «Доски» была бы шумом).
     * Подпись — имя, при пустом имени slug; текущая доска помечается [isCurrent],
     * чтобы UI не пересчитывал это по [KanbanUiState.currentBoardSlug].
     */
    fun boardChips(boards: List<BoardSummary>, current: String?): List<KanbanBoardChipUi> =
        boards.map { board ->
            KanbanBoardChipUi(
                slug = board.slug,
                label = board.name.ifBlank { board.slug },
                isCurrent = board.isCurrent || (current != null && board.slug == current),
                countLabel = taskCountLabel(board.total).takeIf { board.hasCounts },
            )
        }

    /**
     * Фильтр исполнителей: первая плашка — «Все» (снять фильтр), затем по имени.
     * Серверный список исполнителей доски (`board.assignees`) предпочитается
     * подробному `/assignees`: он описывает именно текущую доску, тогда как
     * справочник перечисляет всех известных мосту, включая чужих для неё.
     */
    fun assigneeChips(
        names: List<String>,
        selected: String?,
        counts: Map<String, Int> = emptyMap(),
    ): List<KanbanAssigneeChipUi> = buildList {
        add(
            KanbanAssigneeChipUi(
                name = ALL_ASSIGNEES_TOKEN,
                label = ALL_ASSIGNEES_LABEL,
                countLabel = null,
                isSelected = selected == null,
            )
        )
        names.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach { name ->
                add(
                    KanbanAssigneeChipUi(
                        name = name,
                        label = name,
                        countLabel = counts[name]?.let(::taskCountLabel),
                        isSelected = selected == name,
                    )
                )
            }
    }

    /** Токен плашки «Все» — не имя исполнителя, а команда снять фильтр. */
    const val ALL_ASSIGNEES_TOKEN = "\u0000all"

    /** Бейдж комментариев карточки («2 комментария» сокращаем до счётчика). */
    fun commentsLabel(count: Int): String = "$count комм."

    /** Бейдж дочерних задач карточки. */
    fun childrenLabel(count: Int): String = "$count подзадач"

    /**
     * Маппинг фазы экрана в общее состояние секций ([SectionScreenState]) для
     * рендера через SectionStateViews (волна 3, единый shell разделов).
     * Чистая функция — JVM-тестируется вместе с остальной политикой:
     * - центральное состояние есть только при непустом [KanbanUiState.message];
     * - тихий refresh (LOADING с живыми колонками, message=null) и READY — null,
     *   доска остаётся на экране без мигания;
     * - offline-safe stale-доска (ERROR с кэшем колонок, message=null) — null:
     *   экран показывает колонки + notice-баннер + «Повторить»;
     * - NO_AUTH (нет пары) показывается offline-тоном без retry — повтор
     *   бесполезен до входа;
     * - ERROR (включая «нет сети» при первой загрузке) — Error-вид: retry
     *   обязан быть доступен, когда state.canRetry.
     */
    fun statusStateFor(state: KanbanUiState): SectionScreenState? {
        val message = state.message ?: return null
        return when (state.phase) {
            KanbanPhase.LOADING -> SectionScreenState.Loading
            KanbanPhase.READY -> null
            KanbanPhase.EMPTY -> SectionScreenState.Empty(message)
            KanbanPhase.ERROR -> SectionScreenState.Error(message)
            KanbanPhase.NO_AUTH -> SectionScreenState.Offline(message)
        }
    }

    /** Канонический порядок статусов в статистике (остальные — алфавитно после). */
    private val STATUS_ORDER = listOf("ready", "running", "blocked", "done")

    /** Мэппинг статус→тон: неизвестные и пустые статусы — NEUTRAL (без выдумок). */
    fun toneFor(status: String): KanbanStatusTone = when (status.trim().lowercase()) {
        "ready", "queued", "todo", "open", "new" -> KanbanStatusTone.READY
        "running", "working", "claimed", "in_progress", "started" -> KanbanStatusTone.RUNNING
        "blocked" -> KanbanStatusTone.BLOCKED
        "done", "completed", "archived", "closed" -> KanbanStatusTone.DONE
        else -> KanbanStatusTone.NEUTRAL
    }

    /**
     * Возраст задачи в человекочитаемой форме: null если сервер не отдал
     * `age_seconds`; минуты до часа, часы до суток, дальше дни. Отрицательные
     * значения (рассинхрон часов) схлопываются в «только что».
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

    /** Подпись исполнителя: имя или явное «без исполнителя» (пустые имена не скрываем). */
    fun assigneeLabel(assignee: String?): String =
        assignee?.trim()?.takeIf { it.isNotEmpty() } ?: UNASSIGNED_LABEL

    /**
     * Метка приоритета: показываем только ненулевой приоритет (`priority`
     * dataclass-поля по умолчанию 0 — шуметь «P0» на каждой карточке не нужно).
     */
    fun priorityLabel(priority: Int): String? =
        if (priority != 0) "P$priority" else null

    /** Карточка задачи: все подписи готовятся здесь, UI только рисует. */
    fun cardFor(task: KanbanTask): KanbanTaskCardUi = KanbanTaskCardUi(
        id = task.id,
        title = task.title.ifBlank { "(без названия)" },
        status = task.status,
        tone = if (task.blockKind != null) KanbanStatusTone.BLOCKED else toneFor(task.status),
        assigneeLabel = assigneeLabel(task.assignee),
        priorityLabel = priorityLabel(task.priority),
        ageLabel = ageLabel(task.ageSeconds),
        commentCount = task.commentCount,
        childCount = task.childCount,
        isBlocked = task.blockKind != null,
        isGoalMode = task.goalMode,
    )

    /**
     * Колонки доски в серверном порядке; пустые колонки НЕ прячем (структура
     * доски — тоже информация). changed:false сюда не попадает: короткий
     * polling-конверт отсеивает [KanbanPresenter] до построения UI.
     */
    fun columnsFor(board: KanbanBoard): List<KanbanColumnUi> =
        board.columns.map { column ->
            val cards = column.tasks.map { cardFor(it) }
            KanbanColumnUi(
                name = column.name.ifBlank { "(колонка)" },
                count = cards.size,
                cards = cards,
            )
        }

    /**
     * Чипы статистики: канонические статусы первыми (даже с нулём — структура
     * доски стабильна), неизвестные статусы сервера — алфавитно следом.
     * Пустой `by_status` -> пустые чипы (экран показывает доску без сводки).
     */
    fun statsFor(stats: KanbanStats): KanbanStatsUi {
        val known = STATUS_ORDER.filter { stats.byStatus.containsKey(it) }
        val extra = stats.byStatus.keys.filter { it !in STATUS_ORDER }.sorted()
        val chips = (known + extra).map { status ->
            KanbanStatChipUi(
                status = status,
                label = status,
                value = stats.byStatus[status] ?: 0,
                tone = toneFor(status),
            )
        }
        val total = stats.byStatus.values.sum()
        val oldest = ageLabel(stats.oldestReadyAgeSeconds)
        val summary = when {
            total == 0 && oldest == null -> null
            oldest != null -> "Всего $total · старейшая ready: $oldest"
            else -> "Всего задач: $total"
        }
        return KanbanStatsUi(chips = chips, summary = summary)
    }

    /** Строки detail-диалога: только реально присутствующие поля (null не выдумываем). */
    fun detailRows(task: KanbanTask): List<KanbanDetailRowUi> = buildList {
        add(KanbanDetailRowUi("Исполнитель", assigneeLabel(task.assignee)))
        priorityLabel(task.priority)?.let { add(KanbanDetailRowUi("Приоритет", it)) }
        task.tenant?.takeIf { it.isNotBlank() }?.let { add(KanbanDetailRowUi("Тенант", it)) }
        task.branchName?.takeIf { it.isNotBlank() }?.let { add(KanbanDetailRowUi("Ветка", it)) }
        task.workspacePath?.takeIf { it.isNotBlank() }?.let { add(KanbanDetailRowUi("Рабочее пространство", it)) }
        ageLabel(task.ageSeconds)?.let { add(KanbanDetailRowUi("Возраст", it)) }
        task.blockKind?.takeIf { it.isNotBlank() }?.let {
            val suffix = if (task.blockRecurrences > 0) " (x${task.blockRecurrences})" else ""
            add(KanbanDetailRowUi("Блокировка", "$it$suffix"))
        }
        task.lastFailureError?.takeIf { it.isNotBlank() }?.let { add(KanbanDetailRowUi("Последняя ошибка", it)) }
        if (task.consecutiveFailures > 0) add(KanbanDetailRowUi("Подряд провалов", "${task.consecutiveFailures}"))
        task.modelOverride?.takeIf { it.isNotBlank() }?.let { add(KanbanDetailRowUi("Модель", it)) }
        task.reasoningEffort?.takeIf { it.isNotBlank() }?.let { add(KanbanDetailRowUi("Усилие", it)) }
        if (task.goalMode) add(KanbanDetailRowUi("Режим", "goal"))
        task.result?.takeIf { it.isNotBlank() }?.let { add(KanbanDetailRowUi("Результат", it)) }
    }

    /**
     * Полная модель read-only диалога задачи. [nowEpochSeconds] — необязательные
     * «сейчас» (Activity передаёт системные часы): нужны только для возрастных
     * меток комментариев; без них whenLabel = null и UI прячет метку.
     */
    fun detailUi(detail: KanbanTaskDetail, nowEpochSeconds: Long? = null): KanbanTaskDetailUi {
        val task = detail.task
        val comments = detail.comments.map { comment ->
            KanbanDetailCommentUi(
                author = comment.author.ifBlank { "?" },
                body = comment.body,
                whenLabel = comment.createdAt?.let { created ->
                    nowEpochSeconds?.let { ageLabel(it - created) }
                },
            )
        }
        return KanbanTaskDetailUi(
            id = task.id,
            title = task.title.ifBlank { "(без названия)" },
            status = task.status,
            tone = if (task.blockKind != null) KanbanStatusTone.BLOCKED else toneFor(task.status),
            rows = detailRows(task),
            body = task.body?.takeIf { it.isNotBlank() },
            comments = comments,
            eventLabels = detail.events.takeLast(MAX_DETAIL_EVENTS).map { it.kind },
            parentIds = detail.links.parents,
            childIds = detail.links.children,
        )
    }

    /** Хвост ленты событий в диалоге: последние N, чтобы не топить мобильный экран. */
    const val MAX_DETAIL_EVENTS = 8
}
