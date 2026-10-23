package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.KanbanBoard
import ru.mars.network.model.KanbanColumn
import ru.mars.network.model.KanbanTask

/**
 * JVM-тесты чистой части записи в [KanbanPolicy]: цели пикера перемещения
 * (только реальные дорожки доски, без `running`), подписи выбора и итогов
 * массовых операций. Ничего не знает про Android — только модель доски.
 */
class KanbanPolicyWritesTest {

    private fun task(id: String, status: String = "ready") = KanbanTask(id = id, title = "T$id", status = status)

    @Test
    fun `columnsFor carries the lane statuses the move picker needs`() {
        val board = KanbanBoard(
            columns = listOf(
                KanbanColumn("Ready", statuses = listOf("ready"), tasks = listOf(task("k-1"))),
                KanbanColumn("Сбой", statuses = listOf("blocked")),
            ),
        )
        val columns = KanbanPolicy.columnsFor(board)
        assertEquals(listOf("ready"), columns[0].statuses)
        assertEquals(listOf("blocked"), columns[1].statuses)
    }

    @Test
    fun `moveTargets offers one target per real lane status and never running`() {
        val columns = listOf(
            KanbanColumnUi("Готово", count = 1, cards = emptyList(), statuses = listOf("ready")),
            KanbanColumnUi("В работе", count = 1, cards = emptyList(), statuses = listOf("running")),
            KanbanColumnUi("Сбой", count = 1, cards = emptyList(), statuses = listOf("blocked")),
            KanbanColumnUi("Отработано", count = 1, cards = emptyList(), statuses = listOf("done", "archived")),
        )
        val targets = KanbanPolicy.moveTargets(columns)
        assertEquals(listOf("ready", "blocked", "done", "archived"), targets.map { it.status })
        assertEquals(listOf("Готово", "Сбой", "Отработано", "Отработано"), targets.map { it.label })
    }

    @Test
    fun `moveTargets falls back to card statuses for a config skeleton lane`() {
        val columns = listOf(
            KanbanColumnUi("Ready", count = 1, cards = listOf(card("k-1", "ready"))),
            KanbanColumnUi("Empty", count = 0, cards = emptyList()),
        )
        val targets = KanbanPolicy.moveTargets(columns)
        assertEquals(listOf("ready"), targets.map { it.status })
    }

    @Test
    fun `moveTargets dedupes a status shared by two lanes and survives an empty board`() {
        val columns = listOf(
            KanbanColumnUi("Ready", count = 0, cards = emptyList(), statuses = listOf("ready")),
            KanbanColumnUi("Очередь", count = 0, cards = emptyList(), statuses = listOf("ready", "todo")),
        )
        assertEquals(listOf("ready", "todo"), KanbanPolicy.moveTargets(columns).map { it.status })
        assertTrue(KanbanPolicy.moveTargets(emptyList()).isEmpty())
    }

    @Test
    fun `selection label counts the cards picked for a bulk action`() {
        assertEquals("Ничего не выбрано", KanbanPolicy.selectionLabel(0))
        assertEquals("Выбрано: 1 задача", KanbanPolicy.selectionLabel(1))
        assertEquals("Выбрано: 5 задач", KanbanPolicy.selectionLabel(5))
    }

    @Test
    fun `bulk result labels agree with the plural rules`() {
        assertEquals("Обновлено: 1 задача", KanbanPolicy.bulkDoneLabel(1))
        assertEquals("Обновлено: 3 задачи", KanbanPolicy.bulkDoneLabel(3))
        assertEquals("Обновлено: 11 задач", KanbanPolicy.bulkDoneLabel(11))
        assertEquals("Обновлено: 0 задач", KanbanPolicy.bulkDoneLabel(0))
    }

    @Test
    fun `action outcome separates success from refusal`() {
        assertTrue(KanbanActionOutcome.ok("Задача создана").ok)
        assertFalse(KanbanActionOutcome.failed("нет сети").ok)
        assertEquals("нет сети", KanbanActionOutcome.failed("нет сети").message)
    }

    private fun card(id: String, status: String) = KanbanTaskCardUi(
        id = id,
        title = "T$id",
        status = status,
        tone = KanbanPolicy.toneFor(status),
        assigneeLabel = "",
        priorityLabel = null,
        ageLabel = null,
        commentCount = 0,
        childCount = 0,
        isBlocked = false,
        isGoalMode = false,
    )
}
