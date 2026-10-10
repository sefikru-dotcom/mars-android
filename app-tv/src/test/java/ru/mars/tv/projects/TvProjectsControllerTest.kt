package ru.mars.tv.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.cache.KeyValueStore
import ru.mars.network.cache.ProjectsOfflineCache
import ru.mars.tv.TvOfflineAgeBadge
import ru.mars.tv.TvOfflineCacheStatus
import ru.mars.tv.TvUiDispatcher
import java.util.concurrent.Executor

/**
 * Unit-тесты [TvProjectsController] без Android-фреймворка: surface, gateway,
 * executor и ui-dispatcher — подставные (seam-паттерн TvInteractiveControllerTest);
 * офлайн-кэш — настоящий [ProjectsOfflineCache] поверх in-memory [KeyValueStore].
 * Покрывают: loading/error/metrics-состояния, явные сообщения об недоступности
 * (без сырого JSON), безопасный пустой payload без выдуманных данных,
 * generation-guard от устаревших результатов, destroy и retry после ошибки,
 * а также офлайн-контур: LIVE с сохранением сырого тела в кэш, CACHED с возрастной
 * плашкой при сбое сети, ERROR при пустом/просроченном/нечитаемом снимке.
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
        val statuses = mutableListOf<TvOfflineCacheStatus>()
        val badges = mutableListOf<String?>()
        val errors = mutableListOf<String>()
        override fun showLoading() { loadingCalls++ }
        override fun showMetrics(
            metrics: TvProjectsMetrics,
            status: TvOfflineCacheStatus,
            badgeText: String?,
        ) {
            this.metrics += metrics
            statuses += status
            badges += badgeText
        }
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

    /** In-memory порт хранилища: настоящая сериализация [ProjectsOfflineCache], без Android. */
    private class FakeKeyValueStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
        override fun clearAll() { map.clear() }
    }

    private class Fixture(
        script: List<TvProjectsFetchResult>,
        withCache: Boolean = false,
        startNowMs: Long = BASE_NOW_MS,
    ) {
        val executor = QueueExecutor()
        val ui = QueueUi()
        val surface = RecordingSurface()
        val gateway = ScriptedGateway().apply { this.script.addAll(script) }
        val store = FakeKeyValueStore()
        var nowMs = startNowMs
        val cache: ProjectsOfflineCache? =
            if (withCache) ProjectsOfflineCache(store, { nowMs }) else null
        val controller = TvProjectsController(
            surface = surface,
            gateway = gateway,
            offlineCache = cache,
            clock = { nowMs },
            executor = executor,
            uiDispatcher = ui,
        )
        fun drain() { executor.runAll(); ui.runAll() }
    }

    private val realBody = """
        {"ts": "08.10.2026 в 22:19:34 (МСК)", "prod": {
          "services": [{"title": "Квартал", "state": "active", "uptime": "2 д 14 ч 38 мин"}],
          "host": {"mem": {"text": "0.8 ГБ из 3.8 ГБ (22%)"}, "load": "1.22 (1 мин)"}}}
    """.trimIndent()

    // --- базовый контур (прежние гарантии) ---

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

    // --- офлайн-контур: LIVE обновляет кэш ---

    @Test
    fun liveSuccessSavesRawBodyToCacheAndRendersLiveBadge() {
        val f = Fixture(listOf(TvProjectsFetchResult.Success(realBody)), withCache = true)
        f.controller.load()
        f.drain()
        assertEquals(listOf(TvOfflineCacheStatus.LIVE), f.surface.statuses)
        assertEquals(listOf(TvOfflineAgeBadge.LIVE_BADGE), f.surface.badges)
        assertTrue(f.surface.errors.isEmpty())
        // В кэше — сырое тело как есть + метка времени сохранения.
        assertEquals(realBody, f.cache!!.loadProjectsJson())
        assertEquals(BASE_NOW_MS, f.cache.getSavedTimestamp())
    }

    @Test
    fun malformedLiveBodyDoesNotPoisonTheCache() {
        val f = Fixture(listOf(TvProjectsFetchResult.Success("<html>502 gateway {oops")), withCache = true)
        f.controller.load()
        f.drain()
        assertTrue(f.surface.metrics.isEmpty())
        assertEquals(listOf("Метрики недоступны: некорректный ответ сервера"), f.surface.errors)
        assertNull("неразобранный ответ не должен вытеснять живой снимок", f.cache!!.loadProjectsJson())
        assertNull(f.cache.getSavedTimestamp())
    }

    // --- офлайн-контур: сбой сети при живом кэше ---

    @Test
    fun networkFailureWithFreshCacheRendersCachedMetricsWithAgeBadge() {
        val f = Fixture(listOf(TvProjectsFetchResult.Failure("нет соединения")), withCache = true)
        // Снимок сохранён за 10 минут до сбоя.
        f.nowMs = BASE_NOW_MS - 10 * 60_000
        assertTrue(f.cache!!.saveProjectsJson(realBody))
        f.nowMs = BASE_NOW_MS
        f.controller.load()
        f.drain()
        assertTrue("при живом кэше явная ошибка не показывается", f.surface.errors.isEmpty())
        assertEquals(listOf(TvOfflineCacheStatus.CACHED), f.surface.statuses)
        assertEquals(listOf("Снимок из кэша (10 мин. назад)"), f.surface.badges)
        val shown = f.surface.metrics.single()
        assertEquals("08.10.2026 в 22:19:34 (МСК)", shown.timestamp)
        assertEquals(listOf("Квартал"), shown.services.map { it.title })
        assertEquals("0.8 ГБ из 3.8 ГБ (22%)", shown.host?.ram)
    }

    @Test
    fun dayOldSnapshotIsStillWithinTheProjectsWindow() {
        val f = Fixture(listOf(TvProjectsFetchResult.Failure("таймаут")), withCache = true)
        f.nowMs = BASE_NOW_MS - 20 * 60 * 60_000
        assertTrue(f.cache!!.saveProjectsJson(realBody))
        f.nowMs = BASE_NOW_MS
        f.controller.load()
        f.drain()
        assertEquals(listOf(TvOfflineCacheStatus.CACHED), f.surface.statuses)
        assertEquals(listOf("Снимок из кэша (20 ч. назад)"), f.surface.badges)
        assertTrue(f.surface.errors.isEmpty())
    }

    @Test
    fun snapshotSurvivesScreenRestartOnTheSameStore() {
        val f = Fixture(listOf(TvProjectsFetchResult.Success(realBody)), withCache = true)
        f.controller.load()
        f.drain()

        // «Перезапуск экрана»: новый контроллер/surface поверх того же хранилища, сеть лежит.
        f.nowMs += 2 * 60 * 60_000
        val surface2 = RecordingSurface()
        val gateway2 = ScriptedGateway().apply {
            script.addLast(TvProjectsFetchResult.Failure("нет соединения"))
        }
        val controller2 = TvProjectsController(
            surface = surface2,
            gateway = gateway2,
            offlineCache = ProjectsOfflineCache(f.store, { f.nowMs }),
            clock = { f.nowMs },
            executor = f.executor,
            uiDispatcher = f.ui,
        )
        controller2.load()
        f.executor.runAll()
        f.ui.runAll()

        assertTrue(surface2.errors.isEmpty())
        assertEquals(listOf(TvOfflineCacheStatus.CACHED), surface2.statuses)
        assertEquals(listOf("Снимок из кэша (2 ч. назад)"), surface2.badges)
        assertEquals("Квартал", surface2.metrics.single().services.single().title)
    }

    // --- офлайн-контур: сбой сети при пустом/просроченном/битом кэше ---

    @Test
    fun networkFailureWithEmptyCacheKeepsTheExplicitError() {
        val f = Fixture(listOf(TvProjectsFetchResult.Failure("нет соединения")), withCache = true)
        f.controller.load()
        f.drain()
        assertTrue(f.surface.metrics.isEmpty())
        assertTrue(f.surface.statuses.isEmpty())
        assertEquals(listOf("Метрики недоступны: нет соединения"), f.surface.errors)
    }

    @Test
    fun expiredSnapshotIsNeverShown() {
        val f = Fixture(listOf(TvProjectsFetchResult.Failure("нет соединения")), withCache = true)
        // Снимок на минуту старше 24-часового окна.
        f.nowMs = BASE_NOW_MS - TvProjectsOfflinePolicy.MAX_AGE_MS - 60_000
        assertTrue(f.cache!!.saveProjectsJson(realBody))
        f.nowMs = BASE_NOW_MS
        f.controller.load()
        f.drain()
        assertTrue("устаревшие метрики не должны показываться", f.surface.metrics.isEmpty())
        assertEquals(listOf("Метрики недоступны: нет соединения"), f.surface.errors)
    }

    @Test
    fun snapshotExactlyAtTheWindowEdgeIsStillShown() {
        val f = Fixture(listOf(TvProjectsFetchResult.Failure("нет соединения")), withCache = true)
        f.nowMs = BASE_NOW_MS - TvProjectsOfflinePolicy.MAX_AGE_MS
        assertTrue(f.cache!!.saveProjectsJson(realBody))
        f.nowMs = BASE_NOW_MS
        f.controller.load()
        f.drain()
        assertEquals(listOf(TvOfflineCacheStatus.CACHED), f.surface.statuses)
        assertEquals(listOf("Снимок из кэша (1 дн. назад)"), f.surface.badges)
    }

    @Test
    fun unreadableCachedSnapshotFallsBackToTheHonestNetworkReason() {
        // Кэш хранит сырую строку без валидации: если снимок нечитаем, экран не падает,
        // а показывает исходную сетевую причину — без сырых фрагментов.
        val f = Fixture(listOf(TvProjectsFetchResult.Failure("нет соединения")), withCache = true)
        assertTrue(f.cache!!.saveProjectsJson("<html>{oops"))
        f.controller.load()
        f.drain()
        assertTrue(f.surface.metrics.isEmpty())
        assertEquals(listOf("Метрики недоступны: нет соединения"), f.surface.errors)
        val message = f.surface.errors.single()
        assertFalse("сырой снимок не должен попадать в UI: $message", message.contains("{"))
        assertFalse(message.contains("<html>"))
    }

    @Test
    fun cachelessControllerKeepsTheLegacyExplicitErrorBehaviour() {
        // Кэш не внедрён (null) — сбой сети это снова просто ошибка, без CACHED-ветки.
        val f = Fixture(listOf(TvProjectsFetchResult.Failure("HTTP 502")), withCache = false)
        f.controller.load()
        f.drain()
        assertTrue(f.surface.metrics.isEmpty())
        assertEquals(listOf("Метрики недоступны: HTTP 502"), f.surface.errors)
    }

    @Test
    fun liveAnswerAfterCachedFallbackReplacesTheBadgeWithLive() {
        val f = Fixture(
            listOf(
                TvProjectsFetchResult.Failure("нет соединения"),
                TvProjectsFetchResult.Success(realBody),
            ),
            withCache = true,
        )
        f.nowMs = BASE_NOW_MS - 3 * 60_000
        assertTrue(f.cache!!.saveProjectsJson(realBody))
        f.nowMs = BASE_NOW_MS
        f.controller.load()
        f.drain()
        assertEquals(listOf(TvOfflineCacheStatus.CACHED), f.surface.statuses)
        // Сеть вернулась: следующий цикл даёт LIVE и перезаписывает снимок.
        f.controller.load()
        f.drain()
        assertEquals(
            listOf(TvOfflineCacheStatus.CACHED, TvOfflineCacheStatus.LIVE),
            f.surface.statuses,
        )
        assertEquals(
            listOf("Снимок из кэша (3 мин. назад)", TvOfflineAgeBadge.LIVE_BADGE),
            f.surface.badges,
        )
        assertTrue(f.surface.errors.isEmpty())
    }

    private companion object {
        /** Фиксированная «сейчас»-метка: расчёты возраста детерминированы. */
        val BASE_NOW_MS = 1_760_000_000_000L
    }
}
