package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Режим перевода: языки, направление и промпт.
 *
 * Главное, что здесь проверяется — промпт пригоден для ГОЛОСОВОГО ответа: без markdown
 * (его прочтут вслух как «звёздочка звёздочка»), без пояснений и без кавычек. И то, что
 * одинаковые языки не дают промпта вовсе: перевод с русского на русский — не перевод,
 * и отправлять модели такой запрос значило бы получить лекцию вместо ответа.
 */
class TranslatePolicyTest {

    @Test
    fun `языки на месте и первый — русский`() {
        val codes = TranslatePolicy.LANGUAGES.map { it.code }
        assertEquals(listOf("ru", "en", "de", "tr"), codes)
        assertEquals("ru", TranslatePolicy.LANGUAGES.first().code)
        // Дубли кодов сделали бы выбор неоднозначным: две пилюли с одним языком.
        assertEquals(codes.size, codes.distinct().size)
    }

    @Test
    fun `по умолчанию переводим с русского на английский`() {
        val direction = TranslatePolicy.defaultDirection()
        assertEquals("ru", direction.from.code)
        assertEquals("en", direction.to.code)
        assertFalse(direction.isSame)
    }

    @Test
    fun `перестановка дважды возвращает исходное`() {
        val initial = TranslatePolicy.defaultDirection()
        assertEquals(initial, TranslatePolicy.swap(TranslatePolicy.swap(initial)))
        assertEquals(initial.to.code, TranslatePolicy.swap(initial).from.code)
    }

    @Test
    fun `промпт называет оба языка и годится для чтения вслух`() {
        val prompt = TranslatePolicy.systemPrompt(TranslatePolicy.defaultDirection())
        assertTrue(prompt.isNotBlank())
        assertTrue(prompt.contains("Русский"))
        assertTrue(prompt.contains("Английский"))
        // Ни одного markdown-символа: модель прочла бы их как часть ответа, а TTS —
        // произнёс бы «звёздочка».
        listOf('*', '#', '`', '|', '_').forEach { mark ->
            assertFalse("промпт не должен содержать '$mark'", prompt.contains(mark))
        }
        assertTrue(prompt.length <= 800)
    }

    @Test
    fun `одинаковые языки не дают промпта`() {
        val same = TranslateDirection(
            from = TranslatePolicy.LANGUAGES[0],
            to = TranslatePolicy.LANGUAGES[0],
        )
        assertTrue(same.isSame)
        // Пустая строка, а не текст-отказ: отказ, отправленный модели, был бы исполнен
        // как инструкция и озвучен. Проверять надо isBlank.
        assertTrue(TranslatePolicy.systemPrompt(same).isBlank())
        assertNull(TranslatePolicy.promptOrNull(same))
        assertTrue(TranslatePolicy.promptOrNull(TranslatePolicy.defaultDirection()) != null)
    }

    @Test
    fun `пустой и мусорный ответ не считается переводом`() {
        assertFalse(TranslatePolicy.looksLikeTranslation(""))
        assertFalse(TranslatePolicy.looksLikeTranslation("   "))
        assertFalse(TranslatePolicy.looksLikeTranslation("!!!"))
        assertFalse(TranslatePolicy.looksLikeTranslation("```код```"))
        assertFalse(TranslatePolicy.looksLikeTranslation("**Hello**"))
    }

    @Test
    fun `отказы модели распознаются`() {
        assertFalse(TranslatePolicy.looksLikeTranslation("Извините, я не могу"))
        assertFalse(TranslatePolicy.looksLikeTranslation("Извините, я не могу перевести это."))
        assertFalse(TranslatePolicy.looksLikeTranslation("I'm sorry, I can't help with that."))
        assertFalse(TranslatePolicy.looksLikeTranslation("As an AI language model, I cannot translate."))
    }

    @Test
    fun `обычное отрицание и извинение — это перевод, а не отказ`() {
        // Регрессия на наивный фильтр по словам: «не могу» и «sorry» встречаются
        // в нормальном переводе, и глушить его значило бы ломать половину фраз.
        assertTrue(TranslatePolicy.looksLikeTranslation("Извините, я опоздал."))
        assertTrue(TranslatePolicy.looksLikeTranslation("I can't come tomorrow."))
        assertTrue(TranslatePolicy.looksLikeTranslation("Двести рублей, пожалуйста."))
    }

    @Test
    fun `подсказка говорит, чего ждём`() {
        val hint = TranslatePolicy.pendingHint(TranslatePolicy.defaultDirection())
        assertEquals("Русский → Английский. Говорите — перевод появится здесь.", hint)
    }

    @Test
    fun `подсказка на одинаковых языках просит выбрать разные`() {
        val hint = TranslatePolicy.pendingHint(
            TranslateDirection(TranslatePolicy.LANGUAGES[0], TranslatePolicy.LANGUAGES[0]),
        )
        assertTrue(hint.startsWith("Выберите два разных языка"))
        assertTrue(hint.contains("нечего"))
    }

    @Test
    fun `поиск языка терпим к регистру и пробелам`() {
        assertEquals(TranslatePolicy.LANGUAGES[1], TranslatePolicy.byCode(" EN "))
        assertNull(TranslatePolicy.byCode(null))
        assertNull(TranslatePolicy.byCode("xx"))
        assertNull(TranslatePolicy.byCode("  "))
    }

    @Test
    fun `целевые языки не включают исходный`() {
        val targets = TranslatePolicy.availableTargets(TranslatePolicy.LANGUAGES[0]).map { it.code }
        assertEquals(listOf("en", "de", "tr"), targets)
        assertFalse("ru" in targets)
    }

    @Test
    fun `подпись направления читается как пара языков`() {
        assertEquals(
            "Русский → Английский",
            TranslatePolicy.directionLabel(TranslatePolicy.defaultDirection()),
        )
    }
}
