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
 * - POST /api/reasoning `{effort, model?, provider?, session_id?, scope?}` — persists the
 *   effort (WebUI-parity: the composer chip posts `effort:''` for Default to clear the
 *   override) and answers the same status payload.
 *
 * `POST /api/chat/start` carries NO reasoning field: the server resolves the effort per
 * turn from session override > per-model pin > global config, so the selection must be
 * persisted through this endpoint before the next chat/start — exactly what the WebUI does.
 *
 * Per-model pin parity (static/ui.js composer chip): when the target model carries a pin in
 * `agent.reasoning_overrides` (surfaced as `model_overrides` of the GET status), the chip must
 * edit THAT pin — `{"scope":"model","model":<pinned key>}` — because a global write is silently
 * overridden by the pin at runtime (`set_model_reasoning_override`: session > model > global).
 * [setEffort] therefore preflights `GET /api/reasoning` with the same context and, when a pin
 * matches (spelling-tolerant, `@custom:<provider>:<model>` full-form keys included), posts
 * `scope=model` with the EXISTING key verbatim so the server updates (or, for Default `effort:''`,
 * removes) exactly that entry instead of accumulating a near-duplicate. A failed/absent preflight
 * falls back to the legacy global body — the user action is never blocked by the extra probe.
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
            request = statusRequest(baseUrl, credential, model, provider, sessionId),
            onIoError = { ReasoningStatusResult.Failed(networkMessage(it)) },
        )

    /** Builds the context-carrying `GET /api/reasoning` request shared by [fetchStatus] and the pin preflight. */
    private fun statusRequest(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        model: String?,
        provider: String?,
        sessionId: String?,
    ): Request =
        Request.Builder()
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
            .build()

    /**
     * POST /api/reasoning. [effort] null/blank means "Default" and reaches the wire as
     * `""` (WebUI parity — the key must be present to clear the override).
     *
     * When [model] carries a per-model pin (preflighted `model_overrides` of the GET status),
     * the POST edits that pin (`scope:"model"` + the pinned key verbatim) instead of the global
     * default — matching the WebUI composer chip, which would otherwise have its global write
     * silently overridden by the pin at runtime. Any preflight failure keeps the legacy body.
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
        val wireModel = model?.takeIf(String::isNotBlank)
        wireModel?.let { args["model"] = it }
        provider?.takeIf(String::isNotBlank)?.let { args["provider"] = it }
        sessionId?.takeIf(String::isNotBlank)?.let { args["session_id"] = it }
        if (wireModel != null) {
            findPinnedKey(baseUrl, credential, wireModel, provider, sessionId)?.let { pinnedKey ->
                args["model"] = pinnedKey
                args["scope"] = "model"
            }
        }
        return execute(
            request = Request.Builder()
                .url(baseUrl.toHttpUrl().newBuilder().addPathSegments("api/reasoning").build())
                .header("Authorization", "Bearer ${credential.accessToken}")
                .post(RequestBody.create(jsonMediaType, moshi.adapter<Map<String, Any?>>(mapType).toJson(args)))
                .build(),
            onIoError = { ReasoningStatusResult.Failed(networkMessage(it)) },
        )
    }

    /**
     * POST /api/reasoning with `{"display": "show"|"hide"}` — показ размышлений
     * (`display.show_reasoning`), тот же ключ, что пишут CLI `/reasoning show|hide`
     * и WebUI (`api/config.set_reasoning_display`). Сервер отвечает тем же статусом,
     * что и GET, поэтому вызывающий обновляет чип из ответа, а не из догадки.
     */
    fun setDisplay(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        show: Boolean,
    ): ReasoningStatusResult = execute(
        request = Request.Builder()
            .url(baseUrl.toHttpUrl().newBuilder().addPathSegments("api/reasoning").build())
            .header("Authorization", "Bearer ${credential.accessToken}")
            .post(
                RequestBody.create(
                    jsonMediaType,
                    moshi.adapter<Map<String, Any?>>(mapType).toJson(
                        mapOf("display" to if (show) "show" else "hide")
                    ),
                )
            )
            .build(),
        onIoError = { ReasoningStatusResult.Failed(networkMessage(it)) },
    )

    /**
     * Preflights the GET status and returns the RAW `model_overrides` key pinning [model],
     * or null when the model is unpinned or the probe failed (HTTP error, unparseable body,
     * transport failure) — the caller then posts the legacy global body.
     */
    private fun findPinnedKey(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        model: String,
        provider: String?,
        sessionId: String?,
    ): String? = try {
        client.newCall(statusRequest(baseUrl, credential, model, provider, sessionId)).execute().use { response ->
            if (!response.isSuccessful) return null
            val root = parseMap(response.body?.string().orEmpty()) ?: return null
            val overrides = (root["model_overrides"] as? Map<*, *>)?.mapNotNull { (key, value) ->
                val k = key?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val v = value?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                k to v
            }.orEmpty()
            matchPinnedOverrideKey(overrides, model)
        }
    } catch (_: Exception) {
        // IOException (network) or any parse/runtime surprise: degrade to the legacy body,
        // never block the user action. IOException messages contain no request headers.
        null
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

        /**
         * Finds the RAW `model_overrides` key pinning [model] (first match wins), mirroring the
         * server's spelling tolerance (`api/config._match_reasoning_override_key`: lowercase,
         * `[-_.\s]+` → `-`) plus the full-form `@custom:<provider>:<model>` keys the live config
         * uses. The bare tail after the last `/` is matched too (`qwen/qwen3.8-max` pins as
         * `…:qwen/qwen3.8-max` or `…/qwen3.8-max`). Returns null when nothing matches — the
         * caller then keeps the legacy global write.
         */
        private fun matchPinnedOverrideKey(overrides: List<Pair<String, String>>, model: String): String? {
            val target = normalizeModelKey(model)
            if (target.isEmpty()) return null
            val tail = normalizeModelKey(model.trim().substringAfterLast('/', ""))
            for ((key, _) in overrides) {
                val candidate = normalizeModelKey(key)
                if (candidate.isEmpty()) continue
                if (candidate == target || candidate.endsWith(":$target") || candidate.endsWith("/$target")) return key
                if (tail.isNotEmpty() &&
                    (candidate == tail || candidate.endsWith(":$tail") || candidate.endsWith("/$tail"))
                ) {
                    return key
                }
            }
            return null
        }

        private fun normalizeModelKey(value: String): String =
            value.trim().lowercase().replace(Regex("[\\s._-]+"), "-")
    }
}
