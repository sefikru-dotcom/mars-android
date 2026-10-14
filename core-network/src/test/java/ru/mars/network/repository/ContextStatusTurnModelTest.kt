package ru.mars.network.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.mars.network.model.ContextStatusUpdate

/**
 * Turn-start model attribution (волна C, фича A, транспортная половина): открывающий
 * кадр `context_status` аддитивно несёт фактическую модель хода в ключах `model` /
 * `model_provider` (hermes-webui api/streaming.py + api/gateway_chat.py — resolved_model
 * и запрошенная идентичность провайдера; те же имена, что у финального session payload).
 *
 * Контракт: значения доставляются дословно; отсутствие/пустота → null («сервер не
 * сообщил») — транспорт НИКОГДА не подставляет запрошенную клиентом модель; существующие
 * поля prefill не меняются.
 */
class ContextStatusTurnModelTest {

    @Test
    fun `context_status frame delivers turn model and provider additively`() {
        val payload = """
            {
              "session_id": "s1",
              "prefill": {
                "status": "loaded",
                "source": "session_recall",
                "label": "session recall",
                "message_count": 12
              },
              "model": "qwen3.8-max",
              "model_provider": "custom:plusvibe-api"
            }
        """.trimIndent()

        val update = StreamEventMapper.sideEvent("context_status", payload) as ContextStatusUpdate

        assertEquals("qwen3.8-max", update.model)
        assertEquals("custom:plusvibe-api", update.modelProvider)
        // Существующий контракт prefill не сломан.
        assertEquals("s1", update.sessionId)
        assertEquals("loaded", update.status)
        assertEquals("session recall", update.label)
        assertEquals("session_recall", update.source)
        assertEquals(12, update.messageCount)
    }

    @Test
    fun `frame without model keys keeps nulls - never falls back to the requested model`() {
        val payload = """
            {"session_id":"s1","prefill":{"status":"not_configured","message_count":0}}
        """.trimIndent()

        val update = StreamEventMapper.sideEvent("context_status", payload) as ContextStatusUpdate

        assertNull(update.model)
        assertNull(update.modelProvider)
        assertEquals("not_configured", update.status)
    }

    @Test
    fun `blank or non-string model values are dropped to null`() {
        val payload = """
            {"session_id":"s1","prefill":{"status":"loaded"},"model":"   ","model_provider":""}
        """.trimIndent()

        val update = StreamEventMapper.sideEvent("context_status", payload) as ContextStatusUpdate

        assertNull(update.model)
        assertNull(update.modelProvider)
    }

    @Test
    fun `frame without prefill is still dropped even when it carries a model`() {
        // Drop-семантика маппера неизменна: кадр без prefill/status не несёт состояния
        // префилла и отбрасывается целиком (сервер всегда шлёт prefill в этом кадре).
        val payload = """{"session_id":"s1","model":"gpt-5.2","model_provider":"openai"}"""

        assertNull(StreamEventMapper.sideEvent("context_status", payload))
    }
}
