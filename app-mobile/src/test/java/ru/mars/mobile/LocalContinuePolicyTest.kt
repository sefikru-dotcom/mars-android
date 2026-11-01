package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.local.LocalTurnMetrics

/**
 * Правило «Продолжить» для обрезанного ответа.
 *
 * Закрепляется главное свойство: предложение продолжения появляется только там, где
 * продолжение действительно дописывает недописанное. Обрыв по «Стоп», пустой ответ и
 * любая причина остановки, кроме лимита токенов, дают `false` — иначе кнопка звала бы
 * человека дописывать законченный ответ или обходить фильтр площадки.
 */
class LocalContinuePolicyTest {

    // --- когда предлагать --------------------------------------------------------------

    @Test
    fun honestTruncationWithTextOffersContinue() {
        assertTrue(
            LocalContinuePolicy.shouldOffer(
                truncated = true,
                interrupted = false,
                text = "Первая часть ответа, которая оборвалась на полуслове",
            ),
        )
    }

    @Test
    fun interruptedAnswerNeverOffersContinue() {
        // Человек нажал «Стоп»: продолжение — новый ход против его воли, а не дописывание.
        assertFalse(
            LocalContinuePolicy.shouldOffer(
                truncated = true,
                interrupted = true,
                text = "Начало ответа",
            ),
        )
        // Даже если обрезки не было, обрыв сам по себе ничего не предлагает.
        assertFalse(LocalContinuePolicy.shouldOffer(truncated = false, interrupted = true, text = "Начало"))
    }

    @Test
    fun emptyOrBlankAnswerDoesNotOfferContinue() {
        assertFalse("пустой ответ", LocalContinuePolicy.shouldOffer(truncated = true, interrupted = false, text = ""))
        assertFalse("пробелы", LocalContinuePolicy.shouldOffer(truncated = true, interrupted = false, text = "   "))
        assertFalse(
            "переводы строк",
            LocalContinuePolicy.shouldOffer(truncated = true, interrupted = false, text = "\n\t \n"),
        )
    }

    @Test
    fun notTruncatedAnswerDoesNotOfferContinue() {
        // finish_reason = stop: модель закончила сама, продолжать нечего.
        assertFalse(
            LocalContinuePolicy.shouldOffer(
                truncated = false,
                interrupted = false,
                text = "Полный ответ, законченный точкой.",
            ),
        )
    }

    // --- причина остановки -------------------------------------------------------------

    @Test
    fun onlyLengthReasonAllowsContinue() {
        assertTrue(LocalContinuePolicy.reasonAllowsContinue(LocalTurnMetrics.REASON_LENGTH))
        assertEquals("length", LocalTurnMetrics.REASON_LENGTH)
    }

    @Test
    fun otherReasonsDoNotAllowContinue() {
        // stop — модель закончила сама; tool_calls — ход передан инструментам;
        // content_filter — фильтр площадки, повтор упрётся в него же.
        listOf("stop", "tool_calls", "content_filter").forEach { reason ->
            assertFalse("причина $reason не даёт продолжения", LocalContinuePolicy.reasonAllowsContinue(reason))
        }
    }

    @Test
    fun missingReasonDoesNotAllowContinue() {
        // Провайдер не прислал finish_reason — это норма, но «неизвестно» не значит «обрезано».
        assertFalse(LocalContinuePolicy.reasonAllowsContinue(null))
        assertFalse("пустая строка — тоже неизвестно", LocalContinuePolicy.reasonAllowsContinue(""))
        assertFalse("пробелы — тоже неизвестно", LocalContinuePolicy.reasonAllowsContinue("  "))
    }

    @Test
    fun reasonComparisonIgnoresCaseAndEdgeSpaces() {
        // Часть шлюзов отдаёт причину в верхнем регистре или с пробелом по краям.
        assertTrue(LocalContinuePolicy.reasonAllowsContinue("LENGTH"))
        assertTrue(LocalContinuePolicy.reasonAllowsContinue(" length "))
        // Но регистр не превращает чужую причину в обрезку.
        assertFalse(LocalContinuePolicy.reasonAllowsContinue("STOP"))
        assertFalse(LocalContinuePolicy.reasonAllowsContinue("unknown"))
    }

    // --- служебная реплика -------------------------------------------------------------

    @Test
    fun continuePromptIsShortPlainRussianText() {
        val prompt = LocalContinuePolicy.CONTINUE_PROMPT
        assertTrue("реплика не должна быть пустой", prompt.isNotBlank())
        // Уходит в историю как обычный текст: кавычки, номера и идентификаторы в ней
        // читались бы моделью как часть просьбы, а не как служебная пометка.
        listOf('"', '\'', '«', '»', '“', '”', '`').forEach { quote ->
            assertFalse("кавычка $quote в реплике", prompt.contains(quote))
        }
        assertFalse("идентификатор в реплике", Regex("[A-Za-z0-9_]{4,}").containsMatchIn(prompt))
        // Короткая: она тратит окно на каждом продолжении.
        assertTrue("реплика длиннее 120 символов", prompt.length <= 120)
        // Смысл реплики: продолжить, а не пересказать заново.
        assertTrue("нет просьбы продолжить", prompt.contains("родолж"))
        assertTrue("нет запрета повторов", prompt.contains("повтор"))
    }
}
