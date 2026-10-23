package ru.mars.mobile

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.KanbanBoard
import ru.mars.network.model.KanbanBulkEntry
import ru.mars.network.model.KanbanColumn
import ru.mars.network.model.KanbanTask
import ru.mars.network.repository.KanbanBulkResult
import ru.mars.network.repository.KanbanCommentResult
import ru.mars.network.repository.KanbanLinkResult
import ru.mars.network.repository.KanbanStreamFrame
import ru.mars.network.repository.KanbanTaskWriteResult

/**
 * JVM-тесты записи [KanbanPresenter]: каждый метод переводит результат порта в
 * готовый [KanbanActionOutcome], после УСПЕХА перечитывает доску (серверная
 * правда вместо догадки), а отказы не публикуют LOADING и не ломают экран.
 *
 * Порт записи — записывающий fake [WriteFake]: тесты фиксируют, что именно уходит
 * на сервер (доска, статус, список id, причина блокировки) и что локальные
 * проверки (пустое название/комментарий, нет пары) не тратят запрос.
 */
class KanbanPresenterWritesTest {

    private fun task(id: String, status: String = "ready") = KanbanTask(id = id, title = "T$id", status = status)

    private fun board(readOnly: Boolean = false) = KanbanBoard(
        changed = true,
        latestEventId = 7L,
        readOnly = readOnly,
        columns = listOf(KanbanColumn("Ready", statuses = listOf("ready"), tasks = listOf(task("k-1")))),
    )

    /** Fake порта записи: фиксирует аргументы, отдаёт заранее заданный исход. */
    private class WriteFake : KanbanWritePort {
        var taskResult: KanbanTaskWriteResult =
            KanbanTaskWriteResult.Updated(KanbanTask(id = "new", title = "T", status = "ready"))
        var commentResult: KanbanCommentResult = KanbanCommentResult.Added(5L)
        var bulkResult: KanbanBulkResult = KanbanBulkResult.Applied(listOf(KanbanBulkEntry(id = "k-1", ok = true)))
        var linkResult: KanbanLinkResult = KanbanLinkResult.Linked(null)

        val calls = mutableListOf<String>()
        var lastBoard: String? = null
        var lastTitle: String? = null
        var lastBody: String? = null
        var lastAssignee: String? = null
        var lastTaskId: String? = null
        var lastStatus: String? = null
        var lastText: String? = null
        var lastBlocked: Boolean? = null
        var lastReason: String? = null
        var lastIds: List<String> = emptyList()
        var lastArchive: Boolean? = null
        var lastChildId: String? = null

        override suspend fun createTask(board: String?, title: String, body: String?, assignee: String?): KanbanTaskWriteResult {
            calls += "create"
            lastBoard = board
            lastTitle = title
            lastBody = body
            lastAssignee = assignee
            return taskResult
        }

        override suspend fun patchTask(
            board: String?,
            taskId: String,
            status: String?,
            title: String?,
            body: String?,
            assignee: String?,
        ): KanbanTaskWriteResult {
            calls += "patch"
            lastBoard = board
            lastTaskId = taskId
            lastStatus = status
            return taskResult
        }

        override suspend fun addComment(board: String?, taskId: String, text: String): KanbanCommentResult {
            calls += "comment"
            lastTaskId = taskId
            lastText = text
            return commentResult
        }

        override suspend fun setBlocked(
            board: String?,
            taskId: String,
            blocked: Boolean,
            reason: String?,
        ): KanbanTaskWriteResult {
            calls += if (blocked) "block" else "unblock"
            lastTaskId = taskId
            lastBlocked = blocked
            lastReason = reason
            return taskResult
        }

        override suspend fun bulkUpdate(
            board: String?,
            ids: List<String>,
            status: String?,
            archive: Boolean?,
        ): KanbanBulkResult {
            calls += "bulk"
            lastIds = ids
            lastStatus = status
            lastArchive = archive
            return bulkResult
        }

        override suspend fun linkTasks(board: String?, parentId: String, childId: String): KanbanLinkResult {
            calls += "link"
            lastTaskId = parentId
            lastChildId = childId
            return linkResult
        }
    }

    private fun presenter(
        writes: WriteFake,
        hasCredential: Boolean = true,
        readOnly: Boolean = false,
        boardCalls: MutableList<String?> = mutableListOf(),
    ) = KanbanPresenter(
        hasCredential = { hasCredential },
        fetchBoard = { _, board, _, _ ->
            boardCalls += board
            board(readOnly = readOnly)
        },
        fetchStats = { null },
        fetchTaskDetail = { null },
        isOffline = { false },
        nowEpochSeconds = { 2000L },
        fetchBoards = { emptyList() },
        fetchAssignees = { emptyList() },
        fetchConfig = { null },
        events = { _, _ -> emptyList<KanbanStreamFrame>().asFlow() },
        writes = writes,
    )

    // ── createTask ──

    @Test
    fun `createTask trims the title and refreshes the board on success`() = runBlocking {
        val writes = WriteFake()
        val boards = mutableListOf<String?>()
        val presenter = presenter(writes, boardCalls = boards)
        presenter.load()
        val before = boards.size

        val outcome = presenter.createTask("  Починить  ", "  описание  ", "  ")

        assertTrue(outcome.ok)
        assertEquals(KanbanPolicy.MSG_TASK_CREATED, outcome.message)
        assertEquals("create", writes.calls.single())
        assertEquals("Починить", writes.lastTitle)
        assertEquals("описание", writes.lastBody)
        assertNull("пробелы вместо исполнителя — не исполнитель", writes.lastAssignee)
        assertEquals("успех обязан перечитать доску", before + 1, boards.size)
    }

    @Test
    fun `createTask refuses a blank title and never reaches the port`() = runBlocking {
        val writes = WriteFake()
        val presenter = presenter(writes)
        val outcome = presenter.createTask("   ", null, null)
        assertFalse(outcome.ok)
        assertEquals(KanbanPolicy.MSG_TITLE_REQUIRED, outcome.message)
        assertTrue(writes.calls.isEmpty())
    }

    @Test
    fun `write methods answer no-auth without a credential`() = runBlocking {
        val writes = WriteFake()
        val presenter = presenter(writes, hasCredential = false)
        assertEquals(KanbanPolicy.MSG_NO_AUTH, presenter.createTask("T", null, null).message)
        assertEquals(KanbanPolicy.MSG_NO_AUTH, presenter.moveTask("k-1", "done").message)
        assertEquals(KanbanPolicy.MSG_NO_AUTH, presenter.comment("k-1", "текст").message)
        assertEquals(KanbanPolicy.MSG_NO_AUTH, presenter.setBlocked("k-1", blocked = true).message)
        assertEquals(KanbanPolicy.MSG_NO_AUTH, presenter.linkTasks("k-1", "k-2").message)
        assertTrue(writes.calls.isEmpty())
    }

    // ── moveTask / patchTask ──

    @Test
    fun `moveTask patches only the status and refreshes`() = runBlocking {
        val writes = WriteFake()
        val boards = mutableListOf<String?>()
        val presenter = presenter(writes, boardCalls = boards)
        presenter.load()
        val before = boards.size

        val outcome = presenter.moveTask("k-1", "done")

        assertTrue(outcome.ok)
        assertEquals(KanbanPolicy.MSG_TASK_MOVED, outcome.message)
        assertEquals("k-1", writes.lastTaskId)
        assertEquals("done", writes.lastStatus)
        assertEquals(before + 1, boards.size)
    }

    @Test
    fun `moveTask translates a vanished card into a readable message`() = runBlocking {
        val writes = WriteFake().apply {
            taskResult = KanbanTaskWriteResult.NotFound("task not found")
        }
        val presenter = presenter(writes)
        val outcome = presenter.moveTask("gone", "done")
        assertFalse(outcome.ok)
        assertEquals(KanbanPolicy.MSG_TASK_GONE, outcome.message)
    }

    @Test
    fun `moveTask passes an unknown bridge refusal through as is`() = runBlocking {
        val writes = WriteFake().apply {
            taskResult = KanbanTaskWriteResult.Failed("cannot move a claimed task")
        }
        val presenter = presenter(writes)
        val outcome = presenter.moveTask("k-1", "ready")
        assertFalse(outcome.ok)
        assertEquals("cannot move a claimed task", outcome.message)
    }

    @Test
    fun `a throwing port degrades to a readable failure instead of crashing`() = runBlocking {
        val writes = object : KanbanWritePort by WriteFake() {
            override suspend fun patchTask(
                board: String?,
                taskId: String,
                status: String?,
                title: String?,
                body: String?,
                assignee: String?,
            ): KanbanTaskWriteResult = throw IllegalStateException("socket closed")
        }
        val presenter = KanbanPresenter(
            hasCredential = { true },
            fetchBoard = { _, _, _, _ -> board() },
            fetchStats = { null },
            fetchTaskDetail = { null },
            isOffline = { false },
            nowEpochSeconds = { 2000L },
            fetchBoards = { emptyList() },
            fetchAssignees = { emptyList() },
            fetchConfig = { null },
            events = { _, _ -> emptyList<KanbanStreamFrame>().asFlow() },
            writes = writes,
        )
        val outcome = presenter.moveTask("k-1", "done")
        assertFalse(outcome.ok)
        assertEquals("socket closed", outcome.message)
    }

    // ── comment ──

    @Test
    fun `comment sends the text and confirms`() = runBlocking {
        val writes = WriteFake()
        val presenter = presenter(writes)
        val outcome = presenter.comment("k-1", "  готово  ")
        assertTrue(outcome.ok)
        assertEquals(KanbanPolicy.MSG_COMMENT_ADDED, outcome.message)
        assertEquals("готово", writes.lastText)
    }

    @Test
    fun `comment refuses an empty text and a vanished task differently`() = runBlocking {
        val writes = WriteFake()
        val presenter = presenter(writes)
        val empty = presenter.comment("k-1", "   ")
        assertFalse(empty.ok)
        assertEquals(KanbanPolicy.MSG_COMMENT_REQUIRED, empty.message)
        assertTrue(writes.calls.isEmpty())

        writes.commentResult = KanbanCommentResult.NotFound("task not found")
        val gone = presenter.comment("gone", "текст")
        assertFalse(gone.ok)
        assertEquals(KanbanPolicy.MSG_TASK_GONE, gone.message)
    }

    // ── block / unblock ──

    @Test
    fun `setBlocked picks the verb and keeps the reason`() = runBlocking {
        val writes = WriteFake()
        val presenter = presenter(writes)

        val blocked = presenter.setBlocked("k-1", blocked = true, reason = "  нет доступа  ")
        assertTrue(blocked.ok)
        assertEquals(KanbanPolicy.MSG_TASK_BLOCKED, blocked.message)
        assertEquals(listOf("block"), writes.calls)
        assertEquals("нет доступа", writes.lastReason)

        val unblocked = presenter.setBlocked("k-1", blocked = false)
        assertTrue(unblocked.ok)
        assertEquals(KanbanPolicy.MSG_TASK_UNBLOCKED, unblocked.message)
        assertEquals(listOf("block", "unblock"), writes.calls)
        assertNull("unblock не несёт причину", writes.lastReason)
    }

    // ── link ──

    @Test
    fun `linkTasks confirms a new link and reports a no-op unlink honestly`() = runBlocking {
        val writes = WriteFake()
        val presenter = presenter(writes)

        val linked = presenter.linkTasks("k-1", "  k-2  ")
        assertTrue(linked.ok)
        assertEquals(KanbanPolicy.MSG_LINK_ADDED, linked.message)
        assertEquals("k-2", writes.lastChildId)

        writes.linkResult = KanbanLinkResult.Linked(changed = false)
        val noop = presenter.linkTasks("k-1", "k-2")
        assertTrue(noop.ok)
        assertEquals(KanbanPolicy.MSG_LINK_GONE, noop.message)
    }

    @Test
    fun `linkTasks refuses a blank child id locally`() = runBlocking {
        val writes = WriteFake()
        val presenter = presenter(writes)
        val outcome = presenter.linkTasks("k-1", "   ")
        assertFalse(outcome.ok)
        assertEquals(KanbanPolicy.MSG_LINK_IDS_REQUIRED, outcome.message)
        assertTrue(writes.calls.isEmpty())
    }

    // ── bulk ──

    @Test
    fun `archiveTasks sends one bulk archive and reports the applied count`() = runBlocking {
        val writes = WriteFake().apply {
            bulkResult = KanbanBulkResult.Applied(
                listOf(KanbanBulkEntry("k-1", true), KanbanBulkEntry("k-2", true)),
            )
        }
        val presenter = presenter(writes)
        val outcome = presenter.archiveTasks(listOf("k-1", "k-2"))
        assertTrue(outcome.ok)
        assertEquals(KanbanPolicy.bulkDoneLabel(2), outcome.message)
        assertEquals(true, writes.lastArchive)
        assertEquals(listOf("k-1", "k-2"), writes.lastIds)
    }

    @Test
    fun `moveTasks reports a partial failure but still counts the applied cards`() = runBlocking {
        val writes = WriteFake().apply {
            bulkResult = KanbanBulkResult.Applied(
                listOf(KanbanBulkEntry("k-1", true), KanbanBulkEntry("k-2", false, "not found")),
            )
        }
        val presenter = presenter(writes)
        val outcome = presenter.moveTasks(listOf("k-1", "k-2"), "done")
        assertTrue("частичный успех — не отказ: карточки реально переехали", outcome.ok)
        assertEquals("done", writes.lastStatus)
        assertTrue(outcome.message!!.startsWith(KanbanPolicy.bulkDoneLabel(1)))
        assertTrue(outcome.message!!.contains(KanbanPolicy.MSG_BULK_PARTIAL))
        assertTrue(outcome.message!!.contains(KanbanPolicy.MSG_TASK_GONE))
    }

    @Test
    fun `a fully failed batch is an error and an empty selection never reaches the port`() = runBlocking {
        val writes = WriteFake().apply {
            bulkResult = KanbanBulkResult.Applied(listOf(KanbanBulkEntry("k-1", false, "not found")))
        }
        val presenter = presenter(writes)
        val failed = presenter.archiveTasks(listOf("k-1"))
        assertFalse(failed.ok)

        writes.calls.clear()
        val empty = presenter.archiveTasks(emptyList())
        assertFalse(empty.ok)
        assertEquals(KanbanPolicy.MSG_SELECTION_EMPTY, empty.message)
        assertTrue(writes.calls.isEmpty())
    }

    @Test
    fun `bulk port failure surfaces the bridge message`() = runBlocking {
        val writes = WriteFake().apply {
            bulkResult = KanbanBulkResult.Failed("kanban unavailable: no module")
        }
        val presenter = presenter(writes)
        val outcome = presenter.moveTasks(listOf("k-1"), "done")
        assertFalse(outcome.ok)
        assertEquals("kanban unavailable: no module", outcome.message)
    }
}
