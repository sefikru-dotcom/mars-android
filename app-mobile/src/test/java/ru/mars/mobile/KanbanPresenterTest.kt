package ru.mars.mobile

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.AssigneeStat
import ru.mars.network.model.BoardSummary
import ru.mars.network.model.KanbanBoard
import ru.mars.network.model.KanbanColumn
import ru.mars.network.model.KanbanConfig
import ru.mars.network.model.KanbanStats
import ru.mars.network.model.KanbanTask
import ru.mars.network.model.KanbanTaskDetail
import ru.mars.network.model.KanbanTaskEvent
import ru.mars.network.repository.KanbanStreamFrame

/**
 * Чистые JVM-тесты [KanbanPresenter]: фазы, offline-safe состояния,
 * best-effort статистика и — главное — контракт polling-конверта
 * (`changed:false` никогда не рендерится пустой доской и не мигает LOADING).
 *
 * Контракт read-only: у презентера нет и не тестируется никаких операций
 * create/edit/move/delete/dispatch — только GET-порты.
 */
class KanbanPresenterTest {

    private fun task(id: String, status: String = "ready", title: String = "T$id") =
        KanbanTask(id = id, title = title, status = status)

    private fun board(
        columns: List<KanbanColumn> = listOf(KanbanColumn("Ready", tasks = listOf(task("k-1"), task("k-2")))),
        changed: Boolean = true,
        latestEventId: Long = 7L,
        readOnly: Boolean = true,
    ) = KanbanBoard(changed = changed, latestEventId = latestEventId, readOnly = readOnly, columns = columns)

    private val stats = KanbanStats(byStatus = mapOf("ready" to 2), oldestReadyAgeSeconds = 120L)

    /** Записывающий fake: ответы board по очереди (последний повторяется), история аргументов. */
    private class BoardFake(var responses: List<KanbanBoard?>) {
        val sinceCalls = mutableListOf<Long?>()
        val boardCalls = mutableListOf<String?>()
        val assigneeCalls = mutableListOf<String?>()
        val includeArchivedCalls = mutableListOf<Boolean>()
        suspend fun next(
            since: Long?,
            board: String? = null,
            assignee: String? = null,
            includeArchived: Boolean = false,
        ): KanbanBoard? {
            sinceCalls += since
            boardCalls += board
            assigneeCalls += assignee
            includeArchivedCalls += includeArchived
            if (responses.isEmpty()) return null
            val head = responses.first()
            if (responses.size > 1) responses = responses.drop(1)
            return head
        }
    }

    /** Записывающий fake конфига `/api/kanban/config`: фиксированный ответ + история board-аргументов. */
    private class ConfigFake(var response: KanbanConfig?) {
        val boardCalls = mutableListOf<String?>()
        var calls = 0
            private set
        suspend fun next(board: String?): KanbanConfig? {
            calls += 1
            boardCalls += board
            return response
        }
    }

    private fun presenter(
        boardFake: BoardFake,
        hasCredential: Boolean = true,
        statsResponse: KanbanStats? = stats,
        detailResponse: KanbanTaskDetail? = null,
        offline: Boolean = false,
        boards: List<BoardSummary> = emptyList(),
        assignees: List<AssigneeStat> = emptyList(),
        frames: List<KanbanStreamFrame> = emptyList(),
        configFake: ConfigFake = ConfigFake(null),
    ) = KanbanPresenter(
        hasCredential = { hasCredential },
        fetchBoard = { since, board, assignee, includeArchived ->
            boardFake.next(since, board, assignee, includeArchived)
        },
        fetchStats = { statsResponse },
        fetchTaskDetail = { detailResponse },
        isOffline = { offline },
        nowEpochSeconds = { 2000L },
        fetchBoards = { boards },
        fetchAssignees = { assignees },
        fetchConfig = { board -> configFake.next(board) },
        events = { _, _ -> frames.asFlow() },
    )

    private fun emissions(p: KanbanPresenter): MutableList<KanbanUiState> {
        val seen = mutableListOf<KanbanUiState>()
        p.subscribe { seen += it }
        return seen
    }

    // ── A11: переключатель досок ──

    private val twoBoards = listOf(
        BoardSummary(slug = "default", name = "Default", isCurrent = true, counts = mapOf("done" to 2), total = 2),
        BoardSummary(slug = "work", name = "Работа", counts = mapOf("ready" to 1), total = 1),
    )

    @Test
    fun `boards become chips and the current one is marked`() = runBlocking {
        val p = presenter(BoardFake(listOf(board())), boards = twoBoards)
        p.load()
        assertEquals(listOf("default", "work"), p.state.boards.map { it.slug })
        assertTrue(p.state.boards.first().isCurrent)
        assertFalse(p.state.boards.last().isCurrent)
        assertEquals("2 задачи", p.state.boards.first().countLabel)
        Unit
    }

    @Test
    fun `a board without a name is labelled by its slug`() = runBlocking {
        val p = presenter(
            BoardFake(listOf(board())),
            boards = listOf(BoardSummary(slug = "sprint", name = "")),
        )
        p.load()
        assertEquals("sprint", p.state.boards.single().label)
        Unit
    }

    @Test
    fun `an old bridge without boards leaves the switcher hidden`() = runBlocking {
        val p = presenter(BoardFake(listOf(board())), boards = emptyList())
        p.load()
        assertTrue(p.state.boards.isEmpty())
        assertEquals(KanbanPhase.READY, p.state.phase)
        Unit
    }

    @Test
    fun `selecting a board re-fetches with the slug resetting the cursor`() = runBlocking {
        val fake = BoardFake(listOf(board(latestEventId = 7), board()))
        val p = presenter(fake, boards = twoBoards)
        p.load()
        fake.sinceCalls.clear()
        fake.boardCalls.clear()
        p.selectBoard("work")
        assertEquals(listOf("work"), fake.boardCalls)
        // Курсор обнуляется: события прежней доски к новой не относятся.
        assertEquals(listOf(null), fake.sinceCalls)
        assertEquals("work", p.state.currentBoardSlug)
        Unit
    }

    @Test
    fun `selecting the same board is a no-op`() = runBlocking {
        val fake = BoardFake(listOf(board()))
        val p = presenter(fake, boards = twoBoards)
        p.load()
        fake.boardCalls.clear()
        p.selectBoard(null) // null и есть текущее «как решит сервер»
        assertTrue(fake.boardCalls.isEmpty())
        Unit
    }

    @Test
    fun `refresh forwards the selected board to the repository`() = runBlocking {
        val fake = BoardFake(listOf(board(), board(), board()))
        val p = presenter(fake, boards = twoBoards)
        p.load()
        p.selectBoard("work")
        fake.boardCalls.clear()
        p.refresh()
        assertEquals(listOf("work"), fake.boardCalls)
        Unit
    }

    // ── A11: фильтр исполнителей ──

    private val twoAssignees = listOf(
        AssigneeStat(name = "default", onDisk = true, counts = mapOf("done" to 3)),
        AssigneeStat(name = "office", onDisk = false, counts = mapOf("ready" to 1)),
    )

    @Test
    fun `assignee chips start with an all-filter and mark the selection`() = runBlocking {
        val p = presenter(BoardFake(listOf(board())), assignees = twoAssignees)
        p.load()
        assertEquals(listOf("Все", "default", "office"), p.state.assignees.map { it.label })
        assertTrue(p.state.assignees.first().isSelected)
        assertEquals("3 задачи", p.state.assignees[1].countLabel)
        Unit
    }

    @Test
    fun `board-provided assignees win over the global directory`() = runBlocking {
        // У доски есть свой список: внешний справочник содержит чужих для неё имён.
        val p = presenter(
            BoardFake(listOf(board().copy(assignees = listOf("only-me")))),
            assignees = twoAssignees,
        )
        p.load()
        assertEquals(listOf("Все", "only-me"), p.state.assignees.map { it.label })
        Unit
    }

    @Test
    fun `selecting an assignee filters the board request`() = runBlocking {
        val fake = BoardFake(listOf(board(), board()))
        val p = presenter(fake, assignees = twoAssignees)
        p.load()
        fake.assigneeCalls.clear()
        p.selectAssignee("office")
        assertEquals(listOf("office"), fake.assigneeCalls)
        assertEquals("office", p.state.selectedAssignee)
        Unit
    }

    @Test
    fun `clearing the filter drops the assignee parameter`() = runBlocking {
        val fake = BoardFake(listOf(board(), board(), board()))
        val p = presenter(fake, assignees = twoAssignees)
        p.load()
        p.selectAssignee("office")
        fake.assigneeCalls.clear()
        p.selectAssignee(null)
        assertEquals(listOf(null), fake.assigneeCalls)
        assertEquals(null, p.state.selectedAssignee)
        Unit
    }

    @Test
    fun `a single assignee or no assignees keep the filter row hidden`() = runBlocking {
        val p = presenter(BoardFake(listOf(board())), assignees = listOf(twoAssignees.first()))
        p.load()
        assertTrue(p.state.assignees.isEmpty())
        Unit
    }

    // ── Конфиг доски: GET /api/kanban/config ──

    @Test
    fun `include_archived_by_default flows into every board request`() = runBlocking {
        val fake = BoardFake(listOf(board(), board()))
        val config = ConfigFake(KanbanConfig(includeArchivedByDefault = true))
        val p = presenter(fake, configFake = config)
        p.load()
        assertEquals(listOf(true), fake.includeArchivedCalls)
        // Polling-refresh идёт с тем же флагом из кэша конфига.
        p.refresh()
        assertEquals(listOf(true, true), fake.includeArchivedCalls)
        Unit
    }

    @Test
    fun `without config the board is requested unarchived and stays in server order`() = runBlocking {
        val fake = BoardFake(listOf(board()))
        val p = presenter(fake) // ConfigFake(null) — старый бридж или отказ
        p.load()
        assertEquals(listOf(false), fake.includeArchivedCalls)
        assertEquals(listOf("Ready"), p.state.columns.map { it.name })
        assertEquals(KanbanPhase.READY, p.state.phase)
        Unit
    }

    @Test
    fun `polling refresh reuses the cached config without refetching it`() = runBlocking {
        val fake = BoardFake(listOf(board(), board()))
        val config = ConfigFake(KanbanConfig(columns = listOf("ready")))
        val p = presenter(fake, configFake = config)
        p.load()
        assertEquals(1, config.calls)
        p.refresh()
        assertEquals("Конфиг перечитывается только полной загрузкой/переключением", 1, config.calls)
        Unit
    }

    @Test
    fun `board switch refetches the config for the new board`() = runBlocking {
        val config = ConfigFake(KanbanConfig(columns = listOf("ready")))
        val p = presenter(BoardFake(listOf(board(), board())), boards = twoBoards, configFake = config)
        p.load()
        p.selectBoard("work")
        assertEquals(listOf(null, "work"), config.boardCalls)
        Unit
    }

    @Test
    fun `config columns reorder board columns and unknown lanes go last`() = runBlocking {
        val lanes = KanbanBoard(
            columns = listOf(
                KanbanColumn("new", tasks = listOf(task("k-1"))),
                KanbanColumn("running", tasks = listOf(task("k-2"))),
                KanbanColumn("done", tasks = listOf(task("k-3"))),
                KanbanColumn("archived", tasks = listOf(task("k-4"))),
            )
        )
        val config = ConfigFake(KanbanConfig(columns = listOf("done", "new")))
        val p = presenter(BoardFake(listOf(lanes)), configFake = config)
        p.load()
        // Канонический порядок конфига; неизвестные ему lanes — хвостом в серверном порядке.
        assertEquals(listOf("done", "new", "running", "archived"), p.state.columns.map { it.name })
        assertEquals(4, p.state.taskCount)
        Unit
    }

    @Test
    fun `a board without columns gets the configured skeleton instead of EMPTY`() = runBlocking {
        // Паритет с WebUI: пустой board-конверт + config.columns → пустые lane-колонки.
        val config = ConfigFake(KanbanConfig(columns = listOf("new", "waiting", "done")))
        val p = presenter(BoardFake(listOf(board(columns = emptyList()))), configFake = config)
        p.load()
        assertEquals(KanbanPhase.READY, p.state.phase)
        assertEquals(listOf("new", "waiting", "done"), p.state.columns.map { it.name })
        assertTrue(p.state.columns.all { it.count == 0 && it.cards.isEmpty() })
        assertEquals(0, p.state.taskCount)
        Unit
    }

    // ── A11: живой поток ──

    @Test
    fun `hello frame marks the screen live`() = runBlocking {
        val p = presenter(BoardFake(listOf(board())))
        p.load()
        p.applyStreamFrame(KanbanStreamFrame.Hello(cursor = 3L, board = "default"))
        assertTrue(p.state.live)
        Unit
    }

    @Test
    fun `an events frame triggers a board refresh`() = runBlocking {
        val fake = BoardFake(listOf(board(), board()))
        val p = presenter(fake)
        p.load()
        fake.sinceCalls.clear()
        p.applyStreamFrame(
            KanbanStreamFrame.Events(
                events = listOf(KanbanTaskEvent(id = 9, taskId = "t1", kind = "updated")),
                cursor = 9L,
            )
        )
        assertTrue(fake.sinceCalls.isNotEmpty())
        Unit
    }

    @Test
    fun `an empty events batch does not refetch`() = runBlocking {
        // Пулл может отдать пустой батч (сработал keep-alive): перечитывать нечего.
        val fake = BoardFake(listOf(board(), board()))
        val p = presenter(fake)
        p.load()
        fake.sinceCalls.clear()
        p.applyStreamFrame(KanbanStreamFrame.Events(events = emptyList(), cursor = 4L))
        assertTrue(fake.sinceCalls.isEmpty())
        Unit
    }

    @Test
    fun `an unknown frame changes nothing`() = runBlocking {
        val p = presenter(BoardFake(listOf(board())))
        p.load()
        val before = p.state
        p.applyStreamFrame(KanbanStreamFrame.Unknown(name = "future_kind"))
        assertEquals(before, p.state)
        Unit
    }

    @Test
    fun `an ended stream degrades to polling without an error phase`() = runBlocking {
        val p = presenter(BoardFake(listOf(board())), frames = emptyList())
        p.load()
        p.bindLiveStream(this)
        // Поток закрылся: live снят, но данные и фаза READY остались на экране.
        assertFalse(p.state.live)
        assertEquals(KanbanPhase.READY, p.state.phase)
        Unit
    }

    @Test
    fun `initial state is loading with message`() {
        val p = presenter(BoardFake(emptyList()))
        assertEquals(KanbanPhase.LOADING, p.state.phase)
        assertEquals(KanbanPolicy.MSG_LOADING, p.state.message)
    }

    @Test
    fun `without credential load stops at no-auth and never fetches`() = runBlocking {
        val fake = BoardFake(listOf(board()))
        val p = presenter(fake, hasCredential = false)
        emissions(p)
        p.load()
        assertEquals(KanbanPhase.NO_AUTH, p.state.phase)
        assertEquals(KanbanPolicy.MSG_NO_AUTH, p.state.message)
        assertTrue(fake.sinceCalls.isEmpty())
    }

    @Test
    fun `successful load publishes ready board with stats and cursor`() = runBlocking {
        val fake = BoardFake(listOf(board()))
        val p = presenter(fake)
        val seen = emissions(p)
        p.load()

        assertEquals(KanbanPhase.READY, p.state.phase)
        assertEquals(listOf("Ready"), p.state.columns.map { it.name })
        assertEquals(2, p.state.taskCount)
        assertEquals(2, p.state.columns.single().count)
        assertTrue(p.state.serverReadOnly)
        assertEquals(7L, p.state.latestEventId)
        assertNull(p.state.message)
        // Stats best-effort прикреплены к READY.
        assertNotNull(p.state.stats)
        val chip = p.state.stats!!.chips.single()
        assertEquals("ready", chip.label)
        assertEquals(2, chip.value)
        // Первая загрузка: since = null (полный конверт).
        assertEquals(listOf(null), fake.sinceCalls)
        // Траектория: реплей LOADING -> LOADING -> READY(->stats-уточнение).
        assertTrue(seen.any { it.phase == KanbanPhase.LOADING })
        assertTrue(seen.last().phase == KanbanPhase.READY)
    }

    @Test
    fun `stats failure does not sink the board`() = runBlocking {
        val p = presenter(BoardFake(listOf(board())), statsResponse = null)
        p.load()
        assertEquals(KanbanPhase.READY, p.state.phase)
        assertNull(p.state.stats)
        assertEquals(1, p.state.columns.size)
    }

    @Test
    fun `board failure without cache is retryable error with offline text`() = runBlocking {
        val online = presenter(BoardFake(listOf(null)))
        online.load()
        assertEquals(KanbanPhase.ERROR, online.state.phase)
        assertEquals(KanbanPolicy.MSG_ERROR, online.state.message)
        assertTrue(online.state.canRetry)
        assertNull(online.state.notice)

        val off = presenter(BoardFake(listOf(null)), offline = true)
        off.load()
        assertEquals(KanbanPhase.ERROR, off.state.phase)
        assertEquals(KanbanPolicy.MSG_ERROR_OFFLINE, off.state.message)
        assertEquals(KanbanPolicy.NOTICE_OFFLINE, off.state.notice)
        assertTrue(off.state.canRetry)
    }

    @Test
    fun `board failure with cache keeps stale columns offline-safe`() = runBlocking {
        val fake = BoardFake(listOf(board(), null))
        val p = presenter(fake, offline = true)
        p.load() // READY с кэшем
        p.refresh() // сеть пропала
        assertEquals(KanbanPhase.ERROR, p.state.phase)
        assertEquals(listOf("Ready"), p.state.columns.map { it.name }) // доска не опустела
        assertEquals(KanbanPolicy.NOTICE_STALE, p.state.notice)
        assertNull(p.state.message)
        assertTrue(p.state.canRetry)
        assertEquals(7L, p.state.latestEventId) // курсор пережил отказ
    }

    @Test
    fun `empty changed-true board is EMPTY phase not error`() = runBlocking {
        val p = presenter(BoardFake(listOf(board(columns = emptyList()))))
        p.load()
        assertEquals(KanbanPhase.EMPTY, p.state.phase)
        assertEquals(KanbanPolicy.MSG_EMPTY, p.state.message)
        assertFalse(p.state.canRetry)
    }

    @Test
    fun `defective changed-false envelope without cache degrades to EMPTY never crash`() = runBlocking {
        val p = presenter(BoardFake(listOf(board(changed = false, latestEventId = 3L))))
        p.load()
        assertEquals(KanbanPhase.EMPTY, p.state.phase)
        assertEquals(KanbanPolicy.MSG_EMPTY, p.state.message)
        assertEquals(3L, p.state.latestEventId)
    }

    @Test
    fun `polling refresh passes since cursor and changed-false keeps board without flicker`() = runBlocking {
        val short = board(columns = emptyList(), changed = false, latestEventId = 12L)
        val fake = BoardFake(listOf(board(), short))
        val p = presenter(fake)
        val seen = emissions(p)
        p.load()
        val readyState = p.state
        seen.clear()

        p.refresh()

        // since = курсор последнего полного конверта.
        assertEquals(listOf(null, 7L), fake.sinceCalls)
        // Короткий конверт: колонки прежние, LOADING не публиковался вовсе.
        assertEquals(readyState.columns, p.state.columns)
        assertEquals(KanbanPhase.READY, p.state.phase)
        assertTrue(seen.none { it.phase == KanbanPhase.LOADING })
        // Курсор обновлён — следующий polling пойдёт с 12.
        assertEquals(12L, p.state.latestEventId)

        seen.clear()
        p.refresh() // снова changed:false, курсор прежний (12)
        assertTrue(seen.isEmpty()) // полная тишина: ничего не изменилось
        assertEquals(12L, p.state.latestEventId)
    }

    @Test
    fun `polling refresh with changed-true re-renders the board`() = runBlocking {
        val next = board(
            columns = listOf(KanbanColumn("Done", tasks = listOf(task("k-5", status = "done")))),
            latestEventId = 20L,
        )
        val fake = BoardFake(listOf(board(), next))
        val p = presenter(fake)
        p.load()
        p.refresh()
        assertEquals(KanbanPhase.READY, p.state.phase)
        assertEquals(listOf("Done"), p.state.columns.map { it.name })
        assertEquals(20L, p.state.latestEventId)
        assertEquals(1, p.state.taskCount)
    }

    @Test
    fun `refresh without credential is a no-op`() = runBlocking {
        val fake = BoardFake(listOf(board()))
        val noAuth = presenter(fake, hasCredential = false)
        noAuth.refresh()
        assertTrue(fake.sinceCalls.isEmpty())
        assertEquals(KanbanPhase.LOADING, noAuth.state.phase)
    }

    @Test
    fun `task detail returns read-only ui model with clock and null on failure`() = runBlocking {
        val detail = KanbanTaskDetail(
            task = task("k-1", status = "running"),
            comments = listOf(
                ru.mars.network.model.KanbanComment(id = 1, taskId = "k-1", author = "mars", body = "привет", createdAt = 1000L)
            ),
        )
        val ok = presenter(BoardFake(listOf(board())), detailResponse = detail)
        val ui = ok.loadTaskDetail("k-1")
        assertNotNull(ui)
        assertEquals("k-1", ui!!.id)
        assertEquals("16 мин", ui.comments.single().whenLabel) // now=2000, createdAt=1000

        val broken = presenter(BoardFake(listOf(board())), detailResponse = null)
        assertNull(broken.loadTaskDetail("k-1"))

        val noAuth = presenter(BoardFake(listOf(board())), hasCredential = false, detailResponse = detail)
        assertNull(noAuth.loadTaskDetail("k-1"))
    }

    @Test
    fun `reload after error recovers to ready`() = runBlocking {
        val fake = BoardFake(listOf(null, board()))
        val p = presenter(fake)
        p.load()
        assertEquals(KanbanPhase.ERROR, p.state.phase)
        p.load()
        assertEquals(KanbanPhase.READY, p.state.phase)
        assertNull(p.state.message)
    }
}
