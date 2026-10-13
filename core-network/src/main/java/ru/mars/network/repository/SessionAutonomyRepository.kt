package ru.mars.network.repository

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.mars.network.http.HttpClients
import ru.mars.network.model.ConsumedDeviceCredential
import java.io.IOException

/** Result of the session-autonomy lookup (`GET`; wire mapping in [SessionModeWire]). */
sealed interface AutonomyLookup {
    data class State(val enabled: Boolean) : AutonomyLookup
    data class Failed(val message: String) : AutonomyLookup
}

/**
 * Result of the session-autonomy toggle (`POST`; wire mapping in [SessionModeWire]).
 *
 * The server answers `{"ok": true, <settled flag>}`; when enabling fails (e.g. the
 * pending-approval relay is unavailable) the error body may STILL carry the settled state
 * — [Failed.knownState] preserves it so the UI can project the real server state (web parity:
 * the frontend reads the settled flag from error payloads too).
 */
sealed interface AutonomyToggleResult {
    /** The server committed the toggle; [enabled] is the settled server-side state. */
    data class Applied(val enabled: Boolean) : AutonomyToggleResult
    data class Failed(val message: String, val knownState: Boolean? = null) : AutonomyToggleResult
}

/**
 * Data-layer client for the session-autonomy contract of hermes-webui (`api/routes.py`).
 * The legacy wire names (endpoint path, request/response fields) are mapped exclusively in
 * [SessionModeWire] — this repository speaks only the neutral domain language.
 *
 * Autonomy is session-scoped server state (in-memory; lost on server restart, isolated per
 * session), so the client NEVER persists it locally — it re-fetches after every session
 * load/switch, exactly like the web frontend.
 *
 * Auth/security conventions match [InteractiveRequestsRepository]: `Authorization: Bearer ***
 * only in the header, token never in URLs, logs or result messages. Blocking by design;
 * callers run it on a background dispatcher/executor.
 */
class SessionAutonomyRepository(private val client: OkHttpClient = HttpClients.rest) {

    /** Fetches the current autonomy state of a session. */
    fun fetchState(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
    ): AutonomyLookup {
        if (sessionId.isBlank()) return AutonomyLookup.Failed("session_id is required")
        val request = runCatching {
            Request.Builder()
                .url(
                    baseUrl.toHttpUrl().newBuilder().addPathSegments(SessionModeWire.SESSION_MODE_PATH)
                        .addQueryParameter("session_id", sessionId).build()
                )
                .header("Authorization", "Bearer ${credential.accessToken}")
                .get()
                .build()
        }.getOrElse { return AutonomyLookup.Failed("Invalid base url") }
        return execute(request, onIoError = { AutonomyLookup.Failed(networkMessage(it)) }) { code, obj ->
            val enabled = SessionModeWire.readEnabledFlag(obj)
            when {
                code in 200..299 && enabled != null -> AutonomyLookup.State(enabled)
                else -> AutonomyLookup.Failed(httpMessage(code, obj))
            }
        }
    }

    /** Toggles session autonomy; [AutonomyToggleResult.Applied.enabled] is the settled state. */
    fun setEnabled(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
        enabled: Boolean,
    ): AutonomyToggleResult {
        if (sessionId.isBlank()) return AutonomyToggleResult.Failed("session_id is required")
        val body = buildString {
            append("{\"session_id\":").append(jsonString(sessionId))
            append(",\"enabled\":").append(enabled)
            append('}')
        }
        val request = runCatching {
            Request.Builder()
                .url(baseUrl.toHttpUrl().newBuilder().addPathSegments(SessionModeWire.SESSION_MODE_PATH).build())
                .header("Authorization", "Bearer ${credential.accessToken}")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }.getOrElse { return AutonomyToggleResult.Failed("Invalid base url") }
        return execute(request, onIoError = { AutonomyToggleResult.Failed(networkMessage(it)) }) { code, obj ->
            val settled = SessionModeWire.readEnabledFlag(obj)
            val error = obj?.get("error")?.toString()?.takeIf { it.isNotBlank() }
            if (code in 200..299 && obj?.get("ok") == true && settled != null) {
                AutonomyToggleResult.Applied(settled)
            } else {
                AutonomyToggleResult.Failed(error ?: httpMessage(code, obj), knownState = settled)
            }
        }
    }

    // --- plumbing ---------------------------------------------------------------------------

    private inline fun <T> execute(
        request: Request,
        onIoError: (IOException) -> T,
        parse: (Int, Map<String, Any?>?) -> T,
    ): T = try {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            parse(response.code, StreamEventMapper.parseObject(text))
        }
    } catch (error: IOException) {
        // IOException messages contain host/path at worst — never request headers, so the token cannot leak.
        onIoError(error)
    }

    private fun httpMessage(code: Int, obj: Map<String, Any?>?): String =
        obj?.get("error")?.toString()?.takeIf { it.isNotBlank() } ?: "HTTP $code"

    private fun networkMessage(error: IOException): String = error.message ?: "Network error"

    /** Delegates to the module-wide strict escaper (single point — see [JsonStringEncoding]). */
    private fun jsonString(value: String) = JsonStringEncoding.escape(value)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
