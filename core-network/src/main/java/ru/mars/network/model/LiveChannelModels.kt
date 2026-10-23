package ru.mars.network.model

/**
 * Events of the three live channels the Android client subscribes to instead of polling:
 * `/api/sessions/events`, `/api/approval/stream`, `/api/clarify/stream`.
 *
 * Wire truth (verified read-only in hermes-webui):
 * - `api/session_events.py` + `api/route_session_events.py` — the session feed is an
 *   **invalidation** feed, not a row feed: every frame is
 *   `{type: "sessions_changed", version, reason, profile?, session_id?}` and carries NO row
 *   fields. The client therefore refetches `GET /api/sessions` (the same request the manual
 *   refresh already makes); [SessionListEvent.sessionId] tells it which row is affected so the
 *   UI can keep scroll position and highlight instead of rebuilding blindly.
 * - `api/route_approvals.py::_handle_approval_sse_stream` and
 *   `api/clarify.py::_handle_clarify_sse_stream` — both push the SAME shape the polling
 *   endpoints return: `{pending: {...}|null, pending_count: N}` under the event names
 *   `initial` (snapshot taken atomically with the subscription) and `approval` / `clarify`
 *   (every later change, including `pending: null` when the queue empties).
 */

/** What a `sessions_changed` invalidation means for the sidebar row [SessionListEvent.sessionId]. */
enum class SessionListAction {
    /** A new dialog appeared (`reason = session_new` / `session_import`). */
    ADDED,

    /** A dialog disappeared (`reason = session_delete`). */
    REMOVED,

    /** An existing dialog changed (title, attention badge, worker status, cron completion). */
    UPDATED,

    /** Server-scoped or unscoped change: refetch the whole list, no single row to point at. */
    REFRESH,
}

/**
 * One `/api/sessions/events` frame.
 *
 * @property action derived from [reason] (the server's free-form tag) and the presence of
 *   [sessionId]; unknown reasons degrade to [SessionListAction.REFRESH] — a full refetch is
 *   always safe, a wrong single-row patch is not.
 * @property reason the raw server tag (`session_new`, `attention_pending`, …) kept for
 *   diagnostics and for callers that want stricter handling than [action].
 * @property version monotonically growing server counter; null when the frame omitted it.
 * @property profile non-root profile the event is scoped to; null = all profiles.
 * @property sessionId affected dialog; null = whole list.
 */
data class SessionListEvent(
    val action: SessionListAction,
    val reason: String,
    val version: Long? = null,
    val profile: String? = null,
    val sessionId: String? = null,
    val rawType: String = SESSIONS_CHANGED_TYPE,
) {
    companion object {
        const val SESSIONS_CHANGED_TYPE = "sessions_changed"
    }
}

/**
 * One `/api/approval/stream` frame: the queue head plus the queue length.
 *
 * `payload == null` (with `pendingCount == 0`) is a REAL state, not a parse failure: the server
 * sends it when the queue emptied (answer accepted, stale sweep, session reset) and the card
 * must come down. [StreamEventMapper][ru.mars.network.repository.StreamEventMapper] returns
 * `null` instead when the frame could not be understood at all.
 */
data class ApprovalStreamUpdate(val payload: ApprovalPayload?, val pendingCount: Int) {
    /** True when the server says nothing is waiting — the card must be hidden. */
    val isEmpty: Boolean get() = payload == null || pendingCount <= 0
}

/** One `/api/clarify/stream` frame; same null-is-a-state contract as [ApprovalStreamUpdate]. */
data class ClarifyStreamUpdate(val payload: ClarifyPayload?, val pendingCount: Int) {
    /** True when the server says nothing is waiting — the card must be hidden. */
    val isEmpty: Boolean get() = payload == null || pendingCount <= 0
}
