package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Предпросмотр живого транскрипта в композере.
 *
 * Главное требование: partial ASR никогда не затирает текст, который человек набрал сам.
 * Обратная сторона тоже важна — предпросмотр должен РАБОТАТЬ, иначе непонятно, слышит ли
 * Марс, и человек говорит в пустоту.
 */
class VoicePartialPreviewPolicyTest {

    private val policy = VoicePartialPreviewPolicy()

    @Test
    fun `empty composer accepts the first partial`() {
        val decision = policy.onPartial("привет как дела", "")

        assertEquals(VoicePartialPreviewPolicy.Decision.Apply("привет как дела"), decision)
        assertEquals("привет как дела", policy.lastApplied)
    }

    @Test
    fun `next partial replaces our own previous preview`() {
        policy.onPartial("привет", "")

        val decision = policy.onPartial("привет как", "привет")

        assertEquals(VoicePartialPreviewPolicy.Decision.Apply("привет как"), decision)
    }

    @Test
    fun `user typed text is never overwritten`() {
        policy.onPartial("привет", "")

        // Человек стёр предпросмотр и начал печатать сам.
        val decision = policy.onPartial("привет как дела", "мой ответ")

        assertEquals(VoicePartialPreviewPolicy.Decision.Keep, decision)
        assertEquals("привет", policy.lastApplied)
    }

    @Test
    fun `text typed before the preview started is preserved`() {
        val decision = policy.onPartial("привет", "черновик пользователя")

        assertEquals(VoicePartialPreviewPolicy.Decision.Keep, decision)
    }

    @Test
    fun `blank partial keeps the composer untouched`() {
        assertEquals(VoicePartialPreviewPolicy.Decision.Keep, policy.onPartial("   ", ""))
        assertEquals(VoicePartialPreviewPolicy.Decision.Keep, policy.onPartial("", "текст"))
    }

    @Test
    fun `after the final the composer is free again`() {
        policy.onPartial("привет", "")
        policy.onFinal()

        // Метка снята: текст в поле больше не «наш предпросмотр», и следующий partial его не
        // трогает — финальный текст в поле пишет обычный путь отправки, а не предпросмотр.
        val decision = policy.onPartial("совсем другое", "привет")

        assertEquals(VoicePartialPreviewPolicy.Decision.Keep, decision)
        assertEquals(null, policy.lastApplied)
    }

    @Test
    fun `reset starts the next dialog from scratch`() {
        policy.onPartial("привет", "")
        policy.reset()

        assertEquals(VoicePartialPreviewPolicy.Decision.Apply("другое"), policy.onPartial("другое", ""))
    }

    @Test
    fun `partial with surrounding spaces is trimmed before it lands in the composer`() {
        val decision = policy.onPartial("  привет  ", "")

        assertEquals(VoicePartialPreviewPolicy.Decision.Apply("привет"), decision)
    }
}
