package ru.mars.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Нарезка речи: первый кусок по клаузу, дальше — пачки по границе слова.
 *
 * Почему это тестируется отдельно от Android: ошибка здесь не видна «на глаз» и стоит дорого.
 * Слишком поздно отданный первый кусок — это и есть жалоба «Марс думает после того, как уже
 * ответил»; резка посреди слова — невнятная речь; потерянный `rest` — выпавшее из ответа
 * предложение, которое человек никогда не услышит (и не узнает, что оно было).
 */
class SpeechChunkerTest {

    /** Прокачать текст через нарезку так же, как это делает LocalVoiceTts: дельтами до конца. */
    private fun spoken(text: String, deltaSize: Int = 5): List<String> {
        val chunks = ArrayList<String>()
        var buffer = ""
        for (index in text.indices step deltaSize) {
            buffer += text.substring(index, minOf(index + deltaSize, text.length))
            while (true) {
                val chunk = SpeechChunker.nextChunk(buffer, first = chunks.isEmpty()) ?: break
                chunks += chunk.text
                buffer = chunk.rest
            }
        }
        chunks += SpeechChunker.flush(buffer)
        return chunks
    }

    /** Содержимое без пробелов: пробелы на стыках кусков съедает trim, и это нормально. */
    private fun squashed(text: String): String = text.filterNot { it.isWhitespace() }

    @Test
    fun firstChunkIsFirstClause() {
        val chunk = SpeechChunker.nextChunk("Привет, как дела? Всё хорошо", first = true)

        assertNotNull("клауз до запятой — отдавать можно сразу", chunk)
        assertEquals("Привет,", chunk!!.text)
        assertEquals(" как дела? Всё хорошо", chunk.rest)
    }

    @Test
    fun firstChunkStopsOnEveryClauseMark() {
        val marks = listOf(",", ".", ":", "!", "?", "—", "\n")
        for (mark in marks) {
            val chunk = SpeechChunker.nextChunk("Слово${mark}продолжение", first = true)
            // Перевод строки съедает trim, остальные знаки остаются в куске: синтезу важен
            // смысл паузы, а не сам символ.
            assertEquals("знак «$mark» закрывает первый кусок", "Слово$mark".trimEnd(), chunk?.text)
            assertEquals("после знака «$mark» остаток не тронут", "продолжение", chunk?.rest?.trim())
        }
    }

    @Test
    fun firstChunkWaitsWithoutClauseUntilThreshold() {
        // 59 символов без единого знака: отдавать такой огрызок — заикание.
        val short = "аб аб аб аб аб аб аб аб аб аб аб аб аб аб аб аб аб"
        assertTrue("буфер короче порога: ${short.length}", short.length < SpeechChunker.FIRST_CLAUSE_MIN_CHARS)
        assertNull(SpeechChunker.nextChunk(short, first = true))

        val long = short.padEnd(SpeechChunker.FIRST_CLAUSE_MIN_CHARS, 'б')
        assertNotNull("порог набран — режем по последней границе слова", SpeechChunker.nextChunk(long, first = true))
    }

    @Test
    fun longTextWithoutPunctuationIsCutByWord() {
        val text = (1..40).joinToString(" ") { "слово$it" }
        val chunk = SpeechChunker.nextChunk(text, first = true)

        assertNotNull(chunk)
        val first = chunk!!.text
        assertTrue("первый кусок не больше порога: ${first.length}", first.length <= SpeechChunker.FIRST_CLAUSE_MIN_CHARS)
        // Границей выбран пробел: символ сразу за куском — не буква, значит слово не разорвано.
        assertTrue("кусок короче текста", first.length < text.length)
        assertFalse("кусок не должен кончаться внутри слова", text[first.length].isLetter())
    }

    @Test
    fun batchesAreAboutThreeHundredCharsOnWordBoundary() {
        val text = (1..120).joinToString(" ") { "слово$it" }
        val chunk = SpeechChunker.nextChunk(text, first = false)

        assertNotNull("пачка набралась", chunk)
        val batch = chunk!!.text
        assertTrue("пачка не больше ${SpeechChunker.BATCH_CHARS}: ${batch.length}", batch.length <= SpeechChunker.BATCH_CHARS)
        assertTrue("пачка не меньше двухсот символов: ${batch.length}", batch.length > 200)
        // Резали по пробелу/препинанию, а не по счётчику: слово не разорвано.
        assertFalse("пачка не должна кончаться внутри слова", text[batch.length].isLetter())
        assertTrue("остаток — это хвост того же текста", text.endsWith(chunk.rest))
    }

    @Test
    fun noBatchBeforeThreeHundredChars() {
        val short = (1..10).joinToString(" ") { "слово$it" }
        assertNull("пачку ждём до ${SpeechChunker.BATCH_CHARS} символов", SpeechChunker.nextChunk(short, first = false))
    }

    @Test
    fun dotInsideNumberIsNotAClause() {
        val chunk = SpeechChunker.nextChunk("Сейчас 1.5 градуса, а к ночи минус", first = true)

        assertEquals("«1.5» — одно слово, а не конец клауза", "Сейчас 1.5 градуса,", chunk?.text)
    }

    @Test
    fun hyphenInsideWordIsNotAClause() {
        val chunk = SpeechChunker.nextChunk("По-русски это кто-то, сказал он", first = true)

        assertEquals("дефис внутри слова не режет", "По-русски это кто-то,", chunk?.text)
    }

    @Test
    fun streamEndReturnsShortRemainder() {
        val chunk = SpeechChunker.nextChunk("короткий хвост", first = false, endOfStream = true)

        assertNotNull("конец потока: короткий остаток отдаётся целиком", chunk)
        assertEquals("короткий хвост", chunk!!.text)
        assertEquals("", chunk.rest)
        assertTrue(chunk.drained)
    }

    @Test
    fun flushDrainsEverythingLeft() {
        val text = (1..80).joinToString(" ") { "слово$it" }

        val chunks = SpeechChunker.flush(text)

        assertTrue("flush отдаёт остаток", chunks.isNotEmpty())
        assertEquals("ничего не потеряно", squashed(text), squashed(chunks.joinToString(" ")))
        assertNull("после flush в буфере пусто", SpeechChunker.flush("").firstOrNull())
    }

    @Test
    fun noCharacterIsLostFromStream() {
        val text = """
            Сейчас в Ставрополе плюс двадцать, ветер слабый. К вечеру похолодает до пятнадцати —
            возьмите куртку. Дождь маловероятен: небо чистое, давление в норме.
            Длинный хвост без единой точки на конце, который обязан дозвучать целиком
        """.trimIndent()

        val chunks = spoken(text)

        assertEquals("первый кусок — первый клауз, ждать ответ целиком не нужно", "Сейчас в Ставрополе плюс двадцать,", chunks.first())
        assertTrue("кусков больше одного: ${chunks.size}", chunks.size >= 2)
        assertEquals("ни одного символа не потеряно", squashed(text), squashed(chunks.joinToString(" ")))
    }

    @Test
    fun textWithoutAnyBoundaryStillSpoken() {
        // Худший случай: ни пробелов, ни препинания (ссылка, токен). Режем по счётчику —
        // молчать нельзя, а без пробелов границы слова просто нет.
        val text = "а".repeat(650)

        val chunks = spoken(text, deltaSize = 40)

        assertEquals("текст целиком произнесён", 650, chunks.sumOf { it.length })
        assertTrue("ни один кусок не больше пачки", chunks.all { it.length <= SpeechChunker.BATCH_CHARS })
    }

    @Test
    fun whitespaceOnlyBufferGivesNothing() {
        assertNull("пробелы — не текст", SpeechChunker.nextChunk("   \n  ", first = true))
        assertNull(SpeechChunker.nextChunk("", first = false))
        assertNull(SpeechChunker.nextChunk("   ", first = false, endOfStream = true))
    }
}
