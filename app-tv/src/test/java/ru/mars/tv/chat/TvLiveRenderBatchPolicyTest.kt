package ru.mars.tv.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Батчинг отрисовки живого пузыря: не чаще одной перерисовки в интервал,
 * отложенный flush глушит повторные чанки, итоговый текст всегда доезжает.
 */
class TvLiveRenderBatchPolicyTest {

    @Test
    fun `pending flush swallows further chunks`() {
        val decision = TvLiveRenderBatchPolicy.decide(flushPending = true, elapsedSinceLastRenderMs = 10_000)
        assertEquals(TvLiveRenderBatchPolicy.Decision.Nothing, decision)
    }

    @Test
    fun `first chunk renders immediately`() {
        val decision = TvLiveRenderBatchPolicy.decide(flushPending = false, elapsedSinceLastRenderMs = 1_000)
        assertEquals(TvLiveRenderBatchPolicy.Decision.Now, decision)
    }

    @Test
    fun `a chunk inside the interval defers the flush by the remaining time`() {
        val decision = TvLiveRenderBatchPolicy.decide(
            flushPending = false,
            elapsedSinceLastRenderMs = 20,
            intervalMs = 64,
        )
        assertEquals(TvLiveRenderBatchPolicy.Decision.Defer(44), decision)
    }

    @Test
    fun `elapsed is clamped - a negative value defers by the full interval`() {
        val decision = TvLiveRenderBatchPolicy.decide(flushPending = false, elapsedSinceLastRenderMs = -100)
        assertEquals(
            TvLiveRenderBatchPolicy.Decision.Defer(TvLiveRenderBatchPolicy.MIN_RENDER_INTERVAL_MS),
            decision,
        )
    }

    @Test
    fun `a fast chunk stream renders at most once per interval and never loses the tail`() {
        // Симуляция хоста: чанки каждые 10 мс в течение 1 секунды; отложенный flush
        // рисует ПОСЛЕДНИЙ накопленный текст. Отрисовок должно быть ~16 (1000/64),
        // а не 100 (по одной на чанк), и последний чанк обязан попасть в отрисовку.
        val interval = TvLiveRenderBatchPolicy.MIN_RENDER_INTERVAL_MS
        var now = 0L
        var lastRender = -interval // первый чанк рисуется сразу
        var flushPending = false
        var flushAt = -1L
        var renders = 0
        var renderedText = ""
        var chunks = 0
        var latestText = ""

        fun render(text: String) {
            renders++
            renderedText = text
            lastRender = now
        }

        while (now <= 1_000) {
            // Время дошло до отложенного flush — он срабатывает до обработки чанка.
            if (flushPending && now >= flushAt) {
                flushPending = false
                render(latestText)
            }
            chunks++
            latestText = "chunk#$chunks"
            when (val decision = TvLiveRenderBatchPolicy.decide(flushPending, now - lastRender, interval)) {
                TvLiveRenderBatchPolicy.Decision.Now -> render(latestText)
                is TvLiveRenderBatchPolicy.Decision.Defer -> {
                    flushPending = true
                    flushAt = now + decision.delayMs
                }
                TvLiveRenderBatchPolicy.Decision.Nothing -> Unit
            }
            now += 10
        }
        // Терминальный flush хоста (Done): отложенный текст доезжает в любом случае.
        if (flushPending) render(latestText)

        assertTrue("chunks=$chunks renders=$renders", renders * 5 < chunks)
        assertTrue(renders <= 1000 / interval + 2)
        assertEquals("chunk#$chunks", renderedText)
    }
}
