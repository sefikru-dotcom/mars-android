package ru.mars.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.repository.StreamEventMapper

/**
 * Cold context snapshot of `GET /api/session` (#ring-seed).
 *
 * Срез детали сессии несёт ту же тройку, что и `metering`-кадр, но ПЛОСКО на
 * объекте сессии (api/routes.py: `raw = compact_session | {"context_length": …,
 * "threshold_tokens": …, "last_prompt_tokens": …}`), а не под вложенным `usage`.
 * Клиент раньше эти поля не читал вовсе, поэтому кольцо заполнения стояло пустым
 * до первого хода в открытом диалоге.
 *
 * Инварианты обязаны совпадать со стрим-путём, иначе кольцо «прыгало» бы между
 * загрузкой и первым кадром: приоритет post-compression-оценки, кумулятивный
 * `input_tokens` никогда не база процента (#1436), неизвестное окно — не процент.
 */
class SessionDetailContextSeedTest {

    @Test
    fun flatSessionFieldsMapToContextUsage() {
        // Реальная форма детали: метрики лежат рядом с полями сессии.
        val session = mapOf<String, Any?>(
            "session_id" to "s1",
            "context_length" to 200_000,
            "threshold_tokens" to 160_000,
            "last_prompt_tokens" to 50_000,
        )
        assertEquals(
            ChatEvent.ContextUsage(
                usedTokens = 50_000,
                maxTokens = 200_000,
                percent = 25,
                // Порог автосжатия читается из того же холодного payload — кольцо
                // сразу знает, когда сервер сожмёт, не дожидаясь первого хода.
                thresholdTokens = 160_000,
            ),
            StreamEventMapper.contextUsageOf(session),
        )
    }

    @Test
    fun postCompressionEstimateWinsOnColdLoad() {
        // Тот же приоритет, что у стрима: после сжатия база — оценка, не last_prompt.
        val session = mapOf<String, Any?>(
            "context_length" to 200_000,
            "last_prompt_tokens" to 190_000,
            "post_compression_context_tokens_estimate" to 30_000,
        )
        assertEquals(
            ChatEvent.ContextUsage(30_000, 200_000, 15),
            StreamEventMapper.contextUsageOf(session),
        )
    }

    @Test
    fun cumulativeInputTokensNeverBecomesPercentBase() {
        // #1436: input_tokens суммируется по всем ходам — процент по нему бессмыслен.
        // Есть только кумулятив и окно, промпт-токенов нет -> замера нет вовсе.
        val session = mapOf<String, Any?>(
            "context_length" to 200_000,
            "input_tokens" to 5_000_000,
            "output_tokens" to 90_000,
        )
        assertNull(StreamEventMapper.contextUsageOf(session))
    }

    @Test
    fun unknownOrMissingWindowIsNotFakePercent() {
        assertNull(
            "нулевое окно не даёт процента",
            StreamEventMapper.contextUsageOf(
                mapOf<String, Any?>("context_length" to 0, "last_prompt_tokens" to 50_000),
            ),
        )
        assertNull(
            "отсутствие окна не даёт процента",
            StreamEventMapper.contextUsageOf(mapOf<String, Any?>("last_prompt_tokens" to 50_000)),
        )
        assertNull(
            "пустой payload",
            StreamEventMapper.contextUsageOf(emptyMap()),
        )
    }

    @Test
    fun zeroPromptTokensMeansNotMeasuredYet() {
        // Ноль промпт-токенов — «ещё не измеряли» (WebUI рисует «·»), а не «0%».
        assertNull(
            StreamEventMapper.contextUsageOf(
                mapOf<String, Any?>("context_length" to 200_000, "last_prompt_tokens" to 0),
            ),
        )
    }

    @Test
    fun thresholdTokensPresentButWindowUnknownStaysHidden() {
        // Порог без окна бесполезен: процент порога не из чего считать.
        assertNull(
            StreamEventMapper.contextUsageOf(
                mapOf<String, Any?>("threshold_tokens" to 160_000, "last_prompt_tokens" to 50_000),
            ),
        )
    }

    @Test
    fun percentClampsAtFullWindow() {
        val session = mapOf<String, Any?>(
            "context_length" to 100_000,
            "last_prompt_tokens" to 250_000,
        )
        assertEquals(
            ChatEvent.ContextUsage(250_000, 100_000, 100),
            StreamEventMapper.contextUsageOf(session),
        )
    }
}
