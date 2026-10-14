package ru.mars.mobile

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Contract tests for the manual quality profile surface of [MarsMusicPlayer]
 * (task t_fe9dae31: явный quality query original/standard/economy, обратный default).
 *
 * These exercise the process-scoped player object WITHOUT an Android runtime: with no
 * attached context and no bound engine, [MarsMusicPlayer.setStreamQuality] is a pure
 * field switch (the rebind path is guarded by `engine ?: return`), which is exactly the
 * idle-process contract the UI relies on — the chosen profile applies to the NEXT stream
 * URL that is built.
 */
class MarsMusicPlayerStreamQualityTest {

    @After
    fun restoreDefault() {
        // The object is process-scoped and shared across test classes in the same JVM run:
        // never leak a non-default profile into other tests.
        MarsMusicPlayer.setStreamQuality(StreamQuality.DEFAULT)
    }

    @Test
    fun `default profile keeps the historical query-free url`() {
        MarsMusicPlayer.setStreamQuality(StreamQuality.DEFAULT)
        assertSame(StreamQuality.DEFAULT, MarsMusicPlayer.streamQuality)
        assertEquals("https://marscore.ru/music/api/stream/id1", MarsMusicPlayer.streamUrlFor("id1"))
    }

    @Test
    fun `every manual profile lands in the stream url as an explicit quality query`() {
        for (quality in listOf(StreamQuality.ORIGINAL, StreamQuality.STANDARD, StreamQuality.ECONOMY)) {
            MarsMusicPlayer.setStreamQuality(quality)
            assertSame(quality, MarsMusicPlayer.streamQuality)
            assertEquals(
                "https://marscore.ru/music/api/stream/id1?quality=${quality.queryValue}",
                MarsMusicPlayer.streamUrlFor("id1")
            )
        }
    }

    @Test
    fun `switching profiles while idle only changes the url - no forced downgrade`() {
        // Explicit ORIGINAL must survive untouched (no silent clamp to standard/economy):
        // the client never degrades quality on its own — only the user or the server does.
        MarsMusicPlayer.setStreamQuality(StreamQuality.ORIGINAL)
        assertEquals(
            "https://marscore.ru/music/api/stream/id2?quality=original",
            MarsMusicPlayer.streamUrlFor("id2")
        )
        MarsMusicPlayer.setStreamQuality(StreamQuality.ECONOMY)
        assertEquals(
            "https://marscore.ru/music/api/stream/id2?quality=economy",
            MarsMusicPlayer.streamUrlFor("id2")
        )
        // …and back to the backward-compatible default.
        MarsMusicPlayer.setStreamQuality(StreamQuality.DEFAULT)
        assertEquals("https://marscore.ru/music/api/stream/id2", MarsMusicPlayer.streamUrlFor("id2"))
    }

    @Test
    fun `setting the same profile again is a no-op`() {
        MarsMusicPlayer.setStreamQuality(StreamQuality.STANDARD)
        MarsMusicPlayer.setStreamQuality(StreamQuality.STANDARD)
        assertSame(StreamQuality.STANDARD, MarsMusicPlayer.streamQuality)
        assertEquals(
            "https://marscore.ru/music/api/stream/id3?quality=standard",
            MarsMusicPlayer.streamUrlFor("id3")
        )
    }
}
