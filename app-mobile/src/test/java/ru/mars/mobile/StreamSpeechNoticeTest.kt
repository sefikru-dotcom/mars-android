package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тексты индикации потоковой озвучки: подмена голоса и пропущенная фраза.
 *
 * Требование пользователя — «фраза, которую не смогли произнести, должна быть объявлена».
 * Проверяется не «красивость» текста, а два свойства:
 * 1. НИ ОДНА причина отказа не оставляет человека без сообщения (неизвестная строка даёт
 *    общий текст, а не пустоту);
 * 2. подмена голоса описывается ТЕМ ЖЕ текстом, что в неразрывной озвучке, иначе один сбой
 *    читается двумя разными фразами в двух чатах.
 */
class StreamSpeechNoticeTest {

    @Test
    fun `подмена голоса описывается тем же текстом, что и в обычной озвучке`() {
        assertEquals(VoiceSubstitutionNotice.CLOUD_FAILED, StreamSpeechNotice.LOCAL_FALLBACK)
    }

    @Test
    fun `отказ обоих каналов говорит и про облако, и про локальную модель`() {
        val text = StreamSpeechNotice.LOCAL_FAILED
        assertTrue(text.isNotBlank())
        assertTrue("человек должен понять, что резерв тоже не сработал", text.contains("локальн"))
    }

    @Test
    fun `известные причины отказа дают свой текст, а не общий`() {
        val known = listOf(
            "rate_limited",
            "unauthorized",
            "not_found",
            "server_error",
            "transport",
            "empty_audio",
            "invalid_response",
            "playback",
            "playback_rejected",
        )
        known.forEach { reason ->
            val text = StreamSpeechNotice.phraseFailed(reason)
            assertTrue("пустой текст для $reason", text.isNotBlank())
            assertFalse(
                "причина $reason не должна давать общий текст",
                text == StreamSpeechNotice.PHRASE_SKIPPED,
            )
        }
        // Разные КЛАССЫ причин — разные подсказки: иначе «нет сети» и «сервер перегружен»
        // звучали бы одинаково, и человек не понял бы, ждать ему или настраивать.
        val classes = listOf(
            "rate_limited",
            "unauthorized",
            "not_found",
            "server_error",
            "transport",
            "empty_audio",
            "playback",
        ).map { StreamSpeechNotice.phraseFailed(it) }
        assertEquals(classes.size, classes.toSet().size)
    }

    @Test
    fun `неизвестная и пустая причина не оставляют молчания`() {
        assertEquals(StreamSpeechNotice.PHRASE_SKIPPED, StreamSpeechNotice.phraseFailed(""))
        assertEquals(StreamSpeechNotice.PHRASE_SKIPPED, StreamSpeechNotice.phraseFailed("   "))
        assertEquals(StreamSpeechNotice.PHRASE_SKIPPED, StreamSpeechNotice.phraseFailed("что-то новое"))
        // Причина приходит из разных слоёв: регистр и пробелы не должны ломать разбор.
        assertEquals(
            StreamSpeechNotice.phraseFailed("rate_limited"),
            StreamSpeechNotice.phraseFailed(" RATE_LIMITED "),
        )
    }
}
