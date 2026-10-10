package ru.mars.tv.cron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.CronJob
import ru.mars.network.model.CronJobsResponse
import ru.mars.tv.TvUiDispatcher
import java.util.concurrent.Executor

/**
 * Unit-тесты [TvCronController] без Android-фреймворка: surface, gateway, executor
 * и ui-dispatcher — подставные (seam-паттерн TvProjectsControllerTest). Покрывают:
 * loading/data/error-состояния, явные сообщения (без сырого JSON), мягкие состояния
 * сервера (пустой список, cron_unavailable) как данные, generation-guard, destroy и retry.
 */
class TvCronControllerTest {

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

    private class RecordingSurface : TvCronSurface {
        var loadingCalls = 0
        val responses = mutableListOf<CronJobsResponse>()
        val errors = mutableListOf<String>()
        override fun showLoading() { loadingCalls++ }
        override fun showJobs(response: CronJobsResponse) { responses += response }
        override fun showError(message: String) { errors += message }
    }

    private class ScriptedGateway : TvCronGateway {
        val script = ArrayDeque<TvCronFetchResult>()
        var calls = 0
        override fun fetchJobs(): TvCronFetchResult {
            calls++
            return script.removeFirst()
        }
    }

    private class Fixture(script: List<TvCronFetchResult>) {
        val executor = QueueExecutor()
        val ui = QueueUi()
        val surface = RecordingSurface()
        val gateway = ScriptedGateway().apply { this.script.addAll(script) }
        val controller = TvCronController(surface, gateway, executor, ui)
        fun drain() { executor.runAll(); ui.runAll() }
    }

    private val realResponse = CronJobsResponse(
        jobs = listOf(
            CronJob(id = "j1", name = "Ночная консолидация", scheduleDisplay = "0 3 * * *", enabled = true),
            CronJob(id = "j2", name = "Дайджест", enabled = false),
        ),
        activeProfile = "default",
    )

    private fun success(response: CronJobsResponse) = TvCronFetchResult.Success(response)

    @Test
    fun `load shows loading immediately then the response only through the ui dispatcher`() {
        val f = Fixture(listOf(success(realResponse)))
        f.controller.load()
        assertEquals(1, f.surface.loadingCalls)
        assertTrue(f.surface.responses.isEmpty())
        f.executor.runAll()
        assertEquals(1, f.gateway.calls)
        assertTrue("результат не может попасть в surface мимо ui-dispatcher", f.surface.responses.isEmpty())
        f.ui.runAll()
        assertEquals(listOf(realResponse), f.surface.responses)
        assertTrue(f.surface.errors.isEmpty())
    }

    @Test
    fun `transport failure shows an explicit error with the controller prefix`() {
        val f = Fixture(listOf(TvCronFetchResult.Failure("нет соединения")))
        f.controller.load()
        f.drain()
        assertTrue(f.surface.responses.isEmpty())
        assertEquals(listOf("Расписания недоступны: нет соединения"), f.surface.errors)
        assertFalse(f.surface.errors.single().contains("{"))
    }

    @Test
    fun `missing pairing surfaces as an explicit reason, never as data`() {
        val f = Fixture(listOf(TvCronFetchResult.Failure(RepositoryTvCronGateway.NO_CREDENTIAL)))
        f.controller.load()
        f.drain()
        assertEquals(listOf("Расписания недоступны: устройство не сопряжено"), f.surface.errors)
        assertTrue(f.surface.responses.isEmpty())
    }

    @Test
    fun `server-reported cron unavailability is data, not a controller error`() {
        // Пустой список и cron_unavailable — мягкие состояния API: surface получает их
        // как данные, а копиру выбирает TvCronPolicy.statusHeader/emptyLine.
        val f = Fixture(
            listOf(
                success(CronJobsResponse(emptyList())),
                success(CronJobsResponse(emptyList(), cronUnavailable = true)),
            )
        )
        f.controller.load()
        f.drain()
        f.controller.load()
        f.drain()
        assertTrue(f.surface.errors.isEmpty())
        assertEquals(2, f.surface.responses.size)
        assertFalse(f.surface.responses[0].cronUnavailable)
        assertTrue(f.surface.responses[1].cronUnavailable)
    }

    @Test
    fun `stale result ignored after a newer load`() {
        val f = Fixture(
            listOf(
                success(CronJobsResponse(listOf(CronJob(id = "old", name = "old")), "old")),
                success(realResponse),
            )
        )
        f.controller.load()
        f.controller.load()
        assertEquals(2, f.surface.loadingCalls)
        f.drain()
        assertEquals(listOf(realResponse), f.surface.responses)
    }

    @Test
    fun `destroy ignores late results and blocks new loads`() {
        val f = Fixture(listOf(success(realResponse)))
        f.controller.load()
        f.controller.destroy()
        f.drain()
        assertTrue(f.surface.responses.isEmpty())
        assertTrue(f.surface.errors.isEmpty())
        f.controller.load()
        assertEquals(1, f.gateway.calls)
        assertEquals(1, f.surface.loadingCalls)
    }

    @Test
    fun `retry after error replaces the error state with data`() {
        val f = Fixture(
            listOf(
                TvCronFetchResult.Failure("сбой запроса"),
                success(realResponse),
            )
        )
        f.controller.load()
        f.drain()
        assertEquals(listOf("Расписания недоступны: сбой запроса"), f.surface.errors)
        f.controller.load()
        f.drain()
        assertEquals(listOf(realResponse), f.surface.responses)
        assertEquals(1, f.surface.errors.size)
    }
}
