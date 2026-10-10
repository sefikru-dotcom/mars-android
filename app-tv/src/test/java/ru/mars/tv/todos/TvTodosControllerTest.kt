package ru.mars.tv.todos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.cache.KeyValueStore
import ru.mars.network.cache.TodosOfflineCache
import ru.mars.network.model.TodoItem
import ru.mars.network.model.TodoSnapshot
import ru.mars.network.model.TodoSummary
import ru.mars.tv.TvOfflineAgeBadge
import ru.mars.tv.TvOfflineCacheStatus
import ru.mars.tv.TvUiDispatcher
import java.util.concurrent.Executor

/**
 * Unit-тесты [TvTodosController] без Android-фреймворка: surface, gateway, executor
 * и ui-dispatcher — подставные (seam-паттерн TvProjectsControllerTest); офлайн-кэш —
 * настоящий [TodosOfflineCache] поверх in-memory [KeyValueStore]. Покрывают:
 * loading/data/error-состояния, явные сообщения (без сырого JSON), data-состояния
 * «нет сессии» и «снапшота нет» как данные (не ошибки), generation-guard,
 * destroy и retry после ошибки, а также офлайн-контур: LIVE с обновлением кэша,
 * CACHED с возрастной плашкой при сбое сети, ERROR при пустом/просроченном кэше
 * и выживание снимка между «перезапусками экрана».
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
        val statuses = mutableListOf<TvOfflineCacheStatus>()
        val badges = mutableListOf<String?>()
        val errors = mutableListOf<String>()
        override fun showLoading() { loadingCalls++ }
        override fun showTodos(data: TvTodosData, status: TvOfflineCacheStatus, badgeText: String?) {
            this.data += data
            statuses += status
            badges += badgeText
        }
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

    /** In-memory порт хранилища: настоящая сериализация [TodosOfflineCache], без Android. */
    private class FakeKeyValueStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
        override fun clearAll() { map.clear() }
    }

    private class Fixture(
        script: List<TvTodosFetchResult>,
        withCache: Boolean = false,
        startNowMs: Long = BASE_NOW_MS,
    ) {
        val executor = QueueExecutor()
        val ui = QueueUi()
        val surface = RecordingSurface()
        val gateway = ScriptedGateway().apply { this.script.addAll(script) }
        val store = FakeKeyValueStore()
        var nowMs = startNowMs
        val cache: TodosOfflineCache? = if (withCache) TodosOfflineCache(store, { nowMs }) else null
        val controller = TvTodosController(
            surface = surface,
            gateway = gateway,
            offlineCache = cache,
            clock = { nowMs },
            executor = executor,
            uiDispatcher = ui,
        )
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

    // --- базовый контур (прежние гарантии) ---

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

    // --- офлайн-контур: LIVE обновляет кэш ---

    @Test
    fun `live success renders LIVE with fresh badge and updates the offline cache`() {
        val f = Fixture(listOf(success(realData)), withCache = true)
        f.controller.load()
        f.drain()
        assertEquals(listOf(realData), f.surface.data)
        assertEquals(listOf(TvOfflineCacheStatus.LIVE), f.surface.statuses)
        assertEquals(listOf(TvOfflineAgeBadge.LIVE_BADGE), f.surface.badges)
        assertTrue(f.surface.errors.isEmpty())
        // Снимок действительно лёг в кэш (глобальный слот «текущих» задач).
        assertEquals(realData.snapshot, f.cache!!.loadTodos(null))
        assertEquals(BASE_NOW_MS, f.cache.getSnapshotTimestamp(null))
    }

    @Test
    fun `live success without a snapshot does not touch the cache`() {
        // «Задачи не ставились» (null snapshot) не должно вытеснять последний живой снимок.
        val seeded = TodoSnapshot(todos = listOf(TodoItem(id = "9", content = "Старая", status = "pending")))
        val f = Fixture(listOf(success(TvTodosData("Чат", null))), withCache = true)
        assertTrue(f.cache!!.saveTodos(null, seeded))
        f.controller.load()
        f.drain()
        assertEquals(listOf(TvOfflineCacheStatus.LIVE), f.surface.statuses)
        assertEquals(seeded, f.cache.loadTodos(null))
    }

    // --- офлайн-контур: сбой сети при живом кэше ---

    @Test
    fun `network failure with a fresh cache renders CACHED snapshot with age badge`() {
        val f = Fixture(listOf(TvTodosFetchResult.Failure("нет соединения")), withCache = true)
        // Снимок сохранён за 5 минут до сбоя.
        f.nowMs = BASE_NOW_MS - 5 * 60_000
        assertTrue(f.cache!!.saveTodos(null, realData.snapshot!!))
        f.nowMs = BASE_NOW_MS
        f.controller.load()
        f.drain()
        assertTrue("при живом кэше явная ошибка не показывается", f.surface.errors.isEmpty())
        assertEquals(listOf(TvOfflineCacheStatus.CACHED), f.surface.statuses)
        assertEquals(listOf("Снимок из кэша (5 мин. назад)"), f.surface.badges)
        val shown = f.surface.data.single()
        assertNull("заголовок сессии не кэшируется — честный null", shown.sessionTitle)
        assertEquals(realData.snapshot, shown.snapshot)
    }

    @Test
    fun `cached fallback keeps working for hours-old and day-old snapshots`() {
        val f = Fixture(
            listOf(
                TvTodosFetchResult.Failure("таймаут"),
                TvTodosFetchResult.Failure("таймаут"),
            ),
            withCache = true,
        )
        f.nowMs = BASE_NOW_MS - 3 * 60 * 60_000
        assertTrue(f.cache!!.saveTodos(null, realData.snapshot!!))
        f.nowMs = BASE_NOW_MS
        f.controller.load()
        f.drain()
        assertEquals(listOf("Снимок из кэша (3 ч. назад)"), f.surface.badges)
        // Через сутки снимок ещё в окне (12 ч)... нет: 24 ч > 12 ч — уже просрочен.
        f.nowMs = BASE_NOW_MS + 24 * 60 * 60_000
        f.controller.load()
        f.drain()
        assertEquals(1, f.surface.data.size) // второго показа данных нет
        assertEquals(listOf("Задачи недоступны: таймаут"), f.surface.errors)
    }

    @Test
    fun `snapshot survives a screen restart - a new controller on the same store falls back`() {
        val f = Fixture(listOf(success(realData)), withCache = true)
        f.controller.load()
        f.drain()

        // «Перезапуск экрана»: новый контроллер/surface поверх того же хранилища, сеть лежит.
        f.nowMs += 30 * 60_000
        val surface2 = RecordingSurface()
        val gateway2 = ScriptedGateway().apply {
            script.addLast(TvTodosFetchResult.Failure("нет соединения"))
        }
        val controller2 = TvTodosController(
            surface = surface2,
            gateway = gateway2,
            offlineCache = TodosOfflineCache(f.store, { f.nowMs }),
            clock = { f.nowMs },
            executor = f.executor,
            uiDispatcher = f.ui,
        )
        controller2.load()
        f.executor.runAll()
        f.ui.runAll()

        assertTrue(surface2.errors.isEmpty())
        assertEquals(listOf(TvOfflineCacheStatus.CACHED), surface2.statuses)
        assertEquals(listOf("Снимок из кэша (30 мин. назад)"), surface2.badges)
        assertEquals(realData.snapshot, surface2.data.single().snapshot)
    }

    // --- офлайн-контур: сбой сети при пустом/просроченном кэше ---

    @Test
    fun `network failure with an empty cache keeps the explicit error`() {
        val f = Fixture(listOf(TvTodosFetchResult.Failure("нет соединения")), withCache = true)
        f.controller.load()
        f.drain()
        assertTrue(f.surface.data.isEmpty())
        assertTrue(f.surface.statuses.isEmpty())
        assertEquals(listOf("Задачи недоступны: нет соединения"), f.surface.errors)
        assertFalse(f.surface.errors.single().contains("{"))
    }

    @Test
    fun `expired snapshot is never shown - explicit error instead`() {
        val f = Fixture(listOf(TvTodosFetchResult.Failure("нет соединения")), withCache = true)
        // Снимок на минуту старше 12-часового окна.
        f.nowMs = BASE_NOW_MS - TvTodosOfflinePolicy.MAX_AGE_MS - 60_000
        assertTrue(f.cache!!.saveTodos(null, realData.snapshot!!))
        f.nowMs = BASE_NOW_MS
        f.controller.load()
        f.drain()
        assertTrue("устаревший снимок не должен показываться", f.surface.data.isEmpty())
        assertEquals(listOf("Задачи недоступны: нет соединения"), f.surface.errors)
    }

    @Test
    fun `snapshot exactly at the window edge is still shown`() {
        val f = Fixture(listOf(TvTodosFetchResult.Failure("нет соединения")), withCache = true)
        f.nowMs = BASE_NOW_MS - TvTodosOfflinePolicy.MAX_AGE_MS
        assertTrue(f.cache!!.saveTodos(null, realData.snapshot!!))
        f.nowMs = BASE_NOW_MS
        f.controller.load()
        f.drain()
        assertEquals(listOf(TvOfflineCacheStatus.CACHED), f.surface.statuses)
        assertEquals(listOf("Снимок из кэша (12 ч. назад)"), f.surface.badges)
    }

    @Test
    fun `cacheless controller keeps the legacy explicit-error behaviour`() {
        // Кэш не внедрён (null) — сбой сети это снова просто ошибка, без CACHED-ветки.
        val f = Fixture(listOf(TvTodosFetchResult.Failure("HTTP 502")), withCache = false)
        f.controller.load()
        f.drain()
        assertTrue(f.surface.data.isEmpty())
        assertEquals(listOf("Задачи недоступны: HTTP 502"), f.surface.errors)
    }

    @Test
    fun `live answer after a cached fallback replaces the badge with LIVE`() {
        val f = Fixture(
            listOf(
                TvTodosFetchResult.Failure("нет соединения"),
                success(realData),
            ),
            withCache = true,
        )
        f.nowMs = BASE_NOW_MS - 2 * 60_000
        assertTrue(f.cache!!.saveTodos(null, realData.snapshot!!))
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
            listOf("Снимок из кэша (2 мин. назад)", TvOfflineAgeBadge.LIVE_BADGE),
            f.surface.badges,
        )
        assertTrue(f.surface.errors.isEmpty())
    }

    private companion object {
        /** Фиксированная «сейчас»-метка: расчёты возраста детерминированы. */
        val BASE_NOW_MS = 1_760_000_000_000L
    }
}
