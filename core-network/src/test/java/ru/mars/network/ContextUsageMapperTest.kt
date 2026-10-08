package ru.mars.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.mars.network.model.ChatEvent
import ru.mars.network.repository.StreamEventMapper

/**
 * Context-usage mapping tests pinned against real hermes-webui SSE payloads:
 * `metering` frames from api/streaming.py `_metering_ticker` carry
 * `{tps, tps_available, estimated, high, low, active, session_id, usage}` where
 * `usage` is `_live_usage_snapshot()`: `{input_tokens, output_tokens,
 * context_length, threshold_tokens, last_prompt_tokens,
 * post_compression_context_tokens_estimate, ...}`. Percent parity with
 * `_syncCtxIndicator` (static/ui.js): prompt tokens over window, #1436 —
 * cumulative input_tokens is NEVER the percentage base.
 */
class ContextUsageMapperTest {

    @Test
    fun mapsMeteringUsageToContextUsage() {
        val payload = """
            {"tps":47.3,"tps_available":true,"estimated":false,"high":52.1,"low":31.4,"active":1,
             "session_id":"s1",
             "usage":{"input_tokens":520000,"output_tokens":1200,"estimated_cost":0.42,
                      "cache_read_tokens":100,"cache_write_tokens":10,"cache_hit_percent":0.9,
                      "context_length":200000,"threshold_tokens":160000,"last_prompt_tokens":50000,
                      "post_compression_context_tokens_estimate":null}}
        """.trimIndent()
        assertEquals(
            ChatEvent.ContextUsage(usedTokens = 50000, maxTokens = 200000, percent = 25),
            StreamEventMapper.map("metering", "e1", payload)
        )
    }

    @Test
    fun postCompressionEstimateWinsOverLastPromptTokens() {
        val payload = """
            {"usage":{"context_length":200000,"last_prompt_tokens":190000,
                      "post_compression_context_tokens_estimate":30000}}
        """.trimIndent()
        assertEquals(
            ChatEvent.ContextUsage(30000, 200000, 15),
            StreamEventMapper.map("metering", null, payload)
        )
    }

    @Test
    fun zeroEstimateIsIgnoredLikeWebUi() {
        // postCompression<=0 must NOT mask real prompt tokens (ui.js: hasPostCompressionEstimate=est>0)
        val payload = """{"usage":{"context_length":1000,"last_prompt_tokens":800,"post_compression_context_tokens_estimate":0}}"""
        assertEquals(ChatEvent.ContextUsage(800, 1000, 80), StreamEventMapper.map("metering", null, payload))
    }

    @Test
    fun percentRoundsLikeWebUiAndClampsTo100() {
        // ui.js: rawPct=Math.round(used/window*100); pct=Math.min(100,rawPct)
        assertEquals(
            ChatEvent.ContextUsage(66_666, 200_000, 33),
            StreamEventMapper.map("metering", null, """{"usage":{"context_length":200000,"last_prompt_tokens":66666}}""")
        )
        assertEquals(
            ChatEvent.ContextUsage(250_000, 200_000, 100),
            StreamEventMapper.map("metering", null, """{"usage":{"context_length":200000,"last_prompt_tokens":250000}}""")
        )
    }

    @Test
    fun unknownWindowStaysUnknownNoFakePercentage() {
        // ui.js: ctxWindow>0 required — an unresolved window is NOT a 128K guess (#1436).
        assertNull(StreamEventMapper.map("metering", null, """{"usage":{"context_length":0,"last_prompt_tokens":50000}}"""))
        assertNull(StreamEventMapper.map("metering", null, """{"usage":{"last_prompt_tokens":50000}}"""))
    }

    @Test
    fun zeroPromptTokensMeansNothingMeasuredYet() {
        assertNull(StreamEventMapper.map("metering", null, """{"usage":{"context_length":200000,"last_prompt_tokens":0}}"""))
    }

    @Test
    fun meteringWithoutUsageBlockMapsToNull() {
        // TPS-only frame (final post-done metering packet): no chat content.
        assertNull(StreamEventMapper.map("metering", null, """{"tps":null,"tps_available":false,"estimated":false,"active":0}"""))
    }

    @Test
    fun cumulativeInputTokensNeverBecomeThePercentBase() {
        // Long session: input_tokens=520000 over a 200000 window would be 260% —
        // forbidden by #1436; only last_prompt_tokens counts.
        val payload = """{"usage":{"input_tokens":520000,"output_tokens":3000,"context_length":200000}}"""
        assertNull(StreamEventMapper.map("metering", null, payload))
    }

    @Test
    fun flatContextAndUsageEventNamesAlsoMap() {
        // Alternative frames named `context`/`usage` with a flat payload.
        assertEquals(
            ChatEvent.ContextUsage(40000, 200000, 20),
            StreamEventMapper.map("context", null, """{"context_length":200000,"last_prompt_tokens":40000}""")
        )
        assertEquals(
            ChatEvent.ContextUsage(90000, 128000, 70),
            StreamEventMapper.map("usage", null, """{"context_window":128000,"tokens_used":90000}""")
        )
    }
}
