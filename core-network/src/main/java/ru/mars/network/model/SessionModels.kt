package ru.mars.network.model

/**
 * Pending attention summary attached to a session row (`attention`, hermes-webui
 * routes.py `_session_attention_summary`): `{"kind": "approval"|"clarify",
 * "count": N, "severity": "critical"|"question"}` or `null` when nothing is
 * waiting. Guaranteed on `/api/sessions` rows; parsed leniently wherever present.
 */
data class SessionAttention(
    val kind: String,
    val count: Int = 0,
    val severity: String? = null,
)

/**
 * Active-turn metadata shared by the session detail (`GET /api/session` →
 * `{session: {...}}`) and session list (`GET /api/sessions`) payloads.
 *
 * Wire contract (hermes-webui): `Session.compact(include_runtime=True)` emits
 * `active_stream_id` (stream of the running/pending turn, `null` when idle),
 * `is_streaming` (`active_stream_id` is alive in STREAMS; `false` when runtime
 * data was not requested), `has_pending_user_message` and `pending_started_at`
 * (epoch seconds, float). Sidebar rows additionally carry `attention`.
 *
 * Reattach recipe for the UI layer: when [canReattach], open
 * `GET /api/chat/stream?stream_id=` + [activeStreamId] via
 * `ChatRepository.attachToStream` — the server replays journaled events the
 * client missed and continues live; no `POST /api/chat/start`.
 */
data class ActiveTurnInfo(
    val activeStreamId: String? = null,
    val isStreaming: Boolean = false,
    val hasPendingUserMessage: Boolean = false,
    val pendingStartedAtEpochSeconds: Double? = null,
    val attention: SessionAttention? = null,
) {
    /** True when there is a stream id the client can reattach to. */
    val canReattach: Boolean get() = !activeStreamId.isNullOrBlank()
}

/**
 * One entry of a `todo_state` snapshot's `todos` list (hermes-webui
 * api/todo_state.py; WebUI parity static/ui.js `renderTodoRow`):
 * `{"id": str, "content": str, "status": "pending"|"in_progress"|"completed"|"cancelled"}`
 * (`text` is tolerated as a content alias). [status] is preserved verbatim —
 * mapping unknown statuses onto a rendering fallback is UI-layer work
 * (`todoStatusKey` in the WebUI), not a data-layer decision.
 */
data class TodoItem(
    val id: String? = null,
    val content: String = "",
    val status: String = "pending",
)

/** The `summary` block of a `todo_state` snapshot (counts per status + total). */
data class TodoSummary(
    val total: Int = 0,
    val pending: Int = 0,
    val inProgress: Int = 0,
    val completed: Int = 0,
    val cancelled: Int = 0,
)

/**
 * Normalized `todo_state` snapshot — the single deserialization contract of
 * hermes-webui api/todo_state.py (`{todos, summary, version}`, VERSION = 1).
 *
 * Two producers share the shape:
 * - Cold load: `GET /api/session` attaches `todo_state` derived from the
 *   settled transcript (`attach_todo_state`), parsed here into
 *   [SessionDetail.todoState].
 * - Live: the SSE `todo_state` event adds an envelope `{session_id, stream_id,
 *   source, ts}` on top of the same snapshot. It currently has NO carrier in
 *   the sealed [ChatEvent] (an exhaustive `when` over ChatEvent exists in
 *   app-mobile ChatUiState, so adding a variant would break the app modules);
 *   [tsEpochSeconds] is already part of this model so the future mapper
 *   integration parses the live payload with the same contract.
 *
 * `todos == []` is a VALID snapshot (the latest write cleared the list) —
 * never treat empty as absent.
 */
data class TodoSnapshot(
    val todos: List<TodoItem> = emptyList(),
    val summary: TodoSummary = TodoSummary(),
    val version: Int = 1,
    val tsEpochSeconds: Double? = null,
)

/** A session shown in the WebUI session list. */
data class SessionItem(
    val id: String,
    val title: String? = null,
    val preview: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val messageCount: Int? = null,
    /** Active-turn metadata for reattach (`active_stream_id`/`is_streaming`/...); idle default when absent. */
    val activeTurn: ActiveTurnInfo = ActiveTurnInfo(),
)

/**
 * A message persisted in a chat session. For `role == "user"` the content is
 * returned with the client-injected `[Workspace::v1: ...]` sentinel stripped
 * (WebUI `_strip_workspace_prefix` parity) — the sentinel is transport noise
 * the sender added itself, never display content.
 */
data class SessionMessage(
    val id: String? = null,
    val role: String,
    val content: String,
    val createdAt: String? = null,
    val eventId: String? = null,
)

/** Full session returned by the session detail endpoint. */
data class SessionDetail(
    val id: String,
    val title: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val messages: List<SessionMessage> = emptyList(),
    /** Active-turn metadata for reattach (`active_stream_id`/`is_streaming`/...); idle default when absent. */
    val activeTurn: ActiveTurnInfo = ActiveTurnInfo(),
    /** Latest settled todo snapshot (`todo_state`), `null` when the session never wrote todos. */
    val todoState: TodoSnapshot? = null,
)
