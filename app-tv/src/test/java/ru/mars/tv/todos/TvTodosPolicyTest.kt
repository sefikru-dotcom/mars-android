package ru.mars.tv.todos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.TodoItem
import ru.mars.network.model.TodoSnapshot
import ru.mars.network.model.TodoSummary
import ru.mars.tv.todos.TvTodosPolicy.StatusToken
import ru.mars.tv.todos.TvTodosPolicy.TodosFilter

/**
 * Unit-тесты [TvTodosPolicy] без Android-фреймворка: статусные формулировки
 * (паритет мобильного TodosActivity), фильтрация, честные пустые состояния
 * (не ставились / пустой список / нет сессии), прогресс-математика и clamp
 * фокуса без wrap.
 */
class TvTodosPolicyTest {

    private fun item(id: String?, content: String, status: String) =
        TodoItem(id = id, content = content, status = status)

    private val todos = listOf(
        item("1", "Развернуть билд", "completed"),
        item("2", "Прогнать тесты", "in_progress"),
        item("3", "Написать отчёт", "pending"),
        item("4", "Старая ветка", "cancelled"),
    )

    private fun snapshot(items: List<TodoItem>) = TodoSnapshot(
        todos = items,
        summary = TodoSummary(total = items.size),
    )

    @Test
    fun `status badges match mobile wording with pending fallback for unknown tokens`() {
        assertEquals("Готово", TvTodosPolicy.statusBadge("completed"))
        assertEquals("В работе", TvTodosPolicy.statusBadge("in_progress"))
        assertEquals("Отменено", TvTodosPolicy.statusBadge("cancelled"))
        assertEquals("Ожидает", TvTodosPolicy.statusBadge("pending"))
        // Неизвестный серверный статус: данные не искажаются, рендер через fallback.
        assertEquals("Ожидает", TvTodosPolicy.statusBadge("blocked"))
    }

    @Test
    fun `status tokens carry the semantic dot color`() {
        assertEquals(StatusToken.DONE, TvTodosPolicy.statusToken("completed"))
        assertEquals(StatusToken.ACTIVE, TvTodosPolicy.statusToken("in_progress"))
        assertEquals(StatusToken.CANCELLED, TvTodosPolicy.statusToken("cancelled"))
        assertEquals(StatusToken.WAITING, TvTodosPolicy.statusToken("pending"))
        assertEquals(StatusToken.WAITING, TvTodosPolicy.statusToken("whatever"))
    }

    @Test
    fun `ALL shows every status including cancelled, filters match the exact token`() {
        assertEquals(4, TvTodosPolicy.rows(todos, TodosFilter.ALL).size)
        assertEquals(listOf("Прогнать тесты"), TvTodosPolicy.rows(todos, TodosFilter.IN_PROGRESS).map { it.content })
        assertEquals(listOf("Развернуть билд"), TvTodosPolicy.rows(todos, TodosFilter.COMPLETED).map { it.content })
        assertEquals(listOf("Написать отчёт"), TvTodosPolicy.rows(todos, TodosFilter.PENDING).map { it.content })
        // cancelled виден только в ALL — отдельного фильтра «Отменённые» нет (мобильный паритет).
        assertTrue(TvTodosPolicy.rows(todos, TodosFilter.COMPLETED).none { it.content == "Старая ветка" })
    }

    @Test
    fun `rows keep server order and per-status styling flags`() {
        val rows = TvTodosPolicy.rows(todos, TodosFilter.ALL)
        assertEquals(todos.map { it.content }, rows.map { it.content })
        val done = rows[0]
        assertTrue("completed зачёркнут", done.struckThrough)
        assertFalse(done.emphasized)
        val inProgress = rows[1]
        assertTrue("in_progress акцентирован", inProgress.emphasized)
        assertFalse(inProgress.struckThrough)
        assertFalse(rows[2].struckThrough || rows[2].emphasized)
    }

    @Test
    fun `blank content is dropped and missing ids fall back to a stable index key`() {
        val rows = TvTodosPolicy.rows(
            listOf(item(null, "Без id", "pending"), item("t2", "   ", "pending"), item(null, "Тоже без id", "pending")),
            TodosFilter.ALL,
        )
        assertEquals(listOf("Без id", "Тоже без id"), rows.map { it.content })
        // Ключи стабильны между рендерами: серверный id либо индекс ИСХОДНОГО списка.
        assertEquals(listOf("#0", "#2"), rows.map { it.key })
        assertEquals("t-x", TvTodosPolicy.rows(listOf(item("t-x", "Задача", "pending")), TodosFilter.ALL).single().key)
    }

    @Test
    fun `header names the resolved session or the explicit no-session state`() {
        assertEquals("Сессия: Утренний стендап", TvTodosPolicy.headerLine("Утренний стендап"))
        assertEquals(TvTodosPolicy.NO_SESSION_HEADER, TvTodosPolicy.headerLine(null))
        assertEquals(TvTodosPolicy.NO_SESSION_HEADER, TvTodosPolicy.headerLine("   "))
    }

    @Test
    fun `summary distinguishes no-session, never-wrote, cleared list and real progress`() {
        // Сессия не найдена (пустой список ИЛИ soft-fail сервера — формулировка честна о обоих).
        assertEquals(TvTodosPolicy.NO_SESSION_SUMMARY, TvTodosPolicy.summaryLine(null, null))
        // Сессия есть, todo_state не писали.
        assertEquals(TvTodosPolicy.NEVER_WROTE_SUMMARY, TvTodosPolicy.summaryLine("Чат", null))
        // Валидный ПУСТОЙ снапшот — список очищен последней записью.
        assertEquals(TvTodosPolicy.EMPTY_LIST_SUMMARY, TvTodosPolicy.summaryLine("Чат", snapshot(emptyList())))
        // Прогресс — мобильная математика: done * 100 / total.
        assertEquals("Выполнено 1 из 4 задач (25%)", TvTodosPolicy.summaryLine("Чат", snapshot(todos)))
        assertEquals("Выполнено 2 из 3 задач (66%)", TvTodosPolicy.summaryLine(
            "Чат",
            snapshot(listOf(item("1", "a", "completed"), item("2", "b", "completed"), item("3", "c", "pending"))),
        ))
    }

    @Test
    fun `summary of an all-cancelled list stays honest at zero percent`() {
        assertEquals("Выполнено 0 из 1 задач (0%)", TvTodosPolicy.summaryLine("Чат", snapshot(listOf(item("1", "x", "cancelled")))))
    }

    @Test
    fun `empty state line repeats the summary for list-less states and names the filter otherwise`() {
        assertNull(TvTodosPolicy.emptyStateLine("Чат", snapshot(todos), TodosFilter.ALL))
        assertEquals(TvTodosPolicy.EMPTY_FILTER, TvTodosPolicy.emptyStateLine("Чат", snapshot(todos.filter { it.status == "pending" }), TodosFilter.COMPLETED))
        assertEquals(TvTodosPolicy.NEVER_WROTE_SUMMARY, TvTodosPolicy.emptyStateLine("Чат", null, TodosFilter.ALL))
        assertEquals(TvTodosPolicy.NO_SESSION_SUMMARY, TvTodosPolicy.emptyStateLine(null, null, TodosFilter.ALL))
        assertEquals(TvTodosPolicy.EMPTY_LIST_SUMMARY, TvTodosPolicy.emptyStateLine("Чат", snapshot(emptyList()), TodosFilter.ALL))
    }

    @Test
    fun `restoreSelection clamps inside the new list and never wraps`() {
        assertEquals(2, TvTodosPolicy.restoreSelection(2, 5))
        assertEquals(4, TvTodosPolicy.restoreSelection(9, 5))
        assertEquals(0, TvTodosPolicy.restoreSelection(-3, 5))
        assertEquals(TvTodosPolicy.NO_SELECTION, TvTodosPolicy.restoreSelection(3, 0))
    }

    @Test
    fun `filter labels match the XML chip row copy`() {
        assertEquals(listOf("Все", "В работе", "Выполнены", "В очереди"), TodosFilter.entries.map { it.label })
    }
}
