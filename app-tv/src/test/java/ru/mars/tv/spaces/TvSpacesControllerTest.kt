package ru.mars.tv.spaces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.WorkspaceItem
import ru.mars.network.model.WorkspacesResponse
import ru.mars.tv.TvOfflineCacheStatus
import ru.mars.tv.TvUiDispatcher
import java.util.concurrent.Executor

/**
 * Unit-тесты [TvSpacesController] без Android-фреймворка: loading/data/error,
 * офлайн-кэш последнего удачного каталога (CACHED + возрастная плашка вместо
 * ошибки), generation-guard, destroy и явные сообщения без сырого JSON
 * (seam-паттерн TvCronControllerTest).
 */
class TvSpacesControllerTest {

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

    private class RecordingSurface : TvSpacesSurface {
        var loadingCalls = 0
        val shown = mutableListOf<Triple<WorkspacesResponse, TvOfflineCacheStatus, String?>>()
        val errors = mutableListOf<String>()
        override fun showLoading() { loadingCalls++ }
        override fun showSpaces(response: WorkspacesResponse, status: TvOfflineCacheStatus, badgeText: String?) {
            shown += Triple(response, status, badgeText)
        }
        override fun showError(message: String) { errors += message }
    }

    private class ScriptedGateway : TvSpacesGateway {
        val script = ArrayDeque<TvSpacesFetchResult>()
        var calls = 0
        override fun fetchSpaces(): TvSpacesFetchResult {
            calls++
            return script.removeFirst()
        }
    }

    private class Fixture(script: List<TvSpacesFetchResult>, clock: () -> Long = { 1_700_000_000_000L }) {
        val executor = QueueExecutor()
        val ui = QueueUi()
        val surface = RecordingSurface()
        val gateway = ScriptedGateway().apply { this.script.addAll(script) }
        val controller = TvSpacesController(surface, gateway, executor, ui, clock = clock)
        fun drain() { executor.runAll(); ui.runAll() }
    }

    private val realResponse = WorkspacesResponse(
        workspaces = listOf(WorkspaceItem(name = "Home", path = "/root/workspace")),
        last = "/root/workspace",
    )

    private fun success(response: WorkspacesResponse) = TvSpacesFetchResult.Success(response)

    @Test
    fun `load shows loading immediately then the catalog only through the ui dispatcher`() {
        val f = Fixture(listOf(success(realResponse)))
        f.controller.load()
        assertEquals(1, f.surface.loadingCalls)
        assertTrue(f.surface.shown.isEmpty())
        f.executor.runAll()
        assertTrue("результат не может попасть в surface мимо ui-dispatcher", f.surface.shown.isEmpty())
        f.ui.runAll()
        assertEquals(realResponse, f.surface.shown.single().first)
        assertEquals(TvOfflineCacheStatus.LIVE, f.surface.shown.single().second)
        assertEquals("Обновлено только что", f.surface.shown.single().third)
        assertTrue(f.surface.errors.isEmpty())
    }

    @Test
    fun `fail-soft null of the repository surfaces as an explicit error, never as an empty catalog`() {
        // WorkspaceRepository fail-soft: null при сбое — контроллер обязан озвучить
        // это ошибкой; пустой каталог (EMPTY_LIST) — только для валидного пустого ответа.
        val f = Fixture(listOf(TvSpacesFetchResult.Failure("сбой запроса")))
        f.controller.load()
        f.drain()
        assertTrue(f.surface.shown.isEmpty())
        assertEquals(listOf("Пространства недоступны: сбой запроса"), f.surface.errors)
        assertFalse(f.surface.errors.single().contains("{"))
    }

    @Test
    fun `missing pairing surfaces as an explicit reason`() {
        val f = Fixture(listOf(TvSpacesFetchResult.Failure(RepositoryTvSpacesGateway.NO_CREDENTIAL)))
        f.controller.load()
        f.drain()
        assertEquals(listOf("Пространства недоступны: устройство не сопряжено"), f.surface.errors)
    }

    @Test
    fun `failure after a success falls back to the cached catalog with an age badge`() {
        var now = 1_700_000_000_000L
        val f = Fixture(
            listOf(success(realResponse), TvSpacesFetchResult.Failure("таймаут")),
            clock = { now },
        )
        f.controller.load()
        f.drain()
        now += 3 * 60 * 60_000L // +3 часа — внутри 24-часового окна каталога
        f.controller.load()
        f.drain()
        assertTrue(f.surface.errors.isEmpty())
        val (data, status, badge) = f.surface.shown[1]
        assertEquals(realResponse, data)
        assertEquals(TvOfflineCacheStatus.CACHED, status)
        assertEquals("Снимок из кэша (3 ч. назад)", badge)
    }

    @Test
    fun `empty catalog is data, not an error`() {
        val f = Fixture(listOf(success(WorkspacesResponse())))
        f.controller.load()
        f.drain()
        assertTrue(f.surface.errors.isEmpty())
        assertTrue(f.surface.shown.single().first.workspaces.isEmpty())
    }

    @Test
    fun `stale generation results are dropped`() {
        val f = Fixture(
            listOf(TvSpacesFetchResult.Failure("первый"), TvSpacesFetchResult.Failure("второй")),
        )
        f.controller.load()
        f.controller.load()
        f.drain()
        assertEquals(2, f.gateway.calls)
        assertTrue(f.surface.shown.isEmpty())
        assertEquals(listOf("Пространства недоступны: второй"), f.surface.errors)
    }

    @Test
    fun `destroy invalidates in-flight results and blocks new loads`() {
        val f = Fixture(listOf(success(realResponse)))
        f.controller.load()
        f.controller.destroy()
        f.drain()
        assertTrue(f.surface.shown.isEmpty())
        f.controller.load()
        assertEquals("после destroy новые загрузки не стартуют", 1, f.gateway.calls)
    }
}
