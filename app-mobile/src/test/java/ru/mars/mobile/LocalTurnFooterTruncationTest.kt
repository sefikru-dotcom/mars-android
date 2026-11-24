package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.local.LocalRole

/**
 * «Обрезан» в футере хода (A1 контракта волны 12).
 *
 * Обрезка по лимиту выходных токенов — отдельное слово от «оборван»: обрыв это сбой или
 * «Стоп», обрезка — штатный предел модели. Проверяется чистая [LocalTurnFooter]: `TextView`
 * без эмулятора не собрать, а вся логика «что рисовать и рисовать ли» живёт в ней.
 */
class LocalTurnFooterTruncationTest {

    @Test
    fun `truncated flag renders the word`() {
        assertEquals(LocalTurnFooter.TRUNCATED, LocalTurnFooter.line(truncated = true))
        // Флаг выключен — слова нет вовсе, а не «обрезан: нет».
        assertNull(LocalTurnFooter.line(truncated = false))
    }

    @Test
    fun `truncated word differs from interrupted`() {
        // Разные причины — разные слова: иначе человек не отличит «модель упёрлась в
        // лимит» от «связь упала».
        assertFalse(LocalTurnFooter.TRUNCATED == LocalTurnFooter.INTERRUPTED)
        assertEquals("обрезан", LocalTurnFooter.TRUNCATED)
        assertEquals("оборван", LocalTurnFooter.INTERRUPTED)
    }

    @Test
    fun `parts go tokens then window then ttft then interrupted then truncated`() {
        val line = LocalTurnFooter.line(
            promptTokens = 12_400,
            completionTokens = 800,
            totalTokens = 13_200,
            windowTokens = 200_000,
            interrupted = true,
            truncated = true,
            ttftMs = 900,
        )
        assertEquals(
            "13.2k ток · 12.4k/200k · первый символ 900мс · оборван · обрезан",
            line,
        )
        assertTrue(line!!.endsWith(LocalTurnFooter.TRUNCATED))
    }

    @Test
    fun `truncation is shown next to usage and alone`() {
        assertEquals(
            "13.2k ток · обрезан",
            LocalTurnFooter.line(totalTokens = 13_200, truncated = true),
        )
        // Обрезка без расхода тоже факт: провайдер мог не прислать `usage`, но причину
        // `length` — прислать.
        assertEquals("обрезан", LocalTurnFooter.line(truncated = true))
    }

    @Test
    fun `item overload carries the truncation flag`() {
        val item = LocalChatItem(
            id = "a-1",
            role = LocalRole.ASSISTANT,
            text = "ответ",
            totalTokens = 500,
            truncated = true,
        )
        assertEquals("500 ток · обрезан", LocalTurnFooter.line(item))

        // Сообщение человека обрезкой не помечается: поле по умолчанию false.
        assertFalse(LocalChatItem(id = "u-1", role = LocalRole.USER, text = "вопрос").truncated)
        assertNull(LocalTurnFooter.line(LocalChatItem(id = "a-2", role = LocalRole.ASSISTANT)))
    }
}
