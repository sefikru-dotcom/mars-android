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
import ru.mars.network.model.ReasoningStatus
import java.io.IOException

/** Outcome of a `/api/reasoning` call (both GET and POST answer the same status payload). */
sealed interface ReasoningStatusResult {
    /** Server answered 2xx with a parseable status payload. */
    data class Status(val status: ReasoningStatus) : ReasoningStatusResult

    /**
     * HTTP error (mapped `error` message when present, else `HTTP <code>`) or transport
     * failure; the caller must treat the reasoning state as unknown and stay safe.
     */
    data class Failed(val message: String) : ReasoningStatusResult
}

/**
 * Reasoning-effort contract of the WebUI backend (verified live and against
 * `api/routes.py` `_handle_api_reasoning` / `api/config.get_reasoning_status`):
 * - GET  /api/reasoning?model=&provider=&session_id= — effective status for the context.
 * - POST /api/reasoning `{effort, model?, provider?, session_id?}` — persists the effort
 *   (WebUI-parity: the composer chip posts `effort:''` for Default to clear the override)
 *   and answers the same status payload.
 *
 * `POST /api/chat/start` carries NO reasoning field: the server resolves the effort per
 * turn from session override > per-model pin > global config, so the selection must be
 * persisted through this endpoint before the next chat/start — exactly what the WebUI does.
 *
 * Blocking HTTP calls (same convention as [ContextCompressionRepository]): callers run
 * them on a background dispatcher, never on the UI thread.
 */
class ReasoningRepository(private val client: OkHttpClient = HttpClients.rest) {

    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val moshi = Moshi.Builder().build()
    private val jsonMediaType = "application/json".toMediaType()

    /** GET /api/reasoning with the optional model/provider/session context query. */
    fun fetchStatus(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        model: String? = null,
        provider: String? = null,
        sessionId: String? = null,
    ): ReasoningStatusResult =
        execute(
            request = Request.Builder()
                .url(
                    baseUrl.toHttpUrl().newBuilder()
                        .addPathSegments("api/reasoning")
                        .apply {
                            model?.takeIf(String::isNotBlank)?.let { addQueryParameter("model", it) }
                            provider?.takeIf(String::isNotBlank)?.let { addQueryParameter("provider", it) }
                            sessionId?.takeIf(String::isNotBlank)?.let { addQueryParameter("session_id", it) }
                        }
                        .build()
                )
                .header("Authorization", "Bearer ${credential.accessToken}")
                .get()
                .build(),
            onIoError = { ReasoningStatusResult.Failed(networkMessage(it)) },
        )

    /**
     * POST /api/reasoning. [effort] null/blank means "Default" and reaches the wire as
     * `""` (WebUI parity — the key must be present to clear the override).
     */
    fun setEffort(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        effort: String?,
        model: String? = null,
        provider: String? = null,
        sessionId: String? = null,
    ): ReasoningStatusResult {
        val args = mutableMapOf<String, Any?>("effort" to (effort?.trim().orEmpty()))
        model?.takeIf(String::isNotBlank)?.let { args["model"] = it }
        provider?.takeIf(String::isNotBlank)?.let { args["provider"] = it }
        sessionId?.takeIf(String::isNotBlank)?.let { args["session_id"] = it }
        return execute(
            request = Request.Builder()
                .url(baseUrl.toHttpUrl().newBuilder().addPathSegments("api/reasoning").build())
                .header("Authorization", "Bearer ${credential.accessToken}")
                .post(RequestBody.create(jsonMediaType, moshi.adapter<Map<String, Any?>>(mapType).toJson(args)))
                .build(),
            onIoError = { ReasoningStatusResult.Failed(networkMessage(it)) },
        )
    }

    private inline fun execute(
        request: Request,
        onIoError: (IOException) -> ReasoningStatusResult,
    ): ReasoningStatusResult = try {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val root = parseMap(body)
            if (!response.isSuccessful) {
                return@use ReasoningStatusResult.Failed(stringField(root, "error") ?: "HTTP ${response.code}")
            }
            if (root == null) {
                ReasoningStatusResult.Failed("HTTP ${response.code}: unparseable body")
            } else {
                ReasoningStatusResult.Status(parseStatus(root))
            }
        }
    } catch (error: IOException) {
        onIoError(error)
    }

    private fun parseStatus(root: Map<String, Any?>) = ReasoningStatus(
        showReasoning = (root["show_reasoning"] as? Boolean) ?: true,
        reasoningEffort = stringField(root, "reasoning_effort"),
        source = stringField(root, "source"),
        supportedEfforts = (root["supported_efforts"] as? List<*>)
            .orEmpty()
            .mapNotNull { it?.toString()?.takeIf(String::isNotBlank) },
        supportsReasoningEffort = (root["supports_reasoning_effort"] as? Boolean) ?: false,
        supportsThinkingToggle = (root["supports_thinking_toggle"] as? Boolean) ?: false,
    )

    private fun parseMap(json: String): Map<String, Any?>? =
        try {
            moshi.adapter<Map<String, Any?>>(mapType).fromJson(json)
        } catch (_: Exception) {
            null
        }

    // IOException messages contain host/path at worst — never request headers, so the token cannot leak.
    private fun networkMessage(error: IOException): String = error.message ?: "Network error"

    private companion object {
        private fun stringField(obj: Map<String, Any?>?, key: String): String? =
            obj?.get(key)?.toString()?.takeIf(String::isNotBlank)
    }
}
