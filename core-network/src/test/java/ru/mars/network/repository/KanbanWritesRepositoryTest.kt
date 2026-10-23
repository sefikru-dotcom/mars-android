package ru.mars.network.repository

import kotlinx.coroutines.runBlocking
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

/**
 * Контракт ЗАПИСИ канбана ([KanbanRepository]) на MockWebServer: путь, глагол,
 * тело и авторизация каждого маршрута сверены с `/opt/hermes-webui/api/kanban_bridge.py`
 * (`handle_kanban_post`/`handle_kanban_patch`), а также лестница ошибок бриджа
 * (`{"error": …}` + 400/404/409/503).
 *
 * Главное, что здесь закреплено: device-Bearer допускается только на пары
 * «маршрут + глагол» из `api/auth.py` (`/api/kanban/tasks`, `/tasks/bulk`,
 * `/links`, `/links/delete`, `/tasks/<id>/{patch,comments,block,unblock}`), а
 * локальные отказы (пустой title, пустой список id) не тратят round-trip.
 */
class KanbanWritesRepositoryTest {

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

    private fun enqueue(code: Int = 200, body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .addHeader("Content-Type", "application/json")
                .setBody(body)
        )
    }

    private fun taskEnvelope(id: String = "7", status: String = "ready") =
        """{"task":{"id":"$id","title":"Fix","status":"$status","priority":2},"read_only":false}"""

    // ── POST /api/kanban/tasks ──

    @Test
    fun `createTask posts title body and assignee to the board-scoped route`() = runBlocking {
        enqueue(body = taskEnvelope(id = "new-1", status = "todo"))
        val result = repository.createTask(
            baseUrl(), credential,
            title = "  Починить ленту  ",
            body = "Падает на старте",
            assignee = "mars",
            board = "ops",
        )
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/kanban/tasks?board=ops", recorded.path)
        assertEquals("Bearer native-token", recorded.getHeader("Authorization"))
        assertEquals(
            """{"title":"Починить ленту","body":"Падает на старте","assignee":"mars"}""",
            recorded.body.readUtf8(),
        )
        val updated = result as KanbanTaskWriteResult.Updated
        assertEquals("new-1", updated.task.id)
        assertEquals("todo", updated.task.status)
    }

    @Test
    fun `createTask refuses a blank title locally without a round trip`() = runBlocking {
        val result = repository.createTask(baseUrl(), credential, title = "   ")
        assertEquals("title is required", (result as KanbanTaskWriteResult.Failed).message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `createTask escapes quotes and control characters into strict json`() = runBlocking {
        enqueue(body = taskEnvelope())
        repository.createTask(baseUrl(), credential, title = "a\"b\nc\\d")
        val body = server.takeRequest().body.readUtf8()
        assertEquals("""{"title":"a\"b\nc\\d"}""", body)
    }

    // ── PATCH /api/kanban/tasks/{id}/patch ──

    @Test
    fun `patchTask sends only the fields it was given`() = runBlocking {
        enqueue(body = taskEnvelope(status = "done"))
        val result = repository.patchTask(baseUrl(), credential, taskId = "7", status = "done", board = "ops")
        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/api/kanban/tasks/7/patch?board=ops", recorded.path)
        assertEquals("""{"status":"done"}""", recorded.body.readUtf8())
        assertEquals("done", (result as KanbanTaskWriteResult.Updated).task.status)
    }

    @Test
    fun `patchTask maps 404 to NotFound and 409 to the bridge message`() = runBlocking {
        enqueue(code = 404, body = """{"error":"task not found"}""")
        val missing = repository.patchTask(baseUrl(), credential, taskId = "404", status = "done")
        assertEquals("task not found", (missing as KanbanTaskWriteResult.NotFound).message)

        enqueue(
            code = 409,
            body = """{"error":"Cannot set status to 'running' directly; use the dispatcher/claim path"}""",
        )
        val refused = repository.patchTask(baseUrl(), credential, taskId = "7", status = "running")
        assertTrue(
            (refused as KanbanTaskWriteResult.Failed).message.contains("Cannot set status to 'running'")
        )
    }

    @Test
    fun `patchTask without any field is refused locally`() = runBlocking {
        val result = repository.patchTask(baseUrl(), credential, taskId = "7")
        assertEquals("patch body is empty", (result as KanbanTaskWriteResult.Failed).message)
        assertEquals(0, server.requestCount)
    }

    // ── POST /api/kanban/tasks/{id}/comments ──

    @Test
    fun `addComment posts the text with the android author`() = runBlocking {
        enqueue(body = """{"ok":true,"comment_id":42,"read_only":false}""")
        val result = repository.addComment(baseUrl(), credential, taskId = "7", body = "готово")
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/kanban/tasks/7/comments", recorded.path)
        assertEquals("""{"body":"готово","author":"android"}""", recorded.body.readUtf8())
        assertEquals(42L, (result as KanbanCommentResult.Added).commentId)
    }

    @Test
    fun `addComment refuses an empty text and reports a missing task`() = runBlocking {
        assertEquals(
            "body is required",
            (repository.addComment(baseUrl(), credential, taskId = "7", body = "  ") as KanbanCommentResult.Failed).message,
        )
        assertEquals(0, server.requestCount)

        enqueue(code = 404, body = """{"error":"task not found"}""")
        val missing = repository.addComment(baseUrl(), credential, taskId = "7", body = "текст")
        assertEquals("task not found", (missing as KanbanCommentResult.NotFound).message)
    }

    // ── POST /api/kanban/tasks/{id}/block|unblock ──

    @Test
    fun `setBlocked uses the structured block verb with a reason`() = runBlocking {
        enqueue(body = taskEnvelope(status = "blocked"))
        repository.setBlocked(baseUrl(), credential, taskId = "7", blocked = true, reason = "ждём доступ")
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/kanban/tasks/7/block", recorded.path)
        assertEquals("""{"reason":"ждём доступ"}""", recorded.body.readUtf8())
    }

    @Test
    fun `unblock posts the unblock verb with an empty object`() = runBlocking {
        enqueue(body = taskEnvelope(status = "ready"))
        val result = repository.setBlocked(baseUrl(), credential, taskId = "7", blocked = false)
        val recorded = server.takeRequest()
        assertEquals("/api/kanban/tasks/7/unblock", recorded.path)
        assertEquals("{}", recorded.body.readUtf8())
        assertEquals("ready", (result as KanbanTaskWriteResult.Updated).task.status)
    }

    // ── POST /api/kanban/tasks/bulk ──

    @Test
    fun `bulkUpdate posts ids and one mutation and parses per-id verdicts`() = runBlocking {
        enqueue(
            body = """{"results":[{"id":"1","ok":true},{"id":"2","ok":false,"error":"not found"}],"read_only":false}"""
        )
        val result = repository.bulkUpdate(
            baseUrl(), credential, ids = listOf("1", "2", " 2 "), status = "ready", board = "ops",
        )
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/kanban/tasks/bulk?board=ops", recorded.path)
        assertEquals("""{"ids":["1","2"],"status":"ready"}""", recorded.body.readUtf8())
        val applied = result as KanbanBulkResult.Applied
        assertEquals(2, applied.entries.size)
        assertFalse(applied.allOk)
        assertEquals("2", applied.failures.single().id)
        assertEquals("not found", applied.failures.single().error)
    }

    @Test
    fun `bulkUpdate archive flag travels as a boolean and empty ids are refused locally`() = runBlocking {
        enqueue(body = """{"results":[{"id":"9","ok":true}],"read_only":false}""")
        repository.bulkUpdate(baseUrl(), credential, ids = listOf("9"), archive = true)
        assertEquals("""{"ids":["9"],"archive":true}""", server.takeRequest().body.readUtf8())

        val refused = repository.bulkUpdate(baseUrl(), credential, ids = emptyList())
        assertEquals("ids is required", (refused as KanbanBulkResult.Failed).message)
        assertEquals(1, server.requestCount)
    }

    // ── POST /api/kanban/links ──

    @Test
    fun `linkTasks posts parent and child`() = runBlocking {
        enqueue(body = """{"ok":true,"parent_id":"1","child_id":"2","read_only":false}""")
        val result = repository.linkTasks(baseUrl(), credential, parentId = "1", childId = "2")
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/kanban/links", recorded.path)
        assertEquals("""{"parent_id":"1","child_id":"2"}""", recorded.body.readUtf8())
        assertNull("создание связи не отдаёт changed", (result as KanbanLinkResult.Linked).changed)
    }

    @Test
    fun `linkTasks unlink uses the delete route and reports changed`() = runBlocking {
        enqueue(body = """{"ok":true,"changed":false,"parent_id":"1","child_id":"2","read_only":false}""")
        val result = repository.linkTasks(baseUrl(), credential, parentId = "1", childId = "2", unlink = true)
        assertEquals("/api/kanban/links/delete", server.takeRequest().path)
        assertEquals(false, (result as KanbanLinkResult.Linked).changed)
    }

    @Test
    fun `linkTasks requires both ids and maps a missing endpoint task`() = runBlocking {
        val refused = repository.linkTasks(baseUrl(), credential, parentId = "1", childId = " ")
        assertEquals("parent_id and child_id are required", (refused as KanbanLinkResult.Failed).message)
        assertEquals(0, server.requestCount)

        enqueue(code = 404, body = """{"error":"child task not found"}""")
        val missing = repository.linkTasks(baseUrl(), credential, parentId = "1", childId = "2")
        assertEquals("child task not found", (missing as KanbanLinkResult.NotFound).message)
    }

    // ── Общая лестница ошибок ──

    @Test
    fun `bridge 503 and unparseable bodies degrade to Failed`() = runBlocking {
        enqueue(code = 503, body = """{"error":"kanban unavailable: no module"}""")
        val unavailable = repository.createTask(baseUrl(), credential, title = "T")
        assertEquals(
            "kanban unavailable: no module",
            (unavailable as KanbanTaskWriteResult.Failed).message,
        )

        enqueue(code = 200, body = "<html>not json</html>")
        val garbage = repository.createTask(baseUrl(), credential, title = "T")
        assertTrue(garbage is KanbanTaskWriteResult.Failed)
    }
}
