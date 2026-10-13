package ru.mars.mobile

import org.junit.Assert.*
import org.junit.Test

/**
 * Кадровый троттлинг стрима: leading-кадр сразу, один trailing-flush на окно,
 * промежуточные события накапливаются, терминальный/foreground-сброс не теряет хвост.
 */
class StreamRenderThrottleTest {

    @Test
    fun firstEventRendersImmediately() {
        val throttle = StreamRenderThrottle(minIntervalMs = 48)
        assertTrue("StreamRenderDecision.RenderNow", throttle.onStreamEvent(1000) is StreamRenderDecision.RenderNow)
    }

    @Test
    fun eventInsideWindowSchedulesSingleTrailingFlush() {
        val throttle = StreamRenderThrottle(minIntervalMs = 48)
        assertTrue("StreamRenderDecision.RenderNow", throttle.onStreamEvent(1000) is StreamRenderDecision.RenderNow)
        val decision = throttle.onStreamEvent(1010) as StreamRenderDecision.ScheduleAfter
        assertEquals(38L, decision.delayMs) // 48 - (1010 - 1000)
    }

    @Test
    fun eventsWhileTrailingScheduledAreAccumulatedNotRescheduled() {
        val throttle = StreamRenderThrottle(minIntervalMs = 48)
        throttle.onStreamEvent(1000)
        assertTrue("StreamRenderDecision.ScheduleAfter", throttle.onStreamEvent(1010) is StreamRenderDecision.ScheduleAfter)
        // Окно не «отъезжает» вперёд: иначе при непрерывном стриме кадр никогда бы не вышел.
        assertTrue("StreamRenderDecision.Ignore", throttle.onStreamEvent(1020) is StreamRenderDecision.Ignore)
        assertTrue("StreamRenderDecision.Ignore", throttle.onStreamEvent(1040) is StreamRenderDecision.Ignore)
    }

    @Test
    fun afterWindowClosesNextEventRendersImmediately() {
        val throttle = StreamRenderThrottle(minIntervalMs = 48)
        throttle.onStreamEvent(1000)
        throttle.onRendered(1048) // trailing-flush состоялся
        assertTrue("StreamRenderDecision.RenderNow", throttle.onStreamEvent(1096) is StreamRenderDecision.RenderNow)
    }

    @Test
    fun trailingFlushRenderResetsWindow() {
        val throttle = StreamRenderThrottle(minIntervalMs = 48)
        throttle.onStreamEvent(1000) // leading
        assertTrue("StreamRenderDecision.ScheduleAfter", throttle.onStreamEvent(1010) is StreamRenderDecision.ScheduleAfter)
        throttle.onRendered(1048) // trailing job отработал
        // Следующее событие в новом окне — снова планирует trailing, а не рисует сразу.
        assertTrue("StreamRenderDecision.ScheduleAfter", throttle.onStreamEvent(1050) is StreamRenderDecision.ScheduleAfter)
    }

    @Test
    fun cancelledTrailingFlushUnblocksScheduling() {
        val throttle = StreamRenderThrottle(minIntervalMs = 48)
        throttle.onStreamEvent(1000)
        assertTrue("StreamRenderDecision.ScheduleAfter", throttle.onStreamEvent(1010) is StreamRenderDecision.ScheduleAfter)
        // Хост убил ход (teardownLiveTurn): job отменён БЕЗ рендера — флаг обязан сняться,
        // иначе следующий стрим навсегда застрянет на Ignore.
        throttle.onTrailingCancelled()
        val next = throttle.onStreamEvent(1020) as StreamRenderDecision.ScheduleAfter
        assertEquals(28L, next.delayMs)
    }

    @Test
    fun synchronousRenderFromOtherSourceResetsTrailingFlag() {
        val throttle = StreamRenderThrottle(minIntervalMs = 48)
        throttle.onStreamEvent(1000)
        assertTrue("StreamRenderDecision.ScheduleAfter", throttle.onStreamEvent(1010) is StreamRenderDecision.ScheduleAfter)
        // Любой состоявшийся renderMessages() (тугл, «Стоп», bind сессии) вызывает onRendered.
        throttle.onRendered(1020)
        assertTrue("StreamRenderDecision.ScheduleAfter", throttle.onStreamEvent(1030) is StreamRenderDecision.ScheduleAfter)
    }

    @Test
    fun boundaryEventAtExactIntervalRendersImmediately() {
        val throttle = StreamRenderThrottle(minIntervalMs = 48)
        throttle.onStreamEvent(1000)
        assertTrue("StreamRenderDecision.RenderNow", throttle.onStreamEvent(1048) is StreamRenderDecision.RenderNow)
    }

    @Test
    fun defaultIntervalSitsInThirtyToSixtyMsBand() {
        // Контракт задачи: перерисовка не чаще ~30–60 мс.
        val interval = StreamRenderThrottle.DEFAULT_MIN_INTERVAL_MS
        assertTrue("DEFAULT_MIN_INTERVAL_MS=$interval вне 30..60", interval in 30..60)
    }
}
