package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Защита от шторма обновлений списка сессий на восстановлении сети: моргающий канал
 * (серия restored-переходов) схлопывается в максимум два последовательных запроса,
 * а слишком свежий refresh откладывается, а не дублируется.
 */
class SessionListRefreshThrottleTest {

    @Test
    fun firstTriggerRefreshesImmediately() {
        val throttle = SessionListRefreshThrottle(minIntervalMs = 2_000)
        assertEquals(SessionRefreshDecision.Now, throttle.onTrigger(nowMs = 10_000))
    }

    @Test
    fun flapStormCollapsesIntoSingleCatchUpRefresh() {
        val throttle = SessionListRefreshThrottle(minIntervalMs = 2_000)
        assertEquals(SessionRefreshDecision.Now, throttle.onTrigger(nowMs = 0))
        throttle.onStarted()

        // Сеть «моргает»: триггеры во время полёта поглощаются (Skip), но запоминаются.
        assertEquals(SessionRefreshDecision.Skip, throttle.onTrigger(nowMs = 100))
        assertEquals(SessionRefreshDecision.Skip, throttle.onTrigger(nowMs = 200))
        assertEquals(SessionRefreshDecision.Skip, throttle.onTrigger(nowMs = 300))

        // Ровно один догоняющий refresh после завершения — не три.
        assertTrue(throttle.onFinished(nowMs = 400))
        assertFalse(throttle.onFinished(nowMs = 400))
    }

    @Test
    fun freshRefreshIsDeferredNotDuplicated() {
        val throttle = SessionListRefreshThrottle(minIntervalMs = 2_000)
        throttle.onStarted()
        throttle.onFinished(nowMs = 1_000)

        // Слишком свежее обновление: Later с остатком интервала.
        val later = throttle.onTrigger(nowMs = 1_500)
        assertEquals(SessionRefreshDecision.Later(1_500), later)

        // Интервал выдержан — обновляем.
        assertEquals(SessionRefreshDecision.Now, throttle.onTrigger(nowMs = 3_000))
    }

    @Test
    fun laterWindowCountsFromLastFinish() {
        val throttle = SessionListRefreshThrottle(minIntervalMs = 2_000)
        throttle.onStarted()
        throttle.onFinished(nowMs = 5_000)
        // Догоняющий запуск Later прошёл в 6_000 и завершился в 6_500: окно от 6_500.
        throttle.onStarted()
        throttle.onFinished(nowMs = 6_500)
        assertEquals(SessionRefreshDecision.Now, throttle.onTrigger(nowMs = 8_500))
    }
}
