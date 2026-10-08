package ru.mars.network.repository

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.mars.network.model.ConsumedDeviceCredential
import java.io.IOException

/** Result of `GET /api/session/yolo?session_id=` (`{"yolo_enabled": bool}`). */
sealed interface YoloLookup {
    data class State(val enabled: Boolean) : YoloLookup
    data class Failed(val message: String) : YoloLookup
}

/**
 * Result of `POST /api/session/yolo` (`{"session_id", "enabled"}`).
 *
 * The server answers `{"ok": true, "yolo_enabled": <settled>}`; when enabling fails (e.g. the
 * pending-approval relay is unavailable) the error body may STILL carry the settled `yolo_enabled`
 * — [Failed.knownState] preserves it so the UI can project the real server state (web parity:
 * `cmdYolo` reads `yolo_enabled` from error payloads).
 */
sealed interface YoloToggleResult {
    /** The server committed the toggle; [enabled] is the settled server-side state. */
    data class Applied(val enabled: Boolean) : YoloToggleResult
    data class Failed(val message: String, val knownState: Boolean? = null) : YoloToggleResult
}

/**
 * Data-layer client for the session YOLO contract of hermes-webui (`api/routes.py`):
 * - `GET  /api/session/yolo?session_id=` → `{"yolo_enabled": bool}`
 * - `POST /api/session/yolo` `{"session_id", "enabled"}` → `{"ok": true, "yolo_enabled": bool}`
 *
 * YOLO is session-scoped server state (in-memory; lost on server restart, isolated per session),
 * so the client NEVER persists it locally — it re-fetches after every session load/switch,
 * exactly like the web frontend (`_loadYoloState`).
 *
 * Auth/security conventions match [InteractiveRequestsRepository]: `Authorization: Bearer ***
 * only in the header, token never in URLs, logs or result messages. Blocking by design;
 * callers run it on a background dispatcher/executor.
 */
class SessionYoloRepository(private val client: OkHttpClient = OkHttpClient()) {

    /** Fetches the current YOLO state of a session. */
    fun fetchState(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
    ): YoloLookup {
        if (sessionId.isBlank()) return YoloLookup.Failed("session_id is required")
        val request = runCatching {
            Request.Builder()
                .url(
                    baseUrl.toHttpUrl().newBuilder().addPathSegments("api/session/yolo")
                        .addQueryParameter("session_id", sessionId).build()
                )
                .header("Authorization", "Bearer ${credential.accessToken}")
                .get()
                .build()
        }.getOrElse { return YoloLookup.Failed("Invalid base url") }
        return execute(request, onIoError = { YoloLookup.Failed(networkMessage(it)) }) { code, obj ->
            val enabled = obj?.get("yolo_enabled") as? Boolean
            when {
                code in 200..299 && enabled != null -> YoloLookup.State(enabled)
                else -> YoloLookup.Failed(httpMessage(code, obj))
            }
        }
    }

    /** Toggles YOLO for a session; [YoloToggleResult.Applied.enabled] is the settled state. */
    fun setEnabled(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
        enabled: Boolean,
    ): YoloToggleResult {
        if (sessionId.isBlank()) return YoloToggleResult.Failed("session_id is required")
        val body = buildString {
            append("{\"session_id\":").append(jsonString(sessionId))
            append(",\"enabled\":").append(enabled)
            append('}')
        }
        val request = runCatching {
            Request.Builder()
                .url(baseUrl.toHttpUrl().newBuilder().addPathSegments("api/session/yolo").build())
                .header("Authorization", "Bearer ${credential.accessToken}")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }.getOrElse { return YoloToggleResult.Failed("Invalid base url") }
        return execute(request, onIoError = { YoloToggleResult.Failed(networkMessage(it)) }) { code, obj ->
            val settled = obj?.get("yolo_enabled") as? Boolean
            val error = obj?.get("error")?.toString()?.takeIf { it.isNotBlank() }
            if (code in 200..299 && obj?.get("ok") == true && settled != null) {
                YoloToggleResult.Applied(settled)
            } else {
                YoloToggleResult.Failed(error ?: httpMessage(code, obj), knownState = settled)
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

    private fun jsonString(value: String) = buildString {
        append('"')
        value.forEach {
            when (it) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(it)
            }
        }
        append('"')
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
