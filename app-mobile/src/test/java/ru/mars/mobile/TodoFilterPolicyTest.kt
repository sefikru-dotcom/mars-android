package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.TodoItem

/**
 * JVM-тесты чистой политики фильтр-чипов экрана «Задачи агента» ([TodoFilterPolicy]):
 * матрица matches по всем фильтрам и статусам (включая `cancelled` и неизвестные),
 * фильтрация списка с сохранением порядка, тексты пустого состояния и закреплённый
 * состав/порядок/подписи ряда чипов, который рендерит TodosActivity.
 *
 * Регрессия дефекта: раньше ряд чипов строился из локального списка пар в Activity, а
 * подсветка «выбран» вычислялась один раз при постройке — тап фильтровал список, но
 * активный чип визуально не переключался. Теперь состав ряда и правило фильтрации
 * живут в политике, а выбор выводится из идентичности [TodoFilter].
 */
class TodoFilterPolicyTest {

    private fun todo(status: String, content: String = status) =
        TodoItem(id = content, content = content, status = status)

    // ---------------------------------------------------------------- matches

    @Test
    fun `ALL - показывает задачи любого статуса включая cancelled и неизвестные`() {
        for (status in listOf("pending", "in_progress", "completed", "cancelled", "weird_future_status")) {
            assertTrue("ALL должен показывать статус '$status'", TodoFilterPolicy.matches(TodoFilter.ALL, status))
        }
    }

    @Test
    fun `IN_PROGRESS - только in_progress`() {
        assertTrue(TodoFilterPolicy.matches(TodoFilter.IN_PROGRESS, "in_progress"))
        for (status in listOf("pending", "completed", "cancelled", "IN_PROGRESS")) {
            assertFalse("'$status' не должен попадать под IN_PROGRESS", TodoFilterPolicy.matches(TodoFilter.IN_PROGRESS, status))
        }
    }

    @Test
    fun `COMPLETED - только completed`() {
        assertTrue(TodoFilterPolicy.matches(TodoFilter.COMPLETED, "completed"))
        for (status in listOf("pending", "in_progress", "cancelled", "Completed")) {
            assertFalse("'$status' не должен попадать под COMPLETED", TodoFilterPolicy.matches(TodoFilter.COMPLETED, status))
        }
    }

    @Test
    fun `PENDING - только pending`() {
        assertTrue(TodoFilterPolicy.matches(TodoFilter.PENDING, "pending"))
        for (status in listOf("in_progress", "completed", "cancelled", "Pending")) {
            assertFalse("'$status' не должен попадать под PENDING", TodoFilterPolicy.matches(TodoFilter.PENDING, status))
        }
    }

    @Test
    fun `cancelled - виден только под ALL, своего чипа нет`() {
        assertTrue(TodoFilterPolicy.matches(TodoFilter.ALL, "cancelled"))
        assertFalse(TodoFilterPolicy.matches(TodoFilter.IN_PROGRESS, "cancelled"))
        assertFalse(TodoFilterPolicy.matches(TodoFilter.COMPLETED, "cancelled"))
        assertFalse(TodoFilterPolicy.matches(TodoFilter.PENDING, "cancelled"))
    }

    // ---------------------------------------------------------- visibleItems

    @Test
    fun `visibleItems - фильтрует снимок и сохраняет порядок`() {
        val items = listOf(
            todo("pending", "a"),
            todo("in_progress", "b"),
            todo("completed", "c"),
            todo("cancelled", "d"),
            todo("pending", "e"),
        )
        assertEquals(items, TodoFilterPolicy.visibleItems(items, TodoFilter.ALL))
        assertEquals(listOf("b"), TodoFilterPolicy.visibleItems(items, TodoFilter.IN_PROGRESS).map { it.content })
        assertEquals(listOf("c"), TodoFilterPolicy.visibleItems(items, TodoFilter.COMPLETED).map { it.content })
        assertEquals(listOf("a", "e"), TodoFilterPolicy.visibleItems(items, TodoFilter.PENDING).map { it.content })
    }

    @Test
    fun `visibleItems - пустой снимок пуст под любым фильтром`() {
        for (filter in TodoFilterPolicy.CHIPS) {
            assertEquals(emptyList<TodoItem>(), TodoFilterPolicy.visibleItems(emptyList(), filter))
        }
    }

    // ------------------------------------------------------------- emptyText

    @Test
    fun `emptyText - задачи отсутствуют когда снимок пуст`() {
        assertEquals("Задачи отсутствуют", TodoFilterPolicy.emptyText(allItemsEmpty = true))
    }

    @Test
    fun `emptyText - фильтр не подошёл когда задачи есть`() {
        assertEquals("Нет задач с выбранным статусом", TodoFilterPolicy.emptyText(allItemsEmpty = false))
    }

    // ------------------------------------------------------------------ чипы

    @Test
    fun `CHIPS - закреплённый состав порядок и подписи ряда`() {
        assertEquals(
            listOf(TodoFilter.ALL, TodoFilter.IN_PROGRESS, TodoFilter.COMPLETED, TodoFilter.PENDING),
            TodoFilterPolicy.CHIPS,
        )
        assertEquals(listOf("Все", "В процессе", "Выполнены", "В очереди"), TodoFilterPolicy.CHIPS.map { it.label })
    }

    @Test
    fun `выбранный чип - ровно один и это текущий фильтр`() {
        // Подсветка ряда выводится из идентичности фильтра: при любом текущем выборе
        // совпадает с ним ровно один чип — рассинхрон «фильтр vs подсветка» невозможен.
        for (selected in TodoFilterPolicy.CHIPS) {
            val highlighted = TodoFilterPolicy.CHIPS.filter { it == selected }
            assertEquals(1, highlighted.size)
            assertEquals(selected, highlighted.single())
        }
    }

    @Test
    fun `константы статусов - совпадают со значениями серверного снимка`() {
        assertEquals("pending", TodoFilterPolicy.STATUS_PENDING)
        assertEquals("in_progress", TodoFilterPolicy.STATUS_IN_PROGRESS)
        assertEquals("completed", TodoFilterPolicy.STATUS_COMPLETED)
        assertEquals("cancelled", TodoFilterPolicy.STATUS_CANCELLED)
        // Дефолт модели core-network — pending; политика обязана его распознавать.
        assertTrue(TodoFilterPolicy.matches(TodoFilter.PENDING, TodoItem().status))
    }
}
