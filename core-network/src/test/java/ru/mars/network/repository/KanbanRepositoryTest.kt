package ru.mars.network.repository

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.KanbanAssigneeStats

/**
 * [KanbanRepository] contract tests on MockWebServer, against the shapes verified in
 * `/opt/hermes-webui/api/kanban_bridge.py`: request mapping (paths, whitelisted query
 * params, Bearer auth), tolerant parsing of board/task/detail/stats payloads (including
 * the `changed:false` short envelope and the two `by_assignee` shapes) and the bridge's
 * error ladder (`{"error": msg}` with 400/404/409/503).
 */
class KanbanRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: KanbanRepository
    private val credential = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = KanbanRepository()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl() = server.url("/").toString()

    private fun enqueue(code: Int = 200, body: String, contentType: String = "application/json") {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .addHeader("Content-Type", contentType)
                .setBody(body)
        )
    }

    // ── GET /api/kanban/board ──

    @Test
    fun `getBoard maps all filters onto whitelisted query params with bearer auth`() = runBlocking {
        enqueue(body = """{"changed":false,"latest_event_id":7,"read_only":false}""")
        val result = repository.getBoard(
            baseUrl(), credential,
            board = "ops", tenant = "t1", assignee = "mars",
            includeArchived = true, onlyMine = true, since = 42L,
        )
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals(
            "/api/kanban/board?board=ops&tenant=t1&assignee=mars&include_archived=1&only_mine=1&since=42",
            recorded.path,
        )
        assertEquals("Bearer native-token", recorded.getHeader("Authorization"))
        val board = (result as KanbanBoardResult.Board).board
        assertFalse(board.changed)
        assertEquals(7L, board.latestEventId)
        assertTrue(board.columns.isEmpty())
        Unit
    }

    @Test
    fun `getBoard without filters sends a bare path and parses a full board`() = runBlocking {
        enqueue(
            body = """
            {"changed":true,"latest_event_id":120,"read_only":false,
             "tenants":["default","t1"],"assignees":["mars","sage"],
             "filters":{"tenant":null,"assignee":null,"include_archived":false,"only_mine":false,"profile":"default"},
             "columns":[
               {"name":"Ready","statuses":["ready"],"tasks":[
                 {"id":"k-1","title":"Задача","body":"текст","status":"ready","assignee":"mars",
                  "priority":2,"created_by":"stef","created_at":1700000000,"started_at":null,
                  "completed_at":null,"workspace_kind":"worktree","workspace_path":"/root/wt",
                  "claim_lock":null,"claim_expires":null,"tenant":"default","branch_name":"wave/1",
                  "project_id":null,"result":null,"consecutive_failures":0,"worker_pid":null,
                  "last_failure_error":null,"max_runtime_seconds":3600,"last_heartbeat_at":null,
                  "current_run_id":5,"workflow_template_id":null,"current_step_key":null,
                  "skills":["mars-toolkit"],"model_override":"","provider_override":null,
                  "reasoning_effort":"high","max_retries":null,"goal_mode":true,"goal_max_turns":"8",
                  "session_id":"sess-1","block_kind":null,"block_recurrences":0,
                  "age_seconds":3600,"age":3600,"progress":null,
                  "comment_count":2,"link_counts":{"parents":1,"children":0}},
                 {"id":"k-2","title":null}
               ]},
               {"name":"Working","tasks":[{"id":"k-3","title":"В работе","status":"running","age_seconds":null}],
                "statuses":null}
             ]}
            """.trimIndent(),
        )
        val result = repository.getBoard(baseUrl(), credential)
        assertEquals("/api/kanban/board", server.takeRequest().path)
        val board = (result as KanbanBoardResult.Board).board
        assertTrue(board.changed)
        assertEquals(120L, board.latestEventId)
        assertEquals(listOf("default", "t1"), board.tenants)
        assertEquals("default", board.filters?.profile)
        assertNull(board.filters?.tenant)
        assertEquals(2, board.columns.size)

        val full = board.columns[0].tasks[0]
        assertEquals("k-1", full.id)
        assertEquals("Задача", full.title)
        assertEquals(2, full.priority)
        assertEquals(1700000000L, full.createdAt)
        assertEquals(listOf("mars-toolkit"), full.skills)
        // kanban stores ""-is-NULL for model_override (`_TASK_EMPTY_IS_NULL_COLUMNS`),
        // so a blank wire value correctly degrades to null.
        assertNull(full.modelOverride)
        assertEquals(8L, full.goalMaxTurns)   // numeric string column
        assertTrue(full.goalMode)
        assertEquals(2, full.commentCount)
        assertEquals(1, full.parentCount)
        assertEquals(0, full.childCount)
        assertEquals(3600L, full.ageSeconds)

        // Incomplete task: every absent field degrades to its default.
        val bare = board.columns[0].tasks[1]
        assertEquals("k-2", bare.id)
        assertEquals("", bare.title)
        assertEquals("", bare.status)
        assertNull(bare.createdAt)
        assertNull(bare.skills)
        assertFalse(bare.goalMode)

        // Column with statuses:null still parses.
        assertEquals("Working", board.columns[1].name)
        assertTrue(board.columns[1].statuses.isEmpty())
        Unit
    }

    @Test
    fun `getBoard drops tasks without id and non-map entries`() {
        val root = TolerantJson.parseMap(
            """{"columns":[{"name":"Ready","tasks":["junk",{"title":"no id"},{"id":"ok","title":"t"}]}]}"""
        )!!
        val board = repository.parseBoard(root)
        assertEquals(listOf("ok"), board.columns.single().tasks.map { it.id })
    }

    @Test
    fun `getBoard distinguishes skills null from explicitly empty list`() {
        val root = TolerantJson.parseMap(
            """{"columns":[{"name":"R","tasks":[
                 {"id":"a","skills":null},{"id":"b","skills":[]}]}]}"""
        )!!
        val tasks = repository.parseBoard(root).columns.single().tasks
        assertNull(tasks[0].skills)
        assertEquals(emptyList<String>(), tasks[1].skills)
    }

    @Test
    fun `getBoard maps 404 board-not-found and 503 kanban-unavailable`() = runBlocking {
        enqueue(404, """{"error":"board 'ghost' does not exist"}""")
        val notFound = repository.getBoard(baseUrl(), credential, board = "ghost")
        assertEquals(
            KanbanBoardResult.NotFound("board 'ghost' does not exist"),
            notFound,
        )

        enqueue(503, """{"error":"kanban unavailable: No module named hermes_cli"}""")
        val unavailable = repository.getBoard(baseUrl(), credential)
        assertEquals(
            KanbanBoardResult.Failed("kanban unavailable: No module named hermes_cli"),
            unavailable,
        )
        Unit
    }

    @Test
    fun `getBoard maps 400 invalid slug and bodyless 500`() = runBlocking {
        enqueue(400, """{"error":"invalid board slug: '../x'"}""")
        assertEquals(
            KanbanBoardResult.Failed("invalid board slug: '../x'"),
            repository.getBoard(baseUrl(), credential),
        )
        enqueue(500, "")
        val failed = repository.getBoard(baseUrl(), credential)
        assertEquals(KanbanBoardResult.Failed("HTTP 500: unparseable body"), failed)
        Unit
    }

    @Test
    fun `getBoard maps transport failure without leaking headers`() = runBlocking {
        val port = server.port
        server.shutdown()
        val result = repository.getBoard("http://127.0.0.1:$port/", credential)
        assertTrue(result is KanbanBoardResult.Failed)
        assertFalse((result as KanbanBoardResult.Failed).message.contains("native-token"))
        Unit
    }

    // ── GET /api/kanban/tasks/{id} ──

    @Test
    fun `getTaskDetail maps path and board param and parses the full detail`() = runBlocking {
        enqueue(
            body = """
            {"task":{"id":"k-1","title":"Задача","status":"running","assignee":"mars",
                     "created_at":1700000000,"consecutive_failures":1,
                     "last_failure_error":"worker exited rc=1"},
             "comments":[{"id":3,"task_id":"k-1","author":"stef","body":"комментарий","created_at":1700000100}],
             "events":[{"id":9,"task_id":"k-1","run_id":5,"kind":"claimed","payload":{"pid":111},"created_at":1700000200}],
             "links":{"parents":["k-0"],"children":["k-2","k-3"]},
             "runs":[{"id":5,"task_id":"k-1","profile":"default","step_key":null,"status":"running",
                      "started_at":1700000150,"ended_at":null,"outcome":null,"summary":null,"error":null}],
             "read_only":false}
            """.trimIndent(),
        )
        val result = repository.getTaskDetail(baseUrl(), credential, "k-1", board = "ops")
        val recorded = server.takeRequest()
        assertEquals("/api/kanban/tasks/k-1?board=ops", recorded.path)
        assertEquals("Bearer native-token", recorded.getHeader("Authorization"))

        val detail = (result as KanbanTaskDetailResult.Detail).detail
        assertEquals("k-1", detail.task.id)
        assertEquals(1, detail.task.consecutiveFailures)
        assertEquals("комментарий", detail.comments.single().body)
        assertEquals(3L, detail.comments.single().id)
        val event = detail.events.single()
        assertEquals("claimed", event.kind)
        assertEquals(5L, event.runId)
        assertEquals(111, (event.payload?.get("pid") as Number).toInt())
        assertEquals(listOf("k-0"), detail.links.parents)
        assertEquals(listOf("k-2", "k-3"), detail.links.children)
        assertEquals("running", detail.runs.single().status)
        assertNull(detail.runs.single().endedAt)
        assertFalse(detail.readOnly)
        Unit
    }

    @Test
    fun `getTaskDetail survives missing sections`() = runBlocking {
        enqueue(body = """{"task":{"id":"k-9","title":"t"}}""")
        val detail = (repository.getTaskDetail(baseUrl(), credential, "k-9")
            as KanbanTaskDetailResult.Detail).detail
        assertTrue(detail.comments.isEmpty())
        assertTrue(detail.events.isEmpty())
        assertTrue(detail.runs.isEmpty())
        assertEquals(emptyList<String>(), detail.links.parents)
        Unit
    }

    @Test
    fun `getTaskDetail maps 404 task-not-found distinctly from failures`() = runBlocking {
        enqueue(404, """{"error":"task not found"}""")
        assertEquals(
            KanbanTaskDetailResult.NotFound("task not found"),
            repository.getTaskDetail(baseUrl(), credential, "missing"),
        )
        enqueue(200, """{"read_only":false}""") // 2xx without a task object
        val broken = repository.getTaskDetail(baseUrl(), credential, "k-1")
        assertTrue(broken is KanbanTaskDetailResult.Failed)
        Unit
    }

    @Test
    fun `getTaskDetail percent-encodes the task id path segment`() = runBlocking {
        enqueue(404, """{"error":"task not found"}""")
        repository.getTaskDetail(baseUrl(), credential, "k 1")
        assertEquals("/api/kanban/tasks/k%201", server.takeRequest().path)
        Unit
    }

    // ── GET /api/kanban/stats ──

    @Test
    fun `getStats parses the kanban_db board_stats shape`() = runBlocking {
        enqueue(
            body = """{"board":"default","stats":true,
               "by_status":{"ready":3,"running":1,"done":10,"blocked":2},
               "by_assignee":{"mars":{"running":1,"blocked":2},"sage":{"ready":3}},
               "oldest_ready_age_seconds":4200,"now":1700000000}"""
        )
        val result = repository.getStats(baseUrl(), credential)
        assertEquals("/api/kanban/stats", server.takeRequest().path)
        val stats = (result as KanbanStatsResult.Stats).stats
        assertEquals(mapOf("ready" to 3, "running" to 1, "done" to 10, "blocked" to 2), stats.byStatus)
        assertEquals(KanbanAssigneeStats(total = 3, byStatus = mapOf("running" to 1, "blocked" to 2)), stats.byAssignee["mars"])
        assertEquals(3, stats.byAssignee["sage"]?.total)
        assertEquals(4200L, stats.oldestReadyAgeSeconds)
        assertEquals(1700000000L, stats.now)
        Unit
    }

    @Test
    fun `getStats parses the SQL-fallback flat by_assignee shape without oldest age`() = runBlocking {
        enqueue(body = """{"by_status":{"ready":"2"},"by_assignee":{"mars":2},"oldest_ready_age_seconds":null}""")
        val stats = (repository.getStats(baseUrl(), credential, board = "ops")
            as KanbanStatsResult.Stats).stats
        assertEquals("/api/kanban/stats?board=ops", server.takeRequest().path)
        assertEquals(mapOf("ready" to 2), stats.byStatus) // numeric-string counters
        assertEquals(KanbanAssigneeStats(total = 2), stats.byAssignee["mars"])
        assertNull(stats.oldestReadyAgeSeconds)
        assertNull(stats.now)
        Unit
    }

    @Test
    fun `getStats maps errors onto the server message`() = runBlocking {
        enqueue(404, """{"error":"board 'ghost' does not exist"}""")
        assertEquals(
            KanbanStatsResult.Failed("board 'ghost' does not exist"),
            repository.getStats(baseUrl(), credential, board = "ghost"),
        )
        Unit
    }

    // ── GET /api/kanban/config ──

    @Test
    fun `getConfig sends bearer auth and parses a full config payload`() = runBlocking {
        enqueue(
            body = """{
                "columns":["new","waiting","running","fault","done"],
                "assignees":["mars","sage"],
                "default_tenant":"ops",
                "lane_by_profile":true,
                "include_archived_by_default":false,
                "render_markdown":true,
                "read_only":false}"""
        )
        val result = repository.getConfig(baseUrl(), credential, board = "ops")
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/kanban/config?board=ops", recorded.path)
        assertEquals("Bearer native-token", recorded.getHeader("Authorization"))
        val cfg = (result as KanbanConfigResult.Config).config
        assertEquals(listOf("new", "waiting", "running", "fault", "done"), cfg.columns)
        assertEquals(listOf("mars", "sage"), cfg.assignees)
        assertEquals("ops", cfg.defaultTenant)
        assertTrue(cfg.laneByProfile)
        assertFalse(cfg.includeArchivedByDefault)
        assertTrue(cfg.renderMarkdown)
        assertFalse(cfg.readOnly)
        Unit
    }

    @Test
    fun `getConfig falls back to server defaults on a truncated payload`() = runBlocking {
        // Старый бридж или обрезанный ответ: только columns и assignees.
        enqueue(body = """{"columns":["ready"],"assignees":["me"]}""")
        val cfg = (repository.getConfig(baseUrl(), credential) as KanbanConfigResult.Config).config
        assertEquals("/api/kanban/config", server.takeRequest().path)
        assertEquals(listOf("ready"), cfg.columns)
        assertEquals(listOf("me"), cfg.assignees)
        assertEquals("", cfg.defaultTenant)
        assertTrue(cfg.laneByProfile)
        assertFalse(cfg.includeArchivedByDefault)
        assertTrue(cfg.renderMarkdown)
        assertFalse(cfg.readOnly)
        Unit
    }

    @Test
    fun `getConfig tolerates mis-typed booleans and numbers`() = runBlocking {
        enqueue(
            body = """{
                "columns":[],
                "assignees":[],
                "default_tenant":null,
                "lane_by_profile":"1",
                "include_archived_by_default":0,
                "render_markdown":"TRUE",
                "read_only":1}"""
        )
        val cfg = (repository.getConfig(baseUrl(), credential) as KanbanConfigResult.Config).config
        assertTrue(cfg.laneByProfile)
        assertFalse(cfg.includeArchivedByDefault)
        assertTrue(cfg.renderMarkdown)
        assertTrue(cfg.readOnly)
        Unit
    }

    @Test
    fun `getConfig maps 404 onto Failed for older bridges without the endpoint`() = runBlocking {
        enqueue(404, """{"error":"unknown kanban endpoint"}""")
        assertEquals(
            KanbanConfigResult.Failed("unknown kanban endpoint"),
            repository.getConfig(baseUrl(), credential),
        )
        Unit
    }

    @Test
    fun `getConfig drops nulls and blanks but renders numeric ids as strings`() = runBlocking {
        // Контракт TolerantJson.renderString: null/пробелы отбрасываются, число
        // рендерится строкой без «.0» (kanban id `3` обязан стать «3»).
        enqueue(body = """{"columns":["ready",null,"  ",3],"assignees":["a",""]}""")
        val cfg = (repository.getConfig(baseUrl(), credential) as KanbanConfigResult.Config).config
        assertEquals(listOf("ready", "3"), cfg.columns)
        assertEquals(listOf("a"), cfg.assignees)
        Unit
    }

    // ── POST /api/kanban/dispatch ──

    @Test
    fun `dispatch posts an empty json body with query-only params`() = runBlocking {
        enqueue(
            body = """{"reclaimed":0,"promoted":0,"reconciled_orphans":[],
               "spawned":[["k-1","mars","/root/wt/k-1"]],
               "skipped_unassigned":["k-7"],"auto_assigned_default":[],
               "crashed":[],"auto_blocked":[],"timed_out":[],"stale":[],
               "respawn_guarded":[],"rate_limited":[],"skipped_locked":false,
               "memory_pressure":null}"""
        )
        val result = repository.dispatch(baseUrl(), credential, board = "ops", dryRun = true, maxSpawn = 2)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/kanban/dispatch?board=ops&dry_run=1&max=2", recorded.path)
        assertEquals("{}", recorded.body.readUtf8())
        assertEquals("Bearer native-token", recorded.getHeader("Authorization"))

        val outcome = (result as KanbanDispatchResult.Dispatched).outcome
        assertEquals(1, outcome.spawned.size)
        assertEquals("k-1", outcome.spawned[0].taskId)
        assertEquals("mars", outcome.spawned[0].assignee)
        assertEquals("/root/wt/k-1", outcome.spawned[0].workspacePath)
        assertEquals(listOf("k-7"), outcome.skippedUnassigned)
        assertFalse(outcome.skippedLocked)
        assertNull(outcome.memoryPressure)
        Unit
    }

    @Test
    fun `dispatch without options sends bare path and tolerates partial results`() = runBlocking {
        // A losing dispatcher answers an almost-empty DispatchResult (skipped_locked).
        enqueue(body = """{"skipped_locked":true}""")
        val outcome = (repository.dispatch(baseUrl(), credential)
            as KanbanDispatchResult.Dispatched).outcome
        assertEquals("/api/kanban/dispatch", server.takeRequest().path)
        assertTrue(outcome.skippedLocked)
        assertTrue(outcome.spawned.isEmpty())
        assertEquals(0, outcome.reclaimed)
        Unit
    }

    @Test
    fun `dispatch maps 409 and 503 onto the server message`() = runBlocking {
        enqueue(409, """{"error":"dispatch lock busy"}""")
        assertEquals(
            KanbanDispatchResult.Failed("dispatch lock busy"),
            repository.dispatch(baseUrl(), credential),
        )
        enqueue(503, """{"error":"kanban unavailable: boom"}""")
        assertEquals(
            KanbanDispatchResult.Failed("kanban unavailable: boom"),
            repository.dispatch(baseUrl(), credential),
        )
        Unit
    }

    // ── GET /api/kanban/boards ──

    @Test
    fun `getBoards maps the fixture payload and the current board`() = runBlocking {
        enqueue(body = """
            {"boards":[{"slug":"default","name":"Default","description":"","icon":"","color":"",
            "archived":false,"is_current":true,"counts":{"done":2},"total":2}],
            "current":"default","read_only":false}
        """.trimIndent())
        val result = repository.getBoards(baseUrl(), credential)
        val boards = (result as KanbanBoardsResult.Boards).boards
        assertEquals("default", boards.current)
        assertFalse(boards.readOnly)
        assertEquals(1, boards.boards.size)
        val row = boards.boards.single()
        assertEquals("default", row.slug)
        assertEquals("Default", row.name)
        assertTrue(row.isCurrent)
        assertEquals(2, row.counts["done"])
        assertEquals(2, row.total)
        Unit
    }

    @Test
    fun `getBoards drops rows without a slug and defaults a blank name to the slug`() = runBlocking {
        enqueue(body = """
            {"boards":[{"name":"no slug"},{"slug":"work","name":""}],"current":null,"read_only":true}
        """.trimIndent())
        val boards = (repository.getBoards(baseUrl(), credential) as KanbanBoardsResult.Boards).boards
        assertEquals(1, boards.boards.size)
        // Blank name falls back to the slug — an unlabelled switch target is unusable in UI.
        assertEquals("work", boards.boards.single().name)
        assertTrue(boards.readOnly)
        assertEquals(null, boards.current)
        Unit
    }

    @Test
    fun `getBoards surfaces a bridge error as Failed`() = runBlocking {
        enqueue(code = 503, body = """{"error":"kanban unavailable: no hermes"}""")
        val result = repository.getBoards(baseUrl(), credential)
        assertEquals(
            KanbanBoardsResult.Failed("kanban unavailable: no hermes"),
            result,
        )
        Unit
    }

    // ── GET /api/kanban/assignees ──

    @Test
    fun `getAssignees maps the fixture payload and drops nameless rows`() = runBlocking {
        enqueue(body = """
            {"assignees":[{"name":"default","on_disk":true,"counts":{"done":2}},
            {"name":"office","on_disk":true,"counts":{}},
            {"on_disk":true},{"name":"  ","on_disk":false}]}
        """.trimIndent())
        val result = repository.getAssignees(baseUrl(), credential)
        val assignees = (result as KanbanAssigneesResult.Assignees).assignees.assignees
        assertEquals(listOf("default", "office"), assignees.map { it.name })
        assertEquals(2, assignees.first().counts["done"])
        assertTrue(assignees.first().onDisk)
        Unit
    }

    @Test
    fun `getAssignees hits the whitelisted path with bearer auth`() = runBlocking {
        enqueue(body = """{"assignees":[]}""")
        repository.getAssignees(baseUrl(), credential)
        val request = server.takeRequest()
        assertEquals("/api/kanban/assignees", request.path)
        assertEquals("Bearer native-token", request.getHeader("Authorization"))
        Unit
    }

    // ── GET /api/kanban/events/stream ──

    @Test
    fun `streamEvents yields hello then an events batch carrying the resume cursor`() = runBlocking {
        enqueue(
            body = "event: hello\ndata: {\"cursor\":5,\"board\":\"default\"}\n\n" +
                "id: 9\nevent: events\ndata: {\"events\":[{\"id\":9,\"task_id\":\"t1\",\"kind\":\"updated\"}],\"cursor\":9}\n\n",
            contentType = "text/event-stream",
        )
        val frames = repository.streamEvents(baseUrl(), credential).take(2).toList()
        assertEquals(KanbanStreamFrame.Hello(cursor = 5L, board = "default"), frames[0])
        val events = frames[1] as KanbanStreamFrame.Events
        assertEquals(9L, events.cursor)
        assertEquals("t1", events.events.single().taskId)
        assertEquals("updated", events.events.single().kind)
        Unit
    }

    @Test
    fun `streamEvents prefers the frame id over the body cursor for resume`() = runBlocking {
        // The `id:` field is what a browser would echo back as Last-Event-ID, so manual
        // and automatic resume must agree on it even when the body disagrees.
        enqueue(
            body = "id: 41\nevent: events\ndata: {\"events\":[],\"cursor\":40}\n\n",
            contentType = "text/event-stream",
        )
        val frame = repository.streamEvents(baseUrl(), credential).first()
        assertEquals(41L, (frame as KanbanStreamFrame.Events).cursor)
        Unit
    }

    @Test
    fun `streamEvents sends since and board as whitelisted query params`() = runBlocking {
        enqueue(body = "event: hello\ndata: {\"cursor\":1}\n\n", contentType = "text/event-stream")
        repository.streamEvents(baseUrl(), credential, board = "work", since = 12L).first()
        val request = server.takeRequest()
        assertEquals("/api/kanban/events/stream?board=work&since=12", request.path)
        assertEquals("text/event-stream", request.getHeader("Accept"))
        Unit
    }
}
