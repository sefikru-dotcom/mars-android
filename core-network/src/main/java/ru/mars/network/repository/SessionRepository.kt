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
import ru.mars.network.export.SessionImportDocument
import ru.mars.network.http.HttpClients
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.ProviderQuota
import ru.mars.network.model.ProviderQuotaAmounts
import ru.mars.network.model.SessionAttention
import ru.mars.network.model.SessionBranchResult
import ru.mars.network.model.SessionDetail
import ru.mars.network.model.SessionItem
import ru.mars.network.model.SessionMessage
import ru.mars.network.model.SessionTitleRegenerateResult
import ru.mars.network.model.SessionTurnOpResult
import ru.mars.network.model.SessionUsage
import ru.mars.network.model.WorkerStatusInfo
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
 * Публичная ссылка диалога из `POST /api/share/create` (блок `share`).
 *
 * Сервер отдаёт готовое поле `share.url` (`api/routes.py:12788`,
 * `"url": f"/share/{token}"`) — отдельного `share.path` в ответе НЕТ, искать его
 * в контракте бессмысленно. [path] хранит именно это значение: относительный путь
 * `/share/<token>`; если сервер когда-нибудь перестанет слать `url`, транспорт
 * подставит тот же путь из токена. Абсолютный адрес собирает UI (у клиента свой
 * BASE_URL и своя схема), поэтому транспорт не склеивает URL сам.
 */
data class SessionShareLink(
    val token: String,
    val path: String,
    val title: String? = null,
    val messageCount: Int = 0,
    val createdAtEpochSeconds: Double? = null,
)

/**
 * Исход `POST /api/share/create` / `POST /api/share/revoke`.
 *
 * Обе ручки входят в device-Bearer allowlist сервера (`api/auth.py`,
 * `NATIVE_BEARER_EXACT_PATHS`), поэтому [Failure] — это настоящий отказ
 * (нет сессии, пустой диалог, сеть), а не «фича недоступна на телефоне».
 */
sealed interface SessionShareOutcome {
    data class Success(val share: SessionShareLink) : SessionShareOutcome
    data class Failure(val message: String) : SessionShareOutcome
}

/**
 * Исход `POST /api/session/import`.
 *
 * [Success] несёт строку списка, которую вернул сервер (у импортированного
 * диалога НОВЫЙ id — старый id из файла сервер не переиспользует), поэтому UI
 * кладёт в список именно её, а не догадку.
 */
sealed interface SessionImportOutcome {
    data class Success(val session: SessionItem) : SessionImportOutcome
    data class Failure(val message: String) : SessionImportOutcome
}

/**
 * Outcome of the turn-scoped operations `POST /api/session/undo` /
 * `POST /api/session/retry`.
 *
 * The server distinguishes a REFUSAL from a transport error with an unusual HTTP
 * shape: both success and "nothing to undo" answer 200, and the refusal arrives as
 * `{"error": "Nothing to undo."}` with no `ok` flag. Failure deliberately covers
 * both a real HTTP failure and that in-band error so the caller shows one message
 * path instead of mistaking a refusal for a network problem.
 */
sealed interface SessionTurnOpOutcome {
    data class Success(val result: SessionTurnOpResult) : SessionTurnOpOutcome
    /** Server in-band error, HTTP error, malformed body, or transport failure. */
    data class Failure(val message: String) : SessionTurnOpOutcome
}

/** Outcome of `POST /api/session/branch` — a fork carries its own three-field envelope. */
sealed interface SessionBranchOutcome {
    data class Success(val branch: SessionBranchResult) : SessionBranchOutcome
    data class Failure(val message: String) : SessionBranchOutcome
}

/**
 * Outcome of `POST /api/session/title/regenerate`.
 *
 * Unlike undo/retry this endpoint reports a refusal as a real HTTP error (422 when
 * no better title could be generated, 403 for a read-only import), so [Failure]
 * carries the server text.
 */
sealed interface SessionTitleRegenerateOutcome {
    data class Success(val result: SessionTitleRegenerateResult) : SessionTitleRegenerateOutcome
    data class Failure(val message: String) : SessionTitleRegenerateOutcome
}

/**
 * Outcome of `POST /api/session/toolsets`.
 *
 * Success carries the server's settled `enabled_toolsets` (the client renders what
 * the server confirmed; `null` means the override was cleared). The server rejects
 * an empty list with 400, so [Failure] also covers a shape the UI must not offer.
 */
sealed interface SessionToolsetsOutcome {
    data class Success(val enabledToolsets: List<String>?) : SessionToolsetsOutcome
    data class Failure(val message: String) : SessionToolsetsOutcome
}

/**
 * Outcome of `GET /api/provider/quota`. The endpoint always answers 200, so a
 * transport-level [Failure] means the request never landed — an unsupported
 * provider is [Success] whose payload carries `supported=false`.
 */
sealed interface ProviderQuotaOutcome {
    data class Success(val quota: ProviderQuota) : ProviderQuotaOutcome
    data class Failure(val message: String) : ProviderQuotaOutcome
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
     * Источник для офлайн-экспорта в Markdown ([ru.mars.network.export.SessionMarkdownExporter]).
     * Это НЕ публичная ссылка: шаринг-ссылка делает [createShare] через
     * `POST /api/share/create` (ручка разрешена device-токену).
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

    /**
     * Публичная ссылка диалога: `POST /api/share/create` тело `{"session_id": …}`
     * → `{"ok": true, "share": {token, url, title, message_count, created_at, …},
     * "session": {…}}`.
     *
     * Ручка идемпотентна по смыслу «поделиться»: сервер переиспользует уже
     * выданный токен и обновляет снимок диалога, поэтому повторный вызов из меню
     * не плодит ссылки. Строка сессии в ответе не нужна — снимок списка клиент
     * перечитывает сам.
     */
    suspend fun createShare(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
    ): SessionShareOutcome {
        if (sessionId.isBlank()) return SessionShareOutcome.Failure("Не указан диалог")
        return when (val wire = sessionOp(baseUrl, credential, "api/share/create", mapOf("session_id" to sessionId))) {
            is WireBody -> {
                val share = wire.body["share"] as? Map<*, *>
                val token = share?.let { string(it, "token", "share_token") }
                if (token == null) {
                    SessionShareOutcome.Failure("Сервер не вернул ссылку")
                } else {
                    SessionShareOutcome.Success(
                        SessionShareLink(
                            token = token,
                            path = string(share, "url") ?: "/share/$token",
                            title = string(share, "title"),
                            messageCount = (share["message_count"] as? Number)?.toInt() ?: 0,
                            createdAtEpochSeconds = (share["created_at"] as? Number)?.toDouble(),
                        )
                    )
                }
            }
            is WireError -> SessionShareOutcome.Failure(wire.message)
        }
    }

    /**
     * Отзыв публичной ссылки: `POST /api/share/revoke` тело `{"session_id": …}` →
     * `{"ok": true, "session": {…}}`. Ответ — та же строка сессии, что у
     * pin/archive/delete, поэтому исход один и тот же [SessionMutationOutcome].
     *
     * Отзыв у диалога без ссылки сервер называет `404 Session not found` (так
     * устроена ручка, отдельного кода «ссылки нет» нет) — UI показывает этот
     * текст и предлагает просто создать ссылку заново.
     */
    suspend fun revokeShare(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
    ): SessionMutationOutcome {
        if (sessionId.isBlank()) return SessionMutationOutcome.Failure("Не указан диалог")
        return when (val wire = sessionOp(baseUrl, credential, "api/share/revoke", mapOf("session_id" to sessionId))) {
            is WireBody -> SessionMutationOutcome.Success((wire.body["session"] as? Map<*, *>)?.let(::sessionItem))
            is WireError -> SessionMutationOutcome.Failure(wire.message)
        }
    }

    /**
     * Импорт диалога из JSON-экспорта: `POST /api/session/import` телом самого
     * документа → `{"ok": true, "session": {…}}`.
     *
     * Документ готовит [ru.mars.network.export.SessionImportDocument]: он же
     * отсеивает файлы без массива `messages` (сервер ответил бы на них 400), так
     * что отказ по форме файла приходит без лишнего запроса.
     */
    suspend fun importSession(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        rawJson: String,
    ): SessionImportOutcome = when (val document = SessionImportDocument.prepare(rawJson)) {
        is SessionImportDocument.Invalid -> SessionImportOutcome.Failure(document.reason)
        is SessionImportDocument.Ready -> when (
            val wire = sessionOp(baseUrl, credential, "api/session/import", document.fields)
        ) {
            is WireBody -> {
                val session = (wire.body["session"] as? Map<*, *>)?.let(::sessionItem)
                if (session == null) {
                    SessionImportOutcome.Failure("Сервер не вернул импортированный диалог")
                } else {
                    SessionImportOutcome.Success(session)
                }
            }
            is WireError -> SessionImportOutcome.Failure(wire.message)
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
        // Server draft read path: the field lives on the DETAIL payload because
        // `GET /api/session/draft` is not routed on the server (POST only).
        composerDraft = composerDraft(s["composer_draft"]),
        // Per-session toolset override. Absent key and an explicit `null` both mean
        // "no override" — the composer pill then shows the server default rather
        // than a phantom empty selection.
        enabledToolsets = toolsetNames(s["enabled_toolsets"]),
        // Cold context-window snapshot (#ring-seed): `GET /api/session` puts
        // `context_length` / `threshold_tokens` / `last_prompt_tokens` on the
        // session object itself (api/routes.py). Parsed through the SAME mapper
        // the `metering` frames use, so the ring cannot disagree with the stream:
        // unknown window stays null, never a fake percentage.
        contextUsage = StreamEventMapper.contextUsageOf(
            s.entries.associate { (key, value) -> key.toString() to value },
        ),
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

    /**
     * Откат последнего обмена: `POST /api/session/undo` body `{"session_id"}`.
     *
     * Сервер снимает последний пользовательский ход вместе с ответом ассистента и
     * отвечает либо `200 {"ok": true, "removed_count": N, "removed_preview": "…"}`,
     * либо — если откатывать нечего — `200 {"error": "Nothing to undo."}` БЕЗ флага
     * `ok`. Обе формы разбирает [sessionOp] в [SessionTurnOpOutcome]: отказ сервера
     * идёт как [SessionTurnOpOutcome.Failure] с его собственным текстом, поэтому
     * вызывающий показывает одно сообщение и не путает отказ с сетевой ошибкой.
     */
    suspend fun undoSession(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
    ): SessionTurnOpOutcome = when (val wire = sessionOp(baseUrl, credential, "api/session/undo", mapOf("session_id" to sessionId))) {
        is WireBody -> SessionTurnOpOutcome.Success(turnOpResult(wire.body))
        is WireError -> SessionTurnOpOutcome.Failure(wire.message)
    }

    /**
     * Повтор последнего хода: `POST /api/session/retry` body `{"session_id"}`.
     *
     * Сервер отрезает транскрипт до последнего пользовательского сообщения и
     * возвращает `{"ok": true, "last_user_text": "…", "removed_count": N}` — текст
     * ожидается обратно в композер ([SessionTurnOpResult.lastUserText]), а сам
     * перезапуск делает вызывающий обычным `send`. Отказ («No previous message to
     * retry.») приходит так же 200-м с полем `error`.
     */
    suspend fun retrySession(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
    ): SessionTurnOpOutcome = when (val wire = sessionOp(baseUrl, credential, "api/session/retry", mapOf("session_id" to sessionId))) {
        is WireBody -> SessionTurnOpOutcome.Success(turnOpResult(wire.body))
        is WireError -> SessionTurnOpOutcome.Failure(wire.message)
    }

    /**
     * Ветка от текущего диалога: `POST /api/session/branch` body
     * `{"session_id", "keep_count"?, "title"?}`.
     *
     * [keepCount] — сколько сообщений скопировать: `0` даёт пустую ветку, `null`
     * (поле не отправляется вовсе) копирует всю историю. [title] `null` отдаёт
     * серверу вывод имени: `<исходное название> (fork)`. Ответ —
     * `200 {"session_id", "title", "parent_session_id"}`; это НОВАЯ сессия, её id
     * вызывающий обязан открыть сам.
     */
    suspend fun branchSession(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
        keepCount: Int? = null,
        title: String? = null,
    ): SessionBranchOutcome {
        val fields = mutableMapOf<String, Any?>("session_id" to sessionId)
        // Optional fields are OMITTED (not sent as null): the server reads "field
        // absent" as "copy the whole history" / "derive the title".
        if (keepCount != null) fields["keep_count"] = keepCount
        title?.trim()?.takeIf { it.isNotEmpty() }?.let { fields["title"] = it }
        return when (val wire = sessionOp(baseUrl, credential, "api/session/branch", fields)) {
            is WireBody -> {
                val newId = string(wire.body, "session_id", "id")
                if (newId == null) SessionBranchOutcome.Failure("Ответ сервера без session_id ветки")
                else SessionBranchOutcome.Success(
                    SessionBranchResult(newId, string(wire.body, "title"), string(wire.body, "parent_session_id"))
                )
            }
            is WireError -> SessionBranchOutcome.Failure(wire.message)
        }
    }

    /**
     * Копия диалога: `POST /api/session/duplicate` body `{"session_id"}`.
     *
     * Ответ — `200 {"session": {…}}` с полным дубликатом. Сервер отвечает 404 для
     * неизвестной сессии и 400 для субагентской, поэтому обе причины приходят как
     * [SessionMutationOutcome.Failure] с его текстом.
     */
    suspend fun duplicateSession(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
    ): SessionMutationOutcome = sessionRowOp(baseUrl, credential, "api/session/duplicate", mapOf("session_id" to sessionId))

    /**
     * Обрезка истории до [keepCount] сообщений: `POST /api/session/truncate` body
     * `{"session_id", "keep_count"}`. ОПЕРАЦИЯ РАЗРУШИТЕЛЬНАЯ и необратимая —
     * вызывающий обязан получить подтверждение пользователя до вызова.
     *
     * [keepCount] обязателен по контракту: без него сервер отвечает 400, поэтому
     * параметр не имеет значения по умолчанию и принимает только неотрицательное
     * число (отрицательное отсекается здесь, а не отправляется впустую).
     */
    suspend fun truncateSession(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
        keepCount: Int,
    ): SessionMutationOutcome {
        if (keepCount < 0) return SessionMutationOutcome.Failure("keep_count не может быть отрицательным")
        return sessionRowOp(baseUrl, credential, "api/session/truncate", mapOf("session_id" to sessionId, "keep_count" to keepCount))
    }

    /**
     * Полная очистка истории сессии: `POST /api/session/clear` body `{"session_id"}`.
     * ОПЕРАЦИЯ РАЗРУШИТЕЛЬНАЯ: сервер обнуляет транскрипт, попутно снимает ручное
     * название (сессия заново получит автоимя) — подтверждение в UI обязательно.
     */
    suspend fun clearSession(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
    ): SessionMutationOutcome = sessionRowOp(baseUrl, credential, "api/session/clear", mapOf("session_id" to sessionId))

    /**
     * Перегенерация названия диалога: `POST /api/session/title/regenerate` body
     * `{"session_id", "prefer_latest"?}`.
     *
     * [preferLatest] подсказывает серверу брать последние сообщения вместо первых.
     * Ответ — `200 {"title": "…", "status": "…", "raw_preview": "…"}`; отказ здесь
     * НАСТОЯЩАЯ HTTP-ошибка (422 «не удалось придумать лучше», 403 для read-only
     * импорта), поэтому текст ошибки берётся из [SessionTitleRegenerateOutcome.Failure].
     */
    suspend fun regenerateSessionTitle(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
        preferLatest: Boolean = false,
    ): SessionTitleRegenerateOutcome {
        val fields = mutableMapOf<String, Any?>("session_id" to sessionId)
        if (preferLatest) fields["prefer_latest"] = true
        return when (val wire = sessionOp(baseUrl, credential, "api/session/title/regenerate", fields)) {
            is WireBody -> {
                val title = string(wire.body, "title")
                if (title == null) SessionTitleRegenerateOutcome.Failure("Ответ сервера без названия")
                else SessionTitleRegenerateOutcome.Success(
                    SessionTitleRegenerateResult(title, string(wire.body, "status"), string(wire.body, "raw_preview"))
                )
            }
            is WireError -> SessionTitleRegenerateOutcome.Failure(wire.message)
        }
    }

    /**
     * Перенос диалога в проект: `POST /api/session/move` body
     * `{"session_id", "project_id"}`; `project_id = null` вынимает сессию из проекта.
     *
     * Сервер отвечает `200 {"ok": true, "session": {…}}`, а 404 — если проекта нет
     * или он принадлежит другому профилю, 503 — если сессия сейчас стримит. Обе
     * причины доходят как [SessionMutationOutcome.Failure] и показываются как есть.
     */
    suspend fun moveSession(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
        projectId: String?,
    ): SessionMutationOutcome = sessionRowOp(
        baseUrl,
        credential,
        "api/session/move",
        mapOf("session_id" to sessionId, "project_id" to projectId?.trim()?.takeIf { it.isNotEmpty() }),
    )

    /**
     * Набор инструментов сессии: `POST /api/session/toolsets` body
     * `{"session_id", "toolsets"}`.
     *
     * [toolsets] `null` СБРАСЫВАЕТ переопределение (сессия возвращается к общему
     * набору), непустой список — ограничивает сессию им. Пустой список сервер
     * отвергает 400, поэтому такой вызов не отправляется: вызывающий получает
     * [SessionToolsetsOutcome.Failure] с понятным текстом, не сетевым отказом.
     * Ответ — `{"ok": true, "enabled_toolsets": [...] | null}`, и берётся именно
     * серверное значение, а не отправленное клиентом.
     */
    suspend fun updateSessionToolsets(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
        toolsets: List<String>?,
    ): SessionToolsetsOutcome {
        val cleaned = toolsets?.map { it.trim() }?.filter { it.isNotEmpty() }
        if (cleaned != null && cleaned.isEmpty()) {
            return SessionToolsetsOutcome.Failure("Нужен хотя бы один инструмент или сброс")
        }
        // `null` is a MEANINGFUL value here (reset), so the key is always present —
        // unlike branch/move where an absent field is the neutral form.
        val fields = mapOf<String, Any?>("session_id" to sessionId, "toolsets" to cleaned?.sorted())
        return when (val wire = sessionOp(baseUrl, credential, "api/session/toolsets", fields)) {
            is WireBody -> SessionToolsetsOutcome.Success(toolsetNames(wire.body["enabled_toolsets"]))
            is WireError -> SessionToolsetsOutcome.Failure(wire.message)
        }
    }

    /**
     * Квота активного провайдера: `GET /api/provider/quota`.
     *
     * Путь разрешён device-Bearer на GET и ВСЕГДА отвечает 200, поэтому
     * «квоты нет» — это не ошибка запроса, а поле ответа: разбор отдаёт
     * [ProviderQuota] как есть, включая `supported=false`/`status=unsupported`
     * (живой случай для `custom:plusvibe-api`). [ProviderQuotaOutcome.Failure]
     * означает только то, что запрос не дошёл или тело не разобралось.
     */
    suspend fun getProviderQuota(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
    ): ProviderQuotaOutcome = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegments("api/provider/quota").build().toString()
        try {
            client.newCall(Request.Builder().url(url).header("Authorization", bearer(credential)).get().build())
                .execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val serverMessage = parseMap(text)?.get("error")?.toString()?.takeIf { it.isNotBlank() }
                        return@use ProviderQuotaOutcome.Failure(serverMessage ?: "HTTP ${response.code}")
                    }
                    val root = parseMap(text) ?: return@use ProviderQuotaOutcome.Failure("Пустой ответ квоты")
                    ProviderQuotaOutcome.Success(providerQuota(root))
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ProviderQuotaOutcome.Failure(error.message ?: "Network error")
        }
    }

    /** Разобранное тело сессионной ручки: либо конверт с данными, либо серверная ошибка. */
    private sealed interface Wire {
        val message: String
    }

    private data class WireBody(val body: Map<String, Any?>) : Wire {
        override val message: String get() = ""
    }

    private data class WireError(override val message: String) : Wire

    /**
     * Общий путь всех сессионных POST-ручек: одно место отправки, один разбор
     * конверта и ОДНА трактовка отказа.
     *
     * Три отказовых формы сводятся к [WireError]: неразобранное тело, не-2xx
     * (`{"error": "Session not found"}`, 400 «Subagent sessions are view-only…»,
     * 503 «Session is busy» — текст сервера важнее кода) и ВНУТРИКОНВЕРТНЫЙ
     * `{"error": …}` при 200-м коде (у undo/retry отказ так и приходит, без `ok`).
     * Именно поэтому вызывающие не смотрят на HTTP-код сами.
     */
    private suspend fun sessionOp(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        path: String,
        fields: Map<String, Any?>,
    ): Wire = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegments(path).build()
        try {
            client.newCall(Request.Builder().url(url).header("Authorization", bearer(credential)).post(jsonBodyAny(fields)).build())
                .execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val root = parseMap(text)
                    val inBand = root?.get("error")?.toString()?.takeIf { it.isNotBlank() }
                    if (inBand != null) return@use WireError(inBand)
                    if (!response.isSuccessful) return@use WireError("HTTP ${response.code}")
                    if (root == null) return@use WireError("Пустой ответ сервера")
                    WireBody(root)
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            WireError(error.message ?: "Network error")
        }
    }

    /** Сессионная ручка, отвечающая строкой сессии в конверте (`truncate`/`clear`/`move`/`duplicate`). */
    private suspend fun sessionRowOp(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        path: String,
        fields: Map<String, Any?>,
    ): SessionMutationOutcome = when (val wire = sessionOp(baseUrl, credential, path, fields)) {
        is WireBody -> SessionMutationOutcome.Success((wire.body["session"] as? Map<*, *>)?.let(::sessionItem))
        is WireError -> SessionMutationOutcome.Failure(wire.message)
    }

    /** Общий разбор ответа undo/retry: счётчики плюс текст, который сервер отдал клиенту. */
    private fun turnOpResult(body: Map<String, Any?>): SessionTurnOpResult = SessionTurnOpResult(
        ok = body["ok"] != false,
        removedCount = (body["removed_count"] as? Number)?.toInt() ?: 0,
        lastUserText = body["last_user_text"]?.toString()?.takeIf { it.isNotBlank() },
        removedPreview = body["removed_preview"]?.toString()?.takeIf { it.isNotBlank() },
        error = body["error"]?.toString()?.takeIf { it.isNotBlank() },
    )

    /**
     * Имена тулсетов из `enabled_toolsets`. Пустой список приводится к `null`:
     * сервер трактует «нет переопределения» как `null`, и отдельного состояния
     * «включено ноль инструментов» в контракте не существует.
     */
    private fun toolsetNames(raw: Any?): List<String>? =
        (raw as? List<*>)?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }?.takeIf { it.isNotEmpty() }

    /** Разбор `GET /api/provider/quota` — плоский объект, поля читаются как есть. */
    private fun providerQuota(o: Map<*, *>): ProviderQuota = ProviderQuota(
        ok = o["ok"] == true,
        provider = string(o, "provider"),
        displayName = string(o, "display_name"),
        supported = o["supported"] == true,
        status = string(o, "status"),
        label = string(o, "label"),
        quota = (o["quota"] as? Map<*, *>)?.let { q ->
            ProviderQuotaAmounts(
                limitRemaining = number(q["limit_remaining"]),
                usage = number(q["usage"]),
                limit = number(q["limit"]),
            )
        },
        message = string(o, "message"),
    )

    private fun number(raw: Any?): Double? = (raw as? Number)?.toDouble()


    /**
     * Token usage and estimated cost of one session
     * (`GET /api/session/usage?session_id=<id>` → `{"ok": true, "usage": {…}}`).
     *
     * Returns `null` when the session is unknown (the server answers 404
     * `{"error": "Session not found"}`), the transport failed, or the payload
     * carries no usage block — the UI treats all three as "usage unavailable"
     * and hides the counter instead of showing zeros. A session that simply
     * never ran a turn answers zeros and IS returned (see [SessionUsage]).
     *
     * Parsing lives in [SessionUsageParser] (single point, alias-tolerant);
     * this method only does the request. The metering-based context pill keeps
     * its own [ContextUsage] source — the two deliberately never share state.
     */
    suspend fun getSessionUsage(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
    ): SessionUsage? = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) return@withContext null
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/session/usage")
            .addQueryParameter("session_id", sessionId)
            .build()
            .toString()
        SessionUsageParser.parse(parseMap(execute(url, credential) ?: return@withContext null))
    }

    /**
     * Persists the composer draft of a session server-side
     * (`POST /api/session/draft` body `{"session_id","text"}` →
     * `200 {"ok": true}`); an empty [text] clears the server copy.
     *
     * Returns false when the session id is blank, the request failed, or the
     * server refused it — the caller stays silent on failure (the local draft
     * remains the source of truth offline) and never surfaces a network error
     * for a background autosave. Debouncing/cadence is the caller's job
     * (`ServerDraftSyncPolicy`), not this layer's.
     */
    suspend fun saveComposerDraft(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        sessionId: String,
        text: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) return@withContext false
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegments("api/session/draft").build()
        val body = jsonBody(mapOf("session_id" to sessionId, "text" to text))
        try {
            client.newCall(Request.Builder().url(url).header("Authorization", bearer(credential)).post(body).build())
                .execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    // `{"ok": true}` is the committed form; a 2xx without the flag is
                    // still accepted (the draft block is likewise returned on success).
                    parseMap(response.body?.string().orEmpty())?.get("ok") != false
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun jsonBody(fields: Map<String, String>): RequestBody {
        val stringAdapter = moshi.adapter(String::class.java)
        val json = fields.entries.joinToString(",", "{", "}") { (k, v) ->
            "${stringAdapter.toJson(k)}:${stringAdapter.toJson(v)}"
        }
        return RequestBody.create(jsonMediaType, json)
    }

    /**
     * Тело запроса с НЕстроковыми значениями (`Boolean`, `Int`, вложенные списки) и
     * с осмысленным JSON `null`. Строится тем же адаптером, что и `createSession`,
     * поэтому `null` доезжает до сервера именно как `null`, а не пропадает: для
     * `toolsets`/`project_id` это разные команды («сбросить» против «не трогать»),
     * и потеря поля переключила бы смысл запроса.
     */
    private fun jsonBodyAny(fields: Map<String, Any?>): RequestBody =
        RequestBody.create(jsonMediaType, moshi.adapter<Map<String, Any?>>(mapType).toJson(fields))

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
            workerStatus = workerStatus(o["worker_status"]),
        )
    }

    /**
     * `worker_status` строки списка (hermes-webui api/worker_status.py
     * `attach_worker_status`): объект есть — разбираем поля поимённо, нет объекта
     * или он не Map — null. Lenient by contract: обычные сессии блока не имеют,
     * старые серверы не отдают его вовсе, а частичный объект (например только
     * `status`) не должен ронять разбор строки.
     */
    private fun workerStatus(raw: Any?): WorkerStatusInfo? {
        val o = raw as? Map<*, *> ?: return null
        return WorkerStatusInfo(
            goal = string(o, "goal"),
            status = string(o, "status"),
            durationSeconds = number(o["duration_seconds"]),
            startedAtEpochSeconds = number(o["started_at"]),
            lastAction = string(o, "last_action"),
            failureReason = string(o, "failure_reason"),
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

    /**
     * Server composer draft of a session detail. Two wire forms are live:
     * an object `{"text": "...", "files": [...]}` (current server) and a plain
     * string (older/leaner payloads). A blank/missing text means "no draft",
     * never an empty draft the UI would have to special-case.
     */
    private fun composerDraft(raw: Any?): String? = when (raw) {
        is String -> raw.takeIf { it.isNotBlank() }
        is Map<*, *> -> (raw["text"] as? String)?.takeIf { it.isNotBlank() }
        else -> null
    }

    private fun bearer(c: ConsumedDeviceCredential) = "Bearer ${c.accessToken}"
}
