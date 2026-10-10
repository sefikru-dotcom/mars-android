package ru.mars.tv.cron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.CronJob
import ru.mars.network.model.CronJobsResponse
import ru.mars.tv.cron.TvCronPolicy.CronFilter
import ru.mars.tv.cron.TvCronPolicy.StateToken

/**
 * Unit-тесты [TvCronPolicy] без Android-фреймворка: системный статус (профиль,
 * счётчик с русскими падежами, cron_unavailable), формулировки карточек
 * (мобильный паритет «Активна» / «На паузе», «След.» / «Посл.») и отсутствие
 * выдуманных заглушек: нет поля — нет строки (никаких «ok» и «По расписанию»).
 */
class TvCronPolicyTest {

    private fun job(
        id: String = "j1",
        name: String = "Ночная консолидация",
        scheduleDisplay: String? = "0 3 * * *",
        enabled: Boolean = true,
        nextRunAt: String? = "2026-10-11T03:00:00",
        lastRunAt: String? = "2026-10-10T03:00:00",
        lastStatus: String? = "success",
        model: String? = "qwen3.8-max",
    ) = CronJob(
        id = id, name = name, scheduleDisplay = scheduleDisplay, enabled = enabled,
        nextRunAt = nextRunAt, lastRunAt = lastRunAt, lastStatus = lastStatus, model = model,
    )

    private val jobs = listOf(
        job(id = "a", name = "Активная"),
        job(id = "b", name = "Пауза", enabled = false),
        job(id = "c", name = "Тоже активная"),
    )

    @Test
    fun `state labels match mobile wording`() {
        assertEquals("Активна", TvCronPolicy.stateLabel(true))
        assertEquals("На паузе", TvCronPolicy.stateLabel(false))
    }

    @Test
    fun `status header leads with the explicit unavailability flag`() {
        val unavailable = CronJobsResponse(jobs, activeProfile = "default", cronUnavailable = true)
        assertEquals(TvCronPolicy.CRON_UNAVAILABLE, TvCronPolicy.statusHeader(unavailable))
    }

    @Test
    fun `status header shows profile and declined job count`() {
        // 3 → «Задачи» (русское склонение: 1 задача, 2–4 задачи, 5+ задач).
        assertEquals("Профиль: default · Задачи: 3", TvCronPolicy.statusHeader(CronJobsResponse(jobs, "default")))
        assertEquals("Задачи: 3", TvCronPolicy.statusHeader(CronJobsResponse(jobs)))
        // Нулевое число отображается, а не скрывается: ноль — это тоже факт мониторинга.
        assertEquals("Задача: 1", TvCronPolicy.statusHeader(CronJobsResponse(listOf(job()))))
        assertEquals("Задачи: 2", TvCronPolicy.statusHeader(CronJobsResponse(listOf(job(), job(id = "x")))))
        assertEquals("Задач: 0", TvCronPolicy.statusHeader(CronJobsResponse(emptyList())))
        assertEquals("Задач: 5", TvCronPolicy.statusHeader(CronJobsResponse(jobs + listOf(job(id = "d"), job(id = "e")))))
        // Профиль добавляется префиксом; пустой/whitespace профиль сегмент не порождает.
        assertEquals("Профиль: p · Задача: 1", TvCronPolicy.statusHeader(CronJobsResponse(listOf(job()), "p")))
        assertEquals("Задач: 0", TvCronPolicy.statusHeader(CronJobsResponse(emptyList(), "  ")))
    }

    @Test
    fun `jobs word declines russian plurals including teens`() {
        assertEquals("Задача", TvCronPolicy.jobsWord(1))
        assertEquals("Задачи", TvCronPolicy.jobsWord(2))
        assertEquals("Задачи", TvCronPolicy.jobsWord(4))
        assertEquals("Задач", TvCronPolicy.jobsWord(5))
        assertEquals("Задач", TvCronPolicy.jobsWord(11))
        assertEquals("Задач", TvCronPolicy.jobsWord(14))
        assertEquals("Задача", TvCronPolicy.jobsWord(21))
        assertEquals("Задачи", TvCronPolicy.jobsWord(22))
        assertEquals("Задач", TvCronPolicy.jobsWord(111))
        assertEquals("Задачи", TvCronPolicy.jobsWord(102))
        assertEquals("Задач", TvCronPolicy.jobsWord(0))
    }

    @Test
    fun `schedule line joins present parts only`() {
        assertEquals("Расписание: 0 3 * * * · qwen3.8-max", TvCronPolicy.scheduleLine(job()))
        assertEquals("Расписание: 0 3 * * *", TvCronPolicy.scheduleLine(job(model = null)))
        assertEquals("qwen3.8-max", TvCronPolicy.scheduleLine(job(scheduleDisplay = null)))
        assertNull("без расписания и модели строки нет", TvCronPolicy.scheduleLine(job(scheduleDisplay = null, model = null)))
        assertNull(TvCronPolicy.scheduleLine(job(scheduleDisplay = "  ", model = "")))
    }

    @Test
    fun `timing line never invents an ok status`() {
        assertEquals(
            "След.: 2026-10-11T03:00:00 · Посл.: 2026-10-10T03:00:00 (success)",
            TvCronPolicy.timingLine(job()),
        )
        val noStatus = TvCronPolicy.timingLine(job(lastStatus = null))
        assertEquals("След.: 2026-10-11T03:00:00 · Посл.: 2026-10-10T03:00:00", noStatus)
        assertFalse("статус не выдумывается: $noStatus", noStatus!!.contains("ok"))
        assertEquals("След.: 2026-10-11T03:00:00", TvCronPolicy.timingLine(job(lastRunAt = null, lastStatus = null)))
        // Задача ещё не запускалась и следующего запуска нет — строки нет вообще.
        assertNull(TvCronPolicy.timingLine(job(nextRunAt = null, lastRunAt = null, lastStatus = "success")))
    }

    @Test
    fun `filters split enabled and paused, server order preserved`() {
        assertEquals(listOf("a", "b", "c"), TvCronPolicy.rows(jobs, CronFilter.ALL).map { it.id })
        assertEquals(listOf("a", "c"), TvCronPolicy.rows(jobs, CronFilter.ACTIVE).map { it.id })
        assertEquals(listOf("b"), TvCronPolicy.rows(jobs, CronFilter.PAUSED).map { it.id })
    }

    @Test
    fun `rows carry state semantics for the dot and badge`() {
        val rows = TvCronPolicy.rows(jobs, CronFilter.ALL)
        assertEquals(StateToken.ACTIVE, rows[0].token)
        assertEquals("Активна", rows[0].stateLabel)
        assertEquals(StateToken.PAUSED, rows[1].token)
        assertEquals("На паузе", rows[1].stateLabel)
        assertEquals("Активная", rows[0].title)
    }

    @Test
    fun `empty line prefers unavailability, then honest soft-fail copy, then the filter`() {
        assertEquals(
            TvCronPolicy.CRON_UNAVAILABLE,
            TvCronPolicy.emptyLine(CronJobsResponse(emptyList(), cronUnavailable = true)),
        )
        assertEquals(TvCronPolicy.EMPTY_LIST, TvCronPolicy.emptyLine(CronJobsResponse(emptyList())))
        // Реальная проверка: фильтр, не пропускающий ни одной задачи.
        val noneActive = CronJobsResponse(listOf(job(enabled = false)), null)
        assertEquals(TvCronPolicy.EMPTY_FILTER, TvCronPolicy.emptyLine(noneActive))
        assertTrue(TvCronPolicy.rows(noneActive.jobs, CronFilter.ACTIVE).isEmpty())
    }

    @Test
    fun `restoreSelection clamps inside the new list and never wraps`() {
        assertEquals(1, TvCronPolicy.restoreSelection(1, 3))
        assertEquals(2, TvCronPolicy.restoreSelection(7, 3))
        assertEquals(0, TvCronPolicy.restoreSelection(-1, 3))
        assertEquals(TvCronPolicy.NO_SELECTION, TvCronPolicy.restoreSelection(0, 0))
    }

    @Test
    fun `filter labels match the XML chip row copy`() {
        assertEquals(listOf("Все", "Активные", "На паузе"), CronFilter.entries.map { it.label })
    }
}
