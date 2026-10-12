package ru.mars.tv.kanban

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.KanbanBoard
import ru.mars.network.model.KanbanColumn
import ru.mars.network.model.KanbanStats
import ru.mars.network.model.KanbanTask
import ru.mars.tv.TvOfflineCacheStatus
import ru.mars.tv.TvUiDispatcher
import java.util.concurrent.Executor

/**
 * Unit-тесты [TvKanbanController] без Android-фреймворка: surface, gateway,
 * executor и ui-dispatcher — подставные (seam-паттерн TvCronControllerTest).
 * Покрывают loading/data/error-состояния, офлайн-кэш последнего удачного
 * снимка (CACHED + возрастная плашка вместо ошибки), generation-guard,
 * destroy и явные сообщения без сырого JSON.
 */
class TvKanbanControllerTest {

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

    private class RecordingSurface : TvKanbanSurface {
        var loadingCalls = 0
        val shown = mutableListOf<Triple<TvKanbanData, TvOfflineCacheStatus, String?>>()
        val errors = mutableListOf<String>()
        override fun showLoading() { loadingCalls++ }
        override fun showBoard(data: TvKanbanData, status: TvOfflineCacheStatus, badgeText: String?) {
            shown += Triple(data, status, badgeText)
        }
        override fun showError(message: String) { errors += message }
    }

    private class ScriptedGateway : TvKanbanGateway {
        val script = ArrayDeque<TvKanbanFetchResult>()
        var calls = 0
        override fun fetchBoard(): TvKanbanFetchResult {
            calls++
            return script.removeFirst()
        }
    }

    private class Fixture(
        script: List<TvKanbanFetchResult>,
        clock: () -> Long = { 1_700_000_000_000L },
    ) {
        val executor = QueueExecutor()
        val ui = QueueUi()
        val surface = RecordingSurface()
        val gateway = ScriptedGateway().apply { this.script.addAll(script) }
        val controller = TvKanbanController(surface, gateway, executor, ui, clock = clock)
        fun drain() { executor.runAll(); ui.runAll() }
    }

    private val realData = TvKanbanData(
        board = KanbanBoard(
            columns = listOf(
                KanbanColumn(name = "В работе", tasks = listOf(KanbanTask(id = "t1", title = "Задача"))),
            ),
        ),
        stats = KanbanStats(byStatus = mapOf("running" to 1)),
    )

    private fun success(data: TvKanbanData) = TvKanbanFetchResult.Success(data)

    @Test
    fun `load shows loading immediately then the board only through the ui dispatcher`() {
        val f = Fixture(listOf(success(realData)))
        f.controller.load()
        assertEquals(1, f.surface.loadingCalls)
        assertTrue(f.surface.shown.isEmpty())
        f.executor.runAll()
        assertEquals(1, f.gateway.calls)
        assertTrue("результат не может попасть в surface мимо ui-dispatcher", f.surface.shown.isEmpty())
        f.ui.runAll()
        assertEquals(realData, f.surface.shown.single().first)
        assertEquals(TvOfflineCacheStatus.LIVE, f.surface.shown.single().second)
        assertEquals("Обновлено только что", f.surface.shown.single().third)
        assertTrue(f.surface.errors.isEmpty())
    }

    @Test
    fun `transport failure without a cached snapshot shows an explicit error`() {
        val f = Fixture(listOf(TvKanbanFetchResult.Failure("нет соединения")))
        f.controller.load()
        f.drain()
        assertTrue(f.surface.shown.isEmpty())
        assertEquals(listOf("Канбан недоступен: нет соединения"), f.surface.errors)
        assertFalse(f.surface.errors.single().contains("{"))
    }

    @Test
    fun `missing pairing surfaces as an explicit reason, never as data`() {
        val f = Fixture(listOf(TvKanbanFetchResult.Failure(RepositoryTvKanbanGateway.NO_CREDENTIAL)))
        f.controller.load()
        f.drain()
        assertEquals(listOf("Канбан недоступен: устройство не сопряжено"), f.surface.errors)
        assertTrue(f.surface.shown.isEmpty())
    }

    @Test
    fun `failure after a success falls back to the cached snapshot with an age badge`() {
        var now = 1_700_000_000_000L
        val f = Fixture(
            listOf(success(realData), TvKanbanFetchResult.Failure("таймаут")),
            clock = { now },
        )
        f.controller.load()
        f.drain()
        now += 5 * 60_000L // 5 минут спустя сеть упала
        f.controller.load()
        f.drain()
        assertTrue(f.surface.errors.isEmpty())
        assertEquals(2, f.surface.shown.size)
        val (data, status, badge) = f.surface.shown[1]
        assertEquals(realData, data)
        assertEquals(TvOfflineCacheStatus.CACHED, status)
        assertEquals("Снимок из кэша (5 мин. назад)", badge)
    }

    @Test
    fun `empty board is data, not an error`() {
        val f = Fixture(listOf(success(TvKanbanData(KanbanBoard(columns = emptyList()), null))))
        f.controller.load()
        f.drain()
        assertTrue(f.surface.errors.isEmpty())
        assertTrue(f.surface.shown.single().first.board.columns.isEmpty())
    }

    @Test
    fun `stale generation results are dropped`() {
        // Обе загрузки падают: первая (устаревшая) не публикуется вообще, вторая —
        // ровно один раз. Успех→сбой здесь не годится: успех первой записал бы кэш
        // на executor-потоке, и вторая показала бы CACHED (это другой тест).
        val f = Fixture(listOf(TvKanbanFetchResult.Failure("первый"), TvKanbanFetchResult.Failure("второй")))
        f.controller.load()
        f.controller.load() // вторая загрузка инвалидирует первую
        f.drain()
        assertEquals(2, f.gateway.calls)
        assertTrue("устаревшее поколение не публикуется", f.surface.shown.isEmpty())
        assertEquals(listOf("Канбан недоступен: второй"), f.surface.errors)
    }

    @Test
    fun `destroy invalidates in-flight results and blocks new loads`() {
        val f = Fixture(listOf(success(realData)))
        f.controller.load()
        f.controller.destroy()
        f.drain()
        assertTrue(f.surface.shown.isEmpty())
        f.controller.load()
        assertEquals("после destroy новые загрузки не стартуют", 1, f.gateway.calls)
    }

    @Test
    fun `retry after an explicit error recovers to LIVE`() {
        val f = Fixture(listOf(TvKanbanFetchResult.Failure("сбой запроса"), success(realData)))
        f.controller.load()
        f.drain()
        assertEquals(1, f.surface.errors.size)
        f.controller.load() // «Повторить»
        f.drain()
        assertEquals(TvOfflineCacheStatus.LIVE, f.surface.shown.single().second)
    }
}
