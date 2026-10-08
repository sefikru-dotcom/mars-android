package ru.mars.network.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.SessionAttention
import ru.mars.network.model.SessionDetail
import ru.mars.network.model.SessionItem
import ru.mars.network.model.SessionMessage
import ru.mars.network.model.WorkspacePrefix

/**
 * Outcome of `POST /api/session/new` — an explicit result instead of a silent null
 * (same pattern as [UploadOutcome]): the caller must be able to tell "no session"
 * from "session <id>" and surface the server reason on failure.
 */
sealed interface CreateSessionOutcome {
    /** 200 `{"session": {"session_id": ..., ...}}` parsed into a [SessionDetail]. */
    data class Success(val session: SessionDetail) : CreateSessionOutcome
    /** HTTP error (with the server `error` message when present), malformed body, or transport failure. */
    data class Failure(val message: String) : CreateSessionOutcome
}

/**
 * Port for minting a fresh server session. ChatActivity depends on this abstraction
 * (via FirstSessionFlow) so the first-message flow is unit-testable without HTTP.
 */
interface SessionCreator {
    suspend fun createSession(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        args: Map<String, Any?> = emptyMap(),
    ): CreateSessionOutcome
}

/**
 * Session contracts of the WebUI backend (verified against api/routes.py):
 * - GET  /api/sessions                     — session list
 * - GET  /api/session?session_id=...       — session detail incl. messages
 * - POST /api/session/new {…}              — create; 200 {"session": {"session_id": …}}
 * - POST /api/session/rename {session_id, title} — rename
 * - POST /api/session/delete {session_id}  — delete (returns {"ok": true, ...})
 *
 * All network executes are wrapped in [Dispatchers.IO], so every public
 * function is safe to call from a coroutine on the main/UI dispatcher.
 */
class SessionRepository(private val client: OkHttpClient = OkHttpClient()) : SessionCreator {
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val moshi = Moshi.Builder().build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun getSessions(baseUrl: String, credential: ConsumedDeviceCredential): List<SessionItem> =
        withContext(Dispatchers.IO) {
            val root = parseMap(execute(baseUrl.toHttpUrl().newBuilder().addPathSegments("api/sessions").build().toString(), credential) ?: return@withContext emptyList())
            val items = (root?.get("sessions") ?: root?.get("items")) as? List<*> ?: return@withContext emptyList()
            items.mapNotNull { (it as? Map<*, *>)?.let(::sessionItem) }
        }

    suspend fun getSessionDetail(baseUrl: String, credential: ConsumedDeviceCredential, sessionId: String): SessionDetail? =
        withContext(Dispatchers.IO) {
            val url = baseUrl.toHttpUrl().newBuilder().addPathSegments("api/session").addQueryParameter("session_id", sessionId).build().toString()
            val root = parseMap(execute(url, credential) ?: return@withContext null) ?: return@withContext null
            val s = (root["session"] as? Map<*, *>) ?: root
            SessionDetail(
                string(s, "id") ?: string(s, "session_id") ?: sessionId,
                string(s, "title"),
                string(s, "created_at", "createdAt"),
                string(s, "updated_at", "updatedAt"),
                (s["messages"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(::message) }.orEmpty(),
                activeTurn(s),
                // Cold `todo_state`: parsing lives in ONE place shared with the
                // live SSE path (TodoStateParser; contract documented there).
                TodoStateParser.parse(s["todo_state"] as? Map<*, *>),
                // Live workspace path (Session.compact() `workspace`); absent on
                // older payloads → null ("server default"), backward compatible.
                workspace = string(s, "workspace"),
            )
        }

    /**
     * Creates a session via `POST /api/session/new`. The body may be `{}` — the server
     * fills workspace/model/profile defaults; [args] carries explicit overrides
     * (`worktree`, `profile`, `model`, `prev_session_id`, …) when the caller needs them.
     * Success is a 200 whose `session.session_id` is present and non-blank; anything
     * else (HTTP status, missing id, transport error) maps to [CreateSessionOutcome.Failure]
     * with a human-readable reason. Runs on [Dispatchers.IO]; cancellation is rethrown.
     */
    override suspend fun createSession(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        args: Map<String, Any?>
    ): CreateSessionOutcome = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegments("api/session/new").build()
        val body = RequestBody.create(jsonMediaType, moshi.adapter<Map<String, Any?>>(mapType).toJson(args))
        val request = Request.Builder().url(url).header("Authorization", bearer(credential)).post(body).build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val serverMessage = parseMap(text)?.get("error")?.toString()?.takeIf { it.isNotBlank() }
                    return@use CreateSessionOutcome.Failure(serverMessage ?: "HTTP ${response.code}")
                }
                val root = parseMap(text)
                val session = (root?.get("session") as? Map<*, *>) ?: root
                val id = session?.let { string(it, "session_id", "id") }
                if (session == null || id == null) {
                    CreateSessionOutcome.Failure("Session response without session_id")
                } else {
                    CreateSessionOutcome.Success(
                        SessionDetail(
                            id,
                            string(session, "title"),
                            string(session, "created_at", "createdAt"),
                            string(session, "updated_at", "updatedAt"),
                            // The server resolves the new session's workspace at
                            // creation (profile default or args override) and
                            // returns it in the session payload — surface it so
                            // the caller can echo it on the first chat/start.
                            workspace = string(session, "workspace"),
                        )
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CreateSessionOutcome.Failure(error.message ?: "Network error")
        }
    }

    suspend fun searchSessions(baseUrl: String, credential: ConsumedDeviceCredential, query: String): List<SessionItem> =
        getSessions(baseUrl, credential).filter { item ->
            item.title?.contains(query, ignoreCase = true) == true || item.preview?.contains(query, ignoreCase = true) == true
        }

    suspend fun renameSession(baseUrl: String, credential: ConsumedDeviceCredential, sessionId: String, newTitle: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = baseUrl.toHttpUrl().newBuilder().addPathSegments("api/session/rename").build()
            val body = jsonBody(mapOf("session_id" to sessionId, "title" to newTitle))
            try {
                client.newCall(Request.Builder().url(url).header("Authorization", bearer(credential)).post(body).build())
                    .execute().use { it.isSuccessful }
            } catch (_: Exception) { false }
        }

    suspend fun deleteSession(baseUrl: String, credential: ConsumedDeviceCredential, sessionId: String): Boolean =
        withContext(Dispatchers.IO) {
            val url = baseUrl.toHttpUrl().newBuilder().addPathSegments("api/session/delete").build()
            val body = jsonBody(mapOf("session_id" to sessionId))
            try {
                client.newCall(Request.Builder().url(url).header("Authorization", bearer(credential)).post(body).build())
                    .execute().use { it.isSuccessful }
            } catch (_: Exception) { false }
        }

    private fun jsonBody(fields: Map<String, String>): RequestBody {
        val stringAdapter = moshi.adapter(String::class.java)
        val json = fields.entries.joinToString(",", "{", "}") { (k, v) ->
            "${stringAdapter.toJson(k)}:${stringAdapter.toJson(v)}"
        }
        return RequestBody.create(jsonMediaType, json)
    }

    private fun execute(url: String, c: ConsumedDeviceCredential): String? =
        try { client.newCall(Request.Builder().url(url).header("Authorization", bearer(c)).get().build()).execute().use { if (it.isSuccessful) it.body?.string() else null } } catch (_: Exception) { null }
    private fun parseMap(json: String): Map<String, Any?>? = try { moshi.adapter<Map<String, Any?>>(mapType).fromJson(json) } catch (_: Exception) { null }
    private fun sessionItem(o: Map<*, *>) = SessionItem(string(o, "id", "session_id").orEmpty(), string(o, "title"), string(o, "preview", "last_message", "summary"), string(o, "created_at", "createdAt"), string(o, "updated_at", "updatedAt"), (o["message_count"] as? Number)?.toInt(), activeTurn(o))

    /**
     * Active-turn metadata shared by the detail and list payloads (hermes-webui
     * `Session.compact(include_runtime=True)` + sidebar `attention` stamp). Lenient by
     * contract: absent fields mean "idle" (the detail response omits `attention`; older
     * servers omit the runtime block entirely), never a parse failure.
     */
    private fun activeTurn(o: Map<*, *>): ActiveTurnInfo {
        val attention = (o["attention"] as? Map<*, *>)?.let { a ->
            string(a, "kind")?.let { kind ->
                SessionAttention(kind, (a["count"] as? Number)?.toInt() ?: 0, string(a, "severity"))
            }
        }
        return ActiveTurnInfo(
            activeStreamId = string(o, "active_stream_id"),
            isStreaming = o["is_streaming"] == true,
            hasPendingUserMessage = o["has_pending_user_message"] == true,
            pendingStartedAtEpochSeconds = (o["pending_started_at"] as? Number)?.toDouble(),
            attention = attention,
        )
    }

    private fun message(o: Map<*, *>): SessionMessage {
        val role = o["role"]?.toString().orEmpty()
        val raw = when (val v = o["content"]) { is String -> v; is List<*> -> v.mapNotNull { (it as? Map<*, *>)?.get("text")?.toString() }.joinToString(""); else -> o["text"]?.toString().orEmpty() }
        // The workspace sentinel is client-injected transport noise (WorkspacePrefix.apply on send);
        // WebUI strips it when rendering history (`_strip_workspace_prefix`). Strip for USER content
        // ONLY: an assistant message never carries a real sentinel, and a literal "[Workspace::v1: …]"
        // quoted inside model output must survive verbatim.
        val content = if (role == "user") WorkspacePrefix.strip(raw) else raw
        return SessionMessage(string(o, "id", "message_id"), role, content, string(o, "created_at", "createdAt"), string(o, "event_id"))
    }
    private fun string(o: Map<*, *>, vararg keys: String) = keys.firstNotNullOfOrNull { o[it]?.toString()?.takeIf(String::isNotBlank) }
    private fun bearer(c: ConsumedDeviceCredential) = "Bearer ${c.accessToken}"
}
