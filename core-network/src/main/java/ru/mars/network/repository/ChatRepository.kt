package ru.mars.network.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.mars.network.api.MarsApi
import ru.mars.network.http.HttpClients
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ChatAttachment
import ru.mars.network.model.ChatStartResponse
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.StreamSideEvent
import ru.mars.network.model.TodoSnapshot
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Client-side mirror of the server upload cap. api/upload.py `handle_upload`
 * rejects a Content-Length above MAX_UPLOAD_BYTES (env
 * HERMES_WEBUI_MAX_UPLOAD_MB, default 20) with
 * HTTP 413 `{"error": "File too large (max 20MB)"}`.
 */
const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024

/** Thrown by [readBoundedUploadPayload] as soon as a stream exceeds the cap. */
class UploadTooLargeException(val maxBytes: Long) :
    Exception("File too large (max ${maxBytes / (1024 * 1024)}MB)")

/** Outcome of `POST /api/upload` — an explicit result instead of a silent null. */
sealed interface UploadOutcome {
    data class Success(val attachment: ChatAttachment) : UploadOutcome
    /** Payload exceeds the server limit: caught client-side pre-flight or via HTTP 413. */
    data class TooLarge(val message: String) : UploadOutcome
    /** Any other failure: HTTP status, malformed response body, network error. */
    data class Failure(val message: String) : UploadOutcome
}

/**
 * Reads at most [maxBytes] from [input] in bounded chunks — never an
 * unbounded `readBytes()` of an unknown-size stream. Throws
 * [UploadTooLargeException] as soon as the cap is crossed (detected on the
 * `maxBytes + 1`-th byte), so an oversized file is rejected without being
 * fully read or buffered.
 */
fun readBoundedUploadPayload(input: InputStream, maxBytes: Long = MAX_UPLOAD_BYTES): ByteArray {
    require(maxBytes >= 0) { "maxBytes must be >= 0" }
    val chunkSize = 64 * 1024
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(chunkSize)
    var total = 0L
    while (true) {
        val room = maxBytes + 1 - total
        if (room <= 0) throw UploadTooLargeException(maxBytes)
        val read = input.read(chunk, 0, minOf(chunkSize.toLong(), room).toInt())
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) throw UploadTooLargeException(maxBytes)
        out.write(chunk, 0, read)
    }
    return out.toByteArray()
}

class ChatRepository(
    /**
     * Client for turn-carrying calls: `POST /api/chat/start`, the SSE `GET
     * /api/chat/stream` and the legacy streaming `POST /chat`. Defaults to
     * [HttpClients.stream] (long read timeout — a healthy stream may idle during long
     * tool calls; the server's app-level SSE heartbeat keeps gaps bounded, but HTTP
     * pingInterval is NOT a substitute for it and is deliberately not configured).
     * Injectable for tests: any OkHttpClient overrides it wholesale.
     */
    private val client: OkHttpClient = HttpClients.stream,
    /**
     * Client for bounded non-streaming calls of this repository: `POST /api/upload`
     * and the cancel/steer control requests ([executeControl]). Defaults to
     * [HttpClients.rest] (short per-phase timeouts — a hung cancel must not inherit
     * the 90 s SSE read timeout). Injectable for tests; shares the pool/dispatcher
     * with [client] unless overridden.
     */
    private val restClient: OkHttpClient = HttpClients.rest,
    /**
     * Базовый адрес бэкенда для вызовов БЕЗ явного URL — сейчас это только
     * [transcribePcmWav] (у него контрактный no-arg вид). Остальные методы
     * репозитория по-прежнему принимают baseUrl параметром. По умолчанию —
     * [MarsApi.BASE_URL]; injectable для тестов против локального сервера.
     */
    private val baseUrl: String = MarsApi.BASE_URL,
    /**
     * Источник device-credential для [transcribePcmWav]: тот же `store.read()`
     * AndroidKeyStore, что кормит остальные no-arg-гейтвеи в `ChatActivity`.
     * `null`-результат — устройство не привязано, распознавание честно
     * отказывает [TranscribeOutcome.Failure] без сетевого вызова.
     */
    private val credentialProvider: () -> ConsumedDeviceCredential? = { null },
) {
    /**
     * Sends a message and streams the answer.
     *
     * [onTodoState] — optional NON-SEALED transport callback for live `todo_state`
     * snapshots (the SSE frame has no carrier in the sealed [ChatEvent]: app
     * modules hold exhaustive `when` expressions over it, so a new variant would
     * break their compile). When provided, every `todo_state` frame is parsed by
     * [TodoStateParser] (the same single point as the cold
     * `SessionDetail.todoState`) and handed to the callback instead of being
     * silently dropped; the frame never enters the [ChatEvent] flow, so the chat
     * transcript stays clean. Invoked on this flow's IO context — receivers own
     * any thread hopping and must not throw (an exception surfaces as
     * [ChatEvent.Error]). Default `null` keeps the pre-todo_state behaviour.
     *
     * [workspace] — optional server-side workspace PATH for this turn. When
     * non-blank it is sent as the `workspace` field of the `POST /api/chat/start`
     * body: `_handle_chat_start` resolves it via
     * `_resolve_chat_workspace_with_recovery(s, body["workspace"])` (trusted-path
     * resolution, session recovery on mismatch). Null/blank OMITS the key
     * entirely so the server keeps the session's current workspace — byte-identical
     * to the pre-workspace payload (backward compatible). Value comes from
     * `GET /api/workspaces` ([ru.mars.network.model.WorkspaceItem.path]) or from
     * `SessionDetail.workspace`; never invent a path.
     *
     * [onSideEvent] — optional NON-SEALED transport callback (same delivery
     * pattern as [onTodoState]) for SSE metadata frames without a sealed
     * [ChatEvent] carrier: `title` text, `goal`, `goal_continue`, `context_status`
     * and the subagent lifecycle / turn-ended frames
     * ([ru.mars.network.model.SubagentStatusUpdate] — board state is reduced by
     * [ru.mars.network.repository.SubagentStatusPolicy] at the receiving layer).
     * Delivered in stream order on
     * this flow's IO context, ALONGSIDE the [ChatEvent] flow (a `title` frame
     * still emits [ChatEvent.SessionBound]). Delivery only: receivers must not
     * auto-send a goal continuation — that is an explicit UI decision. Default
     * `null` keeps the previous behaviour (frames are dropped).
     *
     * [onEventId] — optional transport callback receiving the `id:` of EVERY SSE
     * frame that carries one — tokens, tool frames, `todo_state`, `title`, `done`
     * alike, NOT only frames mapped to [ChatEvent.Chunk] — invoked on this flow's
     * IO context AFTER the frame was delivered into the flow / side callbacks
     * (never before: an interrupted delivery must not advance the resume cursor
     * past an undelivered frame). Feed it into
     * [StreamResumeTracker.onFrameId] so a mid-turn reconnect can send
     * `Last-Event-ID` and the server replays its journal gap (docs/API-CONTRACT.md,
     * "Chat SSE: resume and reconnect"). Frames without an `id:` never invoke it.
     * Default `null` disables the callback — existing callers are unaffected.
     */
    fun sendMessage(
        endpoint: String,
        credential: ConsumedDeviceCredential,
        message: String,
        sessionId: String? = null,
        attachments: List<ChatAttachment> = emptyList(),
        model: String? = null,
        modelProvider: String? = null,
        explicitModelPick: Boolean = false,
        workspace: String? = null,
        onTodoState: ((TodoSnapshot) -> Unit)? = null,
        onSideEvent: ((StreamSideEvent) -> Unit)? = null,
        onEventId: ((String) -> Unit)? = null,
    ): Flow<ChatEvent> = flow {
        val base = endpoint.toHttpUrl()
        try {
            if (base.encodedPath == "/chat") {
                stream(requestStart(base, credential, message, sessionId, attachments, model, modelProvider, explicitModelPick, workspace), credential, onTodoState, onSideEvent, onEventId) { event -> emit(event) }
                return@flow
            }
            val startResp = client.newCall(requestStart(base, credential, message, sessionId, attachments, model, modelProvider, explicitModelPick, workspace)).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // The server answers start failures with `{"error": "<reason>"}`
                    // (400 message required, 401 unauthorized, 502 gateway…): surface
                    // the reason, not just the code (parity with uploadAttachment).
                    // A Retry-After directive (429/503) rides along as an anchored
                    // marker for the recovery coordinator (RetryAfterPolicy).
                    emit(
                        ChatEvent.Error(
                            describeHttpError(
                                response.code,
                                body,
                            )
                        )
                    )
                    return@flow
                }
                extractStartResponse(body)
                    ?: run { emit(ChatEvent.Error("Missing stream_id")); return@flow }
            }
            // Фактическая модель/провайдер хода из кадра начала хода; null = сервер
            // не сообщил (локальный выбор сюда подставлять запрещено — см. ChatStartResponse).
            emit(ChatEvent.StreamStarted(startResp.streamId, startResp.model, startResp.provider))
            startResp.sessionId?.let { emit(ChatEvent.SessionBound(it)) }

            val streamUrl = base.newBuilder().addPathSegments("api/chat/stream")
                .addQueryParameter("stream_id", startResp.streamId).build()
            stream(Request.Builder().url(streamUrl).header("Authorization", bearer(credential))
                .header("Accept", "text/event-stream").get().build(), credential, onTodoState, onSideEvent, onEventId) { event -> emit(event) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // Sudden link losses (Wi-Fi <-> LTE handover, DNS/TLS failure, read timeout)
            // must surface as a readable ChatEvent.Error — otherwise the UI hangs in the
            // "waiting for answer" state with no way out.
            emit(ChatEvent.Error(describeNetworkError(error)))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Reattaches to an ALREADY-RUNNING turn without minting a new one: opens
     * `GET /api/chat/stream?stream_id=…` (Bearer) directly — never `POST /api/chat/start`.
     *
     * Call-site contract (UI coordinator): obtain [streamId] from the session detail/list
     * active-turn metadata (`SessionDetail.activeTurn` / `SessionItem.activeTurn`, reattach
     * when `canReattach`), then collect this flow exactly like [sendMessage]. The first
     * event is always [ChatEvent.StreamStarted] with [streamId] so consumers bind the
     * cancel/steer target without special-casing the reattach path.
     *
     * Server behaviour (routes.py `_handle_sse_stream`):
     * - live stream → subscribe; with a resume cursor the journaled events missed since
     *   that cursor are replayed first (gap-checked), then live frames continue;
     * - dead-but-journaled stream → full journal replay, then terminal frames;
     * - unknown/expired stream → HTTP 404 `{"error":"stream not found"}`, surfaced here as
     *   [ChatEvent.Error] `"HTTP 404: stream not found"` (status code + server reason;
     *   matches the coordinator's ANCHORED gone-stream markers — `HTTP 404` /
     *   "stream not found" — without relying on broad substrings) —
     *   the caller should fall back to reloading history.
     *
     * [lastEventId] is the optional resume cursor: the `id:` value (`stream_id:seq`) of the
     * last SSE event the client actually processed, sent as the `Last-Event-ID` header (the
     * spec-compliant reconnect cursor the server honors after explicit query params).
     * Omit it to subscribe live-only (server-side snapshot backfill still applies).
     *
     * [onTodoState] — same live `todo_state` transport callback as in [sendMessage]
     * (the journaled replay of a reattached stream can contain `todo_state` frames too);
     * see its KDoc for the delivery contract.
     *
     * [onSideEvent] — same `title`/`goal`/`goal_continue`/`context_status`/subagent-lifecycle
     * side-channel callback as in
     * [sendMessage] (a reattached stream replays these frames from the journal too).
     *
     * [onEventId] — same per-frame resume-cursor callback as in [sendMessage]: receives
     * the `id:` of EVERY frame (replayed journal frames included), so a
     * [StreamResumeTracker] keeps advancing across reattach cycles.
     */
    fun attachToStream(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        streamId: String,
        lastEventId: String? = null,
        onTodoState: ((TodoSnapshot) -> Unit)? = null,
        onSideEvent: ((StreamSideEvent) -> Unit)? = null,
        onEventId: ((String) -> Unit)? = null,
    ): Flow<ChatEvent> = flow {
        val streamUrl = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/chat/stream")
            .addQueryParameter("stream_id", streamId)
            .build()
        val request = Request.Builder().url(streamUrl)
            .header("Authorization", bearer(credential))
            .header("Accept", "text/event-stream")
            .apply { lastEventId?.takeIf { it.isNotBlank() }?.let { header("Last-Event-ID", it) } }
            .get().build()
        try {
            emit(ChatEvent.StreamStarted(streamId))
            stream(request, credential, onTodoState, onSideEvent, onEventId) { event -> emit(event) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // Same resilience contract as sendMessage: a link loss mid-reattach must leave
            // the UI in a terminal state, not waiting forever.
            emit(ChatEvent.Error(describeNetworkError(error)))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Uploads a file to `POST /api/upload` (multipart: `session_id` + `file`) and maps the
     * server contract from api/upload.py onto an explicit [UploadOutcome]:
     * 200 `{filename, path, size, mime, is_image}` → [UploadOutcome.Success];
     * 413 `{"error": "File too large (max 20MB)"}` → [UploadOutcome.TooLarge] with the server
     * message; any other non-2xx → [UploadOutcome.Failure]. Oversized payloads are rejected
     * client-side against [MAX_UPLOAD_BYTES] before any network call. Runs on Dispatchers.IO —
     * the synchronous OkHttp execute never happens on the caller's (Main) thread.
     */
    suspend fun uploadAttachment(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        filename: String,
        mimeType: String,
        bytes: ByteArray,
        sessionId: String?
    ): UploadOutcome = withContext(Dispatchers.IO) {
        if (bytes.size.toLong() > MAX_UPLOAD_BYTES) {
            return@withContext UploadOutcome.TooLarge("File too large (max ${MAX_UPLOAD_BYTES / (1024 * 1024)}MB)")
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .apply { if (sessionId != null) addFormDataPart("session_id", sessionId) }
            .addFormDataPart("file", filename, bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        val request = Request.Builder().url(baseUrl.toHttpUrl().newBuilder().addPathSegments("api/upload").build())
            .header("Authorization", bearer(credential)).post(multipart).build()
        try {
            restClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val serverMessage = StreamEventMapper.parseObject(body)
                        ?.get("error")?.toString()?.takeIf { it.isNotBlank() }
                    return@use if (response.code == 413) {
                        UploadOutcome.TooLarge(serverMessage ?: "File too large (max ${MAX_UPLOAD_BYTES / (1024 * 1024)}MB)")
                    } else {
                        UploadOutcome.Failure(serverMessage ?: "HTTP ${response.code}")
                    }
                }
                val obj = StreamEventMapper.parseObject(body)
                    ?: return@use UploadOutcome.Failure("Malformed upload response")
                val path = obj["path"]?.toString().orEmpty()
                if (path.isEmpty()) return@use UploadOutcome.Failure("Upload response missing path")
                UploadOutcome.Success(
                    ChatAttachment(
                        obj["filename"]?.toString().orEmpty(),
                        path,
                        obj["mime"]?.toString().orEmpty(),
                        (obj["size"] as? Number)?.toLong(),
                        obj["is_image"] as? Boolean
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            UploadOutcome.Failure(describeNetworkError(error))
        }
    }

    /**
     * Распознаёт речь из готовой WAV-записи: `POST /api/transcribe` (multipart,
     * поле файла ровно `file`). Контракт эндпоинта — fixtures/voice-mvp-contract.md,
     * раздел 1; успех — HTTP 200 и JSON `{"ok": true, "transcript": "<текст>"}`.
     *
     * Авторизация — существующий device-Bearer ([credentialProvider], та же схема,
     * что у остальных native-вызовов); собственная схема не изобретается.
     * `baseUrl`/`credential` берутся из конструктора, потому что контрактный вид
     * метода — no-arg по URL (в отличие от [uploadAttachment]).
     *
     * Никогда не бросает (кроме отмены корутины): пустой вход, отсутствие
     * credential, HTTP-ошибка, пустой/битый ответ и транспортный сбой сворачиваются
     * в явный [TranscribeOutcome.Failure]. Пустой [wav] отсекается ДО сети.
     */
    suspend fun transcribePcmWav(
        wav: ByteArray,
        filename: String = "voice.wav"
    ): TranscribeOutcome = withContext(Dispatchers.IO) {
        if (wav.isEmpty()) {
            return@withContext TranscribeOutcome.Failure("Empty recording: nothing to transcribe")
        }
        val safeName = filename.trim()
        if (safeName.isEmpty()) {
            return@withContext TranscribeOutcome.Failure("Transcribe filename must not be blank")
        }
        val credential = credentialProvider()
            ?: return@withContext TranscribeOutcome.Failure("No device credential")
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", safeName, wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(baseUrl.toHttpUrl().newBuilder().addPathSegments("api/transcribe").build())
            .header("Authorization", bearer(credential)).post(multipart).build()
        try {
            restClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code != 200) {
                    return@use TranscribeOutcome.Failure(describeHttpError(response.code, body), response.code)
                }
                val obj = StreamEventMapper.parseObject(body)
                    ?: return@use TranscribeOutcome.Failure("Malformed transcribe response")
                val transcript = (obj["transcript"] as? String)?.trim().orEmpty()
                if (transcript.isEmpty()) {
                    return@use TranscribeOutcome.Failure("Transcribe response missing transcript")
                }
                TranscribeOutcome.Success(transcript)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            TranscribeOutcome.Failure(describeNetworkError(error))
        }
    }

    /**
     * Executes a control request (cancel/steer). A 2xx alone is NOT success: `/api/chat/steer`
     * answers 200 with `{"accepted": false, "fallback": ...}` when the steer was not applied,
     * which must be reported as failure. Responses without an `accepted` field (e.g. cancel's
     * `{"ok": true, "cancelled": ...}`) keep the legacy "HTTP status is the result" behaviour.
     */
    fun executeControl(request: Request): Boolean = restClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@use false
        val body = response.body?.string().orEmpty()
        StreamEventMapper.parseObject(body)?.get("accepted")?.let { it != false } ?: true
    }

    fun cancelRequest(baseUrl: String, streamId: String, accessToken: String? = null): Request =
        Request.Builder().url(baseUrl.toHttpUrl().newBuilder().addPathSegments("api/chat/cancel")
            .addQueryParameter("stream_id", streamId).build())
            .apply { if (accessToken != null) header("Authorization", "Bearer $accessToken") }.get().build()

    /**
     * Builds `POST /api/chat/steer`. The server contract (`_handle_chat_steer`) requires JSON
     * fields `session_id` and `text` — NOT `stream_id`/`message`, which it silently rejects
     * with `{"accepted": false, "fallback": "no_cached_agent"}`.
     */
    fun steerRequest(baseUrl: String, sessionId: String, message: String, accessToken: String): Request =
        Request.Builder().url(baseUrl.toHttpUrl().newBuilder().addPathSegments("api/chat/steer").build())
            .header("Authorization", "Bearer $accessToken").header("Content-Type", "application/json")
            .post("{\"session_id\":${jsonString(sessionId)},\"text\":${jsonString(message)}}"
                .toRequestBody("application/json".toMediaType())).build()

    private fun requestStart(
        base: HttpUrl,
        credential: ConsumedDeviceCredential,
        message: String,
        sessionId: String? = null,
        attachments: List<ChatAttachment> = emptyList(),
        model: String? = null,
        modelProvider: String? = null,
        explicitModelPick: Boolean = false,
        workspace: String? = null
    ): Request {
        val url = if (base.encodedPath == "/chat") base else base.newBuilder().addPathSegments("api/chat/start").build()
        val jsonBody = buildString {
            append("{\"message\":").append(jsonString(message))
            if (!sessionId.isNullOrBlank()) append(",\"session_id\":").append(jsonString(sessionId))
            // Server `_handle_chat_start` reads body["workspace"] (a trusted path
            // string) and resolves it via `_resolve_chat_workspace_with_recovery`,
            // letting a turn switch the session workspace mid-conversation. Only
            // send the key when a concrete path was chosen — an omitted key keeps
            // the session's current workspace (backward compatible payload).
            if (!workspace.isNullOrBlank()) append(",\"workspace\":").append(jsonString(workspace))
            // Server `_handle_chat_start` reads body["model"] / body["model_provider"]
            // (falling back to session state when absent) and body["explicit_model_pick"]
            // to record a deliberate user pick (#5979). Only send fields we actually have —
            // an omitted key lets the server keep its session/default routing.
            if (!model.isNullOrBlank()) append(",\"model\":").append(jsonString(model))
            if (!modelProvider.isNullOrBlank()) append(",\"model_provider\":").append(jsonString(modelProvider))
            if (!model.isNullOrBlank() && explicitModelPick) append(",\"explicit_model_pick\":true")
            append(",\"attachments\":[")
            attachments.joinTo(this, separator = ",") { attachment ->
                "{\"name\":${jsonString(attachment.name)},\"path\":${jsonString(attachment.path)},\"mime\":${jsonString(attachment.mime)},\"size\":${attachment.size?.toString() ?: "null"},\"is_image\":${attachment.isImage?.toString() ?: "null"}}"
            }
            append("]}")
        }
        return Request.Builder().url(url).header("Authorization", bearer(credential))
            .header("Accept", "text/event-stream")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
    }

    /**
     * Streams an SSE response body chunk-by-chunk through [SseChunkParser].
     *
     * Resilience contract (POCO F5 Wi-Fi/LTE handovers):
     * - the body is read in transport-sized chunks and fed to the parser verbatim: an SSE
     *   frame split across TCP packets ANYWHERE (mid-line, between `\r` and `\n`, between
     *   two `data:` lines) is reassembled from the parser's internal buffer — no partial
     *   state is ever dropped;
     * - multi-byte UTF-8 sequences split across chunks are handled by the InputStreamReader
     *   decoder (never decode a raw byte chunk to String directly);
     * - if the connection dies mid-frame, the IOException propagates to [sendMessage], which
     *   maps it via [describeNetworkError] and emits a terminal ChatEvent.Error so the UI
     *   leaves the waiting state;
     * - cancellation is checked between reads so a cancelled collector does not block on
     *   the socket read loop.
     *
     * [onTodoState]: when non-null, `todo_state` frames are diverted to it (side channel —
     * no sealed ChatEvent carrier exists) instead of reaching [StreamEventMapper.map],
     * which returns null for them. When null, behaviour is unchanged (frames are dropped).
     *
     * [onSideEvent]: when non-null, `title`/`goal`/`goal_continue`/`context_status`
     * and subagent lifecycle / turn-ended frames are ALSO
     * parsed by [StreamEventMapper.sideEvent] and handed to it (typed
     * [StreamSideEvent] side channel). Unlike `todo_state` this does NOT divert
     * the frame from [StreamEventMapper.map] — a `title` frame still emits
     * [ChatEvent.SessionBound] into the flow. When null, behaviour is unchanged.
     *
     * [onEventId]: when non-null, invoked with the frame's `id:` for EVERY frame that
     * carries one — AFTER the frame was delivered (emit returned / the todo_state and
     * side-event callbacks ran), so a cancelled or failed delivery leaves the cursor
     * BEFORE the undelivered frame and the server replay covers it on reconnect.
     * Frames that map to nothing are acked right after their callbacks. Blank ids are
     * skipped (nothing resumable). See the emitFrames comment for the flowOn-buffer
     * caveat.
     */
    private suspend fun stream(
        request: Request,
        credential: ConsumedDeviceCredential,
        onTodoState: ((TodoSnapshot) -> Unit)?,
        onSideEvent: ((StreamSideEvent) -> Unit)? = null,
        onEventId: ((String) -> Unit)? = null,
        emit: suspend (ChatEvent) -> Unit,
    ) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // Same contract as the start POST: non-2xx bodies carry `{"error": ...}`
                // ("stream not found", gateway failures); keep the reason for the user.
                // A Retry-After directive (429/503 from a throttling proxy or a
                // restarting backend) becomes an anchored marker in the message so
                // MidTurnRecoveryCoordinator waits the server-directed pause instead
                // of burning its reconnect ladder (RetryAfterPolicy).
                val errorBody = response.body?.string().orEmpty()
                emit(
                    ChatEvent.Error(
                        describeHttpError(
                            response.code,
                            errorBody,
                            RetryAfterPolicy.parseSeconds(response.header("Retry-After")),
                        )
                    )
                )
                return
            }
            val byteStream = response.body?.byteStream()
            if (byteStream == null) {
                // A 200 with no body must not leave the UI waiting forever.
                emit(ChatEvent.Error("Пустой ответ сервера"))
                return
            }
            val parser = SseChunkParser()
            suspend fun emitFrames(frames: List<SseChunkParser.Frame>) {
                for (frame in frames) {
                    val ackId = frame.id?.takeIf { it.isNotBlank() }
                    val snapshot = onTodoState?.let { StreamEventMapper.todoState(frame.event, frame.data) }
                    if (snapshot != null) {
                        onTodoState.invoke(snapshot)
                        // Cursor ack AFTER the snapshot was handed to the callback: if
                        // the callback throws, the frame counts as NOT delivered and a
                        // reconnect replays it (a duplicate todo snapshot is idempotent).
                        if (onEventId != null && ackId != null) onEventId(ackId)
                        continue
                    }
                    onSideEvent?.let { callback ->
                        StreamEventMapper.sideEvent(frame.event, frame.data)?.let(callback)
                    }
                    val mapped = StreamEventMapper.map(frame.event, frame.id, frame.data)
                    if (mapped != null) {
                        emit(mapped)
                    }
                    // Resume cursor LAST: ack only after the frame was actually handed to
                    // the flow (emit returned) / the side channels ran. emit() suspends and
                    // rethrows cancellation — if the collector dies before this frame is
                    // delivered, the cursor must NOT advance past it, otherwise the server
                    // replay on reconnect would skip exactly this undelivered frame.
                    // Frames that map to nothing are acked immediately (no delivery to wait
                    // for). KNOWN LIMITATION: emit() returns when the event enters the
                    // flowOn channel buffer, not when the terminal collector processed it;
                    // events cancelled while still buffered are acked but not rendered —
                    // consumers needing end-to-end acking should feed StreamResumeTracker
                    // from their terminal collector instead (ChatEvent.Chunk carries
                    // eventId for exactly this).
                    if (onEventId != null && ackId != null) onEventId(ackId)
                }
            }
            val reader = InputStreamReader(byteStream, Charsets.UTF_8)
            val chars = CharArray(8 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = reader.read(chars)
                if (read < 0) break
                if (read > 0) emitFrames(parser.feed(String(chars, 0, read)))
            }
            // Stream ended without the terminating blank line (server closed early, or the
            // last frame lacks it): still deliver the buffered frame.
            emitFrames(parser.end())
        }
    }

    private fun extractStartResponse(json: String): ChatStartResponse? = StreamEventMapper.startResponse(json)

    private fun bearer(credential: ConsumedDeviceCredential) = "Bearer ${credential.accessToken}"

    /** Delegates to the module-wide strict escaper (single point — see [JsonStringEncoding]). */
    private fun jsonString(value: String) = JsonStringEncoding.escape(value)
}
