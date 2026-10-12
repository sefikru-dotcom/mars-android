package ru.mars.tv.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.InsightsSnapshot
import ru.mars.network.model.ServerLogFile
import ru.mars.network.model.ServerLogTail
import ru.mars.network.model.ServerLogWindow
import ru.mars.tv.TvOfflineCacheStatus
import ru.mars.tv.TvUiDispatcher
import java.util.concurrent.Executor

/**
 * Unit-тесты [TvInsightsController] без Android-фреймворка: две вкладки
 * («Аналитика», «Логи») с независимыми generation-guard'ами и офлайн-кэшем
 * последнего удачного ответа каждая (seam-паттерн TvCronControllerTest).
 */
class TvInsightsControllerTest {

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

    private class RecordingSurface : TvInsightsSurface {
        var insightsLoading = 0
        var logsLoading = 0
        val snapshots = mutableListOf<Triple<InsightsSnapshot, TvOfflineCacheStatus, String?>>()
        val windows = mutableListOf<Triple<ServerLogWindow, TvOfflineCacheStatus, String?>>()
        val insightsErrors = mutableListOf<String>()
        val logsErrors = mutableListOf<String>()
        override fun showInsightsLoading() { insightsLoading++ }
        override fun showInsights(snapshot: InsightsSnapshot, status: TvOfflineCacheStatus, badgeText: String?) {
            snapshots += Triple(snapshot, status, badgeText)
        }
        override fun showInsightsError(message: String) { insightsErrors += message }
        override fun showLogsLoading() { logsLoading++ }
        override fun showLogs(window: ServerLogWindow, status: TvOfflineCacheStatus, badgeText: String?) {
            windows += Triple(window, status, badgeText)
        }
        override fun showLogsError(message: String) { logsErrors += message }
    }

    private class ScriptedInsightsGateway : TvInsightsGateway {
        val script = ArrayDeque<TvInsightsFetchResult>()
        val requestedDays = mutableListOf<Int>()
        override fun fetchInsights(days: Int): TvInsightsFetchResult {
            requestedDays += days
            return script.removeFirst()
        }
    }

    private class ScriptedLogsGateway : TvLogsGateway {
        val script = ArrayDeque<TvLogsFetchResult>()
        val requested = mutableListOf<Pair<ServerLogFile, ServerLogTail>>()
        override fun fetchLogs(file: ServerLogFile, tail: ServerLogTail): TvLogsFetchResult {
            requested += file to tail
            return script.removeFirst()
        }
    }

    private class Fixture(
        insights: List<TvInsightsFetchResult> = emptyList(),
        logs: List<TvLogsFetchResult> = emptyList(),
        clock: () -> Long = { 1_700_000_000_000L },
    ) {
        val executor = QueueExecutor()
        val ui = QueueUi()
        val surface = RecordingSurface()
        val insightsGateway = ScriptedInsightsGateway().apply { script.addAll(insights) }
        val logsGateway = ScriptedLogsGateway().apply { script.addAll(logs) }
        val controller = TvInsightsController(surface, insightsGateway, logsGateway, executor, ui, clock)
        fun drain() { executor.runAll(); ui.runAll() }
    }

    private val snapshot = InsightsSnapshot(periodDays = 30, totalSessions = 4, totalMessages = 10)
    private val window = ServerLogWindow(file = "agent", tail = 200, lines = listOf("строка"), totalBytes = 42)

    // ── вкладка «Аналитика» ──

    @Test
    fun `insights load passes the period through and publishes only via the ui dispatcher`() {
        val f = Fixture(insights = listOf(TvInsightsFetchResult.Success(snapshot)))
        f.controller.loadInsights(7)
        assertEquals(1, f.surface.insightsLoading)
        f.executor.runAll()
        assertEquals(listOf(7), f.insightsGateway.requestedDays)
        assertTrue(f.surface.snapshots.isEmpty())
        f.ui.runAll()
        val (data, status, badge) = f.surface.snapshots.single()
        assertEquals(snapshot, data)
        assertEquals(TvOfflineCacheStatus.LIVE, status)
        assertEquals("Обновлено только что", badge)
        assertTrue(f.surface.insightsErrors.isEmpty())
        assertTrue("вкладка логов не трогается", f.surface.logsLoading == 0)
    }

    @Test
    fun `insights failure without a cache shows an explicit error`() {
        val f = Fixture(insights = listOf(TvInsightsFetchResult.Failure("нет соединения")))
        f.controller.loadInsights(30)
        f.drain()
        assertTrue(f.surface.snapshots.isEmpty())
        assertEquals(listOf("Аналитика недоступна: нет соединения"), f.surface.insightsErrors)
        assertTrue(!f.surface.insightsErrors.single().contains("{"))
    }

    @Test
    fun `insights failure after a success falls back to the cached snapshot`() {
        var now = 1_700_000_000_000L
        val f = Fixture(
            insights = listOf(
                TvInsightsFetchResult.Success(snapshot),
                TvInsightsFetchResult.Failure("таймаут"),
            ),
            clock = { now },
        )
        f.controller.loadInsights(30)
        f.drain()
        now += 2 * 60 * 60_000L // 2 часа спустя
        f.controller.loadInsights(30)
        f.drain()
        assertTrue(f.surface.insightsErrors.isEmpty())
        assertEquals(2, f.surface.snapshots.size)
        val (data, status, badge) = f.surface.snapshots[1]
        assertEquals(snapshot, data)
        assertEquals(TvOfflineCacheStatus.CACHED, status)
        assertEquals("Снимок из кэша (2 ч. назад)", badge)
    }

    @Test
    fun `stale insights generation is dropped, logs generation is independent`() {
        val f = Fixture(
            insights = listOf(TvInsightsFetchResult.Failure("старый"), TvInsightsFetchResult.Success(snapshot)),
            logs = listOf(TvLogsFetchResult.Success(window)),
        )
        f.controller.loadInsights(1)
        f.controller.loadInsights(30) // инвалидирует первую загрузку аналитики
        f.controller.loadLogs() // своя нумерация поколений — не конфликтует
        f.drain()
        assertEquals(listOf(1, 30), f.insightsGateway.requestedDays)
        assertEquals(1, f.surface.snapshots.size)
        assertEquals(TvOfflineCacheStatus.LIVE, f.surface.snapshots.single().second)
        assertTrue(f.surface.insightsErrors.isEmpty())
        assertEquals(window, f.surface.windows.single().first)
    }

    // ── вкладка «Логи» ──

    @Test
    fun `logs load passes file and tail through`() {
        val f = Fixture(logs = listOf(TvLogsFetchResult.Success(window)))
        f.controller.loadLogs(ServerLogFile.ERRORS, ServerLogTail.TAIL_500)
        f.drain()
        assertEquals(listOf(ServerLogFile.ERRORS to ServerLogTail.TAIL_500), f.logsGateway.requested)
        assertEquals(1, f.surface.logsLoading)
        val (data, status, badge) = f.surface.windows.single()
        assertEquals(window, data)
        assertEquals(TvOfflineCacheStatus.LIVE, status)
        assertEquals("Обновлено только что", badge)
    }

    @Test
    fun `logs failure without a cache shows the generic logs prefix`() {
        val f = Fixture(logs = listOf(TvLogsFetchResult.Failure("нет доступа")))
        f.controller.loadLogs()
        f.drain()
        assertTrue(f.surface.windows.isEmpty())
        assertEquals(listOf("Логи недоступны: нет доступа"), f.surface.logsErrors)
    }

    @Test
    fun `logs failure after a success falls back to the cached window`() {
        var now = 1_700_000_000_000L
        val f = Fixture(
            logs = listOf(TvLogsFetchResult.Success(window), TvLogsFetchResult.Failure("сбой")),
            clock = { now },
        )
        f.controller.loadLogs()
        f.drain()
        now += 30 * 60_000L
        f.controller.loadLogs()
        f.drain()
        assertTrue(f.surface.logsErrors.isEmpty())
        val (data, status, badge) = f.surface.windows[1]
        assertEquals(window, data)
        assertEquals(TvOfflineCacheStatus.CACHED, status)
        assertEquals("Снимок из кэша (30 мин. назад)", badge)
    }

    @Test
    fun `destroy invalidates both tabs and blocks new loads`() {
        val f = Fixture(
            insights = listOf(TvInsightsFetchResult.Success(snapshot)),
            logs = listOf(TvLogsFetchResult.Success(window)),
        )
        f.controller.loadInsights(30)
        f.controller.loadLogs()
        f.controller.destroy()
        f.drain()
        assertTrue(f.surface.snapshots.isEmpty())
        assertTrue(f.surface.windows.isEmpty())
        f.controller.loadInsights(30)
        f.controller.loadLogs()
        assertEquals(
            "после destroy новые загрузки не стартуют (gateway вызван лишь pre-destroy задачей)",
            listOf(30),
            f.insightsGateway.requestedDays,
        )
        assertEquals(1, f.logsGateway.requested.size)
    }
}
