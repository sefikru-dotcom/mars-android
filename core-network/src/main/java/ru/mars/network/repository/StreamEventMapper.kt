package ru.mars.network.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ApprovalStreamUpdate
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ChatStartResponse
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.model.ClarifyStreamUpdate
import ru.mars.network.model.ContextStatusUpdate
import ru.mars.network.model.ErrorOrigin
import ru.mars.network.model.GoalContinuation
import ru.mars.network.model.GoalUpdate
import ru.mars.network.model.SessionListAction
import ru.mars.network.model.SessionListEvent
import ru.mars.network.model.SessionTitleUpdate
import ru.mars.network.model.StreamSideEvent
import ru.mars.network.model.SubagentStatusUpdate
import ru.mars.network.model.SubagentUpdate
import ru.mars.network.model.TodoSnapshot

/**
 * Maps raw SSE frames (event name + opaque frame id + JSON payload) from
 * `/api/chat/stream` and the legacy `/chat` endpoint onto the sealed [ChatEvent] model.
 *
 * Wire contract (server: hermes-webui `api/streaming.py`):
 * - `tool`            — `{event_type, name, preview, args, tid?}` (tool started; `tid` only on the structured path)
 * - `tool_complete`   — `{event_type, name, preview, args, tid?, duration?, is_error?}` (`preview` is the result snippet)
 * - `interim_assistant` — `{text, already_streamed, reasoning_echo?}`; when `already_streamed` is true the text
 *   was already delivered via token chunks and must NOT be emitted again (frontend behaves the same).
 * - `approval`        — `{approval_id, command, description, pattern_key(s), allow_permanent,
 *   allow_session, run_id?, _gateway_mirror_token?, pending_count, ...}` → [ChatEvent.ApprovalRequired]
 *   preserving the structured payload (ids/options) required by `POST /api/approval/respond`.
 * - `clarify`         — `{clarify_id, question, choices_offered, session_id, requested_at,
 *   timeout_seconds, expires_at}` → [ChatEvent.ClarifyRequired] preserving the structured payload
 *   required by `POST /api/clarify/respond`.
 * - `title`           — `{session_id, title}`; re-binds the session id ([ChatEvent.SessionBound]);
 *   the title TEXT has no sealed carrier and travels via [sideEvent] → [SessionTitleUpdate].
 * - `warning`         — `{type, message}` non-fatal notice (e.g. provider fallback).
 * - `pending_steer_leftover` — `{session_id, text}`: steer text the agent never consumed; typed as [ru.mars.network.model.ChatEvent.SteerLeftover].
 * - `metering`        — `{tps, tps_available, estimated, high, low, active, session_id, usage: {input_tokens,
 *   output_tokens, context_length, threshold_tokens, last_prompt_tokens,
 *   post_compression_context_tokens_estimate, ...}}` (api/metering.py `get_stats` +
 *   api/streaming.py `_live_usage_snapshot`): the `usage` block feeds the context-window
 *   indicator ([ChatEvent.ContextUsage]); percent parity with WebUI `_syncCtxIndicator`
 *   (static/ui.js): prompt tokens (`post_compression_context_tokens_estimate` when present,
 *   else `last_prompt_tokens` — NEVER the cumulative `input_tokens`, #1436) over
 *   `context_length`, clamped to 0..100.
 * - `done` is NOT terminal: `title` and `stream_end` follow it; `stream_end`/`cancel`/`error`/`apperror` are terminal.
 * - subagent/delegation frames — hermes-agent `tools/delegate_tool_progress.py` (`_ChildProgressRelay`)
 *   and the gateway runs API (`gateway/platforms/api_server_runs.py` `_SUBAGENT_EVENT_KEYS`) emit
 *   `subagent.start` / `subagent.complete` (`{goal, task_index, task_count, status, summary,
 *   failure_reason, output_tail, ...}`) plus `delegate.*` lifecycle aliases: mapped to a readable
 *   [ChatEvent.Thought] trace, NEVER body text. Child noise (`subagent.text`/`subagent.thinking`/
 *   `subagent.tool`/`subagent.progress`, `subagent_progress`, `delegate.task_progress`/`task_thinking`/
 *   `tool_started`/`tool_completed`) is dropped (WebUI parity). `message.delta` (gateway runs text
 *   delta, runner-relay reattach) maps to [ChatEvent.Chunk] like `token`.
 * - ANTI-GIBBERISH CONTRACT: text-bearing fields (`text`/`delta`/`content`) are extracted
 *   STRING-TYPED only ([stringOnly]). A frame delivered under the server's `message` fallback
 *   event name (routes.py `_runner_event_name` / `_replay_run_journal`) can carry a
 *   snapshot-shaped payload whose `content` is a LIST of message objects — coercing that with
 *   toString() would dump a `{role=assistant, ...}` sheet into the bubble. Same rule for the
 *   phase label and the error message: raw JSON never reaches the transcript.
 */
object StreamEventMapper {

    /** Максимальная длина строкового поля одного tool-кадра (args/result/preview), симв. */
    const val MAX_TOOL_PAYLOAD_CHARS = 4_000

    /** @return mapped event or `null` when the frame carries nothing representable (metadata/duplicate). */
    fun map(eventName: String?, eventId: String?, payload: String): ChatEvent? {
        val obj = parseObject(payload)
        return when (eventName?.trim()?.ifEmpty { null }) {
            "session", "session_bound" -> sessionId(obj, payload)?.let { ChatEvent.SessionBound(it) }
            // {session_id, title}: keep the session binding; the title TEXT has no sealed
            // carrier and is delivered via [sideEvent] → [SessionTitleUpdate].
            "title" -> sessionId(obj, payload)?.let { ChatEvent.SessionBound(it) }
            "done", "stream_end", "cancel" -> ChatEvent.Done
            // Server-origin seam: `error`/`apperror` frames are the agent runtime's
            // TERMINAL verdict delivered over a healthy stream — tagged ErrorOrigin.SERVER
            // so recovery layers never re-classify arbitrary server text as a retryable
            // network failure (transport-level errors are built by ChatRepository itself
            // and keep the default ErrorOrigin.TRANSPORT).
            "error", "apperror" -> ChatEvent.Error(errorMessage(obj, payload), ErrorOrigin.SERVER)
            "reasoning", "thought", "thinking" -> textOf(obj, payload)?.let { ChatEvent.Thought(it) }
            "interim_assistant" -> interimAssistant(obj, eventId)
            "tool_call", "tool" -> toolCall(obj)
            "tool_result", "tool_complete" -> toolResult(obj)
            "approval" -> approvalPayload(obj)?.let { ChatEvent.ApprovalRequired(it) }
            "clarify" -> clarifyPayload(obj)?.let { ChatEvent.ClarifyRequired(it) }
            // `message` string-only; the raw payload is a fallback ONLY for non-JSON
            // bodies so a parsed frame without a message can't dump JSON into thoughts.
            "warning" -> (stringOnly(obj, "message")
                ?: (if (obj == null) payload.trim().ifBlank { null } else null))
                ?.let { ChatEvent.Thought("Предупреждение: $it") }
            "pending_steer_leftover" -> stringOnly(obj, "text")
                ?.let { ChatEvent.SteerLeftover(it) }
            "status", "phase", "compressing", "compressed" -> phase(obj, payload, eventName)
            "metering", "context", "context_usage", "usage" -> contextUsage(obj)
            // ---- subagent/delegation lifecycle: readable trace in the THOUGHTS area,
            // never body text. Explicit branches so these frames can never fall through
            // to the `message`/null text-chunk path below.
            "subagent.start", "delegate.task_spawned" -> subagentTrace(obj, started = true)
            "subagent.complete", "delegate.task_completed", "delegate.task_failed" -> subagentTrace(obj, started = false)
            // Child progress noise dropped explicitly (WebUI static/ui.js keeps only the
            // delegate_task tool card + lifecycle; api/gateway_chat drops these as noise).
            // The child's raw `output_tail`/free text must never surface in the parent chat.
            "subagent.text", "subagent.thinking", "subagent.tool", "subagent.progress",
            "subagent_progress", "delegate.task_progress", "delegate.task_thinking",
            "delegate.tool_started", "delegate.tool_completed" -> null
            // Gateway runs text delta (runner-relay reattach path): body text, string-only.
            "message.delta" -> stringOnly(obj, "delta")?.let { ChatEvent.Chunk(it, eventId) }
            "token", "delta", "chunk", "message", null -> chunk(obj, payload, eventId)
            // Unknown frames (state_saved, ...) carry no chat content;
            // `todo_state` is deliberately NOT mapped onto ChatEvent — it is
            // delivered via [todoState] on the side channel (see below), and the
            // `title` text / `goal` / `goal_continue` / `context_status` frames
            // travel via [sideEvent].
            else -> null
        }
    }

    /**
     * Live `todo_state` SSE frame (server contract: `event: todo_state`,
     * `data:` = the normalized snapshot `{todos, summary, version}` inside the
     * envelope `{session_id, stream_id, source, ts}` — hermes-webui
     * api/todo_state.py `_broadcast`).
     *
     * It has NO carrier in the sealed [ChatEvent]: app modules hold exhaustive
     * `when` expressions over ChatEvent (ChatUiState, app-tv), so a new variant
     * would break their compile. Delivery instead goes through the
     * `ChatRepository.onTodoState` transport callback, which calls this method —
     * the frame never reaches [map] and never pollutes the chat transcript.
     *
     * Parsing is delegated to [TodoStateParser] — the SAME single point the cold
     * `SessionDetail.todoState` uses (no duplicated contract).
     *
     * @return the snapshot when [eventName] is `todo_state` and the payload is a
     *   snapshot (`todos` list present); `null` for any other event name or a
     *   non-snapshot payload.
     */
    fun todoState(eventName: String?, payload: String): TodoSnapshot? =
        if (eventName?.trim() == "todo_state") TodoStateParser.parsePayload(payload) else null

    /**
     * SSE metadata frames with no sealed [ChatEvent] carrier → typed
     * [StreamSideEvent] for the `ChatRepository.onSideEvent` transport callback
     * (same delivery pattern as [todoState]), or null for any other frame.
     *
     * Handled frames (verified against hermes-webui api/streaming.py):
     * - `title` → [SessionTitleUpdate]: `{session_id, title}` — the title text
     *   [ChatEvent.SessionBound] drops. Blank/missing title → null (nothing to
     *   deliver; the session binding itself still flows through [map]).
     * - `goal` → [GoalUpdate]: `{session_id, state, message, message_key,
     *   message_args?, decision?}` with `state` ∈ `evaluating`/`continuing`/
     *   `idle` preserved verbatim.
     * - `goal_continue` → [GoalContinuation]: `{session_id, continuation_prompt,
     *   text, message, message_key, message_args, decision}` (`text` is a wire
     *   alias of `continuation_prompt`). DELIVERY ONLY — the continuation is
     *   never auto-sent by the transport; queuing it as the next user message
     *   is an explicit UI-layer decision (WebUI /goal parity).
     * - `context_status` → [ContextStatusUpdate]: `{session_id, prefill:
     *   {status, source, label, message_count, error?, compacted?}}` — статус
     *   префилл-контекста хода (api/streaming.py `_public_prefill_context_status`).
     *   Кадр без `prefill`/`status` → null (доставлять нечего).
     *
     * Independent of [map]: a `title` frame yields BOTH [ChatEvent.SessionBound]
     * (via [map]) and a [SessionTitleUpdate] (via this function).
     */
    fun sideEvent(eventName: String?, payload: String): StreamSideEvent? {
        val name = eventName?.trim()
        if (name == "title" || name == "goal" || name == "goal_continue" || name == "context_status") {
            val obj = parseObject(payload) ?: return null
            return when (name) {
                "title" -> stringField(obj, "title")
                    ?.let { SessionTitleUpdate(sessionId(obj, payload), it) }
                "goal" -> goalUpdate(obj, payload)
                "goal_continue" -> goalContinuation(obj, payload)
                else -> contextStatusUpdate(obj, payload)
            }
        }
        // Жизненный цикл субагентов + терминальные кадры хода — сайд-канал доски
        // субагентов ([SubagentStatusUpdate]); запечатанный поток продолжает получать
        // Thought-трейс через [map] (оба канала независимы, как у `title`).
        return subagentUpdate(name, payload)?.let { SubagentStatusUpdate(it) }
    }

    /**
     * Кадр начала хода (`POST /api/chat/start` response body) → [ChatStartResponse].
     *
     * `stream_id` обязателен (без него хода нет). [ChatStartResponse.model]/
     * [ChatStartResponse.provider] читаются из `effective_model`/`effective_model_provider`
     * (фактическая модель хода, WebUI static/messages.js parity) с фолбэком на
     * `model`/`model_provider`; ключи аддитивны и условны — absent/blank/структурные
     * значения дают null («сервер не сообщил»), это не ошибка.
     */
    fun startResponse(json: String): ChatStartResponse? {
        val obj = parseObject(json) ?: return null
        val streamId = idField(obj, "stream_id") ?: return null
        return ChatStartResponse(
            streamId = streamId,
            sessionId = idField(obj, "session_id"),
            model = stringOnly(obj, "effective_model") ?: stringOnly(obj, "model"),
            provider = stringOnly(obj, "effective_model_provider") ?: stringOnly(obj, "model_provider"),
        )
    }

    /** Терминальные кадры хода: после них активный ребёнок продолжить не может. */
    private val TURN_ENDED_FRAMES = setOf("cancel", "error", "apperror")

    /** Статусы ожидания в wire-кадрах субагентов (классификация — у [SubagentStatusPolicy]). */
    private val SUBAGENT_WAITING_STATUSES = setOf("waiting", "awaiting", "blocked", "paused", "pending_approval")

    /** Потолок длины фазы ребёнка: `preview` прогресс-кадра может содержать аргументы инструмента. */
    private const val MAX_SUBAGENT_PHASE_CHARS = 200

    /**
     * Кадр жизненного цикла субагента → [SubagentUpdate] для доски субагентов (P0-2).
     *
     * Wire-контракт (hermes-agent tools/delegate_tool_progress.py `_ChildProgressRelay`
     * + gateway/platforms/api_server_runs.py `_SUBAGENT_EVENT_KEYS`): каждый кадр несёт
     * идентичность `{task_index, task_count, goal, subagent_id, child_session_id?,
     * delegation_id?, parent_id, depth, model?}`; `subagent.progress` — человекочитаемый
     * `preview` (фаза), `subagent.complete` — `status`/`failure_reason`/`summary`.
     *
     * Правила разбора:
     * - `cancel`/`error`/`apperror` (ход умер/остановлен) → [SubagentUpdate.Kind.TurnStopped]
     *   даже с мусорным payload — остановка хода важнее его содержимого;
     * - childId: `subagent_id` → `child_session_id` → `delegation_id`; все отсутствуют →
     *   событие без адреса (политика его игнорирует, но доставка сохраняется);
     * - displayName: `goal` → `preview` → «Субагент N/M» (ТОЛЬКО у старта; preview
     *   прогресс-кадра — это фаза, именем ребёнка он не становится);
     * - шумовые кадры (`subagent.text`/`thinking`/`tool`, `delegate.task_thinking`,
     *   `delegate.tool_started`/`tool_completed`) и нерелевантные имена → null:
     *   запечатанный поток их уже отбрасывает, и факта состояния они не несут;
     * - непроходимый payload у кадров субагента → null (доставлять нечего);
     * - `status` передаётся дословно в [SubagentUpdate.rawStatus] — классификация
     *   делается в [SubagentStatusPolicy], не здесь; `delegate.task_failed` без
     *   `status` получает rawStatus «failed» (имя кадра — сам факт провала).
     */
    fun subagentUpdate(eventName: String?, payload: String): SubagentUpdate? {
        val name = eventName?.trim().orEmpty()
        if (name in TURN_ENDED_FRAMES) return SubagentUpdate(SubagentUpdate.Kind.TurnStopped, childId = null)
        if (!name.startsWith("subagent.") && !name.startsWith("subagent_") && !name.startsWith("delegate.")) return null
        val baseKind = when (name) {
            "subagent.start", "delegate.task_spawned" -> SubagentUpdate.Kind.Start
            "subagent.progress", "subagent_progress", "delegate.task_progress" -> SubagentUpdate.Kind.Progress
            "subagent.complete", "delegate.task_completed", "delegate.task_failed" -> SubagentUpdate.Kind.Complete
            else -> return null
        }
        val obj = parseObject(payload) ?: return null
        val childId = idField(obj, "subagent_id") ?: idField(obj, "child_session_id") ?: idField(obj, "delegation_id")
        val rawStatus = stringOnly(obj, "status")
            ?: if (name == "delegate.task_failed") "failed" else null
        val kind = if (baseKind == SubagentUpdate.Kind.Progress &&
            rawStatus != null && rawStatus.lowercase() in SUBAGENT_WAITING_STATUSES
        ) {
            SubagentUpdate.Kind.Waiting
        } else {
            baseKind
        }
        val goal = stringOnly(obj, "goal")
        val preview = stringOnly(obj, "preview")
        val displayName = if (baseKind == SubagentUpdate.Kind.Start) {
            goal ?: preview ?: taskLabel(obj)
        } else {
            goal
        }
        return SubagentUpdate(
            kind = kind,
            childId = childId,
            displayName = displayName,
            model = stringOnly(obj, "model"),
            phase = if (baseKind == SubagentUpdate.Kind.Progress) preview?.take(MAX_SUBAGENT_PHASE_CHARS) else null,
            rawStatus = rawStatus,
            failureReason = stringOnly(obj, "failure_reason"),
        )
    }

    /** «Субагент N/M» по `task_index`/`task_count` (тот же формат, что у [subagentTrace]). */
    private fun taskLabel(obj: Map<String, Any?>): String? {
        val index = positiveInt(obj["task_index"])
        val count = positiveInt(obj["task_count"])
        return if (index != null && count != null && index <= count) "Субагент $index/$count" else null
    }

    /**
     * SSE `goal` → [GoalUpdate]. A frame without `state` is tolerated only when
     * it still carries a message; a frame with neither is dropped (null).
     */
    private fun goalUpdate(obj: Map<String, Any?>, payload: String): GoalUpdate? {
        val state = stringField(obj, "state")
        val message = stringField(obj, "message")
        if (state == null && message == null) return null
        return GoalUpdate(
            sessionId = sessionId(obj, payload),
            state = state.orEmpty(),
            message = message,
            messageKey = stringField(obj, "message_key"),
            messageArgs = stringList(obj["message_args"]),
            decisionJson = decisionJson(obj),
        )
    }

    /**
     * SSE `goal_continue` → [GoalContinuation]. `continuation_prompt` is
     * canonical, `text` is the wire alias; a frame with neither carries no
     * continuation and is dropped (null).
     */
    private fun goalContinuation(obj: Map<String, Any?>, payload: String): GoalContinuation? {
        val prompt = stringField(obj, "continuation_prompt") ?: stringField(obj, "text") ?: return null
        return GoalContinuation(
            sessionId = sessionId(obj, payload),
            continuationPrompt = prompt,
            message = stringField(obj, "message"),
            messageKey = stringField(obj, "message_key"),
            messageArgs = stringList(obj["message_args"]),
            decisionJson = decisionJson(obj),
        )
    }

    /**
     * SSE `context_status` → [ContextStatusUpdate]. Форма серверного payload
     * (api/streaming.py `_public_prefill_context_status`): `{session_id,
     * prefill: {status, source, label, message_count, error?, compacted?, ...}}`.
     * Поля `prefill` доставляются дословно (WebUI-дефолты `not_configured`/
     * `session recall` применяет UI-презентер, не транспорт); кадр без `prefill`
     * или без `status` ничего не несёт и отбрасывается (null).
     */
    private fun contextStatusUpdate(obj: Map<String, Any?>, payload: String): ContextStatusUpdate? {
        @Suppress("UNCHECKED_CAST")
        val prefill = obj["prefill"] as? Map<String, Any?> ?: return null
        val status = stringField(prefill, "status") ?: return null
        return ContextStatusUpdate(
            sessionId = sessionId(obj, payload),
            status = status,
            label = stringField(prefill, "label"),
            source = stringField(prefill, "source"),
            messageCount = intField(prefill, "message_count") ?: 0,
            error = stringField(prefill, "error"),
            compacted = prefill["compacted"] == true,
            // Turn-start model attribution (волна C, фича A): аддитивные ключи кадра
            // `model`/`model_provider` — фактическая модель хода. Отсутствуют/пусты →
            // null («сервер не сообщил»); запрошенная клиентом модель НЕ подставляется.
            model = stringField(obj, "model"),
            modelProvider = stringField(obj, "model_provider"),
        )
    }

    /** Raw compact JSON of the opaque `decision` object, null when absent. */
    private fun decisionJson(obj: Map<String, Any?>): String? =
        obj["decision"]?.let { jsonValue(it).takeIf(String::isNotBlank) }

    /**
     * Parses the context-window portion of a `metering`/`usage` frame
     * (`_live_usage_snapshot` payload, either nested under `usage` or flat).
     * Returns null when the frame carries no usable data: an unknown window
     * (`context_length` <= 0) must NOT become a fake percentage, and zero
     * prompt tokens means nothing has been measured yet (WebUI shows "·").
     */
    private fun contextUsage(obj: Map<String, Any?>?): ChatEvent.ContextUsage? {
        obj ?: return null
        @Suppress("UNCHECKED_CAST")
        val usage = (obj["usage"] as? Map<String, Any?>) ?: obj
        val maxTokens = intField(usage, "context_length", "context_window", "max_tokens") ?: return null
        if (maxTokens <= 0) return null
        // #1436 parity: the post-compression estimate (when positive) wins, then
        // last_prompt_tokens; the cumulative input_tokens is NEVER a percentage base.
        val postCompression = intField(usage, "post_compression_context_tokens_estimate")?.takeIf { it > 0 }
        val usedTokens = postCompression ?: intField(usage, "last_prompt_tokens", "tokens_used") ?: return null
        if (usedTokens <= 0) return null
        // WebUI parity: Math.round(used/window*100), clamped like the ring (pct=min(100,rawPct)).
        val percent = Math.round(usedTokens.toLong() * 100.0 / maxTokens).toInt().coerceIn(0, 100)
        return ChatEvent.ContextUsage(usedTokens, maxTokens, percent)
    }

    private fun intField(obj: Map<String, Any?>, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key -> (obj[key] as? Number)?.toInt() }

    /**
     * Context-window snapshot of a NON-stream payload (session detail, history
     * header) — the same `context_length` / `threshold_tokens` /
     * `last_prompt_tokens` trio a `metering` frame carries, but sitting flat on
     * the session object instead of under `usage`. Public entry point so
     * `SessionRepository` can seed the composer ring on session load without
     * duplicating the percent maths or the #1436 invariants.
     *
     * Deliberately reuses [contextUsage]: one implementation of "post-compression
     * estimate wins, cumulative input_tokens never counts, unknown window is not
     * a percentage" — a second copy would drift from the stream path.
     */
    fun contextUsageOf(payload: Map<String, Any?>?): ChatEvent.ContextUsage? = contextUsage(payload)

    private fun interimAssistant(obj: Map<String, Any?>?, eventId: String?): ChatEvent? {
        if (obj == null) return null
        // Duplicate guard: text already streamed as token chunks must not be appended twice.
        if (obj["already_streamed"] == true) return null
        val text = stringOnly(obj, "text")?.trim().orEmpty()
        if (text.isEmpty()) return null
        return ChatEvent.Chunk(text, eventId)
    }

    private fun toolCall(obj: Map<String, Any?>?): ChatEvent.ToolCall? = obj?.let { m ->
        val id = idField(m, "tid") ?: idField(m, "id")
        val name = stringField(m, "name") ?: stringField(m, "tool").orEmpty()
        val args = jsonValue(m["arguments"] ?: m["args"])
        val preview = stringOnly(m, "preview").orEmpty()
        val usableArgs = args.takeIf { it.isNotBlank() && it != "{}" && it != "[]" }.orEmpty()
        ChatEvent.ToolCall(id, name, capToolPayload(usableArgs.ifBlank { preview }))
    }

    private fun toolResult(obj: Map<String, Any?>?): ChatEvent.ToolResult? = obj?.let { m ->
        val id = idField(m, "tid") ?: idField(m, "id")
        val name = stringField(m, "name") ?: stringField(m, "tool").orEmpty()
        // Structured result/content is serialized as JSON, never Kotlin Map.toString().
        val preview = stringOnly(m, "preview")
        val legacy = jsonValue(m["result"] ?: m["content"]).ifBlank { null }
        ChatEvent.ToolResult(id, name, capToolPayload(preview ?: legacy.orEmpty()))
    }

    /**
     * Единый ограничитель служебного tool-вывода (live-стрим И сохранённая история):
     * кадр/строка больше [MAX_TOOL_PAYLOAD_CHARS] обрезается с явным маркером полной
     * длины — карточка инструмента не должна превращаться в мегабайтный мегапузырь.
     */
    fun capToolPayload(value: String): String {
        if (value.length <= MAX_TOOL_PAYLOAD_CHARS) return value
        val marker = "\n… обрезано: полная длина ${value.length} симв."
        return value.take((MAX_TOOL_PAYLOAD_CHARS - marker.length).coerceAtLeast(0)) + marker
    }

    private fun chunk(obj: Map<String, Any?>?, payload: String, eventId: String?): ChatEvent? {
        if (obj == null) return null
        // STRING-typed fields only ([stringOnly]): structured `text`/`content` values
        // (Map/List — e.g. a snapshot-shaped payload under the server's `message`
        // fallback event name) are dropped instead of being toString()-dumped into
        // the bubble as a gibberish sheet.
        val text = listOf("text", "delta", "content").firstNotNullOfOrNull { stringOnly(obj, it) } ?: return null
        return ChatEvent.Chunk(text, eventId)
    }

    /**
     * Parses a `clarify` payload (`api/clarify.py` entry data). Public: the same shape is served by
     * `GET /api/clarify/pending` (`{"pending": {...}}`) and reused by InteractiveRequestsRepository.
     * Returns null when the frame carries neither a question nor choices (nothing to render/answer).
     */
    fun clarifyPayload(obj: Map<String, Any?>?): ClarifyPayload? {
        obj ?: return null
        // Frontend parity (messages.js showClarifyCard): question falls back to description.
        val question = stringField(obj, "question") ?: stringField(obj, "description").orEmpty()
        val choices = stringList(obj["choices_offered"] ?: obj["choices"])
        if (question.isBlank() && choices.isEmpty()) return null
        return ClarifyPayload(
            clarifyId = idField(obj, "clarify_id"),
            question = question,
            choices = choices,
            sessionId = idField(obj, "session_id"),
            requestedAtEpochSeconds = (obj["requested_at"] as? Number)?.toDouble(),
            timeoutSeconds = (obj["timeout_seconds"] as? Number)?.toInt(),
            expiresAtEpochSeconds = (obj["expires_at"] as? Number)?.toDouble(),
            pendingCount = positiveInt(obj["pending_count"]) ?: 1,
        )
    }

    /**
     * Parses an `approval` payload (`tools/approval.py` data + `route_approvals.submit_pending`
     * head stamp). Public: the same shape is served by `GET /api/approval/pending`
     * (`{"pending": {...}, "pending_count": N}`) and reused by InteractiveRequestsRepository.
     * Returns null when the frame carries no command/description/tool at all (nothing to approve).
     */
    fun approvalPayload(obj: Map<String, Any?>?): ApprovalPayload? {
        obj ?: return null
        val command = stringField(obj, "command").orEmpty()
        val description = stringField(obj, "description").orEmpty()
        val tool = stringField(obj, "tool") ?: stringField(obj, "name")
        if (command.isBlank() && description.isBlank() && tool.isNullOrBlank()) return null
        val patternKeys = stringList(obj["pattern_keys"]).ifEmpty { listOfNotNull(stringField(obj, "pattern_key")) }
        return ApprovalPayload(
            approvalId = idField(obj, "approval_id"),
            command = command,
            description = description,
            patternKeys = patternKeys,
            // Missing flags: never offer a permanent grant the server did not sanction; session-wide
            // approval is safe for every non-smart-denied prompt (tools/approval.py sets both explicitly).
            allowPermanent = obj["allow_permanent"] as? Boolean ?: false,
            allowSession = obj["allow_session"] as? Boolean ?: true,
            smartDenied = obj["smart_denied"] == true,
            runId = idField(obj, "run_id"),
            mirrorToken = idField(obj, "_gateway_mirror_token"),
            pendingCount = positiveInt(obj["pending_count"]) ?: 1,
            tool = tool,
            riskLevel = stringField(obj, "risk_level"),
            choices = stringList(obj["choices"]),
        )
    }

    /**
     * `/api/sessions/events` frame → [SessionListEvent].
     *
     * Wire contract (hermes-webui `api/route_session_events.py` + `api/session_events.py`
     * `_sessions_changed_payload`): the event name is the payload's own `type`
     * (`sessions_changed`) and the data is `{type, version, reason, profile?, session_id?}`.
     * There are NO row fields on the wire — the server only says "something changed", so the
     * caller refetches `GET /api/sessions` exactly like the manual refresh does.
     *
     * Returns null for frames that are not session invalidations (another event name, a
     * non-JSON body, an empty object) so the transport never refreshes on garbage.
     */
    fun sessionListEvent(eventName: String?, payload: String): SessionListEvent? {
        val obj = parseObject(payload) ?: return null
        val name = eventName?.trim()?.ifEmpty { null }
        val type = stringOnly(obj, "type") ?: name ?: SessionListEvent.SESSIONS_CHANGED_TYPE
        // `type` wins when present (the server sets it to the same event name); a frame that
        // names a different type is not ours.
        if (type != SessionListEvent.SESSIONS_CHANGED_TYPE) return null
        if (obj.isEmpty()) return null
        val reason = stringOnly(obj, "reason").orEmpty()
        val sessionId = idField(obj, "session_id")
        return SessionListEvent(
            action = sessionAction(reason, sessionId),
            reason = reason,
            version = (obj["version"] as? Number)?.toLong(),
            profile = stringOnly(obj, "profile"),
            sessionId = sessionId,
            rawType = type,
        )
    }

    /**
     * `/api/approval/stream` frame → [ApprovalStreamUpdate].
     *
     * Wire contract (`api/route_approvals.py::_handle_approval_sse_stream`): events `initial`
     * (atomic snapshot at subscribe time) and `approval` (every later change) both carry
     * `{pending: {...}|null, pending_count: N}` — the same shape `GET /api/approval/pending`
     * returns, so the payload parser is shared with the polling path ([approvalPayload]).
     *
     * `pending: null` maps to a non-null update with [ApprovalStreamUpdate.isEmpty] — the card
     * must come down. Only an unparsable body / a non-state frame returns null.
     */
    fun approvalStreamUpdate(eventName: String?, payload: String): ApprovalStreamUpdate? {
        val name = eventName?.trim()?.ifEmpty { null }
        if (name != null && name !in APPROVAL_STREAM_EVENTS) return null
        val obj = parseObject(payload) ?: return null
        if (!obj.containsKey("pending") && !obj.containsKey("pending_count")) return null
        val parsed = approvalPayload(obj["pending"] as? Map<String, Any?>)
        val count = (obj["pending_count"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
        if (parsed == null) return ApprovalStreamUpdate(null, 0)
        return ApprovalStreamUpdate(
            payload = if (count > 0) parsed.copy(pendingCount = count) else parsed,
            pendingCount = if (count > 0) count else 1,
        )
    }

    /**
     * `/api/clarify/stream` frame → [ClarifyStreamUpdate].
     *
     * Wire contract (`api/clarify.py::_handle_clarify_sse_stream`): events `initial` and
     * `clarify` with `{pending: {...}|null, pending_count: N}`. Same null-is-a-state rule as
     * [approvalStreamUpdate].
     */
    fun clarifyStreamUpdate(eventName: String?, payload: String): ClarifyStreamUpdate? {
        val name = eventName?.trim()?.ifEmpty { null }
        if (name != null && name !in CLARIFY_STREAM_EVENTS) return null
        val obj = parseObject(payload) ?: return null
        if (!obj.containsKey("pending") && !obj.containsKey("pending_count")) return null
        val parsed = clarifyPayload(obj["pending"] as? Map<String, Any?>)
        val count = (obj["pending_count"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
        if (parsed == null) return ClarifyStreamUpdate(null, 0)
        return ClarifyStreamUpdate(
            payload = if (count > 0) parsed.copy(pendingCount = count) else parsed,
            pendingCount = if (count > 0) count else 1,
        )
    }

    /** Event names the approval SSE handler emits (`initial` + every change). */
    private val APPROVAL_STREAM_EVENTS = setOf("initial", "approval")

    /** Event names the clarify SSE handler emits (`initial` + every change). */
    private val CLARIFY_STREAM_EVENTS = setOf("initial", "clarify")

    /**
     * Maps the server's free-form `reason` tag onto a sidebar action.
     *
     * Unknown reasons REFRESH instead of guessing: a full refetch is always correct, a
     * single-row patch against the wrong row is a visible bug (a deleted dialog lingering in
     * the list, or a new one missing until the next manual refresh).
     */
    private fun sessionAction(reason: String, sessionId: String?): SessionListAction = when (reason) {
        "session_new", "session_import", "session_create" -> SessionListAction.ADDED
        "session_delete", "session_removed" -> SessionListAction.REMOVED
        else -> if (sessionId != null) SessionListAction.UPDATED else SessionListAction.REFRESH
    }

    private fun stringList(value: Any?): List<String> =
        (value as? List<*>)?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }.orEmpty()

    private fun positiveInt(value: Any?): Int? = (value as? Number)?.toInt()?.takeIf { it > 0 }

    private fun phase(obj: Map<String, Any?>?, payload: String, eventName: String?): ChatEvent.Phase? {
        val name = obj?.let { m ->
            listOf("name", "phase", "status", "message").firstNotNullOfOrNull { stringOnly(m, it) }
        }
        // A parsed JSON payload without a human-readable field must NEVER become the phase
        // label (raw `{"session_id":...}` in the status line reads as gibberish): fall back
        // to [phaseLabel]. Legacy plain-text payloads (obj == null) stay verbatim.
        val raw = if (obj == null) payload.trim().ifBlank { null } else null
        return ChatEvent.Phase(name ?: raw ?: phaseLabel(eventName))
    }

    /** Human-readable fallback for phase-like frames that carry no label field. */
    private fun phaseLabel(eventName: String?): String = when (eventName?.trim()) {
        "compressing" -> "Сжатие контекста"
        "compressed" -> "Контекст сжат"
        else -> eventName?.trim()?.ifEmpty { null } ?: "status"
    }

    private fun errorMessage(obj: Map<String, Any?>?, payload: String): String =
        listOf("message", "error", "label", "hint").firstNotNullOfOrNull { stringOnly(obj, it) }
            // Raw payload only when it is NOT a JSON object; a parsed frame without any
            // message-like string field yields the generic label instead of dumping JSON
            // into the bubble (ChatUiState appends Error text to the transcript).
            ?: (if (obj == null) payload.trim().ifBlank { null } else null)
            ?: "Ошибка потока"

    private fun textOf(obj: Map<String, Any?>?, payload: String): String? {
        // Parsed JSON frames: string-typed fields only; when none is present the frame
        // carries nothing renderable and is DROPPED — the raw payload must not become a
        // JSON wall in the thoughts area. The payload fallback applies ONLY to non-JSON
        // (legacy plain-text) reasoning frames.
        if (obj != null) {
            return listOf("text", "content", "message", "thought", "reasoning")
                .firstNotNullOfOrNull { stringOnly(obj, it) }
        }
        return payload.trim().ifBlank { null }
    }

    /**
     * Subagent/delegation lifecycle frame → readable [ChatEvent.Thought] trace.
     * Wire fields per gateway `api_server_runs.py` `_SUBAGENT_EVENT_KEYS`: preview,
     * goal, task_index, task_count, status, summary, failure_reason, output_tail, ...
     *
     * The child's raw output (`output_tail`) is deliberately NEVER surfaced: a wall of
     * another agent's transcript inside the parent bubble is exactly the streaming
     * "gibberish sheet" this mapper must prevent.
     */
    private fun subagentTrace(obj: Map<String, Any?>?, started: Boolean): ChatEvent.Thought? {
        obj ?: return null
        val goal = stringOnly(obj, "goal") ?: stringOnly(obj, "preview")
        val index = positiveInt(obj["task_index"])
        val count = positiveInt(obj["task_count"])
        val label = if (index != null && count != null && index <= count) "Субагент $index/$count" else "Субагент"
        if (started) return ChatEvent.Thought(if (goal != null) "$label запущен: $goal" else "$label запущен")
        val status = stringOnly(obj, "status")
        val failure = stringOnly(obj, "failure_reason")
        val failed = failure != null || (status != null && status in SUBAGENT_FAILED_STATUSES)
        val state = when {
            failed -> "не справился"
            status != null -> "завершён ($status)"
            else -> "завершён"
        }
        val detail = failure ?: stringOnly(obj, "summary") ?: goal
        return ChatEvent.Thought(if (detail != null) "$label $state: $detail" else "$label $state")
    }

    private val SUBAGENT_FAILED_STATUSES = setOf("failed", "error", "timeout")

    private fun sessionId(obj: Map<String, Any?>?, payload: String): String? =
        idField(obj, "session_id") ?: idField(obj, "id")

    /**
     * IDENTIFIER field extraction: unlike [stringOnly] this accepts a JSON number —
     * the wire may deliver `tid`/`session_id`/`approval_id` as a numeric literal, and
     * dropping it would break call↔result correlation (ChatUiState matches tools by id).
     * Structured values (Map/List) are still never toString()-coerced, and integral
     * numbers never surface with Moshi's Double artifact ("42.0").
     */
    private fun idField(obj: Map<String, Any?>?, key: String): String? = when (val value = obj?.get(key)) {
        is String -> value.takeIf { it.isNotBlank() }
        is Number -> numberToString(value)
        else -> null
    }

    /** Canonical string for a JSON number: integral values without a fractional tail. */
    private fun numberToString(value: Number): String? {
        val d = value.toDouble()
        if (d.isNaN() || d.isInfinite()) return null
        val l = d.toLong()
        return if (d == l.toDouble()) l.toString() else value.toString()
    }

    private fun stringField(obj: Map<String, Any?>?, key: String): String? =
        stringOnly(obj, key)

    /**
     * STRING-TYPED field extraction: unlike [stringField] this never coerces a
     * structured value (Map/List) with toString() — a Kotlin dump of a parsed JSON
     * structure (`{role=assistant, content=[...]}`) in a chat bubble is exactly the
     * streaming "gibberish" this mapper must prevent. All text-bearing extractions
     * (chunk, interim_assistant, textOf, phase label, error message) go through here.
     */
    private fun stringOnly(obj: Map<String, Any?>?, key: String): String? =
        (obj?.get(key) as? String)?.takeIf { it.isNotBlank() }

    fun parseObject(payload: String): Map<String, Any?>? = try {
        @Suppress("UNCHECKED_CAST")
        Moshi.Builder().build()
            .adapter<Map<String, Any?>>(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))
            .fromJson(payload)
    } catch (_: Throwable) {
        null
    }

    private fun jsonValue(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        else -> Moshi.Builder().build().adapter(Any::class.java).toJson(value)
    }
}
