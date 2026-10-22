package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Немедленная потоковая речь: первая фраза уходит в синтез на первой же границе, а без
 * пунктуации текст всё равно режется ограниченными кусками.
 *
 * Почему это важно именно для задержки: если ждать точку, ответ без точек (перечисление,
 * длинная реплика с запятыми, поток без знаков конца) не зазвучит до конца хода. Если резать
 * без границы — получим либо абзац в один запрос синтеза (медленно), либо неограниченный
 * рост накопителя (озвучка не стартует вообще).
 */
class SentenceChunkerStreamingTest {

    @Test
    fun `first sentence is emitted immediately without waiting for the turn end`() {
        val chunker = SentenceChunker()

        val phrases = chunker.feed("Привет! Как дела")

        assertEquals(listOf("Привет!"), phrases)
    }

    @Test
    fun `second sentence is emitted while the answer is still streaming`() {
        val chunker = SentenceChunker()
        chunker.feed("Привет! ")

        // Вторая фраза длиннее порога и заканчивается точкой — уходит сразу, без flush.
        val phrases = chunker.feed("Сегодня хорошая погода, можно идти гулять. Осталось")

        assertEquals(listOf("Сегодня хорошая погода, можно идти гулять."), phrases)
        assertTrue(chunker.pendingText.contains("Осталось"))
    }

    @Test
    fun `long text without punctuation is cut by a bounded chunk`() {
        val chunker = SentenceChunker()
        val run = "слово ".repeat(80) // ~480 символов без единого знака конца

        val phrases = chunker.feed(run)

        assertTrue("первая фраза должна уйти до конца хода", phrases.isNotEmpty())
        val max = SentenceChunker.DEFAULT_MAX_PHRASE_LENGTH
        assertTrue("кусок не длиннее лимита: ${phrases.first().length}", phrases.first().length <= max)
    }

    @Test
    fun `text without any whitespace is still cut instead of growing forever`() {
        val chunker = SentenceChunker()
        val blob = "а".repeat(600)

        val phrases = chunker.feed(blob)

        assertTrue("нет пробелов — режем по лимиту, а не копим", phrases.isNotEmpty())
        assertTrue(phrases.all { it.length <= SentenceChunker.DEFAULT_MAX_PHRASE_LENGTH })
    }

    @Test
    fun `flush returns the tail exactly once`() {
        val chunker = SentenceChunker()
        chunker.feed("Хвост без точки")

        assertEquals(listOf("Хвост без точки"), chunker.flush())
        assertTrue("после flush состояние чистое", chunker.flush().isEmpty())
    }

    @Test
    fun `reset drops the buffer so an interrupted turn is not spoken later`() {
        val chunker = SentenceChunker()
        chunker.feed("Недосказанная фраза без точки")

        chunker.reset()

        assertTrue(chunker.flush().isEmpty())
    }

    @Test
    fun `code fences and tables never reach the synthesizer`() {
        // Режет фразы чанкер, а разметку вычищает нормализатор — проверяем связку,
        // потому что именно она стоит между стримом и TTS.
        val chunker = SentenceChunker()
        val normalizer = VoiceNormalizer()

        val spoken = (chunker.feed("Готово.\n```\nval x = 1\n```\n| a | b |\n|---|---|\nДальше текст.") + chunker.flush())
            .map(normalizer::normalize)

        assertTrue("блок кода не должен озвучиваться: $spoken", spoken.none { it.contains("val x") })
        assertTrue("разделитель таблицы не должен озвучиваться: $spoken", spoken.none { it.contains("---") })
        assertTrue("разделитель таблицы не должен озвучиваться: $spoken", spoken.none { it.contains("|") })
        assertTrue(spoken.any { it.contains("Готово") })
        assertTrue("текст после таблицы теряется: $spoken", spoken.any { it.contains("Дальше") })
    }
}
