package ru.mars.network.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import ru.mars.network.http.HttpClients
import ru.mars.network.model.ConsumedDeviceCredential
import java.io.IOException

/**
 * Manual session-context compression contracts of the WebUI backend (verified against
 * `api/routes.py`):
 * - POST /api/session/compress/start `{session_id, focus_topic?}` — admits a background job:
 *   200 `{"ok", "status": "running"|"done"|"error"|"cancelled", "session_id", "focus_topic",
 *   "started_at", "updated_at", ...}` (`done`/`error` payloads merge the terminal job result,
 *   so a small history can finish inside the start call itself); 404 unknown session;
 *   409 "Session is still streaming" / stale Agent runtime.
 * - GET  /api/session/compress/status?session_id=… — `{"ok": true, "status": "idle"}` when no
 *   job exists, otherwise the same status payload; a terminal `done`/`error` stays readable
 *   for the job TTL (10 min), so several clients can observe one result.
 *
 * The `summary` of a finished job is the `_summarize_manual_compression` dict:
 * `{headline, token_line, note, reference_message}` — the client displays headline/token_line
 * and reloads the session transcript (the server replaced the message history).
 */

/** Human-readable outcome of a finished compression job. */
data class CompressionSummary(
    val headline: String?,
    val tokenLine: String?,
    val note: String?,
) {
    /** One-liner for status/toast: headline first, then the token line, then the note. */
    val display: String
        get() = listOfNotNull(headline, tokenLine, note)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
}

/** Wire values of the job `status` field (`_manual_compression_status_payload`). */
enum class CompressionJobStatus { IDLE, RUNNING, DONE, ERROR, CANCELLED, UNKNOWN }

/** Outcome of `POST /api/session/compress/start`. */
sealed interface CompressionStartResult {
    /** Job admitted (`status: running`) — poll [CompressionStatusResult] until terminal. */
    data class Running(val focusTopic: String?) : CompressionStartResult

    /** Fast path: the job finished inside the start call (`status: done` + merged result). */
    data class Done(val summary: CompressionSummary?) : CompressionStartResult

    /**
     * Server refused the job: 409 (session streaming / stale runtime), 404 (unknown session),
     * 400 (bad body), a 200 `error`/`cancelled` payload, or an unparseable success body.
     */
    data class Rejected(val message: String, val httpStatus: Int) : CompressionStartResult

    /** Transport failure; no HTTP response was observed. */
    data class NetworkError(val message: String) : CompressionStartResult
}

/** Outcome of `GET /api/session/compress/status?session_id=…`. */
sealed interface CompressionStatusResult {
    data class Found(
        val status: CompressionJobStatus,
        val error: String?,
        val summary: CompressionSummary?,
    ) : CompressionStatusResult

    data class Failed(val message: String) : CompressionStatusResult
}

/**
 * Blocking HTTP calls (same convention as [InteractiveRequestsRepository]): the compression
 * controller runs them on its own background [java.util.concurrent.Executor] and never on the
 * UI thread, so no suspend/coroutine plumbing is needed here.
 */
class ContextCompressionRepository(private val client: OkHttpClient = HttpClients.rest) {
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val moshi = Moshi.Builder().build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun startCompression(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
        focusTopic: String? = null,
    ): CompressionStartResult {
        if (sessionId.isBlank()) return CompressionStartResult.Rejected("session_id is required", 0)
        val args = mutableMapOf<String, Any?>("session_id" to sessionId)
        focusTopic?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.let { args["focus_topic"] = it.take(MAX_FOCUS_TOPIC_LENGTH) }
        val request = post(baseUrl, "api/session/compress/start", args, credential)
            ?: return CompressionStartResult.Rejected("Invalid base url", 0)
        return execute(request, ::startResult) { CompressionStartResult.NetworkError(networkMessage(it)) }
    }

    fun fetchStatus(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
    ): CompressionStatusResult {
        if (sessionId.isBlank()) return CompressionStatusResult.Failed("session_id is required")
        val request = get(baseUrl, "api/session/compress/status", sessionId, credential)
            ?: return CompressionStatusResult.Failed("Invalid base url")
        return execute(request, ::statusResult) { CompressionStatusResult.Failed(networkMessage(it)) }
    }

    // --- parsing ---------------------------------------------------------------------------

    private fun startResult(code: Int, obj: Map<String, Any?>?): CompressionStartResult {
        val status = statusOf(obj)
        val error = stringField(obj, "error")
        if (code !in 200..299) {
            return CompressionStartResult.Rejected(error ?: "HTTP $code", code)
        }
        return when (status) {
            CompressionJobStatus.RUNNING -> CompressionStartResult.Running(stringField(obj, "focus_topic"))
            CompressionJobStatus.DONE -> CompressionStartResult.Done(parseSummary(obj?.get("summary")))
            CompressionJobStatus.ERROR, CompressionJobStatus.CANCELLED ->
                CompressionStartResult.Rejected(error ?: COMPRESSION_FAILED, code)
            // 2xx with an unknown/absent status is a contract violation — surface it, do not poll.
            CompressionJobStatus.IDLE, CompressionJobStatus.UNKNOWN ->
                CompressionStartResult.Rejected(error ?: "Unexpected compress/start response", code)
        }
    }

    private fun statusResult(code: Int, obj: Map<String, Any?>?): CompressionStatusResult {
        if (code !in 200..299) {
            return CompressionStatusResult.Failed(stringField(obj, "error") ?: "HTTP $code")
        }
        val status = statusOf(obj)
        return CompressionStatusResult.Found(
            status = status,
            error = stringField(obj, "error"),
            summary = if (status == CompressionJobStatus.DONE) parseSummary(obj?.get("summary")) else null,
        )
    }

    private fun statusOf(obj: Map<String, Any?>?): CompressionJobStatus =
        when (stringField(obj, "status")?.lowercase()) {
            "idle" -> CompressionJobStatus.IDLE
            "running" -> CompressionJobStatus.RUNNING
            "done" -> CompressionJobStatus.DONE
            "error" -> CompressionJobStatus.ERROR
            "cancelled" -> CompressionJobStatus.CANCELLED
            else -> CompressionJobStatus.UNKNOWN
        }

    // --- plumbing ---------------------------------------------------------------------------

    private inline fun <T> execute(
        request: Request,
        parse: (Int, Map<String, Any?>?) -> T,
        onIoError: (IOException) -> T,
    ): T = try {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            parse(response.code, StreamEventMapper.parseObject(text))
        }
    } catch (error: IOException) {
        onIoError(error)
    }

    // IOException messages contain host/path at worst — never request headers, so the token cannot leak.
    private fun networkMessage(error: IOException): String = error.message ?: "Network error"

    private fun post(
        baseUrl: String,
        path: String,
        args: Map<String, Any?>,
        credential: ConsumedDeviceCredential,
    ): Request? = runCatching {
        Request.Builder()
            .url(baseUrl.toHttpUrl().newBuilder().addPathSegments(path).build())
            .header("Authorization", "Bearer ${credential.accessToken}")
            .post(RequestBody.create(jsonMediaType, moshi.adapter<Map<String, Any?>>(mapType).toJson(args)))
            .build()
    }.getOrNull()

    private fun get(
        baseUrl: String,
        path: String,
        sessionId: String,
        credential: ConsumedDeviceCredential,
    ): Request? = runCatching {
        Request.Builder()
            .url(
                baseUrl.toHttpUrl().newBuilder().addPathSegments(path)
                    .addQueryParameter("session_id", sessionId).build()
            )
            .header("Authorization", "Bearer ${credential.accessToken}")
            .get()
            .build()
    }.getOrNull()

    companion object {
        const val COMPRESSION_FAILED = "Compression failed"

        /** Server-side cap of the focus topic (`[:500]` in `_handle_session_compress_start`). */
        const val MAX_FOCUS_TOPIC_LENGTH = 500

        /** Parses the `{headline, token_line, note, reference_message}` summary dict. */
        fun parseSummary(value: Any?): CompressionSummary? {
            val map = value as? Map<*, *> ?: return null
            val headline = map["headline"]?.toString()?.takeIf { it.isNotBlank() }
            val tokenLine = map["token_line"]?.toString()?.takeIf { it.isNotBlank() }
            val note = map["note"]?.toString()?.takeIf { it.isNotBlank() }
            if (headline == null && tokenLine == null && note == null) return null
            return CompressionSummary(headline, tokenLine, note)
        }

        private fun stringField(obj: Map<String, Any?>?, key: String): String? =
            obj?.get(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" }
    }
}
