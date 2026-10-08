package ru.mars.network.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Fragmentation-safety tests for [SseChunkParser]: an SSE stream may arrive split across
 * TCP packets at ANY character boundary (Wi-Fi <-> LTE handover on a phone), and no part
 * of a frame may be lost or dispatched early.
 */
class SseChunkParserTest {

    private val twoFrames =
        "id: e1\nevent: chunk\ndata: {\"text\":\"Hel\"}\n\n" +
            "event: chunk\ndata: {\"text\":\"lo\"}\n\n"

    private val expectedTwoFrames = listOf(
        SseChunkParser.Frame("chunk", "e1", "{\"text\":\"Hel\"}"),
        SseChunkParser.Frame("chunk", null, "{\"text\":\"lo\"}"),
    )

    @Test
    fun parsesCompleteBodyInOneFeed() {
        val parser = SseChunkParser()
        val frames = parser.feed(twoFrames) + parser.end()
        assertEquals(expectedTwoFrames, frames)
    }

    /**
     * The harshest fragmentation possible: one character per network packet. The parser
     * must buffer across feeds and produce exactly the same frames as a single feed.
     */
    @Test
    fun parsesBodyFedOneCharAtATime() {
        val parser = SseChunkParser()
        val frames = mutableListOf<SseChunkParser.Frame>()
        twoFrames.forEach { ch -> frames += parser.feed(ch.toString()) }
        frames += parser.end()
        assertEquals(expectedTwoFrames, frames)
    }

    /** CRLF terminator split exactly between \r and \n must count as ONE line break. */
    @Test
    fun crlfSplitAcrossFeedsIsSingleTerminator() {
        val parser = SseChunkParser()
        val frames = parser.feed("data: x\r") + parser.feed("\n") + parser.feed("\r\n") + parser.end()
        assertEquals(listOf(SseChunkParser.Frame(null, null, "x")), frames)
    }

    /**
     * All line-terminator flavours parse identically. Note: without a blank line between
     * `data: 1` and `data: 2` the SSE spec accumulates them into ONE frame (event field is
     * overwritten by the later value) — that's exactly the semantics we must preserve.
     */
    @Test
    fun handlesMixedLineEndings() {
        val parser = SseChunkParser()
        val frames = parser.feed("event: a\rdata: 1\r\nevent: b\ndata: 2\n\n") + parser.end()
        assertEquals(listOf(SseChunkParser.Frame("b", null, "1\n2")), frames)

        // Separate frames need their own blank-line terminator, in any ending flavour.
        val parser2 = SseChunkParser()
        val frames2 = parser2.feed("event: a\rdata: 1\r\n\revent: b\ndata: 2\n\r\n") + parser2.end()
        assertEquals(
            listOf(
                SseChunkParser.Frame("a", null, "1"),
                SseChunkParser.Frame("b", null, "2"),
            ),
            frames2,
        )
    }

    @Test
    fun joinsMultiLineDataWithNewline() {
        val parser = SseChunkParser()
        val frames = parser.feed("data: line1\nda") + parser.feed("ta: line2\n\n") + parser.end()
        assertEquals(listOf(SseChunkParser.Frame(null, null, "line1\nline2")), frames)
    }

    @Test
    fun ignoresKeepAliveComments() {
        val parser = SseChunkParser()
        val frames = parser.feed(": keep-alive\n\n" + ":ping\n" + twoFrames) + parser.end()
        assertEquals(expectedTwoFrames, frames)
    }

    /** Frame value spacing per SSE spec: at most ONE leading space after the colon is stripped. */
    @Test
    fun stripsOnlyOneLeadingSpaceAfterColon() {
        val parser = SseChunkParser()
        val frames = parser.feed("data:{\"a\":1}\n\n") + parser.feed("data:  two spaces\n\n") + parser.end()
        assertEquals(
            listOf(
                SseChunkParser.Frame(null, null, "{\"a\":1}"),
                SseChunkParser.Frame(null, null, " two spaces"),
            ),
            frames,
        )
    }

    /** Event without data lines (e.g. lone ping) must not produce a frame — matches the old dispatch() behaviour. */
    @Test
    fun frameWithoutDataIsNotDispatched() {
        val parser = SseChunkParser()
        val frames = parser.feed("event: ping\n\n") + parser.end()
        assertTrue(frames.isEmpty())
    }

    /** Server closed right after the last data line without the terminating blank line: end() flushes it. */
    @Test
    fun endFlushesUnterminatedFrame() {
        val parser = SseChunkParser()
        val frames = parser.feed("event: chunk\ndata: {\"text\":\"tail\"}")
        assertTrue(frames.isEmpty())
        assertEquals(listOf(SseChunkParser.Frame("chunk", null, "{\"text\":\"tail\"}")), parser.end())
    }

    @Test
    fun idAndEventResetAfterEachFrame() {
        val parser = SseChunkParser()
        val frames = parser.feed("id: 7\nevent: chunk\ndata: a\n\ndata: b\n\n") + parser.end()
        assertEquals(
            listOf(
                SseChunkParser.Frame("chunk", "7", "a"),
                SseChunkParser.Frame(null, null, "b"),
            ),
            frames,
        )
    }

    /**
     * Property-style check: the same stream split at arbitrary random boundaries yields
     * identical frames. Catches any buffer-loss regression at split points the hand-written
     * cases don't cover.
     */
    @Test
    fun randomFragmentationProducesSameFrames() {
        val random = Random(42)
        repeat(50) {
            val parser = SseChunkParser()
            val frames = mutableListOf<SseChunkParser.Frame>()
            var i = 0
            while (i < twoFrames.length) {
                val len = random.nextInt(1, 8)
                frames += parser.feed(twoFrames.substring(i, minOf(i + len, twoFrames.length)))
                i += len
            }
            frames += parser.end()
            assertEquals("iteration with seed-42 fragmentation", expectedTwoFrames, frames)
        }
    }
}
