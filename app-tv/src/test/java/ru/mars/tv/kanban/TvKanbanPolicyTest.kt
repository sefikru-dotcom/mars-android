package ru.mars.tv.kanban

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.KanbanBoard
import ru.mars.network.model.KanbanColumn
import ru.mars.network.model.KanbanStats
import ru.mars.network.model.KanbanTask

/**
 * Unit-тесты [TvKanbanPolicy] без Android-фреймворка: тон статуса, подписи
 * карточки (mobile `KanbanPolicy` parity), группировка колонок, сводка
 * статистики и пустые состояния. Решения — чистая функция от wire-моделей.
 */
class TvKanbanPolicyTest {

    private fun task(
        id: String = "t1",
        title: String = "Починить CI",
        status: String = "running",
        assignee: String? = "mars",
        priority: Int = 0,
        ageSeconds: Long? = null,
        commentCount: Int = 0,
        childCount: Int = 0,
        blockKind: String? = null,
        goalMode: Boolean = false,
    ) = KanbanTask(
        id = id,
        title = title,
        status = status,
        assignee = assignee,
        priority = priority,
        ageSeconds = ageSeconds,
        commentCount = commentCount,
        childCount = childCount,
        blockKind = blockKind,
        goalMode = goalMode,
    )

    // ── тон статуса ──

    @Test
    fun `status tones map canonical statuses and never invent a tone for unknown ones`() {
        assertEquals(TvKanbanPolicy.StatusToken.READY, TvKanbanPolicy.toneFor("ready"))
        assertEquals(TvKanbanPolicy.StatusToken.READY, TvKanbanPolicy.toneFor("QUEUED"))
        assertEquals(TvKanbanPolicy.StatusToken.RUNNING, TvKanbanPolicy.toneFor("running"))
        assertEquals(TvKanbanPolicy.StatusToken.RUNNING, TvKanbanPolicy.toneFor("in_progress"))
        assertEquals(TvKanbanPolicy.StatusToken.BLOCKED, TvKanbanPolicy.toneFor("blocked"))
        assertEquals(TvKanbanPolicy.StatusToken.DONE, TvKanbanPolicy.toneFor("done"))
        assertEquals(TvKanbanPolicy.StatusToken.NEUTRAL, TvKanbanPolicy.toneFor("whatever"))
        assertEquals(TvKanbanPolicy.StatusToken.NEUTRAL, TvKanbanPolicy.toneFor(""))
    }

    // ── возраст ──

    @Test
    fun `age label degrades minutes to hours to days and clamps clock skew`() {
        assertNull(TvKanbanPolicy.ageLabel(null))
        assertEquals("только что", TvKanbanPolicy.ageLabel(30))
        assertEquals("только что", TvKanbanPolicy.ageLabel(-5)) // рассинхрон часов
        assertEquals("5 мин", TvKanbanPolicy.ageLabel(5 * 60))
        assertEquals("3 ч", TvKanbanPolicy.ageLabel(3 * 3600))
        assertEquals("2 дн", TvKanbanPolicy.ageLabel(2 * 24 * 3600))
    }

    // ── карточка ──

    @Test
    fun `card labels are assembled from real facts only`() {
        val card = TvKanbanPolicy.cardFor(
            task(assignee = null, priority = 2, ageSeconds = 3600, commentCount = 3, childCount = 1),
            fallbackKey = "#0-0",
        )
        assertEquals("t1", card.key)
        assertEquals("Починить CI", card.title)
        assertEquals("running", card.statusLabel)
        assertEquals(TvKanbanPolicy.StatusToken.RUNNING, card.token)
        assertEquals("без исполнителя · 1 ч · P2", card.metaLine)
        assertEquals("3 комм. · 1 подзадач", card.badgesLine)
    }

    @Test
    fun `blocked task reads BLOCKED regardless of its status and carries the badge`() {
        val card = TvKanbanPolicy.cardFor(task(status = "running", blockKind = "review"), "#0-0")
        assertEquals(TvKanbanPolicy.StatusToken.BLOCKED, card.token)
        assertEquals("блокировка", card.badgesLine)
    }

    @Test
    fun `goal mode is badged, zero priority is not noise`() {
        val card = TvKanbanPolicy.cardFor(task(goalMode = true, priority = 0), "#0-0")
        assertEquals("goal", card.badgesLine)
        assertNull(TvKanbanPolicy.priorityLabel(0))
        assertTrue(card.metaLine.endsWith("mars"))
    }

    @Test
    fun `blank id falls back to a stable positional key, blank title and status read honestly`() {
        val card = TvKanbanPolicy.cardFor(task(id = " ", title = "  ", status = ""), "#1-2")
        assertEquals("#1-2", card.key)
        assertEquals(TvKanbanPolicy.NO_TITLE_LABEL, card.title)
        assertEquals(TvKanbanPolicy.STATUS_UNKNOWN_LABEL, card.statusLabel)
    }

    @Test
    fun `no badges line when the card carries no comments, children, block or goal`() {
        assertNull(TvKanbanPolicy.cardFor(task(), "#0-0").badgesLine)
    }

    // ── колонки ──

    @Test
    fun `columns keep server order and empty columns stay visible with their counter`() {
        val board = KanbanBoard(
            columns = listOf(
                KanbanColumn(name = "Готово", tasks = listOf(task(id = "a"), task(id = "b"))),
                KanbanColumn(name = "В работе", tasks = emptyList()),
            ),
        )
        val columns = TvKanbanPolicy.columns(board)
        assertEquals(listOf("Готово", "В работе"), columns.map { it.name })
        assertEquals(listOf(2, 0), columns.map { it.count })
        assertEquals(2, TvKanbanPolicy.taskCount(board))
    }

    @Test
    fun `blank column name reads as an explicit placeholder, never empty`() {
        val board = KanbanBoard(columns = listOf(KanbanColumn(name = " ", tasks = emptyList())))
        assertEquals(TvKanbanPolicy.NO_COLUMN_LABEL, TvKanbanPolicy.columns(board).single().name)
    }

    // ── статистика ──

    @Test
    fun `stats line puts canonical statuses first, unknown ones alphabetically, total last`() {
        val stats = KanbanStats(byStatus = mapOf("weird" to 1, "done" to 5, "ready" to 2))
        assertEquals("ready 2 · done 5 · weird 1 · Всего 8", TvKanbanPolicy.statsLine(stats))
    }

    @Test
    fun `stats line is null when the server sent nothing to count`() {
        assertNull(TvKanbanPolicy.statsLine(null))
        assertNull(TvKanbanPolicy.statsLine(KanbanStats()))
    }

    @Test
    fun `stats summary reports the oldest ready age and never invents a zero`() {
        val stats = KanbanStats(byStatus = mapOf("ready" to 3), oldestReadyAgeSeconds = 7200)
        assertEquals("Всего 3 · старейшая ready: 2 ч", TvKanbanPolicy.statsSummary(stats))
        assertNull(TvKanbanPolicy.statsSummary(KanbanStats(byStatus = mapOf("done" to 0))))
        assertNull(TvKanbanPolicy.statsSummary(null))
    }

    // ── пустые состояния и фокус ──

    @Test
    fun `empty board is data with its own line, not an error`() {
        assertEquals(TvKanbanPolicy.EMPTY_BOARD, TvKanbanPolicy.emptyLine(KanbanBoard(columns = emptyList())))
        assertNull(TvKanbanPolicy.emptyLine(KanbanBoard(columns = listOf(KanbanColumn("x")))))
    }

    @Test
    fun `focus restoration clamps and never wraps`() {
        assertEquals(TvKanbanPolicy.NO_SELECTION, TvKanbanPolicy.restoreSelection(3, 0))
        assertEquals(2, TvKanbanPolicy.restoreSelection(7, 3))
        assertEquals(0, TvKanbanPolicy.restoreSelection(-4, 3))
        assertEquals(1, TvKanbanPolicy.restoreSelection(1, 3))
    }
}
