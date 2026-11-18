package ru.mars.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Нарезка речи: первый кусок — ПРЕДЛОЖЕНИЕ, дальше — пачки по границе слова.
 *
 * Почему это тестируется отдельно от Android: ошибка здесь не видна «на глаз» и стоит дорого.
 * Слишком поздно отданный первый кусок — это и есть жалоба «Марс думает после того, как уже
 * ответил»; резка посреди слова — невнятная речь; потерянный `rest` — выпавшее из ответа
 * предложение, которое человек никогда не услышит (и не узнает, что оно было).
 *
 * Волна 21 добавила главную регрессию: раньше `firstCut` резал на ПЕРВОЙ запятой, и
 * `"Конечно,"` (одно слово, 0.5 с звука) уезжало отдельным куском, а следующий кусок ждал
 * 220 символов — отсюда пауза 5–10 секунд ровно после первой запятой. Теперь короткое
 * вводное слово перед запятой границей НЕ считается, и это закреплено тестами ниже.
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
    fun shortIntroBeforeCommaIsNotCutAlone() {
        // Ровно жалоба владельца: «доходит до запятой и останавливается». Одно слово
        // «Конечно,» отдельным куском — это 0.5 с звука и пауза после него.
        val chunk = SpeechChunker.nextChunk("Конечно, сейчас разберёмся.", first = true)

        assertNotNull("конец предложения в буфере — отдавать можно сразу", chunk)
        val piece = chunk!!
        assertEquals("вводное слово едет вместе с фразой", "Конечно, сейчас разберёмся.", piece.text)
        assertEquals("после предложения ничего не осталось", "", piece.rest)
    }

    @Test
    fun greetingIsCutOnQuestionMarkNotOnComma() {
        val chunk = SpeechChunker.nextChunk("Привет, как дела? Всё хорошо", first = true)

        assertNotNull(chunk)
        val piece = chunk!!
        assertEquals("граница — конец предложения, а не запятая после «Привет»", "Привет, как дела?", piece.text)
        assertEquals("остаток не тронут", " Всё хорошо", piece.rest)
    }

    @Test
    fun firstChunkStopsOnSentenceMarks() {
        val marks = listOf(".", "!", "?", "…", ":", ";", "\n")
        for (mark in marks) {
            val chunk = SpeechChunker.nextChunk("Первое слово${mark}продолжение фразы", first = true)
            // Перевод строки съедает trim, остальные знаки остаются в куске: синтезу важен
            // смысл паузы, а не сам символ.
            assertEquals("знак «$mark» закрывает первый кусок", "Первое слово$mark".trimEnd(), chunk?.text)
            assertEquals("после знака «$mark» остаток не тронут", "продолжение фразы", chunk?.rest?.trim())
        }
    }

    @Test
    fun shortSentencesAreGluedInsteadOfCuttingOneWord() {
        // «Да.» отдельным куском звучит как заикание: короткие предложения подряд склеиваются,
        // пока кусок не дотянет до MIN_FIRST_SENTENCE_CHARS.
        val chunk = SpeechChunker.nextChunk(
            "Да. Нет. И вот длинное продолжение мысли без точек, которое тянется дальше",
            first = true,
        )

        assertEquals("короткие предложения склеены", "Да. Нет.", chunk?.text)
    }

    @Test
    fun longClauseIsCutOnCommaOnlyAfterThreshold() {
        // Запятая за порогом FIRST_CLAUSE_MIN_CHARS — законная граница: кусок уже не «одно слово».
        val text = "Сегодня я посмотрел все три сервера и перезапустил сервис, потому что упал воркер"
        val chunk = SpeechChunker.nextChunk(text, first = true)

        assertNotNull(chunk)
        val piece = chunk!!
        assertEquals(
            "длинный клауз режется по запятой",
            "Сегодня я посмотрел все три сервера и перезапустил сервис,",
            piece.text,
        )
        assertTrue(
            "кусок не короче порога: ${piece.text.length}",
            piece.text.length >= SpeechChunker.FIRST_CLAUSE_MIN_CHARS,
        )
        assertEquals(" остаток — продолжение фразы", "потому что упал воркер", piece.rest.trim())
    }

    @Test
    fun firstChunkWaitsForWindowWithoutAnyMark() {
        // Меньше окна и ни одного знака: отдавать такой огрызок — заикание, ждём дельты.
        val atThreshold = "аб ".repeat(SpeechChunker.FIRST_CLAUSE_MIN_CHARS / 3)
        assertEquals("буфер ровно на пороге клауза", SpeechChunker.FIRST_CLAUSE_MIN_CHARS, atThreshold.length)
        assertNull("ни запятой, ни точки — ждём дальше", SpeechChunker.nextChunk(atThreshold, first = true))

        val almostWindow = "аб ".repeat((SpeechChunker.FIRST_MAX_CHARS - 1) / 3) + "а"
        assertTrue("буфер короче окна: ${almostWindow.length}", almostWindow.length < SpeechChunker.FIRST_MAX_CHARS)
        assertNull("окно ещё не набрано — ждём дальше", SpeechChunker.nextChunk(almostWindow, first = true))

        val fullWindow = "аб ".repeat(SpeechChunker.FIRST_MAX_CHARS / 3 + 1)
        assertTrue("окно набрано: ${fullWindow.length}", fullWindow.length >= SpeechChunker.FIRST_MAX_CHARS)
        assertNotNull("потолок первого куска достигнут — режем", SpeechChunker.nextChunk(fullWindow, first = true))
    }

    @Test
    fun longTextWithoutPunctuationIsCutByWord() {
        val text = (1..40).joinToString(" ") { "слово$it" }
        val chunk = SpeechChunker.nextChunk(text, first = true)

        assertNotNull(chunk)
        val first = chunk!!.text
        assertTrue("первый кусок не больше окна: ${first.length}", first.length <= SpeechChunker.FIRST_MAX_CHARS)
        assertTrue("первый кусок заметно длиннее порога: ${first.length}", first.length > SpeechChunker.FIRST_CLAUSE_MIN_CHARS)
        // Границей выбран пробел: символ сразу за куском — не буква, значит слово не разорвано.
        assertTrue("кусок короче текста", first.length < text.length)
        assertFalse("кусок не должен кончаться внутри слова", text[first.length].isLetter())
    }

    @Test
    fun batchesRespectCeilingAndWordBoundary() {
        val text = (1..120).joinToString(" ") { "слово$it" }
        val chunk = SpeechChunker.nextChunk(text, first = false)

        assertNotNull("пачка набралась", chunk)
        val piece = chunk!!
        val batch = piece.text
        assertTrue("пачка не больше ${SpeechChunker.BATCH_CHARS}: ${batch.length}", batch.length <= SpeechChunker.BATCH_CHARS)
        assertTrue("пачка заполнена до потолка: ${batch.length}", batch.length > SpeechChunker.BATCH_CHARS - 20)
        // Резали по пробелу/препинанию, а не по счётчику: слово не разорвано.
        assertFalse("пачка не должна кончаться внутри слова", text[batch.length].isLetter())
        assertTrue("остаток — это хвост того же текста", text.endsWith(piece.rest))
    }

    @Test
    fun finishedSentenceIsEmittedBeforeBatchIsFull() {
        // Главное правило волны 21 для последующих кусков: законченное предложение от 45
        // символов уходит в синтез СРАЗУ, а не ждёт накопления 160 символов.
        val sentence = "Первое предложение здесь звучит целиком и понятно."
        val tail = " Второе предложение ещё не закончилось"
        val text = sentence + tail

        assertTrue("предложение короче полной пачки: ${text.length}", text.length < SpeechChunker.BATCH_CHARS)
        assertTrue("но не короче порога: ${sentence.length}", sentence.length >= SpeechChunker.MIN_SENTENCE_BATCH_CHARS)

        val chunk = SpeechChunker.nextChunk(text, first = false)

        assertNotNull("законченное предложение отдаётся, не дожидаясь пачки", chunk)
        val piece = chunk!!
        assertEquals("кусок — ровно первое предложение", sentence, piece.text)
        assertEquals("остаток — начало второго", tail, piece.rest)
    }

    @Test
    fun shortSentenceDoesNotEmitEarlyBatch() {
        // Огрызок «Короткое предложение.» (19 символов) пачкой не считается: синтез такого
        // куска стоит дороже, чем пауза на его ожидание.
        assertNull(
            "предложение короче ${SpeechChunker.MIN_SENTENCE_BATCH_CHARS} символов — ждём",
            SpeechChunker.nextChunk("Короткое предложение. И хвост", first = false),
        )
        assertNull(
            "пачку ждём до ${SpeechChunker.BATCH_CHARS} символов",
            SpeechChunker.nextChunk((1..10).joinToString(" ") { "слово$it" }, first = false),
        )
    }

    @Test
    fun dotInsideNumberIsNotASentenceEnd() {
        // «1.5» — одно число: резка по такой точке произнесла бы «один точка» с паузой.
        val chunk = SpeechChunker.nextChunk("Сейчас 1.5 градуса, а к ночи минус пять. Ветер слабый.", first = true)

        assertEquals(
            "точка внутри числа не закрывает предложение",
            "Сейчас 1.5 градуса, а к ночи минус пять.",
            chunk?.text,
        )
    }

    @Test
    fun hyphenInsideWordIsNotAClause() {
        // Запятая после «кто-то» — короче порога, поэтому кусок идёт до конца предложения,
        // а дефис внутри слова границей не считается вовсе.
        val text = "По-русски это кто-то, сказал он и улыбнулся."
        val chunk = SpeechChunker.nextChunk(text, first = true)

        assertEquals("дефис внутри слова не режет", text, chunk?.text)
        assertTrue("«кто-то» осталось целым словом", chunk!!.text.contains("кто-то,"))
    }

    @Test
    fun streamEndReturnsShortRemainder() {
        val chunk = SpeechChunker.nextChunk("короткий хвост", first = false, endOfStream = true)

        assertNotNull("конец потока: короткий остаток отдаётся целиком", chunk)
        val piece = chunk!!
        assertEquals("короткий хвост", piece.text)
        assertEquals("", piece.rest)
        assertTrue(piece.drained)
    }

    @Test
    fun streamEndReleasesShortFirstPiece() {
        // На конце потока короткий первый кусок отдаётся: ждать больше нечего.
        val chunk = SpeechChunker.nextChunk("Привет", first = true, endOfStream = true)

        assertEquals("короткий первый кусок на конце потока", "Привет", chunk?.text)
    }

    @Test
    fun splitKeepsSentenceUnits() {
        val chunks = SpeechChunker.split("Привет, как дела? Всё хорошо, спасибо.")

        assertEquals(
            listOf("Привет, как дела?", "Всё хорошо, спасибо."),
            chunks,
        )
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

        assertEquals(
            "первый кусок — целое первое предложение, а не «Сейчас в Ставрополе плюс двадцать,»",
            "Сейчас в Ставрополе плюс двадцать, ветер слабый.",
            chunks.first(),
        )
        assertTrue("кусков больше одного: ${chunks.size}", chunks.size >= 2)
        assertTrue("второй кусок отдан до накопления пачки", chunks[1].length < SpeechChunker.BATCH_CHARS)
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
