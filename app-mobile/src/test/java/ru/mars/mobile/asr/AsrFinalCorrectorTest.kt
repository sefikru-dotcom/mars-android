package ru.mars.mobile.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты [AsrFinalCorrector]: обращение «марш» → «Марс» и, главное, НЕ-срабатывания.
 *
 * Негативные случаи здесь важнее позитивных: корректор стоит на пути в LLM, и лишняя замена
 * «марш» → «Марс» превращает нормальную фразу («включи военный марш») в бессмыслицу, которую
 * модель ещё и уверенно объяснит. Поэтому каждый способ сказать «марш» как обычное слово
 * закреплён отдельным тестом.
 */
class AsrFinalCorrectorTest {

    private fun correct(raw: String) = AsrFinalCorrector.correct(raw)

    // --- обращение: правим ----------------------------------------------------------------

    @Test
    fun `обращение в начале с запятой исправляется`() {
        val c = correct("марш, открой порт восемь тысяч девяносто")

        assertEquals("Марс, открой порт восемь тысяч девяносто", c.text)
        assertEquals(1, c.replacements)
        assertTrue(c.changed)
        assertEquals("сырой текст сохранён", "марш, открой порт восемь тысяч девяносто", c.raw)
    }

    @Test
    fun `обращение в начале без запятой исправляется`() {
        // Реальный вывод модели на «Марс, открой порт 8090»: запятую она не ставит.
        assertEquals("Марс открой порт восемь тысяч девяносто", correct("марш открой порт восемь тысяч девяносто").text)
    }

    @Test
    fun `обращение в конце фразы исправляется`() {
        assertEquals("как дела, Марс?", correct("как дела, марш?").text)
        assertEquals("проверь диск, Марс", correct("проверь диск, марш").text)
    }

    @Test
    fun `заглавное и капсом тоже исправляются`() {
        assertEquals("Марс, слушай", correct("Марш, слушай").text)
        assertEquals("Марс, слушай", correct("МАРШ, слушай").text)
    }

    @Test
    fun `коррекция идемпотентна`() {
        val once = correct("марш, открой порт").text

        val twice = correct(once)

        assertEquals("Марс, открой порт", twice.text)
        assertFalse("повторный прогон ничего не меняет", twice.changed)
    }

    // --- обычное слово: НЕ правим ---------------------------------------------------------

    @Test
    fun `военный марш не трогаем`() {
        assertUnchanged("включи военный марш")
        assertUnchanged("поставь торжественный марш")
        assertUnchanged("это похоронный марш")
    }

    @Test
    fun `марш бросок не трогаем`() {
        assertUnchanged("марш бросок на десять километров")
        assertUnchanged("марш-бросок в пять утра")
    }

    @Test
    fun `команда шагом марш не трогаем`() {
        assertUnchanged("шагом марш")
        assertUnchanged("шагом марш, бойцы")
    }

    @Test
    fun `команда-побуждение марш отсюда не трогаем`() {
        assertUnchanged("марш отсюда")
        assertUnchanged("марш домой")
        assertUnchanged("марш спать")
    }

    @Test
    fun `одиночное марш не трогаем`() {
        assertUnchanged("марш")
        assertUnchanged("марш!")
        assertUnchanged("марш.")
    }

    @Test
    fun `повтор марш марш не трогаем`() {
        assertUnchanged("марш, марш!")
        assertUnchanged("марш, марш, марш")
    }

    @Test
    fun `другие словоформы не трогаем`() {
        assertUnchanged("маршем по площади")
        assertUnchanged("звуки марша")
        assertUnchanged("к маршу готовы")
    }

    @Test
    fun `слово внутри другого слова не трогаем`() {
        assertUnchanged("маршрут до сервера")
        assertUnchanged("маршал приехал")
    }

    @Test
    fun `уже правильное имя не трогаем`() {
        assertUnchanged("Марс, открой порт")
        assertUnchanged("марс открой порт")
    }

    @Test
    fun `обычная речь с марш в середине без запятой не трогается`() {
        assertUnchanged("включи мне марш на колонке")
        assertUnchanged("поставь марш погромче")
    }

    @Test
    fun `пустой и посторонний текст не меняется`() {
        assertUnchanged("")
        assertUnchanged("проверка связи")
        assertUnchanged("альпен и квартал работают")
    }

    // --- несколько вхождений ---------------------------------------------------------------

    @Test
    fun `правится только вокатив, обычное слово в той же фразе остаётся`() {
        val c = correct("марш, включи военный марш")

        assertEquals("Марс, включи военный марш", c.text)
        assertEquals(1, c.replacements)
    }

    private fun assertUnchanged(raw: String) {
        val c = correct(raw)
        assertEquals("текст не должен меняться: «$raw»", raw, c.text)
        assertEquals("замен не должно быть: «$raw»", 0, c.replacements)
        assertFalse(c.changed)
    }
}
