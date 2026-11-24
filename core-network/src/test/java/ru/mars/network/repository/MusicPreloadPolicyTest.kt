package ru.mars.network.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты [MusicPreloadPolicy]: сервер греется только для реально следующего
 * трека, один раз на id, без сети и без Android.
 */
class MusicPreloadPolicyTest {

    @Test
    fun `empty or blank next id is skipped`() {
        assertEquals(MusicPreloadPolicy.Decision.Skip, MusicPreloadPolicy.decide(null, "t1", null))
        assertEquals(MusicPreloadPolicy.Decision.Skip, MusicPreloadPolicy.decide("   ", "t1", null))
        assertEquals(MusicPreloadPolicy.Decision.Skip, MusicPreloadPolicy.decide("", "t1", null))
    }

    @Test
    fun `single-item queue is skipped even when repeat is all`() {
        assertEquals(
            MusicPreloadPolicy.Decision.Skip,
            MusicPreloadPolicy.decide("t1", "t1", null, queueSize = 1),
        )
        assertEquals(
            MusicPreloadPolicy.Decision.Skip,
            MusicPreloadPolicy.decide("t1", "t1", null, queueSize = 1, repeatOne = true),
        )
    }

    @Test
    fun `repeat one with the same next id is skipped`() {
        assertEquals(
            MusicPreloadPolicy.Decision.Skip,
            MusicPreloadPolicy.decide("t1", "t1", null, queueSize = 3, repeatOne = true),
        )
        assertEquals(
            MusicPreloadPolicy.Decision.Warm("t1"),
            MusicPreloadPolicy.decide("t1", "t1", null, queueSize = 3, repeatOne = false),
        )
    }

    @Test
    fun `next track is warmed with repeat all and shuffle already resolved by the caller`() {
        val decision = MusicPreloadPolicy.decide("t3", "t1", "t2", queueSize = 4, repeatOne = false)
        assertTrue(decision is MusicPreloadPolicy.Decision.Warm)
        assertEquals("t3", (decision as MusicPreloadPolicy.Decision.Warm).id)
    }

    @Test
    fun `same next id is never warmed twice in a row`() {
        assertEquals(
            MusicPreloadPolicy.Decision.Skip,
            MusicPreloadPolicy.decide("t2", "t1", "t2", queueSize = 3),
        )
        assertEquals(
            MusicPreloadPolicy.Decision.Skip,
            MusicPreloadPolicy.decide("t2", "t1", " t2 ", queueSize = 3),
        )
    }

    @Test
    fun `trimmed id is used for the decision and for the wire`() {
        assertEquals(
            MusicPreloadPolicy.Decision.Warm("t2"),
            MusicPreloadPolicy.decide("  t2  ", "t1", null, queueSize = 3),
        )
    }
}
