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
 * hermes-webui api/todo_state.py (`{todos, summary, version}`, VERSION = 1),
 * parsed by ONE point ([ru.mars.network.repository.TodoStateParser]) for both
 * producers:
 * - Cold load: `GET /api/session` attaches `todo_state` derived from the
 *   settled transcript (`attach_todo_state`) → [SessionDetail.todoState]; the
 *   envelope fields ([sessionId]/[streamId]/[source]) stay null.
 * - Live: the SSE `todo_state` event adds an envelope `{session_id, stream_id,
 *   source, ts}` on top of the same snapshot. It deliberately has NO carrier
 *   in the sealed [ChatEvent] (an exhaustive `when` over ChatEvent exists in
 *   app-mobile ChatUiState and app-tv, so a new variant would break the app
 *   modules); delivery goes through the `ChatRepository.onTodoState` transport
 *   callback (a non-sealed side channel on `sendMessage`/`attachToStream`),
 *   so live updates are never silently dropped and never leak into the chat
 *   transcript.
 *
 * `todos == []` is a VALID snapshot (the latest write cleared the list) —
 * never treat empty as absent.
 */
data class TodoSnapshot(
    val todos: List<TodoItem> = emptyList(),
    val summary: TodoSummary = TodoSummary(),
    val version: Int = 1,
    val tsEpochSeconds: Double? = null,
    /** Live-envelope session the snapshot belongs to; null on the cold path. */
    val sessionId: String? = null,
    /** Live-envelope stream that broadcast the snapshot; null on the cold path. */
    val streamId: String? = null,
    /** Live-envelope write source (`tool`/`api`/…); null on the cold path. */
    val source: String? = null,
)

/**
 * A session shown in the WebUI session list (`GET /api/sessions` rows,
 * `Session.compact()` wire contract).
 *
 * Provenance fields (hermes-webui models.py `_state_db_source` overlay +
 * `normalize_agent_session_source`, PR #1294): [sourceTag]/[rawSource] carry
 * the raw state.db source (`"webui"`, `"subagent"`, `"cron"`, …),
 * [sessionSource] the normalized bucket (`"webui"`/`"cli"`/`"messaging"`/
 * `"cron"`/`"other"`/`"fork"`), [sourceLabel] the human label. Delegated
 * subagent children additionally get `read_only = true` and a
 * [parentSessionId] pointing at the delegating session.
 */
data class SessionItem(
    val id: String,
    val title: String? = null,
    val preview: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val messageCount: Int? = null,
    /** Active-turn metadata for reattach (`active_stream_id`/`is_streaming`/...); idle default when absent. */
    val activeTurn: ActiveTurnInfo = ActiveTurnInfo(),
    /** Normalized source bucket (`session_source`): `webui`/`cli`/`messaging`/`cron`/`other`/`fork`. */
    val sessionSource: String? = null,
    /** Raw state.db source tag (`source_tag`): `webui`, `subagent`, `cron`, … */
    val sourceTag: String? = null,
    /** Raw source alias (`raw_source`), same vocabulary as [sourceTag]. */
    val rawSource: String? = null,
    /** Human-readable source label (`source_label`): `WebUI`, `Subagent`, … */
    val sourceLabel: String? = null,
    /** Server-side read-only flag (`read_only`) — true for delegated subagent children. */
    val readOnly: Boolean = false,
    /** Delegating/branch parent (`parent_session_id`), null for top-level sessions. */
    val parentSessionId: String? = null,
    /** Effective model identity returned by the server for this session. */
    val model: String? = null,
    val modelProvider: String? = null,
) {
    /**
     * True for delegated subagent children (WebUI parity #5244/#5305): the
     * WebUI sidebar never promotes them to top-level rows — they stack under
     * their parent's lineage and are suppressed when the parent is out of
     * scope. The normalized [sessionSource] for them is `"other"`, so the
     * reliable marker is the raw source tag. The drawer hides these rows by
     * default (toggle to reveal); see `ChatUiState.drawerSessions`.
     */
    val isSubagent: Boolean
        get() = "subagent".equals(sourceTag?.trim(), ignoreCase = true) ||
            "subagent".equals(rawSource?.trim(), ignoreCase = true) ||
            "subagent".equals(sessionSource?.trim(), ignoreCase = true)
}

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
    /**
     * Service `role:"tool"` rows only: the tool that produced the row
     * (wire `tool_name`, fallback `name`). Persisted history carries one row
     * per tool result; the display layer folds it into a collapsed tool card
     * on its assistant anchor instead of rendering raw output as a bubble.
     */
    val toolName: String? = null,
    /** Correlation id of the tool call (wire `tool_call_id`), kept for card identity/dedupe. */
    val toolCallId: String? = null,
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
    /**
     * The session's CURRENT server-side workspace path (`GET /api/session` →
     * `session.workspace`, emitted by `Session.compact()`; also present on the
     * `POST /api/session/new` response). This is the authoritative value the
     * client echoes back as `ChatRepository.sendMessage(workspace = …)` on the
     * next send and the source for the `[Workspace::v1: …]` sentinel
     * ([WorkspacePrefix]). `null` when the server omitted the field (older
     * payloads) — never guess a path; `null` means "server default".
     *
     * Note: `Session.compact()` also emits `created_workspace` (the immutable
     * birth workspace, #6672) — [workspace] is the LIVE one that follows
     * mid-session switches.
     */
    val workspace: String? = null,
    /** Effective model identity returned by the server for this session. */
    val model: String? = null,
    val modelProvider: String? = null,
    /**
     * Server-side composer draft of this session (`session.composer_draft` of
     * `GET /api/session`) — the text the user left in the composer and never
     * sent. Reading it is ONLY possible here: `GET /api/session/draft` has no
     * GET branch on the server, so the detail payload is the read path (writes
     * go through `POST /api/session/draft`).
     *
     * The wire value is an object (`{text, files}`) on the current server and a
     * plain string on older/leaner payloads — both are accepted; `null` or an
     * empty text means "no draft". This is transport state, not a message: it
     * never enters the transcript.
     */
    val composerDraft: String? = null,
)
