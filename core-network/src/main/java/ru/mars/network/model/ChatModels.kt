package ru.mars.network.model

data class ChatAttachment(
    val name: String,
    val path: String,
    val mime: String,
    val size: Long? = null,
    val isImage: Boolean? = null
)

/**
 * Ответ `POST /api/chat/start` (hermes-webui api/routes.py `_chat_start_response_from_run_start`).
 *
 * [model]/[provider] — ФАКТИЧЕСКИЕ модель и провайдер начатого хода: серверные ключи
 * `effective_model`/`effective_model_provider` (WebUI static/messages.js читает те же),
 * с аддитивным фолбэком на `model`/`model_provider`. Ключи условны: null означает
 * «сервер не сообщил» — это штатная ситуация, а не ошибка. Подставлять сюда локальный
 * выбор модели из запроса запрещено: догадка не должна выглядеть как факт.
 */
data class ChatStartResponse(
    val streamId: String,
    val sessionId: String? = null,
    val model: String? = null,
    val provider: String? = null,
)

/**
 * Structured payload of an `approval` SSE attention event.
 *
 * Wire contract (hermes-webui): `tools/approval.py` producers publish
 * `{command, description, pattern_key, pattern_keys, allow_permanent, allow_session, smart_denied?}`;
 * `api/route_approvals.submit_pending` stamps `approval_id` (uuid4 hex) and the SSE notify callback
 * (`api/streaming.py _approval_notify_cb`) merges the queue head and adds `pending_count`.
 * Gateway-run mirrors (`api/gateway_chat._gateway_runs_approval_event`) additionally carry
 * `run_id`, `_gateway_mirror_token`, `tool`, `args`, `risk_level` and `choices`.
 *
 * `approval_id`/`run_id`/`mirrorToken` MUST be preserved end-to-end: the respond endpoint
 * (`POST /api/approval/respond`) uses them to resolve the exact queued entry and to relay
 * gateway-run approvals; losing them turns a targeted response into a stale head resolution.
 */
data class ApprovalPayload(
    val approvalId: String?,
    val command: String,
    val description: String,
    val patternKeys: List<String> = emptyList(),
    val allowPermanent: Boolean = false,
    val allowSession: Boolean = true,
    val smartDenied: Boolean = false,
    val runId: String? = null,
    val mirrorToken: String? = null,
    val pendingCount: Int = 1,
    val tool: String? = null,
    val riskLevel: String? = null,
    val choices: List<String> = emptyList(),
)

/**
 * Structured payload of a `clarify` SSE attention event.
 *
 * Wire contract (hermes-webui `api/clarify.py`): `{question, choices_offered, session_id,
 * kind:"clarify", requested_at, timeout_seconds, expires_at, clarify_id}` where `clarify_id`
 * is stamped by `submit_pending` (uuid4 hex prefix) and `expires_at = requested_at +
 * timeout_seconds`. `POST /api/clarify/respond` requires `session_id`, a non-blank `response`
 * and matches the exact entry by `clarify_id` (stale ids are rejected with 409 `stale:true`).
 */
data class ClarifyPayload(
    val clarifyId: String?,
    val question: String,
    val choices: List<String> = emptyList(),
    val sessionId: String? = null,
    val requestedAtEpochSeconds: Double? = null,
    val timeoutSeconds: Int? = null,
    val expiresAtEpochSeconds: Double? = null,
    val pendingCount: Int = 1,
)

sealed interface ChatEvent {
    data class SessionBound(val sessionId: String) : ChatEvent

    /**
     * Ход начался. [model]/[provider] — ФАКТИЧЕСКИЕ модель и провайдер хода из ответа
     * `POST /api/chat/start` (ключи `effective_model`/`effective_model_provider` с
     * фолбэком на `model`/`model_provider`); null означает «сервер не сообщил»
     * (старый сервер, GET-подключение к чужому ходу) — это штатно, подставлять
     * локальный выбор модели вместо null запрещено. Поля добавлены к существующему
     * data-классу аддитивно: одноаргументная конструкция и равенство по дефолтам
     * сохранены, исчерпывающие `when` потребителей (app-mobile ChatUiState, app-tv)
     * не ломаются.
     */
    data class StreamStarted(
        val streamId: String,
        val model: String? = null,
        val provider: String? = null,
    ) : ChatEvent
    data class Chunk(val text: String, val eventId: String? = null) : ChatEvent
    data class Thought(val text: String) : ChatEvent
    data class ToolCall(val id: String?, val name: String, val arguments: String) : ChatEvent
    data class ToolResult(val id: String?, val name: String, val result: String) : ChatEvent
    data class Phase(val name: String) : ChatEvent

    /** Agent is blocked awaiting a command-approval decision; respond via `POST /api/approval/respond`. */
    data class ApprovalRequired(val payload: ApprovalPayload) : ChatEvent

    /** Agent is blocked awaiting a clarification answer; respond via `POST /api/clarify/respond`. */
    data class ClarifyRequired(val payload: ClarifyPayload) : ChatEvent

    /**
     * Context-window fill level for the composer indicator (WebUI parity with
     * `_syncCtxIndicator`, static/ui.js). Sourced from the SSE `metering`
     * frames' `usage` payload (`last_prompt_tokens` / `post_compression_context_
     * tokens_estimate` over `context_length`).
     *
     * [usedTokens] — tokens of the most recent prompt (never the cumulative
     * `input_tokens`, which would overflow the window on long sessions, #1436).
     * [maxTokens] — the model's context window (>0).
     * [percent] — 0..100, clamped like the WebUI ring.
     */
    data class ContextUsage(val usedTokens: Int, val maxTokens: Int, val percent: Int) : ChatEvent

    /**
     * Steer text the agent never consumed (SSE `pending_steer_leftover`: `{session_id, text}`).
     * WebUI parity (static/messages.js): the text is surfaced to the user AND returned to the
     * composer input so the correction is not lost when the turn ended mid-steer.
     */
    data class SteerLeftover(val text: String) : ChatEvent

    data object Done : ChatEvent

    /**
     * Terminal error of the turn. [origin] is the transport failure seam: it tells
     * recovery layers WHY the error exists —
     * - [ErrorOrigin.TRANSPORT]: the HTTP request/stream itself failed (connect/read
     *   timeout, dropped socket, non-2xx response — `describeNetworkError` /
     *   `describeHttpError` output). Only these may be fed to mid-turn reconnect
     *   policies as retryable network failures.
     * - [ErrorOrigin.SERVER]: the server's SSE `error`/`apperror` frame — the agent
     *   runtime reported a TERMINAL failure inside an otherwise healthy stream. The
     *   message is arbitrary server text (tool output, runtime tracebacks); it must
     *   never be re-classified as a network failure or re-attached.
     * The default keeps every pre-seam construction site (`ChatEvent.Error(msg)`)
     * source-compatible and semantically unchanged (transport).
     *
     * [errorCode] — МАШИННЫЙ код отказа из тела HTTP-ответа (поле `code`, например
     * `unsupported_voice_mode_backend`), а не текст для человека. Приложения классифицируют
     * отказы по нему: человеческая формулировка (`error`) меняется между бэкендами и
     * локалями, а код — часть контракта. `null` — тело кода не несло.
     */
    data class Error(
        val message: String,
        val origin: ErrorOrigin = ErrorOrigin.TRANSPORT,
        val errorCode: String? = null,
    ) : ChatEvent
}

/** Provenance of a [ChatEvent.Error]; see its KDoc for the recovery contract. */
enum class ErrorOrigin { TRANSPORT, SERVER }
