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
 * Состояние исполнителя-субагента из `GET /api/sessions` (`worker_status`,
 * hermes-webui api/worker_status.py `attach_worker_status`).
 *
 * [goal] — ТЕМА РАБОТЫ ребёнка: единственный её носитель, потому что `title`
 * у субагентов в базе NULL. Все поля nullable: null значит «сервер не сообщил»,
 * транспорт догадок не подставляет. Презентация (подпись, тон, длительность) —
 * работа UI-слоя (`SubagentWorkLabelPolicy`), модель её не считает.
 */
data class WorkerStatusInfo(
    /** Текст задачи субагента (`goal`); null/пусто — темы нет. */
    val goal: String? = null,
    /** Дословный серверный статус: `running`/`completed`/`failed`/`stopped`/`interrupted`/`unknown`. */
    val status: String? = null,
    /** Длительность работы в секундах (`duration_seconds`); null — не сообщена. */
    val durationSeconds: Double? = null,
    /** Момент старта, epoch-СЕКУНДЫ (`started_at`); null — не сообщён. */
    val startedAtEpochSeconds: Double? = null,
    /** Последний шаг ребёнка (`last_action`); null — не сообщён. */
    val lastAction: String? = null,
    /** Причина провала (`failure_reason`); null — не сообщена. */
    val failureReason: String? = null,
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
    /**
     * Server-side pin flag (`pinned` in `Session.compact()`). Toggled through
     * `POST /api/session/pin`; pinned rows are what the pin quota counts, so the
     * UI never computes this locally.
     */
    val pinned: Boolean = false,
    /**
     * Server-side archive flag (`archived` in `Session.compact()`). Toggled through
     * `POST /api/session/archive`; the server keeps archived rows in a separate
     * bucket of `GET /api/sessions`, so an archived row only disappears from the
     * list after the server confirms the mutation.
     */
    val archived: Boolean = false,
    /**
     * Состояние исполнителя-субагента (`worker_status`) — тема его работы, статус
     * и длительность. null = сервер блок не прислал (обычные сессии его не имеют,
     * старые серверы не отдают вовсе); подпись строки строит
     * `SubagentWorkLabelPolicy`, догадок здесь нет.
     */
    val workerStatus: WorkerStatusInfo? = null,
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

/**
 * Result of the turn-scoped server operations `POST /api/session/undo` and
 * `POST /api/session/retry`.
 *
 * Both endpoints answer `200 {"ok": true, "removed_count": N, ...}` on success and
 * `200 {"error": "..."}` on a refusal (the server deliberately avoids 4xx here so
 * the client can show a toast instead of a transport error). Failure cases carry
 * the server's own Russian-agnostic English text — `"Nothing to undo."` for an
 * empty exchange, `"No previous message to retry."` when the transcript has no
 * user turn left. That message is surfaced verbatim: it is server state, not a
 * client guess.
 *
 * [lastUserText] is the text the server pulled out of the transcript and, for
 * retry, expects the client to put back into the composer. [removedPreview] is a
 * server-truncated 40-char preview of the removed user message (undo only).
 */
data class SessionTurnOpResult(
    val ok: Boolean,
    val removedCount: Int = 0,
    val lastUserText: String? = null,
    val removedPreview: String? = null,
    val error: String? = null,
)

/**
 * Result of `POST /api/session/branch` — a new fork of an existing session.
 *
 * Body `{session_id, keep_count?, title?}`; `keep_count` is the number of
 * messages copied (`0` yields an empty branch, an absent field copies the whole
 * history) and an absent/blank `title` makes the server derive
 * `"<original title> (fork)"`. Answer: `200 {"session_id", "title",
 * "parent_session_id"}`.
 */
data class SessionBranchResult(
    val sessionId: String,
    val title: String? = null,
    val parentSessionId: String? = null,
)

/**
 * Result of `POST /api/session/title/regenerate` —
 * `200 {"session": {...}, "title": "...", "status": "...", "raw_preview": "..."}`.
 *
 * A refusal is a real HTTP error here (422 `"Could not generate a better title"`,
 * 403 for read-only imports), so [SessionTitleRegenerateOutcome.Failure] carries
 * that text instead of an `error` field.
 */
data class SessionTitleRegenerateResult(
    val title: String,
    val status: String? = null,
    val rawPreview: String? = null,
)

/**
 * Quota / rate-limit status of the ACTIVE provider — `GET /api/provider/quota`.
 *
 * The endpoint is in the device-Bearer allow-list for GET and ALWAYS answers 200,
 * so availability is read from the PAYLOAD, never from the HTTP code:
 * - `supported=false, status="unsupported"` — the provider exposes no quota at all
 *   (the live case for `custom:plusvibe-api`); [message] explains why.
 * - `supported=true, status="available"` — [label] + [quota] carry numbers.
 * - `status="no_key" | "invalid_key" | "unavailable"` — supported provider whose
 *   quota could not be read right now; retryable, and distinct from "unsupported".
 *
 * This is the PROVIDER account quota (credits / token windows). It has nothing to
 * do with the local context budget the composer's context pill shows
 * (`ContextBudgetPolicy`) — the two are separate sources that never share state.
 */
data class ProviderQuota(
    val ok: Boolean,
    val provider: String? = null,
    val displayName: String? = null,
    val supported: Boolean = false,
    val status: String? = null,
    val label: String? = null,
    val quota: ProviderQuotaAmounts? = null,
    val message: String? = null,
) {
    /**
     * True when the provider simply has no quota concept — a settled state, not an
     * error, and not worth a retry button.
     */
    val isUnsupported: Boolean get() = !supported || status.equals("unsupported", ignoreCase = true)

    /** True when quota exists but this read failed — a retry may help. */
    val isUnavailable: Boolean
        get() = supported && status in setOf("unavailable", "no_key", "invalid_key")
}

/**
 * Numeric block of a supported provider's quota (OpenRouter credits shape:
 * `{"limit_remaining", "usage", "limit"}`). Every field is nullable because the
 * wire omits what the provider did not report — the UI must not invent zeros.
 */
data class ProviderQuotaAmounts(
    val limitRemaining: Double? = null,
    val usage: Double? = null,
    val limit: Double? = null,
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
    /**
     * Per-session toolset override (`session.enabled_toolsets` of `GET /api/session`,
     * written by `POST /api/session/toolsets`). `null` means "no override" — the
     * session runs with the server's default toolset set, which is NOT the same as
     * an empty list (the server rejects an empty list outright). Non-null holds the
     * names the session is restricted to.
     */
    val enabledToolsets: List<String>? = null,
)
