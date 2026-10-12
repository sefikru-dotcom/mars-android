package ru.mars.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.KanbanBoard
import ru.mars.network.model.KanbanColumn
import ru.mars.network.model.KanbanStats
import ru.mars.network.model.KanbanTask
import ru.mars.network.model.KanbanTaskDetail

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

    /** Записывающий fake: ответы board по очереди (последний повторяется), история since-аргументов. */
    private class BoardFake(var responses: List<KanbanBoard?>) {
        val sinceCalls = mutableListOf<Long?>()
        suspend fun next(since: Long?): KanbanBoard? {
            sinceCalls += since
            if (responses.isEmpty()) return null
            val head = responses.first()
            if (responses.size > 1) responses = responses.drop(1)
            return head
        }
    }

    private fun presenter(
        boardFake: BoardFake,
        hasCredential: Boolean = true,
        statsResponse: KanbanStats? = stats,
        detailResponse: KanbanTaskDetail? = null,
        offline: Boolean = false,
    ) = KanbanPresenter(
        hasCredential = { hasCredential },
        fetchBoard = { since -> boardFake.next(since) },
        fetchStats = { statsResponse },
        fetchTaskDetail = { detailResponse },
        isOffline = { offline },
        nowEpochSeconds = { 2000L },
    )

    private fun emissions(p: KanbanPresenter): MutableList<KanbanUiState> {
        val seen = mutableListOf<KanbanUiState>()
        p.subscribe { seen += it }
        return seen
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
