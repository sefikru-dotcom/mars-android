package ru.mars.network.repository

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.mars.network.http.HttpClients
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.model.ConsumedDeviceCredential
import java.io.IOException

/**
 * Decision values accepted by `POST /api/approval/respond` (hermes-webui `_handle_approval_respond`:
 * any other value is rejected with 400 `Invalid choice`). The enum makes invalid choices
 * unrepresentable at compile time.
 */
enum class ApprovalChoice(val wireValue: String) {
    ONCE("once"),
    SESSION("session"),
    ALWAYS("always"),
    DENY("deny"),
}

/** Outcome of an approval/clarify respond call, mirroring the server response contract. */
sealed interface InteractiveRespondResult {
    /**
     * HTTP 200 with `{"ok": true, ...}`.
     * @param staleCleared approval only: the answered `approval_id` was already gone and the server
     *   cleared a stale head instead (`stale_cleared: true`) — the client should re-fetch pending.
     * @param autonomyEnabled present when the response toggled session-wide autonomy
     *   (settled server flag; wire mapping in [SessionModeWire]).
     */
    data class Accepted(val staleCleared: Boolean = false, val autonomyEnabled: Boolean? = null) : InteractiveRespondResult

    /**
     * Clarify: HTTP 409 with `{"ok": false, "stale": true}` — the prompt expired or was already
     * resolved. Retrying the same `clarify_id` can never succeed; the dialog must be dismissed.
     */
    data class Stale(val message: String) : InteractiveRespondResult

    /**
     * Server refused the response: HTTP 200 `{"ok": false}`, HTTP 409 gateway relay failure
     * (`code` carries e.g. `gateway_run_unavailable`), 4xx validation errors, or local pre-flight
     * validation ([httpStatus] = 0, no request was sent).
     *
     * @param autonomyEnabled present when the failed response still toggled session-wide
     *   autonomy (web parity: the settled flag is projected from error payloads too — the server
     *   commits it even when the individual approval relay fails, e.g. `gateway_run_unavailable`;
     *   wire mapping in [SessionModeWire]).
     */
    data class Rejected(
        val message: String,
        val httpStatus: Int,
        val code: String? = null,
        val autonomyEnabled: Boolean? = null,
    ) : InteractiveRespondResult

    /** Transport failure; no HTTP response was observed. */
    data class NetworkError(val message: String) : InteractiveRespondResult
}

/** Result of `GET /api/approval/pending?session_id=` (`{"pending": {...}|null, "pending_count": N}`). */
sealed interface PendingApprovalLookup {
    data class Found(val payload: ApprovalPayload) : PendingApprovalLookup
    data object Empty : PendingApprovalLookup
    data class Failed(val message: String) : PendingApprovalLookup
}

/** Result of `GET /api/clarify/pending?session_id=` (`{"pending": {...}|null}`). */
sealed interface PendingClarifyLookup {
    data class Found(val payload: ClarifyPayload) : PendingClarifyLookup
    data object Empty : PendingClarifyLookup
    data class Failed(val message: String) : PendingClarifyLookup
}

/**
 * Data-layer client for the interactive attention contracts of hermes-webui:
 * `POST /api/approval/respond`, `POST /api/clarify/respond` and the matching `pending` GETs.
 *
 * Auth: the device credential travels in the `Authorization` header (Bearer scheme), like every
 * other repository. All four routes are in the server device-Bearer allow-list:
 * `NATIVE_BEARER_EXACT_PATHS` in `api/auth.py` (lines 95-98) lists
 * `/api/approval/pending`, `/api/approval/respond`, `/api/clarify/pending` and
 * `/api/clarify/respond`, and `check_auth` admits a verified native credential on an exact path
 * regardless of method. A device token therefore works directly, with no browser cookie session
 * and no server-side debt. A 401 here means the token itself is invalid or revoked, not a missing
 * allow-list entry — it surfaces as [InteractiveRespondResult.Rejected]; do NOT work around it by
 * weakening server auth.
 *
 * Security: the access token is only ever placed into the Authorization header. It is never logged
 * and never copied into result messages (result text comes from the server `error` field, the HTTP
 * status, or an [IOException] message — none of which contain request headers).
 *
 * Blocking by design (same convention as [ChatRepository.executeControl]); callers run it on a
 * background dispatcher/executor.
 */
class InteractiveRequestsRepository(private val client: OkHttpClient = HttpClients.rest) {

    /**
     * Answers a pending approval. Body contract (messages.js respondApproval):
     * `{"session_id", "choice", "approval_id"}` plus `run_id`/`mirror_token` ONLY when both are
     * present (a partial gateway triple is rejected by the server with 409
     * `gateway_run_unavailable`) and the autonomy-grant flag only when true (wire name in
     * [SessionModeWire]).
     */
    fun respondApproval(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
        payload: ApprovalPayload,
        choice: ApprovalChoice,
        grantAutonomy: Boolean = false,
    ): InteractiveRespondResult {
        if (sessionId.isBlank()) return localRejection("session_id is required")
        val body = buildString {
            append("{\"session_id\":").append(jsonString(sessionId))
            append(",\"choice\":").append(jsonString(choice.wireValue))
            append(",\"approval_id\":").append(jsonString(payload.approvalId.orEmpty()))
            val runId = payload.runId?.trim().orEmpty()
            val mirrorToken = payload.mirrorToken?.trim().orEmpty()
            if (runId.isNotEmpty() && mirrorToken.isNotEmpty()) {
                append(",\"run_id\":").append(jsonString(runId))
                append(",\"mirror_token\":").append(jsonString(mirrorToken))
            }
            if (grantAutonomy) append(SessionModeWire.grantSuffix())
            append('}')
        }
        val request = jsonPost(baseUrl, "api/approval/respond", body, credential)
            ?: return localRejection("Invalid base url")
        return execute(
            request,
            parse = { code, obj -> respondResult(code, obj, approval = true) },
            onIoError = ::networkError,
        )
    }

    /**
     * Answers a pending clarify. Body contract (messages.js respondClarify):
     * `{"session_id", "response", "clarify_id"}` — `clarify_id` is always sent (empty string when
     * unknown, matching the frontend's `clarifyId || ""`); the server resolves the head entry then.
     */
    fun respondClarify(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
        payload: ClarifyPayload,
        response: String,
    ): InteractiveRespondResult {
        if (sessionId.isBlank()) return localRejection("session_id is required")
        if (response.isBlank()) return localRejection("response is required")
        val answer = response.trim()
        val body = buildString {
            append("{\"session_id\":").append(jsonString(sessionId))
            append(",\"response\":").append(jsonString(answer))
            append(",\"clarify_id\":").append(jsonString(payload.clarifyId.orEmpty()))
            append('}')
        }
        val request = jsonPost(baseUrl, "api/clarify/respond", body, credential)
            ?: return localRejection("Invalid base url")
        return execute(
            request,
            parse = { code, obj -> respondResult(code, obj, approval = false) },
            onIoError = ::networkError,
        )
    }

    /** Fetches the approval queue head — used to show the next queued request after `stale_cleared`. */
    fun fetchPendingApproval(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
    ): PendingApprovalLookup {
        val request = pendingGet(baseUrl, "api/approval/pending", sessionId, credential)
            ?: return PendingApprovalLookup.Failed("Invalid base url")
        return execute(
            request,
            parse = { code, obj ->
                if (code !in 200..299) return@execute PendingApprovalLookup.Failed(httpMessage(code, obj))
                @Suppress("UNCHECKED_CAST")
                val parsed = StreamEventMapper.approvalPayload(obj?.get("pending") as? Map<String, Any?>)
                    ?: return@execute PendingApprovalLookup.Empty
                val count = (obj?.get("pending_count") as? Number)?.toInt()?.takeIf { it > 0 }
                PendingApprovalLookup.Found(if (count != null) parsed.copy(pendingCount = count) else parsed)
            },
            onIoError = { PendingApprovalLookup.Failed(networkMessage(it)) },
        )
    }

    /** Fetches the pending clarify prompt — recovery path after a reconnect. */
    fun fetchPendingClarify(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
    ): PendingClarifyLookup {
        val request = pendingGet(baseUrl, "api/clarify/pending", sessionId, credential)
            ?: return PendingClarifyLookup.Failed("Invalid base url")
        return execute(
            request,
            parse = { code, obj ->
                if (code !in 200..299) return@execute PendingClarifyLookup.Failed(httpMessage(code, obj))
                @Suppress("UNCHECKED_CAST")
                val parsed = StreamEventMapper.clarifyPayload(obj?.get("pending") as? Map<String, Any?>)
                    ?: return@execute PendingClarifyLookup.Empty
                PendingClarifyLookup.Found(parsed)
            },
            onIoError = { PendingClarifyLookup.Failed(networkMessage(it)) },
        )
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

    private fun respondResult(
        code: Int,
        obj: Map<String, Any?>?,
        approval: Boolean,
    ): InteractiveRespondResult {
        val ok = obj?.get("ok") == true
        val stale = obj?.get("stale") == true
        val error = obj?.get("error")?.toString()?.takeIf { it.isNotBlank() }
        if (code in 200..299 && ok) {
            return InteractiveRespondResult.Accepted(
                staleCleared = obj?.get("stale_cleared") == true,
                autonomyEnabled = SessionModeWire.readEnabledFlag(obj),
            )
        }
        // Clarify 409 always carries stale:true; treat a bare 409 the same (frontend parity:
        // respondClarify treats HTTP 409 as terminal even without the flag).
        if (stale || (code == 409 && !approval)) {
            return InteractiveRespondResult.Stale(
                error ?: "Clarification prompt expired or not found. The agent may have already proceeded."
            )
        }
        val message = error
            ?: if (code in 200..299) {
                if (approval) "Approval response not accepted." else "Clarification response not accepted."
            } else {
                "HTTP $code"
            }
        return InteractiveRespondResult.Rejected(
            message = message,
            httpStatus = code,
            code = obj?.get("code")?.toString()?.takeIf { it.isNotBlank() },
            autonomyEnabled = SessionModeWire.readEnabledFlag(obj),
        )
    }

    private fun httpMessage(code: Int, obj: Map<String, Any?>?): String =
        obj?.get("error")?.toString()?.takeIf { it.isNotBlank() } ?: "HTTP $code"

    private fun localRejection(message: String) =
        InteractiveRespondResult.Rejected(message = message, httpStatus = 0)

    private fun networkError(error: IOException): InteractiveRespondResult =
        InteractiveRespondResult.NetworkError(networkMessage(error))

    // IOException messages contain host/path at worst — never request headers, so the token cannot leak.
    private fun networkMessage(error: IOException): String = error.message ?: "Network error"

    private fun jsonPost(
        baseUrl: String,
        path: String,
        body: String,
        credential: ConsumedDeviceCredential,
    ): Request? = runCatching {
        Request.Builder()
            .url(baseUrl.toHttpUrl().newBuilder().addPathSegments(path).build())
            .header("Authorization", "Bearer ${credential.accessToken}")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }.getOrNull()

    private fun pendingGet(
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

    /** Delegates to the module-wide strict escaper (single point — see [JsonStringEncoding]). */
    private fun jsonString(value: String) = JsonStringEncoding.escape(value)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
