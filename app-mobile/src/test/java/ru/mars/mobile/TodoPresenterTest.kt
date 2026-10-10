package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.TodoItem
import ru.mars.network.model.TodoSnapshot
import ru.mars.network.model.TodoSummary

class TodoPresenterTest {

    @Test
    fun present_nullOrEmptyReturnsHidden() {
        assertEquals(TodoCardView.HIDDEN, TodoPresenter.present(null))
        val emptySnapshot = TodoSnapshot(todos = emptyList(), summary = TodoSummary())
        assertEquals(TodoCardView.HIDDEN, TodoPresenter.present(emptySnapshot))
    }

    @Test
    fun present_formatsProgressAndItems() {
        val snapshot = TodoSnapshot(
            todos = listOf(
                TodoItem(id = "1", content = "Собрать проект", status = "completed"),
                TodoItem(id = "2", content = "Запустить тесты", status = "in_progress"),
                TodoItem(id = "3", content = "Сделать релиз", status = "pending"),
            ),
            summary = TodoSummary(total = 3, completed = 1, inProgress = 1, pending = 1)
        )

        val card = TodoPresenter.present(snapshot)
        assertTrue(card.visible)
        assertEquals(1, card.completedCount)
        assertEquals(3, card.totalCount)
        assertEquals("Запустить тесты", card.inProgressContent)
        assertEquals("Задачи сессии: 1/3 выполнено", card.title)
        assertEquals(3, card.items.size)

        val item1 = card.items[0]
        assertTrue(item1.isCompleted)
        assertFalse(item1.isInProgress)

        val item2 = card.items[1]
        assertFalse(item2.isCompleted)
        assertTrue(item2.isInProgress)

        val item3 = card.items[2]
        assertFalse(item3.isCompleted)
        assertFalse(item3.isInProgress)
    }
}
