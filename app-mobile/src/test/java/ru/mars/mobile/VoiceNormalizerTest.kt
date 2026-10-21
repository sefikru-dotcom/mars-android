package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Произносимая форма технического текста перед синтезом.
 *
 * Набор из задачи проверяется целиком: порт, код HTTP, число комнат и IP с процентом —
 * именно на них серверный TTS без нормализации читает цифры неверно.
 */
class VoiceNormalizerTest {

    private val normalizer = VoiceNormalizer()

    @Test
    fun `порт читается словами`() {
        assertEquals("восемь тысяч девяносто", normalizer.normalize("8090"))
        assertEquals(
            "Альпен работает на порту восемь тысяч девяносто.",
            normalizer.normalize("Альпен работает на порту 8090."),
        )
    }

    @Test
    fun `код HTTP и аббревиатура читаются по словарю`() {
        assertEquals(
            "Я нашёл ошибку эйч-ти-ти-пи пятьсот два в журнале.",
            normalizer.normalize("Я нашёл ошибку HTTP 502 в журнале."),
        )
    }

    @Test
    fun `число комнат остаётся числом словами`() {
        assertEquals(
            "В базе обнаружено двенадцать активных игровых комнат.",
            normalizer.normalize("В базе обнаружено 12 активных игровых комнат."),
        )
    }

    @Test
    fun `IP и процент заменяются произносимыми формами`() {
        val spoken = normalizer.normalize("Квартал: 45.129.2.36, диск 77%")

        assertTrue(spoken.contains("ай-пи адрес"))
        assertTrue(spoken.contains("семьдесят семь процентов"))
        assertFalse(spoken.contains("45.129"))
    }

    @Test
    fun `версия читается по компонентам`() {
        assertEquals("один точка семь точка шесть", normalizer.normalize("1.7.6"))
    }

    @Test
    fun `род числительного берётся по следующему слову`() {
        assertEquals("одна комната", normalizer.normalize("1 комната"))
        assertEquals("две комнаты", normalizer.normalize("2 комнаты"))
        assertEquals("один порт", normalizer.normalize("1 порт"))
    }

    @Test
    fun `время читается часами и минутами`() {
        assertEquals("семь часов ровно", normalizer.normalize("07:00"))
        assertEquals("семь часов тридцать минут", normalizer.normalize("07:30"))
    }

    @Test
    fun `ссылки, пути и хэши не читаются посимвольно`() {
        assertEquals("ссылка", normalizer.normalize("https://example.com/a/b"))
        assertEquals("путь", normalizer.normalize("/root/workspace/app.kt"))
        val withHash = normalizer.normalize("коммит 3f9a2b1c7d готов")
        assertFalse(withHash.contains("3f9a2b1c7d"))
    }

    @Test
    fun `эмодзи и markdown-символы удаляются`() {
        assertEquals("Готово", normalizer.normalize("Готово ✅"))
        assertEquals("Важно: тест", normalizer.normalize("**Важно**: тест"))
    }

    @Test
    fun `блок кода выпадает целиком`() {
        val spoken = normalizer.normalize("До кода.\n```\nval x = 1\n```\nПосле кода.")

        assertFalse(spoken.contains("val x"))
        assertTrue(spoken.contains("До кода."))
        assertTrue(spoken.contains("После кода."))
    }

    @Test
    fun `мегабайты и гигабайты произносятся словами`() {
        assertEquals("двенадцать гигабайт", normalizer.normalize("12 GB"))
        assertEquals("пятьсот мегабайт", normalizer.normalize("500 МБ"))
    }
}
