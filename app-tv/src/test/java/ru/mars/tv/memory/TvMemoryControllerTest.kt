package ru.mars.tv.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.tv.TvOfflineCacheStatus
import ru.mars.tv.TvUiDispatcher
import java.util.concurrent.Executor

/**
 * Unit-тесты [TvMemoryController] без Android-фреймворка: loading/data/error,
 * офлайн-кэш последнего удачного снимка (CACHED + возрастная плашка вместо
 * ошибки, за окном 24 ч — ERROR), generation-guard, destroy и явные сообщения
 * без сырого JSON (seam-паттерн TvSpacesControllerTest).
 */
class TvMemoryControllerTest {

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

    private class RecordingSurface : TvMemorySurface {
        var loadingCalls = 0
        val shown = mutableListOf<Triple<TvMemorySnapshot, TvOfflineCacheStatus, String?>>()
        val errors = mutableListOf<String>()
        override fun showLoading() { loadingCalls++ }
        override fun showMemory(snapshot: TvMemorySnapshot, status: TvOfflineCacheStatus, badgeText: String?) {
            shown += Triple(snapshot, status, badgeText)
        }
        override fun showError(message: String) { errors += message }
    }

    private class ScriptedGateway : TvMemoryGateway {
        val script = ArrayDeque<TvMemoryFetchResult>()
        var calls = 0
        override fun fetchMemory(): TvMemoryFetchResult {
            calls++
            return script.removeFirst()
        }
    }

    private class Fixture(script: List<TvMemoryFetchResult>, clock: () -> Long = { 1_700_000_000_000L }) {
        val executor = QueueExecutor()
        val ui = QueueUi()
        val surface = RecordingSurface()
        val gateway = ScriptedGateway().apply { this.script.addAll(script) }
        val controller = TvMemoryController(surface, gateway, executor, ui, clock = clock)
        fun drain() { executor.runAll(); ui.runAll() }
    }

    private val realSnapshot = TvMemorySnapshot(
        memory = "# Память",
        user = "Профиль",
        projectContextName = "AGENTS.md",
    )

    private fun success(snapshot: TvMemorySnapshot) = TvMemoryFetchResult.Success(snapshot)

    @Test
    fun `load shows loading immediately then the snapshot only through the ui dispatcher`() {
        val f = Fixture(listOf(success(realSnapshot)))
        f.controller.load()
        assertEquals(1, f.surface.loadingCalls)
        assertTrue(f.surface.shown.isEmpty())
        f.executor.runAll()
        assertTrue("результат не может попасть в surface мимо ui-dispatcher", f.surface.shown.isEmpty())
        f.ui.runAll()
        assertEquals(realSnapshot, f.surface.shown.single().first)
        assertEquals(TvOfflineCacheStatus.LIVE, f.surface.shown.single().second)
        assertEquals("Обновлено только что", f.surface.shown.single().third)
        assertTrue(f.surface.errors.isEmpty())
    }

    @Test
    fun `network failure surfaces as an explicit error, never as empty sections`() {
        // Сетевой сбой обязан озвучиваться ошибкой; «пустая секция» — только для
        // валидного ответа сервера с пустым текстом.
        val f = Fixture(listOf(TvMemoryFetchResult.Failure("сбой запроса")))
        f.controller.load()
        f.drain()
        assertTrue(f.surface.shown.isEmpty())
        assertEquals(listOf("Память недоступна: сбой запроса"), f.surface.errors)
        assertFalse(f.surface.errors.single().contains("{"))
    }

    @Test
    fun `unparsable response surfaces as an explicit error`() {
        val f = Fixture(listOf(TvMemoryFetchResult.Failure("ответ не распарсился")))
        f.controller.load()
        f.drain()
        assertEquals(listOf("Память недоступна: ответ не распарсился"), f.surface.errors)
    }

    @Test
    fun `missing pairing surfaces as an explicit reason`() {
        val f = Fixture(listOf(TvMemoryFetchResult.Failure(HttpTvMemoryGateway.NO_CREDENTIAL)))
        f.controller.load()
        f.drain()
        assertEquals(listOf("Память недоступна: устройство не сопряжено"), f.surface.errors)
    }

    @Test
    fun `failure after a success falls back to the cached snapshot with an age badge`() {
        var now = 1_700_000_000_000L
        val f = Fixture(
            listOf(success(realSnapshot), TvMemoryFetchResult.Failure("таймаут")),
            clock = { now },
        )
        f.controller.load()
        f.drain()
        now += 3 * 60 * 60_000L // +3 часа — внутри 24-часового окна снимка
        f.controller.load()
        f.drain()
        assertTrue(f.surface.errors.isEmpty())
        val (data, status, badge) = f.surface.shown[1]
        assertEquals(realSnapshot, data)
        assertEquals(TvOfflineCacheStatus.CACHED, status)
        assertEquals("Снимок из кэша (3 ч. назад)", badge)
    }

    @Test
    fun `snapshot older than the 24h window is an explicit error, not stale data`() {
        var now = 1_700_000_000_000L
        val f = Fixture(
            listOf(success(realSnapshot), TvMemoryFetchResult.Failure("таймаут")),
            clock = { now },
        )
        f.controller.load()
        f.drain()
        now += 25 * 60 * 60_000L // +25 часов — за пределами окна
        f.controller.load()
        f.drain()
        assertEquals("просроченный снимок не показывается", 1, f.surface.shown.size)
        assertEquals(listOf("Память недоступна: таймаут"), f.surface.errors)
    }

    @Test
    fun `snapshot with blank sections is data, not an error`() {
        val blank = TvMemorySnapshot()
        val f = Fixture(listOf(success(blank)))
        f.controller.load()
        f.drain()
        assertTrue(f.surface.errors.isEmpty())
        assertEquals(blank, f.surface.shown.single().first)
    }

    @Test
    fun `stale generation results are dropped`() {
        val f = Fixture(
            listOf(TvMemoryFetchResult.Failure("первый"), TvMemoryFetchResult.Failure("второй")),
        )
        f.controller.load()
        f.controller.load()
        f.drain()
        assertEquals(2, f.gateway.calls)
        assertTrue(f.surface.shown.isEmpty())
        assertEquals(listOf("Память недоступна: второй"), f.surface.errors)
    }

    @Test
    fun `destroy invalidates in-flight results and blocks new loads`() {
        val f = Fixture(listOf(success(realSnapshot)))
        f.controller.load()
        f.controller.destroy()
        f.drain()
        assertTrue(f.surface.shown.isEmpty())
        f.controller.load()
        assertEquals("после destroy новые загрузки не стартуют", 1, f.gateway.calls)
    }
}
