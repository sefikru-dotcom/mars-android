package ru.mars.tv

import ru.mars.network.model.SessionAttention
import ru.mars.network.model.SessionDetail
import ru.mars.network.model.SessionItem
import ru.mars.tv.chat.TvToolGroupPolicy

/**
 * Pure session-browser policy of the TV chat screen (no Android, no I/O — JVM-testable).
 * Owns every DECISION of the «Диалоги» dialog; [TvChatActivity] only fetches
 * ([ru.mars.network.repository.SessionRepository]) and renders what the policy returns:
 *
 * - [visibleSessions]/[rows]: which sessions the dialog lists and how each row reads.
 *   Mirrors the mobile drawer (`ChatUiState.drawerSessions`): subagent children are
 *   suppressed unless they are the session currently on screen (the user must always
 *   see the row they are inside), an untitled session reads «Новый диалог».
 * - [initialSelection]/[move]/[selected]: D-pad navigation — focus opens on the current
 *   session, steps deterministically row by row and STAYS at the edges (never wraps:
 *   on a TV an escaping focus means losing the dialog, not cycling it).
 * - [loadOutcome]/[transcript]: a fetched [SessionDetail] turns into transcript bubbles
 *   (mobile `historyMessages` parity: `role == "user"` -> «Вы», anything else -> «Марс»;
 *   blank persisted messages are dropped — an empty bubble is noise on a 10-foot screen).
 * - [listOutcome]/[listStatusMessage]/[shouldOfferRetry]: offline honesty of the LIST
 *   fetch — core-network answers every failed fetch with an empty list, so the dialog
 *   separates the server-confirmed «empty» from the offline «unavailable + retry» using
 *   the connectivity probe snapshot instead of lying with [EMPTY_LIST].
 */
object TvSessionBrowserPolicy {

    /** Row title of a session without a (non-blank) title — mobile drawer parity. */
    const val UNTITLED = "Новый диалог"

    /** Dialog status line when the server returned no listable sessions. */
    const val EMPTY_LIST = "Список диалогов пуст"

    /**
     * Dialog status line when no list arrived AND the connectivity probe says the network
     * is down. core-network answers EVERY list-fetch failure (offline, server down,
     * timeout) with an empty list, so emptiness alone is not evidence of «no dialogs» —
     * an offline TV must read «unavailable» (+ retry), never «empty» ([listOutcome]).
     */
    const val LIST_UNREACHABLE = "Список диалогов недоступен — проверьте сеть"

    /** Label of the D-pad retry action rendered in the [ListOutcome.Unreachable] state. */
    const val RETRY_LABEL = "Повторить"

    /** Status line when opening a session failed (detail == null / no id). */
    const val LOAD_FAILED = "Не удалось открыть диалог"

    /** Bubble authors — same literals [TvChatActivity.append] uses for the live chat. */
    const val USER_AUTHOR = "Вы"
    const val BOT_AUTHOR = "Марс"

    /** [initialSelection]/[move] result when there is nothing to focus. */
    const val NO_SELECTION = -1

    /**
     * Meta-line marker of the dialog the user is CURRENTLY inside. Focus alone is not
     * enough on a 10-foot screen: the browser opens focused on the current row
     * ([initialSelection]), but once the user steps away nothing tells them which
     * dialog is live. The row gets this marker plus a ruby-tinted title.
     */
    const val CURRENT_MARKER = "текущий диалог"

    /** Бейдж строки: ход идёт прямо сейчас (mobile «Активный ход»). */
    const val LIVE_TURN = "Активный ход"

    /** Бейдж строки: сообщение в очереди, ход ещё не стартовал. */
    const val QUEUED_TURN = "Сообщение в очереди"

    /** One focusable row of the dialog: everything the TV renders, nothing it computes. */
    data class SessionRow(
        val id: String,
        val title: String,
        /** Last-message preview; `null` when the server sent none. */
        val subtitle: String?,
        /** «N сообщений · субагент · только чтение» — `null` when nothing applies. */
        val meta: String?,
        /**
         * Бейдж состояния хода/внимания (`attention` / `is_streaming` /
         * `has_pending_user_message` строки `GET /api/sessions`). `null` — ход
         * спокойный. Мобильный паритет: `ChatSessionsActivity` рисует ту же тройку.
         */
        val badge: RowBadge? = null,
        /**
         * Тема работы субагента (`worker_status.goal`) — подпись строки. `null` —
         * сервер блок не прислал либо строка не субагент.
         */
        val work: String? = null,
    )

    /** Бейдж строки: [text] готов к выводу, [tone] — тон из палитры. */
    data class RowBadge(val text: String, val tone: BadgeTone)

    /** Тон бейджа: цвет выбирает хост, в тексте глифов нет (design-гейт). */
    enum class BadgeTone {
        /** Ход ждёт решения пользователя — самый важный сигнал. */
        ATTENTION,

        /** Ход идёт прямо сейчас. */
        LIVE,

        /** Сообщение в очереди, ход ещё не стартовал. */
        QUEUED,
    }

    /** Вид строки транскрипта: обычный пузырь или свёрнутая карточка инструментов. */
    enum class TranscriptKind { MESSAGE, TOOL_GROUP }

    /** One rendered bubble of a loaded transcript. */
    data class TranscriptRow(
        val author: String,
        val text: String,
        val isUser: Boolean,
        /** Для [TranscriptKind.TOOL_GROUP] — имена вызовов серии (для строки имён). */
        val toolNames: List<String> = emptyList(),
        val kind: TranscriptKind = TranscriptKind.MESSAGE,
    )

    /** What opening a session produced: the loaded detail or an honest failure line. */
    sealed interface LoadOutcome {
        data class Open(val detail: SessionDetail) : LoadOutcome
        data class Failed(val message: String) : LoadOutcome
    }

    /**
     * Sessions the dialog lists: top-level rows plus, when the user is currently inside a
     * delegated subagent child, that child (a hidden current row would look like a lost
     * session). Mobile `drawerSessions(showSubagents = false)` parity — the TV has no
     * reveal toggle, so all OTHER subagent children stay suppressed.
     */
    fun visibleSessions(sessions: List<SessionItem>, currentSessionId: String? = null): List<SessionItem> =
        sessions.filter { !it.isSubagent || it.id == currentSessionId }

    /** The dialog's rows in server order (newest first — the API already sorts). */
    fun rows(sessions: List<SessionItem>, currentSessionId: String? = null): List<SessionRow> =
        visibleSessions(sessions, currentSessionId).map(::rowOf)

    /**
     * Display model of one session: title fallback, preview, the «count · source ·
     * read-only» meta line, the live/attention badge and the subagent work goal.
     * No glyph symbols — the TV design system guard (TvDesignSystemTest) bans them
     * module-wide.
     */
    fun rowOf(item: SessionItem): SessionRow {
        val meta = buildList {
            item.messageCount?.takeIf { it > 0 }?.let { add("$it ${messageWord(it)}") }
            if (item.isSubagent) add(item.sourceLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "субагент")
            if (item.readOnly) add("только чтение")
        }.joinToString(" · ").takeIf { it.isNotEmpty() }
        return SessionRow(
            id = item.id,
            title = item.title?.trim()?.takeIf { it.isNotEmpty() } ?: UNTITLED,
            subtitle = item.preview?.trim()?.takeIf { it.isNotEmpty() },
            meta = meta,
            badge = badgeOf(item),
            work = if (item.isSubagent) {
                item.workerStatus?.goal?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            },
        )
    }

    /**
     * Бейдж строки — ровно та тройка, что рисует мобильный список
     * (`ChatSessionsActivity`): ожидание решения важнее живого хода, живой ход —
     * важнее очереди. Порядок не случаен: на ТВ видно одну строку, и «требуется
     * подтверждение» — единственное состояние, где от пользователя ЧТО-ТО нужно.
     */
    fun badgeOf(item: SessionItem): RowBadge? {
        val turn = item.activeTurn
        turn.attention?.let { attention ->
            return RowBadge(
                text = attentionLabel(attention),
                tone = BadgeTone.ATTENTION,
            )
        }
        if (turn.isStreaming) return RowBadge(LIVE_TURN, BadgeTone.LIVE)
        if (turn.hasPendingUserMessage) return RowBadge(QUEUED_TURN, BadgeTone.QUEUED)
        return null
    }

    /** Человекочитаемая подпись ожидания (`attention` строки), мобильный паритет. */
    fun attentionLabel(attention: SessionAttention): String {
        val kind = when (attention.kind.trim().lowercase()) {
            "approval" -> "подтверждения"
            "clarify" -> "уточнения"
            else -> attention.kind.trim()
        }
        val count = if (attention.count > 1) " x${attention.count}" else ""
        return if (attention.severity?.trim()?.lowercase() == "critical") {
            "Критично — ожидает $kind$count"
        } else {
            "Ожидает $kind$count"
        }
    }

    /** Russian plural of «сообщение» for the meta line (1 сообщение, 2 сообщения, 5 сообщений, 11/21…). */
    fun messageWord(count: Int): String {
        val mod10 = count % 10
        val mod100 = count % 100
        return when {
            mod10 == 1 && mod100 != 11 -> "сообщение"
            mod10 in 2..4 && mod100 !in 12..14 -> "сообщения"
            else -> "сообщений"
        }
    }

    /**
     * Row the dialog focuses when it opens: the CURRENT session when its row is listed
     * (so the user sees where they are), otherwise the first row; [NO_SELECTION] for an
     * empty list (focus stays on «Закрыть»).
     */
    fun initialSelection(rows: List<SessionRow>, currentSessionId: String?): Int {
        if (rows.isEmpty()) return NO_SELECTION
        val current = rows.indexOfFirst { it.id == currentSessionId }
        return if (current >= 0) current else 0
    }

    /**
     * D-pad step: `selection + delta` clamped to the list — the edges STAY (no wrap),
     * because falling off the last row into the first one reads as a broken remote on a
     * TV. An out-of-range selection (fresh dialog, list shrank under a fetch) recovers
     * towards the travelled direction: down -> first row, up -> last row. [NO_SELECTION]
     * when there is nothing to focus.
     */
    fun move(selection: Int, size: Int, delta: Int): Int {
        if (size <= 0) return NO_SELECTION
        if (selection !in 0 until size) return if (delta > 0) 0 else size - 1
        return (selection + delta).coerceIn(0, size - 1)
    }

    /** The row under [index], or `null` when the index fell out of the list. */
    fun selected(rows: List<SessionRow>, index: Int): SessionRow? = rows.getOrNull(index)

    /** True when [row] is the dialog the user is currently inside (blank ids never match). */
    fun isCurrent(row: SessionRow, currentSessionId: String?): Boolean =
        !currentSessionId.isNullOrBlank() && row.id == currentSessionId

    /**
     * Meta line of a rendered row: for the CURRENT dialog the «текущий диалог» marker
     * leads (it is the most important fact about the row), the server meta follows;
     * every other row keeps its meta unchanged. `null` only when nothing applies.
     */
    fun metaWithCurrent(meta: String?, current: Boolean): String? = when {
        !current -> meta
        meta.isNullOrBlank() -> CURRENT_MARKER
        else -> "$CURRENT_MARKER · $meta"
    }

    /**
     * Transcript of a loaded session, oldest first (server order).
     *
     * `role:"tool"` строки НЕ становятся пузырями «Марс»: непрерывная серия таких
     * строк сворачивается в ОДНУ карточку [TranscriptKind.TOOL_GROUP] со сводкой
     * ([TvToolGroupPolicy] — мобильный паритет `ToolGroupPresenter`). Сырой вывод
     * инструмента на ТВ не показывается: это не речь модели, а на 10 футов — шум,
     * в котором теряется настоящий ответ.
     *
     * Всё остальное: `user` читает «Вы», прочие роли (assistant/system) — «Марс»;
     * пустые сообщения отбрасываются (пустой пузырь — визуальный мусор).
     */
    fun transcript(detail: SessionDetail): List<TranscriptRow> {
        val rows = mutableListOf<TranscriptRow>()
        // Имена вызовов текущей непрерывной серии tool-строк.
        var pendingTools = mutableListOf<String>()

        fun flushTools() {
            if (pendingTools.isEmpty()) return
            val names = pendingTools.toList()
            pendingTools = mutableListOf()
            rows += TranscriptRow(
                author = TvToolGroupPolicy.TOOL_AUTHOR,
                text = TvToolGroupPolicy.summary(names.size),
                isUser = false,
                toolNames = names,
                kind = TranscriptKind.TOOL_GROUP,
            )
        }

        detail.messages.forEach { message ->
            if (message.role.trim().lowercase() == "tool") {
                pendingTools += TvToolGroupPolicy.toolName(message.toolName) ?: TvToolGroupPolicy.UNKNOWN_TOOL
                return@forEach
            }
            flushTools()
            if (message.content.isBlank()) return@forEach
            val isUser = message.role == "user"
            rows += TranscriptRow(
                author = if (isUser) USER_AUTHOR else BOT_AUTHOR,
                text = message.content,
                isUser = isUser,
            )
        }
        flushTools()
        return rows
    }

    /**
     * Outcome of `getSessionDetail`: [LoadOutcome.Open] only for a detail with a real id
     * (the id is what the next chat/start binds to), [LoadOutcome.Failed] otherwise — the
     * network layer answers failures with `null` and must never open a nameless session.
     */
    fun loadOutcome(detail: SessionDetail?): LoadOutcome =
        if (detail != null && detail.id.isNotBlank()) LoadOutcome.Open(detail)
        else LoadOutcome.Failed(LOAD_FAILED)

    /**
     * What the dialog makes of a fetched session LIST. core-network maps every list-fetch
     * failure (radio down, server down, timeout) onto an EMPTY list, so emptiness alone
     * cannot distinguish «no dialogs» from «unreachable»: [Unreachable] keeps the offline
     * dead end honest and retryable, [Empty] is reserved for a server-confirmed nothing.
     */
    sealed interface ListOutcome {
        /** The fetch produced listable rows — render them (connectivity is irrelevant). */
        data class Show(val rows: List<SessionRow>) : ListOutcome

        /** Nothing is listable and the network was up: the honest empty state. */
        object Empty : ListOutcome

        /** Nothing arrived and the network is down: honest failure + a retry action. */
        object Unreachable : ListOutcome
    }

    /**
     * The dialog verdict for one `getSessions` result: any VISIBLE row wins regardless of
     * connectivity (data beats the probe — a stale-but-real list is still navigable),
     * otherwise [networkAvailable] (the [TvConnectivityProbe] snapshot taken at render
     * time) decides between the server-confirmed [ListOutcome.Empty] and the offline
     * [ListOutcome.Unreachable]. A list whose only entries are suppressed subagent
     * children counts as empty — the user cannot pick any of them anyway.
     */
    fun listOutcome(
        sessions: List<SessionItem>,
        currentSessionId: String?,
        networkAvailable: Boolean,
    ): ListOutcome {
        val visible = rows(sessions, currentSessionId)
        return when {
            visible.isNotEmpty() -> ListOutcome.Show(visible)
            networkAvailable -> ListOutcome.Empty
            else -> ListOutcome.Unreachable
        }
    }

    /**
     * Status line for [outcome]: `null` for [ListOutcome.Show] (the rows speak, the status
     * line hides), [EMPTY_LIST] for the server-confirmed empty state, [LIST_UNREACHABLE]
     * for the offline dead end. The two failure-free states must never read alike.
     */
    fun listStatusMessage(outcome: ListOutcome): String? = when (outcome) {
        is ListOutcome.Show -> null
        ListOutcome.Empty -> EMPTY_LIST
        ListOutcome.Unreachable -> LIST_UNREACHABLE
    }

    /**
     * True only for [ListOutcome.Unreachable]: a dead network is retryable, so the dialog
     * renders a «Повторить» action; a server-confirmed empty list is a settled verdict —
     * offering a retry there would be noise.
     */
    fun shouldOfferRetry(outcome: ListOutcome): Boolean = outcome is ListOutcome.Unreachable
}
