package ru.mars.tv.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MarsProfile
import ru.mars.network.model.ProfilesResponse
import ru.mars.tv.TvOfflineCacheStatus
import ru.mars.tv.TvUiDispatcher
import java.util.concurrent.Executor

/**
 * Unit-тесты [TvProfilesController] без Android-фреймворка: loading/data/error,
 * офлайн-кэш последнего удачного каталога (CACHED с возрастной плашкой вместо
 * ошибки), generation-guard, destroy и явные сообщения без сырого JSON
 * (seam-паттерн TvSpacesControllerTest).
 */
class TvProfilesControllerTest {

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

    private class RecordingSurface : TvProfilesSurface {
        var loadingCalls = 0
        val shown = mutableListOf<Triple<ProfilesResponse, TvOfflineCacheStatus, String?>>()
        val errors = mutableListOf<String>()
        override fun showLoading() { loadingCalls++ }
        override fun showProfiles(response: ProfilesResponse, status: TvOfflineCacheStatus, badgeText: String?) {
            shown += Triple(response, status, badgeText)
        }
        override fun showError(message: String) { errors += message }
    }

    private class ScriptedGateway : TvProfilesGateway {
        val script = ArrayDeque<TvProfilesFetchResult>()
        var calls = 0
        override fun fetchProfiles(): TvProfilesFetchResult {
            calls++
            return script.removeFirst()
        }
    }

    private val catalog = ProfilesResponse(
        profiles = listOf(MarsProfile(name = "main", isActive = true), MarsProfile(name = "office")),
        active = "main",
    )

    private class Fixture(script: List<TvProfilesFetchResult>, clock: () -> Long = { 1_700_000_000_000L }) {
        val executor = QueueExecutor()
        val ui = QueueUi()
        val surface = RecordingSurface()
        val gateway = ScriptedGateway().apply { this.script.addAll(script) }
        val controller = TvProfilesController(surface, gateway, executor, ui, clock = clock)
        fun drain() { executor.runAll(); ui.runAll() }
    }

    @Test
    fun `load announces loading synchronously and shows the catalog as LIVE`() {
        val f = Fixture(listOf(TvProfilesFetchResult.Success(catalog)))
        f.controller.load()
        assertEquals(1, f.surface.loadingCalls)
        assertTrue(f.surface.shown.isEmpty())
        f.drain()
        assertEquals(1, f.surface.shown.size)
        assertEquals(catalog, f.surface.shown.single().first)
        assertEquals(TvOfflineCacheStatus.LIVE, f.surface.shown.single().second)
        assertEquals("Обновлено только что", f.surface.shown.single().third)
        assertTrue(f.surface.errors.isEmpty())
    }

    @Test
    fun `failure without a snapshot is an explicit error with the policy prefix`() {
        val f = Fixture(listOf(TvProfilesFetchResult.Failure("сбой запроса")))
        f.controller.load()
        f.drain()
        assertTrue(f.surface.shown.isEmpty())
        assertEquals(listOf("Профили недоступны: сбой запроса"), f.surface.errors)
    }

    @Test
    fun `failure without a stated reason still names the screen`() {
        val f = Fixture(listOf(TvProfilesFetchResult.Failure("")))
        f.controller.load()
        f.drain()
        assertEquals(listOf("Профили недоступны: нет данных"), f.surface.errors)
    }

    @Test
    fun `failure after a good load shows the cached catalog instead of an error`() {
        val f = Fixture(
            listOf(
                TvProfilesFetchResult.Success(catalog),
                TvProfilesFetchResult.Failure("сбой запроса"),
            ),
        )
        f.controller.load()
        f.drain()
        f.controller.load()
        f.drain()
        assertEquals(2, f.surface.shown.size)
        assertEquals(TvOfflineCacheStatus.CACHED, f.surface.shown[1].second)
        assertEquals(catalog, f.surface.shown[1].first)
        assertTrue(f.surface.shown[1].third!!.startsWith("Снимок из кэша"))
        assertTrue(f.surface.errors.isEmpty())
    }

    @Test
    fun `an expired snapshot is never shown as data`() {
        var now = 1_700_000_000_000L
        val f = Fixture(
            listOf(
                TvProfilesFetchResult.Success(catalog),
                TvProfilesFetchResult.Failure("сбой запроса"),
            ),
            clock = { now },
        )
        f.controller.load()
        f.drain()
        now += TvProfilesPolicy.CACHE_MAX_AGE_MS + 1
        f.controller.load()
        f.drain()
        assertEquals(1, f.surface.shown.size)
        assertEquals(listOf("Профили недоступны: сбой запроса"), f.surface.errors)
    }

    @Test
    fun `a stale in-flight result never overwrites the newer one`() {
        val f = Fixture(
            listOf(
                TvProfilesFetchResult.Success(catalog),
                TvProfilesFetchResult.Failure("сбой запроса"),
            ),
        )
        f.controller.load()
        f.controller.load()
        // Оба ответа пришли, но UI-очередь разбирается только теперь: победил второй
        // (generation-guard), а первый отброшен.
        f.executor.runAll()
        f.ui.runAll()
        assertEquals(1, f.surface.shown.size)
        assertEquals(TvOfflineCacheStatus.CACHED, f.surface.shown.single().second)
        assertTrue(f.surface.errors.isEmpty())
    }

    @Test
    fun `destroy drops late results and blocks new loads`() {
        val f = Fixture(listOf(TvProfilesFetchResult.Success(catalog)))
        f.controller.load()
        f.controller.destroy()
        f.drain()
        assertTrue(f.surface.shown.isEmpty())
        f.controller.load()
        f.drain()
        assertEquals(1, f.gateway.calls)
        assertTrue(f.surface.shown.isEmpty())
    }

    @Test
    fun `empty catalog is delivered as data, not as an error`() {
        val empty = ProfilesResponse(profiles = emptyList(), active = null)
        val f = Fixture(listOf(TvProfilesFetchResult.Success(empty)))
        f.controller.load()
        f.drain()
        assertEquals(empty, f.surface.shown.single().first)
        assertEquals(TvProfilesPolicy.EMPTY_LIST, TvProfilesPolicy.emptyLine(empty))
        assertTrue(f.surface.errors.isEmpty())
    }

    @Test
    fun `no credential is reported as a pairing problem, not a network one`() {
        val gateway = RepositoryTvProfilesGateway(
            repository = ru.mars.network.repository.ProfilesRepository(),
            baseUrl = "https://example.invalid",
            credentialProvider = { null },
        )
        val outcome = gateway.fetchProfiles()
        assertTrue(outcome is TvProfilesFetchResult.Failure)
        assertEquals(RepositoryTvProfilesGateway.NO_CREDENTIAL, (outcome as TvProfilesFetchResult.Failure).reason)
    }

    @Test
    fun `gateway exposes read-only fetching only`() {
        // Read-only инвариант на уровне типа: у seam'а ровно один метод — чтение каталога.
        val methods = TvProfilesGateway::class.java.methods
            .filter { it.declaringClass == TvProfilesGateway::class.java }
            .map { it.name }
            .sorted()
        assertEquals(listOf("fetchProfiles"), methods)
        assertFalse(methods.any { it.contains("switch", ignoreCase = true) })
    }
}
