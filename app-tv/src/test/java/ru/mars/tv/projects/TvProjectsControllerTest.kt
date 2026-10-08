package ru.mars.tv.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.tv.TvUiDispatcher
import java.util.concurrent.Executor

/**
 * Unit-тесты [TvProjectsController] без Android-фреймворка: surface, gateway,
 * executor и ui-dispatcher — подставные (seam-паттерн TvInteractiveControllerTest).
 * Покрывают: loading/error/metrics-состояния, явные сообщения об недоступности
 * (без сырого JSON), безопасный пустой payload без выдуманных данных,
 * generation-guard от устаревших результатов, destroy и retry после ошибки.
 */
class TvProjectsControllerTest {

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

    private class RecordingSurface : TvProjectsSurface {
        var loadingCalls = 0
        val metrics = mutableListOf<TvProjectsMetrics>()
        val errors = mutableListOf<String>()
        override fun showLoading() { loadingCalls++ }
        override fun showMetrics(metrics: TvProjectsMetrics) { this.metrics += metrics }
        override fun showError(message: String) { errors += message }
    }

    private class ScriptedGateway : TvProjectsGateway {
        val script = ArrayDeque<TvProjectsFetchResult>()
        var calls = 0
        override fun fetchMetrics(): TvProjectsFetchResult {
            calls++
            return script.removeFirst()
        }
    }

    private class Fixture(script: List<TvProjectsFetchResult>) {
        val executor = QueueExecutor()
        val ui = QueueUi()
        val surface = RecordingSurface()
        val gateway = ScriptedGateway().apply { this.script.addAll(script) }
        val controller = TvProjectsController(surface, gateway, executor, ui)
        fun drain() { executor.runAll(); ui.runAll() }
    }

    private val realBody = """
        {"ts": "08.10.2026 в 22:19:34 (МСК)", "prod": {
          "services": [{"title": "Квартал", "state": "active", "uptime": "2 д 14 ч 38 мин"}],
          "host": {"mem": {"text": "0.8 ГБ из 3.8 ГБ (22%)"}, "load": "1.22 (1 мин)"}}}
    """.trimIndent()

    @Test
    fun loadShowsLoadingImmediatelyThenMetricsAfterDispatch() {
        val f = Fixture(listOf(TvProjectsFetchResult.Success(realBody)))
        f.controller.load()
        // showLoading — синхронно на UI-потоке, до фонового fetch.
        assertEquals(1, f.surface.loadingCalls)
        assertTrue(f.surface.metrics.isEmpty())
        f.executor.runAll()
        assertEquals(1, f.gateway.calls)
        assertTrue("результат не может попасть в surface мимо ui-dispatcher", f.surface.metrics.isEmpty())
        f.ui.runAll()
        assertEquals(1, f.surface.metrics.size)
        assertTrue(f.surface.errors.isEmpty())
        val shown = f.surface.metrics.single()
        assertEquals("08.10.2026 в 22:19:34 (МСК)", shown.timestamp)
        assertEquals(listOf("Квартал"), shown.services.map { it.title })
        assertEquals("0.8 ГБ из 3.8 ГБ (22%)", shown.host?.ram)
    }

    @Test
    fun transportFailureShowsExplicitUnavailabilityError() {
        val f = Fixture(listOf(TvProjectsFetchResult.Failure("нет соединения")))
        f.controller.load()
        f.drain()
        assertTrue(f.surface.metrics.isEmpty())
        assertEquals(listOf("Метрики недоступны: нет соединения"), f.surface.errors)
        assertFalse("сообщение не должно содержать сырой JSON", f.surface.errors.single().contains("{"))
    }

    @Test
    fun malformedBodyShowsSafeErrorWithoutRawPayload() {
        val f = Fixture(listOf(TvProjectsFetchResult.Success("<html>502 gateway {oops")))
        f.controller.load()
        f.drain()
        assertTrue(f.surface.metrics.isEmpty())
        assertEquals(1, f.surface.errors.size)
        val message = f.surface.errors.single()
        assertTrue(message.startsWith("Метрики недоступны"))
        assertFalse("сырой ответ не должен попадать в UI: $message", message.contains("{"))
        assertFalse(message.contains("<html>"))
    }

    @Test
    fun emptyPayloadYieldsMetricsWithoutInventedData() {
        val f = Fixture(listOf(TvProjectsFetchResult.Success("{}")))
        f.controller.load()
        f.drain()
        assertTrue(f.surface.errors.isEmpty())
        val shown = f.surface.metrics.single()
        // Никаких выдуманных «Онлайн»: пустые данные UI отрисует как явную недоступность.
        assertTrue(shown.services.isEmpty())
        assertNull(shown.host)
    }

    @Test
    fun staleResultIgnoredAfterNewerLoad() {
        val f = Fixture(
            listOf(
                TvProjectsFetchResult.Success("""{"ts": "старый", "prod": {"services": []}}"""),
                TvProjectsFetchResult.Success("""{"ts": "новый", "prod": {"services": []}}"""),
            )
        )
        f.controller.load()
        f.controller.load()
        assertEquals(2, f.surface.loadingCalls)
        f.drain()
        assertEquals(1, f.surface.metrics.size)
        assertEquals("новый", f.surface.metrics.single().timestamp)
        assertTrue(f.surface.errors.isEmpty())
    }

    @Test
    fun destroyIgnoresLateResults() {
        val f = Fixture(listOf(TvProjectsFetchResult.Success(realBody)))
        f.controller.load()
        f.controller.destroy()
        f.drain()
        assertTrue(f.surface.metrics.isEmpty())
        assertTrue(f.surface.errors.isEmpty())
        // После destroy новые загрузки не стартуют.
        f.controller.load()
        assertEquals(1, f.gateway.calls)
        assertEquals(1, f.surface.loadingCalls)
    }

    @Test
    fun retryAfterErrorReplacesErrorWithMetrics() {
        val f = Fixture(
            listOf(
                TvProjectsFetchResult.Failure("HTTP 502"),
                TvProjectsFetchResult.Success(realBody),
            )
        )
        f.controller.load()
        f.drain()
        assertEquals(listOf("Метрики недоступны: HTTP 502"), f.surface.errors)
        f.controller.load()
        f.drain()
        assertEquals(1, f.surface.metrics.size)
        assertEquals("Квартал", f.surface.metrics.single().services.single().title)
        assertEquals(1, f.surface.errors.size) // старая ошибка не дублируется
    }

    @Test
    fun loadingIsAnnouncedOnEveryLoad() {
        val f = Fixture(listOf(TvProjectsFetchResult.Success(realBody), TvProjectsFetchResult.Success(realBody)))
        f.controller.load()
        f.drain()
        f.controller.load()
        assertEquals(2, f.surface.loadingCalls)
        f.drain()
        assertEquals(2, f.surface.metrics.size)
    }
}
