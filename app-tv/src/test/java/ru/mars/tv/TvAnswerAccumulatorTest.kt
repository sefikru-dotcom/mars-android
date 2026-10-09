package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [TvAnswerAccumulator] — pins the streaming answer contract:
 * the «Марс: » placeholder is dropped exactly once (at the first chunk), and
 * later chunks are concatenated verbatim even when they themselves contain
 * «Марс: » at the start of the accumulated text (the regression the old
 * per-chunk `removePrefix` had).
 */
class TvAnswerAccumulatorTest {

    @Test
    fun `empty first chunk still drops the placeholder`() {
        val acc = TvAnswerAccumulator("Марс: ")
        // The first chunk (even empty) marks the placeholder as replaced.
        assertEquals("", acc.onChunk(""))
        assertEquals("текст", acc.onChunk("текст"))
    }

    @Test
    fun `split placeholder arrives as chunks - dropped once, answer intact`() {
        val acc = TvAnswerAccumulator("Марс: ")
        assertEquals("Мар", acc.onChunk("Мар"))
        assertEquals("Марс: привет", acc.onChunk("с: привет"))
        assertEquals("Марс: привет!", acc.onChunk("!"))
    }

    @Test
    fun `plain concatenation after first chunk`() {
        val acc = TvAnswerAccumulator("Марс: ")
        assertEquals("Привет", acc.onChunk("Привет"))
        assertEquals("Привет, мир", acc.onChunk(", мир"))
        assertEquals("Привет, мир!", acc.onChunk("!"))
    }

    @Test
    fun `accumulated text starting with prefix keeps it on later chunks`() {
        val acc = TvAnswerAccumulator("Марс: ")
        assertEquals("Марс: ", acc.onChunk("Марс: "))
        // The accumulated answer itself starts with «Марс: » — it must NOT be stripped again.
        assertEquals("Марс: ответ", acc.onChunk("ответ"))
        assertEquals("Марс: ответ полностью", acc.onChunk(" полностью"))
    }

    @Test
    fun `first chunk equal to placeholder drops placeholder only once`() {
        val acc = TvAnswerAccumulator("Марс: ")
        assertEquals("Марс: ", acc.onChunk("Марс: "))
        assertEquals("Марс: хвост", acc.onChunk("хвост"))
    }
}
