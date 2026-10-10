package ru.mars.network.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Limit-enforcement tests for [StreamBufferGuard]: per-frame clamp, sliding window over
 * the accumulated transcript, the hard "never longer than maxAccumulatedChars" invariant,
 * marker semantics and de-duplication.
 */
class StreamBufferGuardTest {

    private val marker = StreamBufferGuard.TRUNCATION_MARKER

    @Test
    fun markerTextIsTheContractedOne() {
        assertEquals("[...вывод обрезан во избежание OOM...]", marker)
    }

    @Test
    fun smallAppendPassesThroughUntouched() {
        val guard = StreamBufferGuard(maxFrameChars = 100, maxAccumulatedChars = 1_000)
        val result = guard.append("abc", "def")
        assertEquals("abcdef", result.text)
        assertFalse(result.truncated)
        assertFalse(result.frameTruncated)
        assertFalse(result.accumulatedTruncated)
        assertEquals(0, result.droppedChars)
    }

    @Test
    fun emptyChunkKeepsCurrentBuffer() {
        val guard = StreamBufferGuard(maxFrameChars = 10, maxAccumulatedChars = 100)
        val result = guard.append("hello", "")
        assertEquals("hello", result.text)
        assertFalse(result.truncated)
    }

    @Test
    fun emptyCurrentAcceptsFirstChunk() {
        val guard = StreamBufferGuard(maxFrameChars = 10, maxAccumulatedChars = 100)
        assertEquals("first", guard.append("", "first").text)
    }

    @Test
    fun oversizedFrameIsClampedAndMarked() {
        val guard = StreamBufferGuard(maxFrameChars = 10, maxAccumulatedChars = 1_000)
        val result = guard.append("log> ", "x".repeat(25))
        assertEquals("log> " + "x".repeat(10) + marker, result.text)
        assertTrue(result.frameTruncated)
        assertFalse(result.accumulatedTruncated)
        assertEquals(15, result.droppedChars)
    }

    @Test
    fun frameExactlyAtLimitIsNotTruncated() {
        val guard = StreamBufferGuard(maxFrameChars = 10, maxAccumulatedChars = 1_000)
        val result = guard.append("", "x".repeat(10))
        assertEquals("x".repeat(10), result.text)
        assertFalse(result.frameTruncated)
    }

    @Test
    fun accumulatedBufferSlidesKeepingNewestTail() {
        val guard = StreamBufferGuard(maxFrameChars = 200, maxAccumulatedChars = 100)
        var text = ""
        repeat(10) { i ->
            text = guard.append(text, "chunk$i|".repeat(10)).text // 70 chars per chunk
        }
        assertTrue("buffer grew to ${text.length}", text.length <= 100)
        assertTrue(text.startsWith(marker))
        assertTrue("newest output must survive the slide", text.endsWith("chunk9|"))
    }

    @Test
    fun hardInvariantUnderHostileInput() {
        val guard = StreamBufferGuard(maxFrameChars = 64, maxAccumulatedChars = 200)
        val random = Random(3)
        var text = ""
        var sawFrameTruncation = false
        var sawWindowTruncation = false
        repeat(200) {
            val chunk = buildString { repeat(random.nextInt(1, 900)) { append('я') } }
            val result = guard.append(text, chunk)
            text = result.text
            sawFrameTruncation = sawFrameTruncation || result.frameTruncated
            sawWindowTruncation = sawWindowTruncation || result.accumulatedTruncated
            assertTrue("invariant broken: ${text.length} > 200", text.length <= 200)
            assertTrue("droppedChars must be reported", !result.truncated || result.droppedChars > 0)
        }
        assertTrue(sawFrameTruncation)
        assertTrue(sawWindowTruncation)
    }

    @Test
    fun headMarkerIsNeverDuplicated() {
        // maxAccumulatedChars = 76: window keeps exactly 38 chars — one whole marker.
        // A second truncated frame would naively produce MARKER+MARKER at the head.
        val guard = StreamBufferGuard(maxFrameChars = 10, maxAccumulatedChars = 76)
        assertEquals(38, marker.length)
        var result = guard.append("", "x".repeat(50))
        assertEquals("x".repeat(10) + marker, result.text) // 48 chars, still inside window
        result = guard.append(result.text, "y".repeat(50))
        assertFalse(result.text.startsWith(marker + marker))
        assertEquals(marker, result.text.take(marker.length))
        assertTrue(result.text.length <= 76)
    }

    @Test
    fun currentAlreadyOverLimitIsTrimmedOnNextAppend() {
        // The caller may hand in an oversized buffer (e.g. limits were tightened
        // mid-stream); the very next append must bring it back under the cap.
        val guard = StreamBufferGuard(maxFrameChars = 10, maxAccumulatedChars = 60)
        val result = guard.append("y".repeat(500), "z")
        assertTrue(result.accumulatedTruncated)
        assertTrue(result.text.length <= 60)
        assertTrue(result.text.startsWith(marker))
        assertTrue(result.text.endsWith("z"))
        assertTrue(result.droppedChars >= 500 - (60 - marker.length))
    }

    @Test
    fun droppedCharsCountBothLimits() {
        val guard = StreamBufferGuard(maxFrameChars = 10, maxAccumulatedChars = 100)
        val result = guard.append("c".repeat(80), "n".repeat(30))
        // Frame clamp: 30 -> 10 ('n'), 20 dropped. Combined = 80c + 10n + 38 marker = 128;
        // window keeps the newest 100 - 38 = 62 chars, evicting the 66 oldest 'c'.
        assertTrue(result.frameTruncated)
        assertTrue(result.accumulatedTruncated)
        assertEquals(100, result.text.length)
        assertEquals(20 + 66, result.droppedChars)
        assertEquals("c".repeat(14) + "n".repeat(10) + marker, result.text.removePrefix(marker))
    }

    @Test
    fun degenerateLimitsAreCoercedAndStillSafe() {
        val guard = StreamBufferGuard(maxFrameChars = 0, maxAccumulatedChars = 1)
        assertTrue(guard.maxFrameChars >= 1)
        assertTrue(guard.maxAccumulatedChars >= marker.length + 1)
        val result = guard.append("abc".repeat(100), "def".repeat(100))
        assertTrue(result.text.length <= guard.maxAccumulatedChars)
        assertTrue(result.truncated)
    }

    @Test
    fun defaultLimitsAreSane() {
        val guard = StreamBufferGuard()
        assertEquals(32 * 1024, guard.maxFrameChars)
        assertEquals(256 * 1024, guard.maxAccumulatedChars)
        assertTrue(guard.maxFrameChars <= guard.maxAccumulatedChars)
    }

    @Test
    fun megabyteFrameIsContainedInOneAppend() {
        val guard = StreamBufferGuard() // production defaults
        val result = guard.append("start\n", "A".repeat(1_500_000))
        assertTrue(result.text.length <= guard.maxAccumulatedChars)
        assertEquals(1_500_000 - guard.maxFrameChars, result.droppedChars)
        assertTrue(result.text.startsWith("start\n"))
        assertTrue(result.text.contains(marker))
    }
}
