package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Что из хода звучит вслух: ответ — да, мысли — нет, инструменты — одна фраза, финал — не повтор.
 *
 * Тест держит три пользовательских требования: (1) потоковая озвучка начинается на дельтах и
 * не ждёт `Done`; (2) `Done` не читает ответ второй раз; (3) на `tool.started` звучит ровно одно
 * «Проверяю…» без имени инструмента и без обещанного результата, сколько бы инструментов ни
 * вызвалось.
 */
class ChatVoiceTurnSpeechPolicyTest {

    private val policy = VoiceTurnSpeechPolicy()

    @Test
    fun `generated delta is spoken and marks the turn as sounding`() {
        val delta = policy.onGeneratedText("Сейчас посмотрю погоду")
        assertEquals("Сейчас посмотрю погоду", delta)
        assertTrue(policy.generatedSpoken)
    }

    @Test
    fun `empty delta is ignored and does not mark the turn`() {
        assertNull(policy.onGeneratedText(""))
        assertFalse(policy.generatedSpoken)
    }

    @Test
    fun `deliberation is never spoken and never counts as spoken`() {
        assertNull(policy.onDeliberation("сначала я подумал X, но нет, Y"))
        assertFalse(policy.generatedSpoken)
        // Мысли не должны блокировать «Проверяю…»: иначе ход с длинным reasoning молчал бы.
        assertEquals(VoiceTurnSpeechPolicy.PROGRESS_LINE, policy.onToolStarted("search"))
    }

    @Test
    fun `tool start speaks the progress line exactly once per turn`() {
        assertEquals(VoiceTurnSpeechPolicy.PROGRESS_LINE, policy.onToolStarted("search"))
        // Второй и пятый вызовы молчат: пять инструментов — одна фраза, а не пять.
        assertNull(policy.onToolStarted("read_file"))
        assertNull(policy.onToolStarted("web_search"))
        assertTrue(policy.progressAnnounced)
    }

    @Test
    fun `progress line promises nothing about the result`() {
        assertEquals("Проверяю…", VoiceTurnSpeechPolicy.PROGRESS_LINE)
        // Ни «нашёл», ни «готово»: результата на момент tool.started ещё нет.
        assertFalse(VoiceTurnSpeechPolicy.PROGRESS_LINE.contains("нашл"))
        assertFalse(VoiceTurnSpeechPolicy.PROGRESS_LINE.contains("готов"))
        assertFalse(VoiceTurnSpeechPolicy.PROGRESS_LINE.contains("успеш"))
    }

    @Test
    fun `tool start is silent when the answer is already sounding`() {
        policy.onGeneratedText("Ответ пошёл")
        assertNull(policy.onToolStarted("search"))
    }

    @Test
    fun `tool result and phase names are never spoken`() {
        assertNull(policy.onToolFinished("search"))
        assertNull(policy.onProgress("tool_call"))
        assertNull(policy.onProgress("planning"))
    }

    @Test
    fun `final answer is not repeated after streaming speech`() {
        policy.onGeneratedText("Половина отве")
        policy.onGeneratedText("та")
        assertNull(policy.onFinal("Половина ответа"))
    }

    @Test
    fun `final answer is spoken once when nothing sounded`() {
        // Ход без потоковой озвучки (восстановление, фолбэк): финал читается обычным путём.
        assertEquals("Готовый ответ", policy.onFinal("Готовый ответ"))
        assertNull(policy.onFinal("Готовый ответ"))
    }

    @Test
    fun `blank final is not spoken`() {
        assertNull(policy.onFinal(null))
        assertNull(policy.onFinal("   "))
    }

    @Test
    fun `new turn resets both counters`() {
        policy.onGeneratedText("прошлый ответ")
        policy.onToolStarted("search")
        policy.onTurnStarted()
        assertFalse(policy.generatedSpoken)
        assertFalse(policy.progressAnnounced)
        // Следующий ход снова получает «Проверяю…» и свой финал.
        assertEquals(VoiceTurnSpeechPolicy.PROGRESS_LINE, policy.onToolStarted("search"))
        assertEquals("новый ответ", policy.onFinal("новый ответ"))
    }

    @Test
    fun `final after progress line is still spoken`() {
        // «Проверяю…» — не ответ: после него финал обязан прозвучать.
        policy.onToolStarted("search")
        assertEquals("Ответ", policy.onFinal("Ответ"))
    }
}
