package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Шторм триггеров обновления списка диалогов схлопывается: серия из пяти событий
 * (моргающая сеть, пачка подтверждённых мутаций) даёт максимум ДВЕ загрузки —
 * одну сразу и один догоняющий refresh, а не пять запросов `GET /api/sessions`.
 *
 * Хост здесь честный: те же три ветки, что в `ChatSessionsActivity` (сразу /
 * отложить / поглотить), только вместо корутин — ручные часы и ручной запуск
 * отложенного действия. Поэтому проверяется именно цикл
 * [SessionListRefreshScheduler], а не его копия в тесте.
 */
class SessionListRefreshSchedulerTest {

    /**
     * @param autoFinish `true` — загрузка завершается синхронно (быстрый сервер),
     *   `false` — висит «в полёте» до [finishLoad] (медленный канал).
     */
    private class Host(private val autoFinish: Boolean) {
        var nowMs = 0L
        var loads = 0
        var pendingDelayMs: Long? = null
        private var pendingRun: (() -> Unit)? = null
        private var finishLoad: (() -> Unit)? = null

        val scheduler = SessionListRefreshScheduler(
            nowMs = { nowMs },
            startLoad = { onFinished ->
                loads++
                finishLoad = onFinished
                if (autoFinish) onFinished()
            },
            // Хост отменяет предыдущий отложенный запуск и ставит новый — ровно так
            // это делает ChatSessionsActivity (refreshJob?.cancel() + delay).
            scheduleLater = { delayMs, run ->
                pendingDelayMs = delayMs
                pendingRun = run
            },
        )

        /** Дождаться конца отложенного окна и выполнить его. */
        fun elapse() {
            val run = pendingRun ?: error("нет отложенного запуска")
            pendingRun = null
            pendingDelayMs = null
            run()
        }

        /** Завершить висящую загрузку (ответ сервера пришёл). */
        fun completeInFlight() {
            val finish = finishLoad ?: error("нет загрузки в полёте")
            finishLoad = null
            finish()
        }
    }

    @Test
    fun singleTriggerLoadsOnce() {
        val host = Host(autoFinish = true)
        host.scheduler.trigger()
        assertEquals(1, host.loads)
        assertNull(host.pendingDelayMs)
    }

    @Test
    fun stormWhileInFlightCollapsesIntoOneCatchUp() {
        // Медленный канал: пять триггеров подряд, пока первый запрос ещё летит.
        val host = Host(autoFinish = false)
        repeat(5) {
            host.nowMs += 100
            host.scheduler.trigger()
        }
        assertEquals("во время полёта новые запросы не стартуют", 1, host.loads)

        // Загрузка завершилась — throttle помнит, что данные могли быть устаревшими,
        // и запускает ровно один догоняющий refresh.
        host.nowMs = 600
        host.completeInFlight()
        assertEquals("пять триггеров → максимум две загрузки", 2, host.loads)

        // Догоняющий завершился: больше догоняющих нет.
        host.completeInFlight()
        assertEquals(2, host.loads)
    }

    @Test
    fun stormInsideFreshWindowCollapsesIntoTwoLoads() {
        // Быстрый сервер: первая загрузка завершилась, дальше четыре триггера внутри
        // окна 2 с. Каждый откладывается, но отложенный запуск ОДИН (последний).
        val host = Host(autoFinish = true)
        host.nowMs = 0
        host.scheduler.trigger()
        assertEquals(1, host.loads)

        for (t in listOf(100L, 200L, 300L, 400L)) {
            host.nowMs = t
            host.scheduler.trigger()
        }
        assertEquals("внутри окна загрузок нет", 1, host.loads)
        // Остаток окна считается от последнего ЗАВЕРШЁННОГО обновления (t=0), а не от
        // момента триггера: к t=400 остаётся 1600 мс.
        assertEquals(1_600L, host.pendingDelayMs)

        host.nowMs = 2_000
        host.elapse()
        assertEquals(2, host.loads)
    }

    @Test
    fun triggerAfterWindowLoadsImmediately() {
        val host = Host(autoFinish = true)
        host.scheduler.trigger()
        host.nowMs = 2_000
        host.scheduler.trigger()
        assertEquals(2, host.loads)
    }

    @Test
    fun fiveTriggersAcrossTwoSecondsNeverExceedTwoLoads() {
        // Смешанный сценарий: триггеры и в полёте, и в окне — всё равно не больше двух
        // загрузок на серию (дальше окно истекает и разрешена следующая пара).
        val host = Host(autoFinish = true)
        repeat(5) {
            host.nowMs += 50
            host.scheduler.trigger()
        }
        host.nowMs = 2_000
        host.elapse()
        assertEquals(2, host.loads)
    }
}
