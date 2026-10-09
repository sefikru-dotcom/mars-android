package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicTrack

class RadioQueuePolicyTest {

    private fun track(id: String) = MusicTrack(id = id, title = "T$id", artist = "A$id")

    @Test fun preservesServerOrderAndAppendsAllNewTracks() {
        val fetched = listOf(track("r1"), track("r2"), track("r3"))
        val additions = RadioQueuePolicy.tracksToAdd(fetched, queuedIds = emptyList(), currentTrackId = null)
        assertEquals(listOf("r1", "r2", "r3"), additions.map { it.id })
    }

    @Test fun skipsTracksAlreadyQueued() {
        val fetched = listOf(track("r1"), track("r2"), track("r3"))
        val additions = RadioQueuePolicy.tracksToAdd(fetched, queuedIds = listOf("r2"), currentTrackId = null)
        assertEquals(listOf("r1", "r3"), additions.map { it.id })
    }

    @Test fun skipsCurrentTrackEvenWhenNotInQueue() {
        val fetched = listOf(track("cur"), track("r1"))
        val additions = RadioQueuePolicy.tracksToAdd(fetched, queuedIds = emptyList(), currentTrackId = "cur")
        assertEquals(listOf("r1"), additions.map { it.id })
    }

    @Test fun deduplicatesWithinTheFetchedBatchItself() {
        val fetched = listOf(track("r1"), track("r1"), track("r2"))
        val additions = RadioQueuePolicy.tracksToAdd(fetched, queuedIds = emptyList(), currentTrackId = null)
        assertEquals(listOf("r1", "r2"), additions.map { it.id })
    }

    @Test fun capsAdditionsAtLimitWithoutReordering() {
        val fetched = (1..10).map { track("r$it") }
        val additions = RadioQueuePolicy.tracksToAdd(fetched, queuedIds = emptyList(), currentTrackId = null, limit = 4)
        assertEquals(listOf("r1", "r2", "r3", "r4"), additions.map { it.id })
    }

    @Test fun defaultLimitIsThirty() {
        val fetched = (1..50).map { track("r$it") }
        val additions = RadioQueuePolicy.tracksToAdd(fetched, queuedIds = emptyList(), currentTrackId = null)
        assertEquals(RadioQueuePolicy.MAX_ADDITIONS, additions.size)
        assertEquals(30, RadioQueuePolicy.MAX_ADDITIONS)
    }

    @Test fun nonPositiveLimitAddsNothing() {
        val additions = RadioQueuePolicy.tracksToAdd(listOf(track("r1")), emptyList(), null, limit = 0)
        assertTrue(additions.isEmpty())
    }

    @Test fun emptyFetchYieldsNoAdditions() {
        assertTrue(RadioQueuePolicy.tracksToAdd(emptyList(), listOf("q1"), "q1").isEmpty())
    }

    @Test fun blankTrackIdsAreNeverAdded() {
        val fetched = listOf(track(""), track("  "), track("r1"))
        val additions = RadioQueuePolicy.tracksToAdd(fetched, queuedIds = emptyList(), currentTrackId = null)
        assertEquals(listOf("r1"), additions.map { it.id })
    }

    @Test fun blankCurrentTrackIdIsIgnoredAsBlockEntry() {
        val fetched = listOf(track("r1"))
        val additions = RadioQueuePolicy.tracksToAdd(fetched, queuedIds = emptyList(), currentTrackId = " ")
        assertEquals(listOf("r1"), additions.map { it.id })
    }

    @Test fun allDuplicatesYieldEmptyAdditions() {
        val additions = RadioQueuePolicy.tracksToAdd(
            fetched = listOf(track("a"), track("b")),
            queuedIds = listOf("a", "b"),
            currentTrackId = "a",
        )
        assertTrue(additions.isEmpty())
    }
}
