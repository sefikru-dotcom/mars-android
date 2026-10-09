package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TvAnswerAccumulator] — pins the streaming answer contract:
 *
 * 1. the «Марс: » placeholder is dropped exactly once (at the first chunk), and later chunks
 *    are concatenated verbatim even when they themselves contain «Марс: » at the start of the
 *    accumulated text (the regression the old per-chunk `removePrefix` had);
 * 2. TERMINAL lock (reviewer defect): the SSE transport keeps delivering frames after
 *    Error/Done, and the old wiring let a late Chunk overwrite «Ошибка: …» with buffered
 *    text, mutate the finished answer after Done, and let a tail Error clobber a completed
 *    answer. First terminal event wins; late events are rejected (null/false).
 */
class TvAnswerAccumulatorTest {

    // ── Placeholder-once streaming contract ────────────────────────────────────────────

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

    // ── Terminal lock: Error ────────────────────────────────────────────────────────────

    @Test
    fun `late chunk after error is ignored - error text survives`() {
        val acc = TvAnswerAccumulator("Марс: ")
        assertEquals("частичный ", acc.onChunk("частичный "))
        assertEquals("Ошибка: обрыв потока", acc.onError("обрыв потока"))
        assertEquals(TvAnswerAccumulator.State.ERROR, acc.state)
        // The defect: this late frame used to render «частичный хвост», erasing the error.
        assertNull(acc.onChunk("хвост"))
        assertEquals("Ошибка: обрыв потока", acc.text())
        assertEquals("Ошибка: обрыв потока", acc.onChunkOrTextIgnored("ещё"))
    }

    @Test
    fun `error replaces the partial answer and locks it`() {
        val acc = TvAnswerAccumulator("Марс: ")
        acc.onChunk("начало")
        assertEquals("Ошибка: connection reset", acc.onError("connection reset"))
        assertEquals("Ошибка: connection reset", acc.text())
        assertNull(acc.onError("вторая ошибка"))
        assertEquals("Ошибка: connection reset", acc.text())
    }

    @Test
    fun `error before any chunk replaces the placeholder`() {
        val acc = TvAnswerAccumulator("Марс: ")
        assertEquals("Ошибка: HTTP 502: bad gateway", acc.onError("HTTP 502: bad gateway"))
        assertEquals("Ошибка: HTTP 502: bad gateway", acc.text())
        assertNull(acc.onChunk("поздний текст"))
    }

    // ── Terminal lock: Done ─────────────────────────────────────────────────────────────

    @Test
    fun `late chunk after done is ignored - final answer survives`() {
        val acc = TvAnswerAccumulator("Марс: ")
        assertEquals("готовый ответ", acc.onChunk("готовый ответ"))
        assertTrue(acc.onDone())
        assertEquals(TvAnswerAccumulator.State.DONE, acc.state)
        // The defect: a late frame used to extend the finished answer («…мусор»).
        assertNull(acc.onChunk("мусор"))
        assertEquals("готовый ответ", acc.text())
    }

    @Test
    fun `late error after done does not clobber the completed answer`() {
        val acc = TvAnswerAccumulator("Марс: ")
        acc.onChunk("готовый ответ")
        assertTrue(acc.onDone())
        // Tail network error during the stream teardown used to overwrite the bubble.
        assertNull(acc.onError("connection reset"))
        assertEquals("готовый ответ", acc.text())
        assertEquals(TvAnswerAccumulator.State.DONE, acc.state)
    }

    @Test
    fun `repeated done frames are inert`() {
        val acc = TvAnswerAccumulator("Марс: ")
        acc.onChunk("ответ")
        assertTrue(acc.onDone())
        // Wire contract: `done` is followed by `stream_end` — both map to Done.
        assertFalse(acc.onDone())
        assertEquals("ответ", acc.text())
    }

    @Test
    fun `done after error keeps the error outcome`() {
        val acc = TvAnswerAccumulator("Марс: ")
        assertEquals("Ошибка: обрыв", acc.onError("обрыв"))
        assertFalse(acc.onDone())
        assertEquals(TvAnswerAccumulator.State.ERROR, acc.state)
        assertEquals("Ошибка: обрыв", acc.text())
    }

    // ── New turn after a terminal one ───────────────────────────────────────────────────

    @Test
    fun `fresh accumulator streams normally after a terminal turn`() {
        val failed = TvAnswerAccumulator("Марс: ")
        failed.onChunk("частичный ")
        failed.onError("обрыв потока")
        failed.onDone()
        // State is per-instance: the next turn's accumulator is untouched by the dead one.
        val next = TvAnswerAccumulator("Марс: ")
        assertEquals(TvAnswerAccumulator.State.STREAMING, next.state)
        assertEquals("новый ", next.onChunk("новый "))
        assertEquals("новый ответ", next.onChunk("ответ"))
        assertTrue(next.onDone())
        assertEquals("новый ответ", next.text())
    }

    /** Helper mirroring the Activity rule: a rejected chunk must leave [TvAnswerAccumulator.text] as-is. */
    private fun TvAnswerAccumulator.onChunkOrTextIgnored(chunk: String): String {
        onChunk(chunk)
        return text()
    }
}
