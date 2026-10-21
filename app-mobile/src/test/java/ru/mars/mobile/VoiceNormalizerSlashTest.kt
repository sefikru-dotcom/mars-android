package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Литеральный `/` перед синтезом.
 *
 * Замер обратным STT (research/REPORT.md): piper читает `/` как «косая черта» —
 * `«Слэш ждёт прогулку. / »` → «Касай черта!». Поэтому символ обязан исчезнуть до TTS при
 * любом движке, но чистка должна быть КОНТЕКСТНОЙ: `и/или` и `api/tts` — не мусор.
 *
 * Имя «Слэш» не переписывается: словарной замены нет вовсе (она ломает Svetlana и не
 * помогает irina).
 */
class VoiceNormalizerSlashTest {

    private val normalizer = VoiceNormalizer()

    @Test
    fun `lone slash is not spoken as a word`() {
        val spoken = normalizer.normalize("Слэш ждёт прогулку. / ")

        assertFalse("символ не должен доехать до синтеза: $spoken", spoken.contains("/"))
        assertTrue(spoken.contains("Слэш"))
        assertFalse(spoken.contains("косая"))
        assertFalse(spoken.contains("черта"))
    }

    @Test
    fun `path separators are gone along with the path`() {
        val spoken = normalizer.normalize("Открой путь /root/workspace и слэш в конце.")

        assertFalse(spoken, spoken.contains("/"))
        assertTrue(spoken, spoken.contains("слэш"))
        assertTrue("путь читается словом «путь»: $spoken", spoken.contains("путь"))
    }

    @Test
    fun `and-or slash becomes the word or`() {
        assertEquals("Можно так или так", normalizer.normalize("Можно так и/или так"))
        assertEquals("Один или два", normalizer.normalize("Один и/или  два"))
    }

    @Test
    fun `word slash word keeps both words without the symbol`() {
        val spoken = normalizer.normalize("Ручка api/tts отвечает быстро")

        assertFalse(spoken, spoken.contains("/"))
        // Аббревиатуры читаются по словарю, но оба слова на месте и в правильном порядке.
        assertTrue(spoken, spoken.contains("эй-пи-ай"))
        assertTrue(spoken, spoken.contains("ти-ти-эс"))
        assertTrue(spoken, spoken.indexOf("эй-пи-ай") < spoken.indexOf("ти-ти-эс"))
    }

    @Test
    fun `double and back slashes collapse to a pause`() {
        val spoken = normalizer.normalize("Вариант // второй и C:\\temp")

        assertFalse(spoken, spoken.contains("/"))
        assertFalse(spoken, spoken.contains("\\"))
        assertTrue(spoken.contains("Вариант"))
        assertTrue(spoken.contains("второй"))
    }

    @Test
    fun `the name Slash is never rewritten into a phonetic spelling`() {
        // Ни «Слээш», ни «Слэйш»: единственное правильное написание — «Слэш».
        assertEquals("Слэш", normalizer.normalize("Слэш"))
        assertEquals("Слэш, ко мне!", normalizer.normalize("Слэш, ко мне!"))
        assertEquals("Слэш и Марс идут гулять.", normalizer.normalize("Слэш и Марс идут гулять."))
    }

    @Test
    fun `text without slashes is untouched`() {
        val text = "Альпен работает на порту восемь тысяч девяносто."
        assertEquals(text, normalizer.normalize(text))
    }
}
