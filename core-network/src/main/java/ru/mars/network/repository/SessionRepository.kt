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
import ru.mars.network.http.HttpClients
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
 * Outcome of a session mutation that returns the settled row
 * (`POST /api/session/pin` и `/api/session/archive` оба отвечают
 * `{"ok": true, "session": {...}}`).
 *
 * The payload is surfaced instead of a bare Boolean because the mutation result IS
 * server state: after pin the row may carry a different quota-driven flag set, and
 * the UI must render what the server confirmed, not what it optimistically assumed.
 */
sealed interface SessionMutationOutcome {
    /** 200 `{"ok": true, "session": {...}}`; [session] is null when the server sent no row. */
    data class Success(val session: SessionItem?) : SessionMutationOutcome
    /** HTTP error (with the server `error` message when present), malformed body, or transport failure. */
    data class Failure(val message: String) : SessionMutationOutcome
}

/**
 * Outcome of `GET /api/session/export?session_id=` — a full session document
 * (session_id, title, workspace, created_workspace, model, model_provider, messages…)
 * parsed into the same [SessionDetail] the detail endpoint produces, so the Markdown
 * exporter consumes either source identically.
 */
sealed interface SessionExportOutcome {
    data class Success(val detail: SessionDetail) : SessionExportOutcome
    data class Failure(val message: String) : SessionExportOutcome
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
class SessionRepository(private val client: OkHttpClient = HttpClients.rest) : SessionCreator {
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
            detail(s, sessionId)
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
                            model = string(session, "model"),
                            modelProvider = string(session, "model_provider", "provider"),
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

    /**
     * Глобальный серверный поиск: `GET /api/sessions/search?q=<query>`.
     *
     * Контракт (проверен живым запросом, device Bearer принимается):
     * `200 {"sessions": [...], "query": "…", "count": N, "all_profiles": bool,
     * "active_profile": "…"}` — форма строки сессии та же, что у `GET /api/sessions`,
     * поэтому разбор идёт тем же [sessionItem].
     *
     * Отличие от [searchSessions]: тот фильтрует УЖЕ загруженный локальный список
     * (работает офлайн, видит только открытые строки); этот спрашивает сервер и
     * находит диалоги, которых в локальном списке нет вовсе. Оба метода
     * сосуществуют: локальный фильтр остаётся мгновенным отзывом на ввод, а
     * серверный подмешивает результаты по всей базе.
     *
     * [allProfiles] добавляет `&all_profiles=1` — поиск по всем профилям, не только
     * по активному. Пустой/пробельный запрос серверу не отправляется: возвращаем
     * пустой список (поиск без запроса не имеет смысла и не должен тянуть весь список).
     * Сбой сети/HTTP/разбора — пустой список, никогда не исключение.
     */
    suspend fun searchSessionsGlobal(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        query: String,
        allProfiles: Boolean = false,
    ): List<SessionItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/sessions/search")
            .addQueryParameter("q", q)
            .apply { if (allProfiles) addQueryParameter("all_profiles", "1") }
            .build()
        val root = parseMap(execute(url.toString(), credential) ?: return@withContext emptyList())
        val items = (root?.get("sessions") ?: root?.get("items")) as? List<*> ?: return@withContext emptyList()
        items.mapNotNull { (it as? Map<*, *>)?.let(::sessionItem) }
    }

    /**
     * Закрепление диалога: `POST /api/session/pin` body
     * `{"session_id": "...", "pinned": true|false}` → `200 {"ok": true, "session": {...}}`.
     *
     * Ответ разбирается в [SessionMutationOutcome.Success] с серверной строкой сессии:
     * флаг «закреплён» — серверное состояние (у сервера есть квота закреплённых),
     * поэтому UI обязан показывать подтверждённое значение, а не своё предположение.
     */
    suspend fun setSessionPinned(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
        pinned: Boolean,
    ): SessionMutationOutcome = mutateSession(baseUrl, credential, "api/session/pin", sessionId, "pinned", pinned)

    /**
     * Архивация диалога: `POST /api/session/archive` body
     * `{"session_id": "...", "archived": true|false}` → `200 {"ok": true, "session": {...}}`
     * (симметрично [setSessionPinned]).
     *
     * Архивные строки сервер держит в отдельном ведре `GET /api/sessions`, поэтому
     * строка исчезает из активного списка только после подтверждения сервером.
     */
    suspend fun setSessionArchived(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
        archived: Boolean,
    ): SessionMutationOutcome = mutateSession(baseUrl, credential, "api/session/archive", sessionId, "archived", archived)

    /**
     * Полный JSON сессии: `GET /api/session/export?session_id=<id>` → объект сессии
     * (`session_id`, `title`, `workspace`, `created_workspace`, `model`,
     * `model_provider`, `messages`…). Форма плоская — сам объект сессии, без
     * обёртки `{"session": …}` (в отличие от [getSessionDetail]), поэтому корень
     * берётся как есть, с фолбэком на вложенный `session` для устойчивости.
     *
     * Источник для офлайн-экспорта в Markdown ([ru.mars.network.export.SessionMarkdownExporter]);
     * серверные ручки `/api/share/create` и `/api/share/revoke` на device-токене отдают 401
     * (серверный блокер), поэтому шаринг делается локально из этого документа.
     */
    suspend fun exportSession(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
    ): SessionExportOutcome = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/session/export")
            .addQueryParameter("session_id", sessionId)
            .build()
        try {
            client.newCall(Request.Builder().url(url).header("Authorization", bearer(credential)).get().build())
                .execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val serverMessage = parseMap(text)?.get("error")?.toString()?.takeIf { it.isNotBlank() }
                        return@use SessionExportOutcome.Failure(serverMessage ?: "HTTP ${response.code}")
                    }
                    val root = parseMap(text)
                    val s = (root?.get("session") as? Map<*, *>) ?: root
                    if (s == null) return@use SessionExportOutcome.Failure("Empty session document")
                    SessionExportOutcome.Success(detail(s, sessionId))
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SessionExportOutcome.Failure(error.message ?: "Network error")
        }
    }

    /** Общий путь pin/archive: один и тот же конверт ответа и разбор строки сессии. */
    private suspend fun mutateSession(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        path: String,
        sessionId: String,
        field: String,
        value: Boolean,
    ): SessionMutationOutcome = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegments(path).build()
        val body = RequestBody.create(
            jsonMediaType,
            moshi.adapter<Map<String, Any?>>(mapType).toJson(mapOf("session_id" to sessionId, field to value)),
        )
        try {
            client.newCall(Request.Builder().url(url).header("Authorization", bearer(credential)).post(body).build())
                .execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val serverMessage = parseMap(text)?.get("error")?.toString()?.takeIf { it.isNotBlank() }
                        return@use SessionMutationOutcome.Failure(serverMessage ?: "HTTP ${response.code}")
                    }
                    val root = parseMap(text)
                    val row = root?.get("session") as? Map<*, *>
                    SessionMutationOutcome.Success(row?.let(::sessionItem))
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SessionMutationOutcome.Failure(error.message ?: "Network error")
        }
    }

    /**
     * Объект сессии wire → [SessionDetail]. Единая точка разбора для
     * `GET /api/session` и `GET /api/session/export` (у них одна форма сессии);
     * [fallbackId] подставляется, когда сервер не назвал id.
     */
    private fun detail(s: Map<*, *>, fallbackId: String): SessionDetail = SessionDetail(
        string(s, "id") ?: string(s, "session_id") ?: fallbackId,
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
        model = string(s, "model"),
        modelProvider = string(s, "model_provider", "provider"),
    )

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
    /**
     * List row → [SessionItem]. Nullable by contract: a row without a non-blank
     * `id`/`session_id` is dropped (getSessions' mapNotNull) instead of surfacing a
     * phantom `SessionItem(id = "")` in the drawer — such an item can't be opened,
     * renamed or deleted. [string] already rejects blank values, so a whitespace-only
     * id is dropped too. Legacy key order (`id`, then `session_id`) and the
     * source/read-only provenance parse are unchanged.
     */
    private fun sessionItem(o: Map<*, *>): SessionItem? {
        val id = string(o, "id", "session_id") ?: return null
        return SessionItem(
            id = id,
            title = string(o, "title"),
            preview = string(o, "preview", "last_message", "summary"),
            createdAt = string(o, "created_at", "createdAt"),
            updatedAt = string(o, "updated_at", "updatedAt"),
            messageCount = (o["message_count"] as? Number)?.toInt(),
            activeTurn = activeTurn(o),
            sessionSource = string(o, "session_source"),
            sourceTag = string(o, "source_tag"),
            rawSource = string(o, "raw_source"),
            sourceLabel = string(o, "source_label"),
            readOnly = o["read_only"] as? Boolean ?: false,
            parentSessionId = string(o, "parent_session_id"),
            model = string(o, "model"),
            modelProvider = string(o, "model_provider", "provider"),
            pinned = o["pinned"] as? Boolean ?: false,
            archived = o["archived"] as? Boolean ?: false,
        )
    }

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
        return SessionMessage(
            id = string(o, "id", "message_id"),
            role = role,
            content = content,
            createdAt = createdAt(o),
            eventId = string(o, "event_id"),
            toolName = string(o, "tool_name", "name"),
            toolCallId = string(o, "tool_call_id"),
        )
    }

    /**
     * History timestamp of one wire row. `GET /api/session` messages carry the
     * persisted `timestamp` (epoch SECONDS — state.db REAL or sidecar string like
     * "1791921277.8392885"), not `created_at`; without this alias every history
     * bubble loses its clock label and day separators. Moshi decodes JSON numbers
     * as Double, so the plain-string form is restored via BigDecimal to avoid the
     * scientific notation ("1.791921277E9") that no display-side parser accepts.
     */
    private fun createdAt(o: Map<*, *>): String? {
        string(o, "created_at", "createdAt")?.let { return it }
        return when (val value = o["timestamp"]) {
            is String -> value.trim().takeIf(String::isNotBlank)
            is Number -> java.math.BigDecimal.valueOf(value.toDouble()).toPlainString()
            else -> null
        }
    }

    private fun string(o: Map<*, *>, vararg keys: String) = keys.firstNotNullOfOrNull { o[it]?.toString()?.takeIf(String::isNotBlank) }
    private fun bearer(c: ConsumedDeviceCredential) = "Bearer ${c.accessToken}"
}
