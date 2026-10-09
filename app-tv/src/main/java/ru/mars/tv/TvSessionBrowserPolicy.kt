package ru.mars.tv

import ru.mars.network.model.SessionDetail
import ru.mars.network.model.SessionItem

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
 */
object TvSessionBrowserPolicy {

    /** Row title of a session without a (non-blank) title — mobile drawer parity. */
    const val UNTITLED = "Новый диалог"

    /** Dialog status line when the server returned no listable sessions. */
    const val EMPTY_LIST = "Список диалогов пуст"

    /** Status line when opening a session failed (detail == null / no id). */
    const val LOAD_FAILED = "Не удалось открыть диалог"

    /** Bubble authors — same literals [TvChatActivity.append] uses for the live chat. */
    const val USER_AUTHOR = "Вы"
    const val BOT_AUTHOR = "Марс"

    /** [initialSelection]/[move] result when there is nothing to focus. */
    const val NO_SELECTION = -1

    /** One focusable row of the dialog: everything the TV renders, nothing it computes. */
    data class SessionRow(
        val id: String,
        val title: String,
        /** Last-message preview; `null` when the server sent none. */
        val subtitle: String?,
        /** «N сообщений · субагент · только чтение» — `null` when nothing applies. */
        val meta: String?,
    )

    /** One rendered bubble of a loaded transcript. */
    data class TranscriptRow(val author: String, val text: String, val isUser: Boolean)

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
     * Display model of one session: title fallback, preview and the «count · source ·
     * read-only» meta line. No glyph symbols — the TV design system guard
     * (TvDesignSystemTest) bans them module-wide.
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
        )
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

    /**
     * Transcript of a loaded session, oldest first (server order): every persisted
     * message with non-blank content becomes one bubble; `user` reads «Вы», all other
     * roles (assistant/tool/system leftovers) read «Марс» — mobile `historyMessages` parity.
     */
    fun transcript(detail: SessionDetail): List<TranscriptRow> =
        detail.messages
            .filter { it.content.isNotBlank() }
            .map { message ->
                val isUser = message.role == "user"
                TranscriptRow(
                    author = if (isUser) USER_AUTHOR else BOT_AUTHOR,
                    text = message.content,
                    isUser = isUser,
                )
            }

    /**
     * Outcome of `getSessionDetail`: [LoadOutcome.Open] only for a detail with a real id
     * (the id is what the next chat/start binds to), [LoadOutcome.Failed] otherwise — the
     * network layer answers failures with `null` and must never open a nameless session.
     */
    fun loadOutcome(detail: SessionDetail?): LoadOutcome =
        if (detail != null && detail.id.isNotBlank()) LoadOutcome.Open(detail)
        else LoadOutcome.Failed(LOAD_FAILED)
}
