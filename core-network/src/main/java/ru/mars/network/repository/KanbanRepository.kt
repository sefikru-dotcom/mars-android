package ru.mars.network.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.mars.network.http.HttpClients
import ru.mars.network.model.AssigneeStat
import ru.mars.network.model.BoardSummary
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.KanbanAssigneeStats
import ru.mars.network.model.KanbanAssignees
import ru.mars.network.model.KanbanBoard
import ru.mars.network.model.KanbanBoardFilters
import ru.mars.network.model.KanbanBoards
import ru.mars.network.model.KanbanColumn
import ru.mars.network.model.KanbanComment
import ru.mars.network.model.KanbanConfig
import ru.mars.network.model.KanbanDispatchOutcomePayload
import ru.mars.network.model.KanbanDispatchSpawn
import ru.mars.network.model.KanbanRun
import ru.mars.network.model.KanbanStats
import ru.mars.network.model.KanbanTask
import ru.mars.network.model.KanbanTaskDetail
import ru.mars.network.model.KanbanTaskEvent
import ru.mars.network.model.KanbanTaskLinks
import java.io.IOException
import java.io.InputStreamReader

/** Outcome of `GET /api/kanban/board`. */
sealed interface KanbanBoardResult {
    /** 2xx with a parseable board envelope (INCLUDING the short `changed:false` form). */
    data class Board(val board: KanbanBoard) : KanbanBoardResult

    /** 404 — board slug does not exist (`LookupError` on the bridge). */
    data class NotFound(val message: String) : KanbanBoardResult

    /** HTTP error (mapped `error` message when present) or transport failure. */
    data class Failed(val message: String) : KanbanBoardResult
}

/** Outcome of `GET /api/kanban/tasks/{id}`. */
sealed interface KanbanTaskDetailResult {
    data class Detail(val detail: KanbanTaskDetail) : KanbanTaskDetailResult

    /** 404 — `task not found` is a VALID settled state, distinct from a transport failure. */
    data class NotFound(val message: String) : KanbanTaskDetailResult

    data class Failed(val message: String) : KanbanTaskDetailResult
}

/** Outcome of `GET /api/kanban/stats`. */
sealed interface KanbanStatsResult {
    data class Stats(val stats: KanbanStats) : KanbanStatsResult
    data class Failed(val message: String) : KanbanStatsResult
}

/**
 * Outcome of `GET /api/kanban/config`. No NotFound case: an older bridge answers the
 * unknown endpoint with the shared 404 `{"error": …}` envelope, and for the screen a
 * missing config is just [Failed] — best-effort, board falls back to server order.
 */
sealed interface KanbanConfigResult {
    data class Config(val config: KanbanConfig) : KanbanConfigResult
    data class Failed(val message: String) : KanbanConfigResult
}

/** Outcome of `GET /api/kanban/boards`. */
sealed interface KanbanBoardsResult {
    data class Boards(val boards: KanbanBoards) : KanbanBoardsResult
    data class Failed(val message: String) : KanbanBoardsResult
}

/** Outcome of `GET /api/kanban/assignees`. */
sealed interface KanbanAssigneesResult {
    data class Assignees(val assignees: KanbanAssignees) : KanbanAssigneesResult
    data class Failed(val message: String) : KanbanAssigneesResult
}

/** Outcome of `POST /api/kanban/dispatch`. */
sealed interface KanbanDispatchResult {
    data class Dispatched(val outcome: KanbanDispatchOutcomePayload) : KanbanDispatchResult
    data class Failed(val message: String) : KanbanDispatchResult
}

/**
 * One decoded frame of the kanban event stream, as delivered by [KanbanRepository.streamEvents].
 * The raw SSE vocabulary is kept (`hello` / `events`) instead of being folded into a single
 * "event" type: the first frame tells the client which board the server bound it to and where
 * the cursor starts, and the two carry different payloads.
 */
sealed interface KanbanStreamFrame {
    /** Opening frame: the stream is live, bound to [board], cursor starts at [cursor]. */
    data class Hello(val cursor: Long, val board: String?) : KanbanStreamFrame

    /** Event batch: [events] to apply, [cursor] to send back as `Last-Event-ID`. */
    data class Events(val events: List<KanbanTaskEvent>, val cursor: Long) : KanbanStreamFrame

    /** A frame whose shape is not part of the contract; ignored by callers. */
    data class Unknown(val name: String?) : KanbanStreamFrame
}

/**
 * Kanban bridge of the WebUI backend (contract verified read-only against
 * `/opt/hermes-webui/api/kanban_bridge.py`; errors are `{"error": "<msg>"}` with
 * 400 ValueError / 404 LookupError / 409 RuntimeError / 503 ImportError per the
 * bridge's `handle_kanban_get`/`handle_kanban_post` except-ladder):
 *
 * - `GET  /api/kanban/board?board=&tenant=&assignee=&include_archived=&only_mine=&since=`
 * - `GET  /api/kanban/tasks/{id}?board=`
 * - `GET  /api/kanban/stats?board=`
 * - `GET  /api/kanban/config?board=`
 * - `POST /api/kanban/dispatch?board=&dry_run=&max=`
 *
 * SAFETY of the mutating surface: [dispatch] is the ONLY write here and it never
 * mutates task content — it asks the server's dispatcher for one tick. All gating
 * (claim locks, failure breaker, per-profile caps, single-writer tick lock) lives
 * server-side; `dryRun = true` performs a no-op scan and is the safe probe. Board
 * creation/patch, task create/patch, comments and links are deliberately NOT
 * exposed: a phone client mutating cards needs product decisions this layer must
 * not pre-empt.
 */
class KanbanRepository(private val client: OkHttpClient = HttpClients.rest) {

    private val jsonMediaType = "application/json".toMediaType()

    /**
     * `GET /api/kanban/board`. [since] is the client's `latest_event_id` cursor: when
     * the server answers `changed:false` the cached board is still current and
     * [KanbanBoard.columns] is legitimately empty. Boolean filters travel as `1`
     * (the bridge's `_bool_query` also accepts true/yes/on).
     */
    suspend fun getBoard(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        board: String? = null,
        tenant: String? = null,
        assignee: String? = null,
        includeArchived: Boolean = false,
        onlyMine: Boolean = false,
        since: Long? = null,
    ): KanbanBoardResult = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/kanban/board")
            .boardParam(board)
            .queryParam("tenant", tenant)
            .queryParam("assignee", assignee)
            .apply { if (includeArchived) addQueryParameter("include_archived", "1") }
            .apply { if (onlyMine) addQueryParameter("only_mine", "1") }
            .apply { since?.let { addQueryParameter("since", it.toString()) } }
            .build()
        execute(
            request = get(url, credential),
            networkFailure = { KanbanBoardResult.Failed(it) },
        ) { code, root ->
            when {
                root == null -> KanbanBoardResult.Failed(unparseable(code))
                code == 404 -> KanbanBoardResult.NotFound(errorMessage(root, code))
                code in 200..299 -> KanbanBoardResult.Board(parseBoard(root))
                else -> KanbanBoardResult.Failed(errorMessage(root, code))
            }
        }
    }

    /** `GET /api/kanban/tasks/{id}` — full card detail with comments/events/runs/links. */
    suspend fun getTaskDetail(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        taskId: String,
        board: String? = null,
    ): KanbanTaskDetailResult = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/kanban/tasks")
            // addPathSegment percent-encodes; task ids are URL-safe slugs but stay literal.
            .addPathSegment(taskId)
            .boardParam(board)
            .build()
        execute(
            request = get(url, credential),
            networkFailure = { KanbanTaskDetailResult.Failed(it) },
        ) { code, root ->
            val taskMap = TolerantJson.mapField(root, "task")
            when {
                root == null -> KanbanTaskDetailResult.Failed(unparseable(code))
                code == 404 -> KanbanTaskDetailResult.NotFound(errorMessage(root, code))
                code in 200..299 && taskMap != null ->
                    KanbanTaskDetailResult.Detail(parseTaskDetail(root, taskMap))
                code in 200..299 -> KanbanTaskDetailResult.Failed(unparseable(code))
                else -> KanbanTaskDetailResult.Failed(errorMessage(root, code))
            }
        }
    }

    /** `GET /api/kanban/stats` — per-status/per-assignee counters of one board. */
    suspend fun getStats(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        board: String? = null,
    ): KanbanStatsResult = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/kanban/stats")
            .boardParam(board)
            .build()
        execute(
            request = get(url, credential),
            networkFailure = { KanbanStatsResult.Failed(it) },
        ) { code, root ->
            when {
                root == null -> KanbanStatsResult.Failed(unparseable(code))
                code in 200..299 -> KanbanStatsResult.Stats(parseStats(root))
                else -> KanbanStatsResult.Failed(errorMessage(root, code))
            }
        }
    }

    /**
     * `GET /api/kanban/config` — display configuration of the board: the ordered
     * lane-name list (`columns`), known assignees and the display flags of
     * `dashboard.kanban` (`include_archived_by_default`, `render_markdown`, …).
     *
     * Read-only GET, so it clears the device-Bearer allowlist like the rest of the
     * kanban surface here. Best-effort for the caller: an older bridge without the
     * endpoint answers the shared 404 error envelope, which maps to
     * [KanbanConfigResult.Failed] — the screen then keeps the server column order
     * and never requests archived tasks by default.
     */
    suspend fun getConfig(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        board: String? = null,
    ): KanbanConfigResult = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/kanban/config")
            .boardParam(board)
            .build()
        execute(
            request = get(url, credential),
            networkFailure = { KanbanConfigResult.Failed(it) },
        ) { code, root ->
            when {
                root == null -> KanbanConfigResult.Failed(unparseable(code))
                code in 200..299 -> KanbanConfigResult.Config(parseConfig(root))
                else -> KanbanConfigResult.Failed(errorMessage(root, code))
            }
        }
    }

    /**
     * `GET /api/kanban/boards` — every board the operator can switch between.
     *
     * Read-only: device-Bearer is allowlisted for GET/HEAD on `/api/kanban/...` only,
     * so listing boards is permitted while board creation is not (and is not exposed).
     */
    suspend fun getBoards(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
    ): KanbanBoardsResult = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/kanban/boards")
            .build()
        execute(
            request = get(url, credential),
            networkFailure = { KanbanBoardsResult.Failed(it) },
        ) { code, root ->
            when {
                root == null -> KanbanBoardsResult.Failed(unparseable(code))
                code in 200..299 -> KanbanBoardsResult.Boards(parseBoards(root))
                else -> KanbanBoardsResult.Failed(errorMessage(root, code))
            }
        }
    }

    /**
     * `GET /api/kanban/assignees` — assignee names known to the bridge, with their
     * tallies. Used by the screen as a filter source; the list is advisory and never
     * a client-side authority on whether a name may be assigned.
     */
    suspend fun getAssignees(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
    ): KanbanAssigneesResult = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/kanban/assignees")
            .build()
        execute(
            request = get(url, credential),
            networkFailure = { KanbanAssigneesResult.Failed(it) },
        ) { code, root ->
            when {
                root == null -> KanbanAssigneesResult.Failed(unparseable(code))
                code in 200..299 -> KanbanAssigneesResult.Assignees(parseAssignees(root))
                else -> KanbanAssigneesResult.Failed(errorMessage(root, code))
            }
        }
    }

    /**
     * `GET /api/kanban/events/stream` — live board events over SSE.
     *
     * Read-only GET, so it clears the device-Bearer allowlist. Frames decodable by
     * the shared [SseChunkParser] are mapped through [KanbanStreamParser] (which
     * drops comments such as `: keepalive` that carry no `data` line).
     *
     * The flow completes when the server closes the stream; cancellation of the
     * collecting coroutine tears the call down immediately (the screen cancels it in
     * `onPause`, so a backgrounded board holds no connection).
     *
     * [since] is the resume cursor: pass [KanbanStreamFrame.Hello.cursor] or the last
     * known `latest_event_id` to continue where the previous stream stopped.
     */
    fun streamEvents(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        board: String? = null,
        since: Long? = null,
    ): Flow<KanbanStreamFrame> = flow {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/kanban/events/stream")
            .boardParam(board)
            .queryParam("since", since?.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .authHeader(credential)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body ?: throw IOException("HTTP ${response.code}: empty body")
            if (!response.isSuccessful) {
                val text = runCatching { body.string() }.getOrNull().orEmpty()
                val message = TolerantJson.parseMap(text)?.let { errorMessage(it, response.code) }
                    ?: "HTTP ${response.code}"
                throw IOException(message)
            }
            val parser = SseChunkParser()
            body.byteStream().use { stream ->
                val reader = InputStreamReader(stream, Charsets.UTF_8)
                val chars = CharArray(8 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = reader.read(chars)
                    if (read < 0) break
                    if (read > 0) {
                        KanbanStreamParser.frames(parser.feed(String(chars, 0, read)))
                            .forEach { emit(it) }
                    }
                }
                KanbanStreamParser.frames(parser.end()).forEach { emit(it) }
            }
        }
    }

    /**
     * `POST /api/kanban/dispatch` — one dispatcher tick. The bridge reads `board`,
     * `dry_run` and `max` from the QUERY string (`_resolve_board(parsed)`,
     * `_bool_query`, `_int_query` on `parsed`), so all params travel as query
     * params and the body is an empty JSON object (the global POST handler parses
     * the body before dispatch and rejects non-JSON).
     *
     * [dryRun]` = true` asks for a no-op scan (nothing is spawned) — the safe probe.
     */
    suspend fun dispatch(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        board: String? = null,
        dryRun: Boolean = false,
        maxSpawn: Int? = null,
    ): KanbanDispatchResult = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/kanban/dispatch")
            .boardParam(board)
            .apply { if (dryRun) addQueryParameter("dry_run", "1") }
            .apply { maxSpawn?.let { addQueryParameter("max", it.toString()) } }
            .build()
        val request = Request.Builder()
            .url(url)
            .authHeader(credential)
            .post("{}".toRequestBody(jsonMediaType))
            .build()
        execute(
            request = request,
            networkFailure = { KanbanDispatchResult.Failed(it) },
        ) { code, root ->
            when {
                root == null -> KanbanDispatchResult.Failed(unparseable(code))
                code in 200..299 -> KanbanDispatchResult.Dispatched(parseDispatch(root))
                else -> KanbanDispatchResult.Failed(errorMessage(root, code))
            }
        }
    }

    // ── request plumbing ──

    private fun get(url: HttpUrl, credential: ConsumedDeviceCredential): Request =
        Request.Builder().url(url).authHeader(credential).get().build()

    private fun Request.Builder.authHeader(credential: ConsumedDeviceCredential): Request.Builder =
        header("Authorization", "Bearer ${credential.accessToken}")

    private fun HttpUrl.Builder.queryParam(name: String, value: String?): HttpUrl.Builder =
        apply { value?.takeIf(String::isNotBlank)?.let { addQueryParameter(name, it) } }

    private fun HttpUrl.Builder.boardParam(board: String?): HttpUrl.Builder =
        queryParam("board", board)

    private inline fun <T> execute(
        request: Request,
        crossinline networkFailure: (message: String) -> T,
        crossinline map: (code: Int, root: Map<String, Any?>?) -> T,
    ): T = try {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            map(response.code, TolerantJson.parseMap(body))
        }
    } catch (error: IOException) {
        // IOException messages carry host/path at worst — never request headers, no token leak.
        networkFailure(error.message ?: "Network error")
    }

    private fun errorMessage(root: Map<String, Any?>?, code: Int): String =
        TolerantJson.string(root, "error") ?: "HTTP $code"

    private fun unparseable(code: Int): String = "HTTP $code: unparseable body"

    // ── parsing (all tolerant: absent/null/mis-typed fields degrade to defaults) ──

    internal fun parseBoard(root: Map<String, Any?>): KanbanBoard {
        val columns = TolerantJson.listField(root, "columns").mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            KanbanColumn(
                name = TolerantJson.string(map, "name").orEmpty(),
                statuses = TolerantJson.stringListOrEmpty(map, "statuses"),
                tasks = TolerantJson.listField(map, "tasks").mapNotNull { parseTaskOrNull(it) },
            )
        }
        val filtersMap = TolerantJson.mapField(root, "filters")
        return KanbanBoard(
            // Default true: a payload WITHOUT the flag is a full board, not a stale no-op.
            changed = TolerantJson.bool(root, "changed", default = true),
            latestEventId = TolerantJson.longOrNull(root, "latest_event_id") ?: 0L,
            readOnly = TolerantJson.bool(root, "read_only"),
            columns = columns,
            tenants = TolerantJson.stringListOrEmpty(root, "tenants"),
            assignees = TolerantJson.stringListOrEmpty(root, "assignees"),
            filters = filtersMap?.let {
                KanbanBoardFilters(
                    tenant = TolerantJson.string(it, "tenant"),
                    assignee = TolerantJson.string(it, "assignee"),
                    includeArchived = TolerantJson.bool(it, "include_archived"),
                    onlyMine = TolerantJson.bool(it, "only_mine"),
                    profile = TolerantJson.string(it, "profile"),
                )
            },
        )
    }

    internal fun parseTaskOrNull(raw: Any?): KanbanTask? {
        val map = raw as? Map<*, *> ?: return null
        @Suppress("UNCHECKED_CAST")
        val task = map as Map<String, Any?>
        val id = TolerantJson.string(task, "id") ?: return null
        val linkCounts = TolerantJson.mapField(task, "link_counts")
        return KanbanTask(
            id = id,
            title = TolerantJson.string(task, "title").orEmpty(),
            body = TolerantJson.string(task, "body"),
            status = TolerantJson.string(task, "status").orEmpty(),
            assignee = TolerantJson.string(task, "assignee"),
            priority = TolerantJson.int(task, "priority"),
            createdBy = TolerantJson.string(task, "created_by"),
            createdAt = TolerantJson.longOrNull(task, "created_at"),
            startedAt = TolerantJson.longOrNull(task, "started_at"),
            completedAt = TolerantJson.longOrNull(task, "completed_at"),
            workspaceKind = TolerantJson.string(task, "workspace_kind"),
            workspacePath = TolerantJson.string(task, "workspace_path"),
            claimLock = TolerantJson.string(task, "claim_lock"),
            claimExpires = TolerantJson.longOrNull(task, "claim_expires"),
            tenant = TolerantJson.string(task, "tenant"),
            branchName = TolerantJson.string(task, "branch_name"),
            projectId = TolerantJson.string(task, "project_id"),
            result = TolerantJson.string(task, "result"),
            consecutiveFailures = TolerantJson.int(task, "consecutive_failures"),
            workerPid = TolerantJson.longOrNull(task, "worker_pid"),
            lastFailureError = TolerantJson.string(task, "last_failure_error"),
            maxRuntimeSeconds = TolerantJson.longOrNull(task, "max_runtime_seconds"),
            lastHeartbeatAt = TolerantJson.longOrNull(task, "last_heartbeat_at"),
            currentRunId = TolerantJson.longOrNull(task, "current_run_id"),
            workflowTemplateId = TolerantJson.string(task, "workflow_template_id"),
            currentStepKey = TolerantJson.string(task, "current_step_key"),
            // null (defaults only) vs [] (explicitly none) is MEANINGFUL on the wire.
            skills = TolerantJson.stringListOrNull(task, "skills"),
            modelOverride = TolerantJson.string(task, "model_override"),
            providerOverride = TolerantJson.string(task, "provider_override"),
            reasoningEffort = TolerantJson.string(task, "reasoning_effort"),
            maxRetries = TolerantJson.intOrNull(task, "max_retries"),
            goalMode = TolerantJson.bool(task, "goal_mode"),
            goalMaxTurns = TolerantJson.longOrNull(task, "goal_max_turns"),
            sessionId = TolerantJson.string(task, "session_id"),
            blockKind = TolerantJson.string(task, "block_kind"),
            blockRecurrences = TolerantJson.int(task, "block_recurrences"),
            ageSeconds = TolerantJson.longOrNull(task, "age_seconds"),
            commentCount = TolerantJson.int(task, "comment_count"),
            parentCount = TolerantJson.int(linkCounts, "parents"),
            childCount = TolerantJson.int(linkCounts, "children"),
        )
    }

    internal fun parseTaskDetail(root: Map<String, Any?>, taskMap: Map<String, Any?>): KanbanTaskDetail {
        val links = TolerantJson.mapField(root, "links")
        return KanbanTaskDetail(
            task = parseTaskOrNull(taskMap) ?: KanbanTask(id = "", title = ""),
            comments = TolerantJson.listField(root, "comments").mapNotNull { entry ->
                val map = entry as? Map<*, *> ?: return@mapNotNull null
                KanbanComment(
                    id = TolerantJson.longOrNull(map, "id") ?: 0L,
                    taskId = TolerantJson.string(map, "task_id").orEmpty(),
                    author = TolerantJson.string(map, "author").orEmpty(),
                    body = TolerantJson.string(map, "body").orEmpty(),
                    createdAt = TolerantJson.longOrNull(map, "created_at"),
                )
            },
            events = TolerantJson.listField(root, "events").mapNotNull { entry ->
                val map = entry as? Map<*, *> ?: return@mapNotNull null
                KanbanTaskEvent(
                    id = TolerantJson.longOrNull(map, "id") ?: 0L,
                    taskId = TolerantJson.string(map, "task_id").orEmpty(),
                    runId = TolerantJson.longOrNull(map, "run_id"),
                    kind = TolerantJson.string(map, "kind").orEmpty(),
                    payload = TolerantJson.mapField(map, "payload"),
                    createdAt = TolerantJson.longOrNull(map, "created_at"),
                )
            },
            links = KanbanTaskLinks(
                parents = TolerantJson.stringListOrEmpty(links, "parents"),
                children = TolerantJson.stringListOrEmpty(links, "children"),
            ),
            runs = TolerantJson.listField(root, "runs").mapNotNull { entry ->
                val map = entry as? Map<*, *> ?: return@mapNotNull null
                KanbanRun(
                    id = TolerantJson.longOrNull(map, "id") ?: 0L,
                    taskId = TolerantJson.string(map, "task_id").orEmpty(),
                    profile = TolerantJson.string(map, "profile"),
                    stepKey = TolerantJson.string(map, "step_key"),
                    status = TolerantJson.string(map, "status").orEmpty(),
                    startedAt = TolerantJson.longOrNull(map, "started_at"),
                    endedAt = TolerantJson.longOrNull(map, "ended_at"),
                    outcome = TolerantJson.string(map, "outcome"),
                    summary = TolerantJson.string(map, "summary"),
                    error = TolerantJson.string(map, "error"),
                )
            },
            readOnly = TolerantJson.bool(root, "read_only"),
        )
    }

    internal fun parseStats(root: Map<String, Any?>): KanbanStats {
        val byStatus = TolerantJson.intCounts(root, "by_status")
        val byAssignee = TolerantJson.mapField(root, "by_assignee").orEmpty().entries
            .mapNotNull { (assignee, value) ->
                val name = assignee.takeIf(String::isNotBlank) ?: return@mapNotNull null
                parseAssigneeStats(value)?.let { name to it }
            }
            .toMap()
        return KanbanStats(
            byStatus = byStatus,
            byAssignee = byAssignee,
            oldestReadyAgeSeconds = TolerantJson.longOrNull(root, "oldest_ready_age_seconds"),
            now = TolerantJson.longOrNull(root, "now"),
        )
    }

    /**
     * `GET /api/kanban/config`: `{columns, assignees, default_tenant, lane_by_profile,
     * include_archived_by_default, render_markdown, read_only}` (`_config_payload` in
     * `kanban_bridge.py`). Absent/mis-typed fields fall back to the SERVER's defaults —
     * `lane_by_profile` and `render_markdown` are true there, everything else false —
     * so a truncated payload behaves exactly like an unconfigured bridge.
     */
    internal fun parseConfig(root: Map<String, Any?>): KanbanConfig = KanbanConfig(
        columns = TolerantJson.stringListOrEmpty(root, "columns"),
        assignees = TolerantJson.stringListOrEmpty(root, "assignees"),
        defaultTenant = TolerantJson.string(root, "default_tenant").orEmpty(),
        laneByProfile = TolerantJson.bool(root, "lane_by_profile", default = true),
        includeArchivedByDefault = TolerantJson.bool(root, "include_archived_by_default"),
        renderMarkdown = TolerantJson.bool(root, "render_markdown", default = true),
        readOnly = TolerantJson.bool(root, "read_only"),
    )

    /**
     * One `by_assignee` value: the kanban_db.board_stats shape is `{status: n}` (nested),
     * while the bridge's SQL-fallback shape is a bare count `n`. Both are valid wire
     * contracts; anything else is dropped.
     */
    private fun parseAssigneeStats(value: Any?): KanbanAssigneeStats? = when (value) {
        is Map<*, *> -> {
            val perStatus = value.entries.mapNotNull { (k, v) ->
                val status = k?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val count = (v as? Number)?.toInt() ?: v?.toString()?.toIntOrNull() ?: return@mapNotNull null
                status to count
            }.toMap()
            KanbanAssigneeStats(total = perStatus.values.sum(), byStatus = perStatus)
        }
        is Number -> KanbanAssigneeStats(total = value.toInt())
        else -> null
    }

    /**
     * `GET /api/kanban/boards`: `{boards: [...], current: "default", read_only: false}`.
     * A row missing `slug` is dropped — a board with no slug cannot be addressed by the
     * rest of the API, so surfacing it would produce a switch target that 404s.
     */
    internal fun parseBoards(root: Map<String, Any?>): KanbanBoards =
        KanbanBoards(
            boards = TolerantJson.listField(root, "boards").mapNotNull { entry ->
                val map = entry as? Map<*, *> ?: return@mapNotNull null
                val slug = TolerantJson.string(map, "slug").orEmpty()
                if (slug.isBlank()) return@mapNotNull null
                BoardSummary(
                    slug = slug,
                    name = TolerantJson.string(map, "name").orEmpty().ifBlank { slug },
                    description = TolerantJson.string(map, "description").orEmpty(),
                    icon = TolerantJson.string(map, "icon").orEmpty(),
                    color = TolerantJson.string(map, "color").orEmpty(),
                    archived = TolerantJson.bool(map, "archived"),
                    isCurrent = TolerantJson.bool(map, "is_current"),
                    counts = TolerantJson.intCounts(map, "counts"),
                    total = TolerantJson.int(map, "total"),
                )
            },
            current = TolerantJson.string(root, "current"),
            readOnly = TolerantJson.bool(root, "read_only"),
        )

    /**
     * `GET /api/kanban/assignees`: `{assignees: [{name, on_disk, counts: {status: n}}]}`.
     * Rows without a name are dropped; the list feeds a filter, and an empty filter row
     * would be unselectable.
     */
    internal fun parseAssignees(root: Map<String, Any?>): KanbanAssignees =
        KanbanAssignees(
            assignees = TolerantJson.listField(root, "assignees").mapNotNull { entry ->
                val map = entry as? Map<*, *> ?: return@mapNotNull null
                val name = TolerantJson.string(map, "name").orEmpty()
                if (name.isBlank()) return@mapNotNull null
                AssigneeStat(
                    name = name,
                    onDisk = TolerantJson.bool(map, "on_disk"),
                    counts = TolerantJson.intCounts(map, "counts"),
                )
            },
        )

    internal fun parseDispatch(root: Map<String, Any?>): KanbanDispatchOutcomePayload =
        KanbanDispatchOutcomePayload(
            spawned = TolerantJson.listField(root, "spawned").mapNotNull { entry ->
                // asdict turns (task_id, assignee, workspace_path) tuples into JSON arrays.
                val triple = entry as? List<*> ?: return@mapNotNull null
                KanbanDispatchSpawn(
                    taskId = triple.getOrNull(0)?.toString().orEmpty(),
                    assignee = triple.getOrNull(1)?.toString().orEmpty(),
                    workspacePath = triple.getOrNull(2)?.toString().orEmpty(),
                )
            },
            reclaimed = TolerantJson.int(root, "reclaimed"),
            promoted = TolerantJson.int(root, "promoted"),
            reconciledOrphans = TolerantJson.stringListOrEmpty(root, "reconciled_orphans"),
            skippedUnassigned = TolerantJson.stringListOrEmpty(root, "skipped_unassigned"),
            autoAssignedDefault = TolerantJson.stringListOrEmpty(root, "auto_assigned_default"),
            crashed = TolerantJson.stringListOrEmpty(root, "crashed"),
            autoBlocked = TolerantJson.stringListOrEmpty(root, "auto_blocked"),
            timedOut = TolerantJson.stringListOrEmpty(root, "timed_out"),
            stale = TolerantJson.stringListOrEmpty(root, "stale"),
            rateLimited = TolerantJson.stringListOrEmpty(root, "rate_limited"),
            skippedLocked = TolerantJson.bool(root, "skipped_locked"),
            memoryPressure = TolerantJson.string(root, "memory_pressure"),
        )
}
