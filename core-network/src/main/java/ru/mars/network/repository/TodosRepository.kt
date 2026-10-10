package ru.mars.network.repository

import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.SessionItem
import ru.mars.network.model.TodoSnapshot

/**
 * Result of resolving the todos of the current session: the source session plus its
 * latest settled `todo_state` snapshot. `snapshot == null` is the VALID "session never
 * wrote todos" state (mobile `TodosActivity` parity: «В текущей сессии задачи не
 * ставились») — it must never be conflated with a transport failure.
 */
data class SessionTodos(
    val session: SessionItem,
    val snapshot: TodoSnapshot?,
)

/**
 * Read-only facade over the session todo contract for "current tasks" screens
 * (mobile `TodosActivity` parity; wire contract — hermes-webui `api/todo_state.py`):
 *
 * - `GET /api/sessions` — candidate sessions ([listTodoSessions]: subagent children are
 *   dropped, both app UIs hide them by default);
 * - `GET /api/session?session_id=…` — session detail; only its `.todoState` is consumed.
 *
 * All HTTP and parsing is delegated to [SessionRepository] (the single `todo_state`
 * parse point — [TodoStateParser]); this class adds no transport and no parsing of its
 * own, only session resolution shared between screens.
 */
class TodosRepository(private val sessions: SessionRepository = SessionRepository()) {

    /** Sessions a todos screen may list: server order (newest first), subagent children excluded. */
    suspend fun listTodoSessions(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
    ): List<SessionItem> = sessions.getSessions(baseUrl, credential).filter { !it.isSubagent }

    /** Snapshot of one session; `null` when it never wrote todos or its detail failed to load. */
    suspend fun fetchTodos(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
    ): TodoSnapshot? = sessions.getSessionDetail(baseUrl, credential, sessionId)?.todoState

    /**
     * One-shot resolution for a "current tasks" screen: pick the session with [pick]
     * (default [pickCurrentSession] — mobile `TodosActivity.pickCurrentSession` parity),
     * then fetch its snapshot. Returns `null` only when there is no candidate session at
     * all; an empty list of sessions is indistinguishable from a server outage at this
     * layer ([SessionRepository.getSessions] fails soft), so UI copy must stay honest
     * about both.
     */
    suspend fun fetchCurrentTodos(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        pick: (List<SessionItem>) -> SessionItem? = { pickCurrentSession(it) },
    ): SessionTodos? {
        val session = pick(listTodoSessions(baseUrl, credential)) ?: return null
        return SessionTodos(session, fetchTodos(baseUrl, credential, session.id))
    }

    companion object {
        /**
         * The session whose todos are "current": the first one with a running or queued
         * turn ([ru.mars.network.model.ActiveTurnInfo.canReattach], `isStreaming` or a
         * pending user message), else the newest visible one. Subagent children never
         * win — their todos belong to a delegated worker, not to the user's screen.
         */
        fun pickCurrentSession(sessions: List<SessionItem>): SessionItem? {
            val visible = sessions.filter { !it.isSubagent }
            return visible.firstOrNull {
                it.activeTurn.canReattach || it.activeTurn.isStreaming || it.activeTurn.hasPendingUserMessage
            } ?: visible.firstOrNull()
        }
    }
}
