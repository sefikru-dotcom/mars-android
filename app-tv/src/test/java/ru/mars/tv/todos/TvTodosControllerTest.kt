package ru.mars.tv.todos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.TodoItem
import ru.mars.network.model.TodoSnapshot
import ru.mars.network.model.TodoSummary
import ru.mars.tv.TvUiDispatcher
import java.util.concurrent.Executor

/**
 * Unit-тесты [TvTodosController] без Android-фреймворка: surface, gateway, executor
 * и ui-dispatcher — подставные (seam-паттерн TvProjectsControllerTest). Покрывают:
 * loading/data/error-состояния, явные сообщения (без сырого JSON), data-состояния
 * «нет сессии» и «снапшота нет» как данные (не ошибки), generation-guard,
 * destroy и retry после ошибки.
 */
class TvTodosControllerTest {

    private class QueueExecutor : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private class QueueUi : TvUiDispatcher {
        val queue = ArrayDeque<Runnable>()
        override fun dispatch(block: () -> Unit) { queue.addLast(block) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private class RecordingSurface : TvTodosSurface {
        var loadingCalls = 0
        val data = mutableListOf<TvTodosData>()
        val errors = mutableListOf<String>()
        override fun showLoading() { loadingCalls++ }
        override fun showTodos(data: TvTodosData) { this.data += data }
        override fun showError(message: String) { errors += message }
    }

    private class ScriptedGateway : TvTodosGateway {
        val script = ArrayDeque<TvTodosFetchResult>()
        var calls = 0
        override fun fetchTodos(): TvTodosFetchResult {
            calls++
            return script.removeFirst()
        }
    }

    private class Fixture(script: List<TvTodosFetchResult>) {
        val executor = QueueExecutor()
        val ui = QueueUi()
        val surface = RecordingSurface()
        val gateway = ScriptedGateway().apply { this.script.addAll(script) }
        val controller = TvTodosController(surface, gateway, executor, ui)
        fun drain() { executor.runAll(); ui.runAll() }
    }

    private val realData = TvTodosData(
        sessionTitle = "Утренний стендап",
        snapshot = TodoSnapshot(
            todos = listOf(TodoItem(id = "1", content = "Шаг один", status = "in_progress")),
            summary = TodoSummary(total = 1, inProgress = 1),
        ),
    )

    private fun success(data: TvTodosData) = TvTodosFetchResult.Success(data)

    @Test
    fun `load shows loading immediately then data only through the ui dispatcher`() {
        val f = Fixture(listOf(success(realData)))
        f.controller.load()
        // showLoading — синхронно на UI-потоке, до фонового fetch.
        assertEquals(1, f.surface.loadingCalls)
        assertTrue(f.surface.data.isEmpty())
        f.executor.runAll()
        assertEquals(1, f.gateway.calls)
        assertTrue("результат не может попасть в surface мимо ui-dispatcher", f.surface.data.isEmpty())
        f.ui.runAll()
        assertEquals(listOf(realData), f.surface.data)
        assertTrue(f.surface.errors.isEmpty())
    }

    @Test
    fun `transport failure shows an explicit error with the controller prefix`() {
        val f = Fixture(listOf(TvTodosFetchResult.Failure("нет соединения")))
        f.controller.load()
        f.drain()
        assertTrue(f.surface.data.isEmpty())
        assertEquals(listOf("Задачи недоступны: нет соединения"), f.surface.errors)
        assertFalse(f.surface.errors.single().contains("{"))
    }

    @Test
    fun `missing pairing surfaces as an explicit reason, never as data`() {
        val f = Fixture(listOf(TvTodosFetchResult.Failure(RepositoryTvTodosGateway.NO_CREDENTIAL)))
        f.controller.load()
        f.drain()
        assertEquals(listOf("Задачи недоступны: устройство не сопряжено"), f.surface.errors)
        assertTrue(f.surface.data.isEmpty())
    }

    @Test
    fun `no candidate session is data, not an error`() {
        // fetchCurrentTodos == null: soft-fail контракт репозитория — surface получает
        // пустые данные, честную формулировку выбирает TvTodosPolicy.summaryLine.
        val f = Fixture(listOf(success(TvTodosData(null, null))))
        f.controller.load()
        f.drain()
        assertTrue(f.surface.errors.isEmpty())
        val shown = f.surface.data.single()
        assertNull(shown.sessionTitle)
        assertNull(shown.snapshot)
    }

    @Test
    fun `session without a todo_state snapshot stays a null snapshot`() {
        val f = Fixture(listOf(success(TvTodosData("Чат", null))))
        f.controller.load()
        f.drain()
        assertTrue(f.surface.errors.isEmpty())
        val shown = f.surface.data.single()
        assertEquals("Чат", shown.sessionTitle)
        assertNull("never-wrote-todos не должно превращаться в пустой снапшот", shown.snapshot)
    }

    @Test
    fun `stale result ignored after a newer load`() {
        val f = Fixture(
            listOf(
                success(TvTodosData("старая", null)),
                success(realData),
            )
        )
        f.controller.load()
        f.controller.load()
        assertEquals(2, f.surface.loadingCalls)
        f.drain()
        assertEquals(listOf(realData), f.surface.data)
        assertTrue(f.surface.errors.isEmpty())
    }

    @Test
    fun `destroy ignores late results and blocks new loads`() {
        val f = Fixture(listOf(success(realData)))
        f.controller.load()
        f.controller.destroy()
        f.drain()
        assertTrue(f.surface.data.isEmpty())
        assertTrue(f.surface.errors.isEmpty())
        f.controller.load()
        assertEquals(1, f.gateway.calls)
        assertEquals(1, f.surface.loadingCalls)
    }

    @Test
    fun `retry after error replaces the error state with data`() {
        val f = Fixture(
            listOf(
                TvTodosFetchResult.Failure("сбой запроса"),
                success(realData),
            )
        )
        f.controller.load()
        f.drain()
        assertEquals(listOf("Задачи недоступны: сбой запроса"), f.surface.errors)
        f.controller.load()
        f.drain()
        assertEquals(listOf(realData), f.surface.data)
        assertEquals(1, f.surface.errors.size) // старая ошибка не дублируется
    }

    @Test
    fun `loading is announced on every load, including background refresh`() {
        val f = Fixture(listOf(success(realData), success(realData)))
        f.controller.load()
        f.drain()
        f.controller.load()
        assertEquals(2, f.surface.loadingCalls)
        f.drain()
        assertEquals(2, f.surface.data.size)
    }
}
