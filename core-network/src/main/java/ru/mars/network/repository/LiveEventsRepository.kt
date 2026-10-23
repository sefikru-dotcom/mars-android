package ru.mars.network.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay as coroutineDelay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.mars.network.http.HttpClients
import ru.mars.network.model.ApprovalStreamUpdate
import ru.mars.network.model.ClarifyStreamUpdate
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.LiveChannelEvent
import ru.mars.network.model.LiveChannelMode
import ru.mars.network.model.SessionListAction
import ru.mars.network.model.SessionListEvent
import java.io.InputStreamReader

/**
 * Long-lived subscriptions for the three live channels of the Mars backend:
 * `/api/sessions/events`, `/api/approval/stream`, `/api/clarify/stream`.
 *
 * Why a separate repository instead of extending [ChatRepository]: these feeds are not part of
 * a turn. They outlive any single stream, they are subscribed per SCREEN (the dialog list, the
 * open chat), and their failure mode is different — a dead chat stream means "the turn broke",
 * a dead attention feed means "fall back to polling, the user must still get the card".
 *
 * ## Delivery contract
 * Each method returns a cold [Flow]; collecting it opens the SSE connection on
 * [Dispatchers.IO] and keeps it open until the collector is cancelled. Elements are
 * [LiveChannelEvent.Update]s plus [LiveChannelEvent.Mode] transitions so the caller can show
 * "live" vs "polling" honestly instead of pretending the socket is up.
 *
 * ## Resilience (three layers, in this order)
 * 1. **Reconnect with backoff** — [AdaptiveSseReconnectPolicy]: jittered exponential ladder
 *    (500 ms → 30 s, ±20%), and the streak resets as soon as the connection proves alive
 *    (any byte read counts: the server writes a `: keepalive` comment every 5 s, so a silent
 *    but healthy feed still resets it). Resetting on the HTTP 200 alone would let a proxy that
 *    accepts-then-closes pin us to the 500 ms rung forever.
 * 2. **Polling fallback** — once the retry budget is spent, the same flow switches to polling
 *    the endpoint the client used before the stream existed (`GET /api/approval/pending`,
 *    `GET /api/clarify/pending`, and for the session feed a synthetic REFRESH that makes the
 *    caller re-run `GET /api/sessions`). Updates keep flowing through the SAME element type, so
 *    the UI code path does not change and no event is lost while the socket is down.
 * 3. **Automatic recovery** — every [DEFAULT_SSE_RETRY_AFTER_POLLS] polls the flow retries SSE
 *    once. A successful re-open emits [LiveChannelMode.STREAMING] again and the caller can
 *    reconcile (the session feed's consumers refetch on that transition, which covers events
 *    that happened during the outage). Cancelling the collector cancels the in-flight OkHttp
 *    call, so `onPause` closes the socket immediately instead of waiting for the next heartbeat.
 *
 * Verified wire shapes (read-only against `/opt/hermes-webui`):
 * - `api/route_session_events.py` — every frame is `sessions_changed` with an invalidation body;
 * - `api/route_approvals.py::_handle_approval_sse_stream` — `initial` + `approval`, body
 *   `{pending, pending_count}` (identical to the polling GET);
 * - `api/clarify.py::_handle_clarify_sse_stream` — `initial` + `clarify`, same envelope.
 *
 * Auth: all three routes are reached with the device Bearer (`Authorization: Bearer <token>`).
 * `/api/sessions/events` is covered by the `/api/sessions` prefix in `api/auth.py`; the two
 * attention streams are GET-only entries in the native read-only allow-list.
 */
class LiveEventsRepository(
    private val client: OkHttpClient = HttpClients.stream,
    private val restClient: OkHttpClient = HttpClients.rest,
    private val reconnectPolicyFactory: () -> AdaptiveSseReconnectPolicy = { AdaptiveSseReconnectPolicy() },
    private val sessionsPollIntervalMs: Long = DEFAULT_SESSIONS_POLL_INTERVAL_MS,
    private val attentionPollIntervalMs: Long = DEFAULT_ATTENTION_POLL_INTERVAL_MS,
    private val sseRetryAfterPolls: Int = DEFAULT_SSE_RETRY_AFTER_POLLS,
    private val delay: suspend (Long) -> Unit = { coroutineDelay(it) },
) {

    /**
     * Session-list invalidations. The fallback poll is a synthetic
     * [SessionListAction.REFRESH] event: the list is re-read by the caller's existing
     * `GET /api/sessions` path, which is exactly what polling did before.
     */
    fun subscribeToSessionsEvents(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
    ): Flow<LiveChannelEvent<SessionListEvent>> = liveChannel(
        url = sseUrl(baseUrl, SESSIONS_PATH),
        credential = credential,
        pollIntervalMs = sessionsPollIntervalMs,
        parse = { frame -> StreamEventMapper.sessionListEvent(frame.event, frame.data) },
        poll = { SessionListEvent(action = SessionListAction.REFRESH, reason = POLL_FALLBACK_REASON) },
    )

    /** Pending command approvals of one dialog; `pending: null` means "take the card down". */
    fun subscribeToApprovalStream(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
    ): Flow<LiveChannelEvent<ApprovalStreamUpdate>> = liveChannel(
        url = sseUrl(baseUrl, APPROVAL_STREAM_PATH, SESSION_ID_PARAM to sessionId),
        credential = credential,
        pollIntervalMs = attentionPollIntervalMs,
        parse = { frame -> StreamEventMapper.approvalStreamUpdate(frame.event, frame.data) },
        poll = { pollApproval(baseUrl, credential, sessionId) },
    )

    /** Pending clarify prompts of one dialog. */
    fun subscribeToClarifyStream(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
    ): Flow<LiveChannelEvent<ClarifyStreamUpdate>> = liveChannel(
        url = sseUrl(baseUrl, CLARIFY_STREAM_PATH, SESSION_ID_PARAM to sessionId),
        credential = credential,
        pollIntervalMs = attentionPollIntervalMs,
        parse = { frame -> StreamEventMapper.clarifyStreamUpdate(frame.event, frame.data) },
        poll = { pollClarify(baseUrl, credential, sessionId) },
    )

    /**
     * The shared state machine: SSE → reconnect ladder → polling → periodic SSE retry.
     *
     * Mode transitions are emitted ONCE per real change: [LiveChannelMode.CONNECTING] before the
     * first attempt and after every failure, [LiveChannelMode.STREAMING] as soon as a connection
     * proves alive (also after a fallback re-open — that is the caller's cue to reconcile),
     * [LiveChannelMode.POLLING] when the ladder is exhausted. A stream the server closes cleanly
     * is a failure, not a success: these feeds are long-lived by contract.
     */
    private fun <T> liveChannel(
        url: HttpUrl,
        credential: ConsumedDeviceCredential,
        pollIntervalMs: Long,
        parse: (SseChunkParser.Frame) -> T?,
        poll: suspend () -> T?,
    ): Flow<LiveChannelEvent<T>> = flow {
        val policy = reconnectPolicyFactory()
        var lastReason: String? = null
        var lastMode: LiveChannelMode? = null
        var retryFromPolling = false
        // Mode is announced only when it actually CHANGES: a reconnect attempt every 10 s during
        // a long outage must not flicker the "polling" badge back to "connecting".
        suspend fun announce(mode: LiveChannelMode, reason: String? = null) {
            if (lastMode == mode) return
            lastMode = mode
            emit(LiveChannelEvent.Mode(mode, reason))
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!retryFromPolling) announce(LiveChannelMode.CONNECTING, lastReason)
            retryFromPolling = false
            try {
                pumpSse(
                    url = url,
                    credential = credential,
                    // Any byte proves liveness: the server's 5 s keepalive comment is a byte
                    // read even when no real event is produced for hours.
                    onAlive = {
                        policy.onStreamEvent()
                        announce(LiveChannelMode.STREAMING)
                    },
                    parse = parse,
                    onFrame = { value -> emit(LiveChannelEvent.Update(value)) },
                )
                lastReason = STREAM_CLOSED_REASON
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastReason = describe(error)
            }
            val plan = policy.onStreamFailure()
            if (plan != null) {
                delay(plan.delayMs)
                continue
            }
            // Budget spent: keep the user informed through polling until SSE is reachable again.
            announce(LiveChannelMode.POLLING, lastReason)
            var pollsSinceSse = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val snapshot = try {
                    poll()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null // a failed poll is not a state change: keep the last rendered card
                }
                if (snapshot != null) emit(LiveChannelEvent.Update(snapshot))
                delay(pollIntervalMs)
                pollsSinceSse++
                if (pollsSinceSse >= sseRetryAfterPolls) break // one SSE retry per cycle
            }
            // Stay in POLLING while the retry is in flight: the badge flips back only when the
            // stream actually proves alive (STREAMING) or the retry fails (no change).
            retryFromPolling = true
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Opens one SSE connection and feeds parsed frames to [onFrame] until EOF.
     *
     * Throws on a non-2xx response (the caller turns that into a reconnect decision) and on
     * transport errors. The in-flight call is cancelled when the collector is cancelled, so a
     * screen that goes to the background stops the socket at once.
     */
    private suspend fun <T> pumpSse(
        url: HttpUrl,
        credential: ConsumedDeviceCredential,
        onAlive: suspend () -> Unit,
        parse: (SseChunkParser.Frame) -> T?,
        onFrame: suspend (T) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${credential.accessToken}")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        val call = client.newCall(request)
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion {
            // Cancellation must not leave a blocking read pinned to a dead screen.
            runCatching { call.cancel() }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw LiveStreamHttpException(response.code, response.body?.string().orEmpty())
                }
                val body = response.body ?: throw LiveStreamHttpException(response.code, "")
                val parser = SseChunkParser()
                val reader = InputStreamReader(body.byteStream(), Charsets.UTF_8)
                val chars = CharArray(8 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = reader.read(chars)
                    if (read < 0) break
                    if (read > 0) {
                        onAlive()
                        for (frame in parser.feed(String(chars, 0, read))) {
                            parse(frame)?.let { onFrame(it) }
                        }
                    }
                }
                // The server may close right after the last `data:` line without the blank
                // terminator; flush the buffered frame so the final event is not lost.
                for (frame in parser.end()) parse(frame)?.let { onFrame(it) }
            }
        } finally {
            cancelHandle?.dispose()
        }
    }

    /** `GET /api/approval/pending` — the pre-SSE polling call, reused verbatim as the fallback. */
    private suspend fun pollApproval(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
    ): ApprovalStreamUpdate? = withContext(Dispatchers.IO) {
        val body = getJson(
            baseUrl.toHttpUrl().newBuilder()
                .addPathSegments(APPROVAL_PENDING_PATH)
                .addQueryParameter(SESSION_ID_PARAM, sessionId)
                .build(),
            credential,
        ) ?: return@withContext null
        StreamEventMapper.approvalStreamUpdate(null, body)
    }

    /** `GET /api/clarify/pending` — same fallback for the clarify card. */
    private suspend fun pollClarify(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
    ): ClarifyStreamUpdate? = withContext(Dispatchers.IO) {
        val body = getJson(
            baseUrl.toHttpUrl().newBuilder()
                .addPathSegments(CLARIFY_PENDING_PATH)
                .addQueryParameter(SESSION_ID_PARAM, sessionId)
                .build(),
            credential,
        ) ?: return@withContext null
        StreamEventMapper.clarifyStreamUpdate(null, body)
    }

    private fun getJson(url: HttpUrl, credential: ConsumedDeviceCredential): String? {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${credential.accessToken}")
            .get()
            .build()
        return restClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }

    private fun sseUrl(baseUrl: String, path: String, query: Pair<String, String>? = null): HttpUrl =
        baseUrl.toHttpUrl().newBuilder()
            .addPathSegments(path)
            .apply { query?.let { addQueryParameter(it.first, it.second) } }
            .build()

    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName

    /** Non-2xx answer from the SSE endpoint; the status is all the caller needs to retry. */
    private class LiveStreamHttpException(val code: Int, body: String) :
        Exception("HTTP $code${if (body.isBlank()) "" else ": ${body.take(200)}"}")

    companion object {
        /** Paths, kept in one place so a server rename is a one-line change. */
        const val SESSIONS_PATH = "api/sessions/events"
        const val APPROVAL_STREAM_PATH = "api/approval/stream"
        const val CLARIFY_STREAM_PATH = "api/clarify/stream"
        const val APPROVAL_PENDING_PATH = "api/approval/pending"
        const val CLARIFY_PENDING_PATH = "api/clarify/pending"
        const val SESSION_ID_PARAM = "session_id"

        /** Reason carried by the synthetic session-feed event produced while polling. */
        const val POLL_FALLBACK_REASON = "poll_fallback"

        /** Internal marker: the server closed a feed that is supposed to stay open. */
        const val STREAM_CLOSED_REASON = "stream_closed"

        /** Poll cadence for the dialog list fallback: a list refetch is heavier than a head GET. */
        const val DEFAULT_SESSIONS_POLL_INTERVAL_MS: Long = 15_000L

        /** Poll cadence for approval/clarify fallback — the pre-SSE WebUI cadence (1.5 s) with headroom. */
        const val DEFAULT_ATTENTION_POLL_INTERVAL_MS: Long = 2_000L

        /** How many fallback polls pass before the flow retries SSE once (2 s × 5 = 10 s). */
        const val DEFAULT_SSE_RETRY_AFTER_POLLS: Int = 5
    }
}

/**
 * Keeps only the payloads of a live channel, dropping [LiveChannelEvent.Mode] transitions.
 * Callers that need the transitions (to show a "polling" hint or to reconcile on reconnect)
 * handle the sealed type directly.
 */
@Suppress("UNCHECKED_CAST")
fun <T> Flow<LiveChannelEvent<T>>.updatesOnly(): Flow<T> =
    mapNotNull { event -> event.payloadOrNull }
