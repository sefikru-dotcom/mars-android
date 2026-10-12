package ru.mars.tv.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SkillItem
import ru.mars.tv.TvOfflineCacheStatus
import ru.mars.tv.TvUiDispatcher
import java.util.concurrent.Executor

/**
 * Unit-тесты [TvSkillsController] без Android-фреймворка: loading/data/error,
 * офлайн-кэш последнего удачного снимка (CACHED + возрастная плашка вместо
 * ошибки, за окном 24 ч — ERROR), fail-soft статистика (usage=null — данные,
 * не ошибка), generation-guard, destroy и явные сообщения без сырого JSON
 * (seam-паттерн TvSpacesControllerTest).
 */
class TvSkillsControllerTest {

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

    private class RecordingSurface : TvSkillsSurface {
        var loadingCalls = 0
        val shown = mutableListOf<Triple<TvSkillsData, TvOfflineCacheStatus, String?>>()
        val errors = mutableListOf<String>()
        override fun showLoading() { loadingCalls++ }
        override fun showSkills(data: TvSkillsData, status: TvOfflineCacheStatus, badgeText: String?) {
            shown += Triple(data, status, badgeText)
        }
        override fun showError(message: String) { errors += message }
    }

    private class ScriptedGateway : TvSkillsGateway {
        val script = ArrayDeque<TvSkillsFetchResult>()
        var calls = 0
        override fun fetchSkills(): TvSkillsFetchResult {
            calls++
            return script.removeFirst()
        }
    }

    private class Fixture(script: List<TvSkillsFetchResult>, clock: () -> Long = { 1_700_000_000_000L }) {
        val executor = QueueExecutor()
        val ui = QueueUi()
        val surface = RecordingSurface()
        val gateway = ScriptedGateway().apply { this.script.addAll(script) }
        val controller = TvSkillsController(surface, gateway, executor, ui, clock = clock)
        fun drain() { executor.runAll(); ui.runAll() }
    }

    private val realData = TvSkillsData(
        skills = listOf(SkillItem(name = "mars-toolkit", description = "RAG-память", category = "devops")),
        usage = TvSkillsUsage(
            bySkill = mapOf("mars-toolkit" to TvSkillUsage(useCount = 3, viewCount = 1, patchCount = 0)),
            totalInvocations = 4,
            uniqueSkillsUsed = 1,
        ),
    )

    private fun success(data: TvSkillsData) = TvSkillsFetchResult.Success(data)

    @Test
    fun `load shows loading immediately then the catalog only through the ui dispatcher`() {
        val f = Fixture(listOf(success(realData)))
        f.controller.load()
        assertEquals(1, f.surface.loadingCalls)
        assertTrue(f.surface.shown.isEmpty())
        f.executor.runAll()
        assertTrue("результат не может попасть в surface мимо ui-dispatcher", f.surface.shown.isEmpty())
        f.ui.runAll()
        assertEquals(realData, f.surface.shown.single().first)
        assertEquals(TvOfflineCacheStatus.LIVE, f.surface.shown.single().second)
        assertEquals("Обновлено только что", f.surface.shown.single().third)
        assertTrue(f.surface.errors.isEmpty())
    }

    @Test
    fun `network failure surfaces as an explicit error, never as an empty catalog`() {
        // Сетевой сбой обязан озвучиваться ошибкой; «На сервере пока нет скиллов» —
        // только для валидного пустого ответа.
        val f = Fixture(listOf(TvSkillsFetchResult.Failure("сбой запроса")))
        f.controller.load()
        f.drain()
        assertTrue(f.surface.shown.isEmpty())
        assertEquals(listOf("Скиллы недоступны: сбой запроса"), f.surface.errors)
        assertFalse(f.surface.errors.single().contains("{"))
    }

    @Test
    fun `unparsable response surfaces as an explicit error`() {
        val f = Fixture(listOf(TvSkillsFetchResult.Failure("ответ не распарсился")))
        f.controller.load()
        f.drain()
        assertEquals(listOf("Скиллы недоступны: ответ не распарсился"), f.surface.errors)
    }

    @Test
    fun `missing pairing surfaces as an explicit reason`() {
        val f = Fixture(listOf(TvSkillsFetchResult.Failure(HttpTvSkillsGateway.NO_CREDENTIAL)))
        f.controller.load()
        f.drain()
        assertEquals(listOf("Скиллы недоступны: устройство не сопряжено"), f.surface.errors)
    }

    @Test
    fun `usage statistics are fail-soft - a null usage snapshot is still data`() {
        val noUsage = TvSkillsData(skills = realData.skills, usage = null)
        val f = Fixture(listOf(success(noUsage)))
        f.controller.load()
        f.drain()
        assertTrue(f.surface.errors.isEmpty())
        assertNull(f.surface.shown.single().first.usage)
        assertEquals(TvOfflineCacheStatus.LIVE, f.surface.shown.single().second)
    }

    @Test
    fun `failure after a success falls back to the cached catalog with an age badge`() {
        var now = 1_700_000_000_000L
        val f = Fixture(
            listOf(success(realData), TvSkillsFetchResult.Failure("таймаут")),
            clock = { now },
        )
        f.controller.load()
        f.drain()
        now += 3 * 60 * 60_000L // +3 часа — внутри 24-часового окна снимка
        f.controller.load()
        f.drain()
        assertTrue(f.surface.errors.isEmpty())
        val (data, status, badge) = f.surface.shown[1]
        assertEquals(realData, data)
        assertEquals(TvOfflineCacheStatus.CACHED, status)
        assertEquals("Снимок из кэша (3 ч. назад)", badge)
    }

    @Test
    fun `snapshot older than the 24h window is an explicit error, not stale data`() {
        var now = 1_700_000_000_000L
        val f = Fixture(
            listOf(success(realData), TvSkillsFetchResult.Failure("таймаут")),
            clock = { now },
        )
        f.controller.load()
        f.drain()
        now += 25 * 60 * 60_000L // +25 часов — за пределами окна
        f.controller.load()
        f.drain()
        assertEquals("просроченный снимок не показывается", 1, f.surface.shown.size)
        assertEquals(listOf("Скиллы недоступны: таймаут"), f.surface.errors)
    }

    @Test
    fun `empty catalog is data, not an error`() {
        val f = Fixture(listOf(success(TvSkillsData(skills = emptyList(), usage = null))))
        f.controller.load()
        f.drain()
        assertTrue(f.surface.errors.isEmpty())
        assertTrue(f.surface.shown.single().first.skills.isEmpty())
    }

    @Test
    fun `stale generation results are dropped`() {
        val f = Fixture(
            listOf(TvSkillsFetchResult.Failure("первый"), TvSkillsFetchResult.Failure("второй")),
        )
        f.controller.load()
        f.controller.load()
        f.drain()
        assertEquals(2, f.gateway.calls)
        assertTrue(f.surface.shown.isEmpty())
        assertEquals(listOf("Скиллы недоступны: второй"), f.surface.errors)
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
}
