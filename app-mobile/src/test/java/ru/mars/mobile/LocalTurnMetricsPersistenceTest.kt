package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.local.LocalMessage
import ru.mars.network.local.LocalRole
import ru.mars.network.local.LocalSession
import ru.mars.network.local.LocalTurnMetrics

/**
 * Метрики хода в файле диалога (A2 контракта волны 12).
 *
 * Три требования, которые проверяются здесь: метрика переживает запись и чтение;
 * старый файл без `metrics` читается как раньше; у сообщений человека и TOOL метрики не
 * пишутся вовсе. Формат — `org.json` из тестового classpath, никакого Android.
 */
class LocalTurnMetricsPersistenceTest {

    private fun encode(messages: List<LocalMessage>): String =
        LocalSessionJson.encode(LocalSession(id = "s1", title = "Диалог", updatedAt = 1L), messages)

    private fun decode(raw: String): List<LocalMessage> =
        LocalSessionJson.decode(raw)?.messages.orEmpty()

    private fun assistant(metrics: LocalTurnMetrics?): LocalMessage = LocalMessage(
        id = "m1",
        role = LocalRole.ASSISTANT,
        text = "ответ",
        createdAt = 5L,
        metrics = metrics,
    )

    // --- запись и чтение ----------------------------------------------------

    @Test
    fun `metrics survive encode and decode`() {
        val metrics = LocalTurnMetrics(
            promptTokens = 1_200,
            completionTokens = 300,
            totalTokens = 1_500,
            ttftMs = 640L,
            interrupted = true,
            truncated = true,
            finishReason = "length",
        )

        val restored = decode(encode(listOf(assistant(metrics)))).single()

        assertEquals("метрики потерялись при сохранении", metrics, restored.metrics)
        assertEquals("ответ", restored.text)
    }

    @Test
    fun `null finish reason is not written and reads back as null`() {
        val raw = encode(listOf(assistant(LocalTurnMetrics(totalTokens = 10))))

        // Ключ не пишем: `null` в файле утверждал бы «причина — null», хотя она неизвестна.
        assertFalse("finishReason не должен появляться при null: $raw", raw.contains("finishReason"))
        assertNull(decode(raw).single().metrics?.finishReason)
        assertEquals(10, decode(raw).single().metrics?.totalTokens)
    }

    @Test
    fun `unmeasured metrics are not written at all`() {
        // Нули — не «ноль токенов», а «шаг не измерялся»: сохранять их значит утверждать
        // расход, которого провайдер не сообщал.
        val raw = encode(listOf(assistant(LocalTurnMetrics())))
        assertFalse("нулевые метрики писать нельзя: $raw", raw.contains("\"metrics\""))
        assertNull(decode(raw).single().metrics)
    }

    @Test
    fun `message without metrics keeps the old set of keys`() {
        // Свежий диалог обязан остаться таким, каким его писала волна 1.
        val json = LocalSessionJson.encodeMessage(
            LocalMessage(id = "m1", role = LocalRole.ASSISTANT, text = "ответ", createdAt = 7L)
        )
        val keys = json.keys().asSequence().toSet()
        assertEquals(setOf("id", "role", "text", "createdAt"), keys)
    }

    // --- метрики не у тех, кому не положено --------------------------------

    @Test
    fun `user message metrics are never written`() {
        val user = LocalMessage(
            id = "u1",
            role = LocalRole.USER,
            text = "вопрос",
            metrics = LocalTurnMetrics(promptTokens = 12, totalTokens = 12),
        )
        val raw = encode(listOf(user))

        assertFalse("у сообщения человека метрик быть не должно: $raw", raw.contains("\"metrics\""))
        assertNull(decode(raw).single().metrics)
    }

    @Test
    fun `metrics in a file for a non assistant role are ignored on read`() {
        // Файл мог быть испорчен или записан чужой версией: футер под вопросом человека —
        // худший из возможных исходов.
        val raw = """{"version":2,"id":"s1","title":"Т","updatedAt":5,"messages":[
            {"id":"u1","role":"user","text":"вопрос",
             "metrics":{"promptTokens":12,"totalTokens":12}}]}"""

        val message = decode(raw).single()
        assertNull(message.metrics)
        assertEquals("вопрос", message.text)
    }

    // --- совместимость со старыми файлами ----------------------------------

    @Test
    fun `file without metrics still opens`() {
        val old = """{"version":1,"id":"s1","title":"Т","updatedAt":5,"messages":[
            {"id":"u1","role":"user","text":"привет"},
            {"id":"m2","role":"assistant","text":"ответ"}]}"""

        val messages = decode(old)

        assertEquals(2, messages.size)
        assertEquals("привет", messages[0].text)
        assertEquals("ответ", messages[1].text)
        assertNull("у старого файла метрик нет", messages[1].metrics)
    }

    @Test
    fun `broken metrics field does not lose the dialog`() {
        val broken = """{"version":2,"id":"s1","title":"Т","updatedAt":5,"messages":[
            {"id":"m2","role":"assistant","text":"ответ","metrics":"не объект"}]}"""

        val message = decode(broken).single()

        assertEquals("ответ", message.text)
        assertNull("битое поле метрик — это null, а не исключение", message.metrics)
    }

    @Test
    fun `metrics values are read tolerantly`() {
        // Строка с числом и флаг числом/строкой: файл писался другой версией кодека —
        // это не повод терять метрику целиком.
        val loose = """{"version":2,"id":"s1","title":"Т","updatedAt":5,"messages":[
            {"id":"m2","role":"assistant","text":"ответ","metrics":{
              "promptTokens":"1200","completionTokens":"300","totalTokens":"1500",
              "ttftMs":"640","interrupted":"true","truncated":1,"finishReason":"length"}}]}"""

        val metrics = decode(loose).single().metrics

        assertEquals(1_200, metrics?.promptTokens)
        assertEquals(300, metrics?.completionTokens)
        assertEquals(1_500, metrics?.totalTokens)
        assertEquals(640L, metrics?.ttftMs)
        assertTrue(metrics?.interrupted == true)
        assertTrue(metrics?.truncated == true)
        assertEquals("length", metrics?.finishReason)
    }

    @Test
    fun `missing flags in metrics read as false`() {
        val sparse = """{"version":2,"id":"s1","title":"Т","updatedAt":5,"messages":[
            {"id":"m2","role":"assistant","text":"ответ","metrics":{"totalTokens":10}}]}"""

        val metrics = decode(sparse).single().metrics

        assertEquals(10, metrics?.totalTokens)
        assertFalse(metrics?.interrupted == true)
        assertFalse(metrics?.truncated == true)
        assertNull(metrics?.finishReason)
    }
}
