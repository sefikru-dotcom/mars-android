package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicTrack

/**
 * Unit tests for the pure cross-device queue sync core ([RemoteQueueSyncPolicy]):
 * track-level diffing, every decideSync precedence branch (echo suppression, empty local,
 * playback asymmetry, both-idle divergence) and the append-style merge.
 */
class RemoteQueueSyncPolicyTest {

    private fun track(id: String) = MusicTrack(id = id, title = "Title $id", artist = "Artist $id")
    private fun queue(vararg ids: String): List<MusicTrack> = ids.map { track(it) }

    private val phone = "device-phone"
    private val tv = "device-tv"

    private fun decide(
        localQueue: List<MusicTrack> = queue("a", "b"),
        remoteQueue: List<MusicTrack> = queue("a", "b"),
        localIsPlaying: Boolean = false,
        remoteIsPlaying: Boolean = false,
        localTrackId: String? = localQueue.firstOrNull()?.id,
        remoteTrackId: String? = remoteQueue.firstOrNull()?.id,
        localDeviceId: String = phone,
        remoteDeviceId: String? = tv,
    ) = RemoteQueueSyncPolicy.decideSync(
        localQueue = localQueue,
        remoteQueue = remoteQueue,
        localIsPlaying = localIsPlaying,
        remoteIsPlaying = remoteIsPlaying,
        localTrackId = localTrackId,
        remoteTrackId = remoteTrackId,
        localDeviceId = localDeviceId,
        remoteDeviceId = remoteDeviceId,
    )

    // --- calculateDiff --------------------------------------------------------

    @Test
    fun `identical queues produce an empty identical diff`() {
        val diff = RemoteQueueSyncPolicy.calculateDiff(queue("a", "b"), queue("a", "b"))
        assertTrue(diff.isIdentical)
        assertEquals(emptyList<MusicTrack>(), diff.addedTracks)
        assertEquals(emptyList<MusicTrack>(), diff.removedTracks)
    }

    @Test
    fun `both-empty queues are identical`() {
        val diff = RemoteQueueSyncPolicy.calculateDiff(emptyList(), emptyList())
        assertTrue(diff.isIdentical)
        assertTrue(diff.addedTracks.isEmpty())
        assertTrue(diff.removedTracks.isEmpty())
    }

    @Test
    fun `tracks present only in remote are reported as added in remote order`() {
        val diff = RemoteQueueSyncPolicy.calculateDiff(queue("a"), queue("a", "b", "c"))
        assertFalse(diff.isIdentical)
        assertEquals(listOf("b", "c"), diff.addedTracks.map { it.id })
        assertEquals(emptyList<MusicTrack>(), diff.removedTracks)
    }

    @Test
    fun `tracks present only in local are reported as removed in local order`() {
        val diff = RemoteQueueSyncPolicy.calculateDiff(queue("a", "b", "c"), queue("c"))
        assertFalse(diff.isIdentical)
        assertEquals(emptyList<MusicTrack>(), diff.addedTracks)
        assertEquals(listOf("a", "b"), diff.removedTracks.map { it.id })
    }

    @Test
    fun `disjoint queues report every track on both sides`() {
        val diff = RemoteQueueSyncPolicy.calculateDiff(queue("a", "b"), queue("x", "y"))
        assertFalse(diff.isIdentical)
        assertEquals(listOf("x", "y"), diff.addedTracks.map { it.id })
        assertEquals(listOf("a", "b"), diff.removedTracks.map { it.id })
    }

    @Test
    fun `pure reorder has no added or removed tracks but is not identical`() {
        val diff = RemoteQueueSyncPolicy.calculateDiff(queue("a", "b"), queue("b", "a"))
        assertEquals(emptyList<MusicTrack>(), diff.addedTracks)
        assertEquals(emptyList<MusicTrack>(), diff.removedTracks)
        assertFalse("play order matters, so a reorder is a real difference", diff.isIdentical)
    }

    @Test
    fun `duplicate ids within a side collapse to the first occurrence`() {
        val diff = RemoteQueueSyncPolicy.calculateDiff(queue("a"), queue("a", "b", "b", "c", "b"))
        assertFalse(diff.isIdentical)
        assertEquals(listOf("b", "c"), diff.addedTracks.map { it.id })
    }

    @Test
    fun `diff against an empty remote reports everything as removed`() {
        val diff = RemoteQueueSyncPolicy.calculateDiff(queue("a", "b"), emptyList())
        assertFalse(diff.isIdentical)
        assertTrue(diff.addedTracks.isEmpty())
        assertEquals(listOf("a", "b"), diff.removedTracks.map { it.id })
    }

    // --- decideSync: echo suppression ------------------------------------------

    @Test
    fun `update from own device is ignored as a stale echo`() {
        val decision = decide(remoteDeviceId = phone)
        assertEquals(SyncResolution.IGNORE_STALE, decision.resolution)
        assertEquals(queue("a", "b"), decision.resolvedQueue)
        assertEquals("echo from own device", decision.reason)
    }

    @Test
    fun `echo suppression wins over every other rule`() {
        // Own-device echo that would otherwise adopt (remote playing, local idle, diverged queue).
        val decision = decide(
            localQueue = queue("a"),
            remoteQueue = queue("x", "y"),
            remoteIsPlaying = true,
            localIsPlaying = false,
            remoteDeviceId = phone,
        )
        assertEquals(SyncResolution.IGNORE_STALE, decision.resolution)
        assertEquals(queue("a"), decision.resolvedQueue)
    }

    @Test
    fun `null remote device id is not treated as an echo`() {
        val decision = decide(
            localQueue = emptyList(),
            remoteQueue = queue("x"),
            remoteDeviceId = null,
        )
        assertEquals(SyncResolution.ADOPT_REMOTE, decision.resolution)
    }

    // --- decideSync: empty local queue ------------------------------------------

    @Test
    fun `empty local queue adopts a non-empty remote queue while both idle`() {
        val remote = queue("x", "y")
        val decision = decide(localQueue = emptyList(), remoteQueue = remote)
        assertEquals(SyncResolution.ADOPT_REMOTE, decision.resolution)
        assertEquals(remote, decision.resolvedQueue)
    }

    @Test
    fun `empty local queue adopts remote even when local claims to be playing`() {
        // Defensive: an "playing" flag with nothing queued cannot own the session.
        val remote = queue("x")
        val decision = decide(localQueue = emptyList(), remoteQueue = remote, localIsPlaying = true)
        assertEquals(SyncResolution.ADOPT_REMOTE, decision.resolution)
        assertEquals(remote, decision.resolvedQueue)
    }

    @Test
    fun `both queues empty keeps local`() {
        val decision = decide(localQueue = emptyList(), remoteQueue = emptyList())
        assertEquals(SyncResolution.KEEP_LOCAL, decision.resolution)
        assertEquals(emptyList<MusicTrack>(), decision.resolvedQueue)
    }

    // --- decideSync: playback asymmetry -------------------------------------------

    @Test
    fun `remote playing while local idle adopts the remote queue`() {
        val remote = queue("x", "y")
        val decision = decide(localQueue = queue("a"), remoteQueue = remote, remoteIsPlaying = true)
        assertEquals(SyncResolution.ADOPT_REMOTE, decision.resolution)
        assertEquals(remote, decision.resolvedQueue)
    }

    @Test
    fun `remote playing adopts even when queues are identical`() {
        val remote = queue("a", "b")
        val decision = decide(remoteQueue = remote, remoteIsPlaying = true, remoteTrackId = "b")
        assertEquals(SyncResolution.ADOPT_REMOTE, decision.resolution)
        assertEquals(remote, decision.resolvedQueue)
    }

    @Test
    fun `local playing while remote idle keeps the local queue despite divergence`() {
        val local = queue("a", "b")
        val decision = decide(localQueue = local, remoteQueue = queue("x", "y"), localIsPlaying = true)
        assertEquals(SyncResolution.KEEP_LOCAL, decision.resolution)
        assertEquals(local, decision.resolvedQueue)
    }

    @Test
    fun `both playing keeps the local queue`() {
        val local = queue("a", "b")
        val decision = decide(
            localQueue = local,
            remoteQueue = queue("x"),
            localIsPlaying = true,
            remoteIsPlaying = true,
        )
        assertEquals(SyncResolution.KEEP_LOCAL, decision.resolution)
        assertEquals(local, decision.resolvedQueue)
    }

    // --- decideSync: both idle ------------------------------------------------------

    @Test
    fun `both idle with diverged non-empty remote adopts the remote queue`() {
        val remote = queue("a", "c")
        val decision = decide(localQueue = queue("a", "b"), remoteQueue = remote)
        assertEquals(SyncResolution.ADOPT_REMOTE, decision.resolution)
        assertEquals(remote, decision.resolvedQueue)
    }

    @Test
    fun `both idle with a reordered remote queue adopts it`() {
        val remote = queue("b", "a")
        val decision = decide(localQueue = queue("a", "b"), remoteQueue = remote)
        assertEquals(SyncResolution.ADOPT_REMOTE, decision.resolution)
        assertEquals(remote, decision.resolvedQueue)
    }

    @Test
    fun `both idle with identical queues keeps local`() {
        val local = queue("a", "b")
        val decision = decide(localQueue = local, remoteQueue = queue("a", "b"))
        assertEquals(SyncResolution.KEEP_LOCAL, decision.resolution)
        assertEquals(local, decision.resolvedQueue)
    }

    @Test
    fun `both idle with an empty remote keeps the local queue`() {
        val local = queue("a", "b")
        val decision = decide(localQueue = local, remoteQueue = emptyList())
        assertEquals(SyncResolution.KEEP_LOCAL, decision.resolution)
        assertEquals(local, decision.resolvedQueue)
    }

    @Test
    fun `every decision carries a non-blank reason`() {
        val decisions = listOf(
            decide(remoteDeviceId = phone),
            decide(localQueue = emptyList(), remoteQueue = queue("x")),
            decide(remoteIsPlaying = true),
            decide(localIsPlaying = true),
            decide(localIsPlaying = true, remoteIsPlaying = true),
            decide(remoteQueue = queue("z")),
            decide(),
        )
        assertTrue(decisions.all { it.reason.isNotBlank() })
    }

    // --- mergeQueueAppend -------------------------------------------------------------

    @Test
    fun `merge preserves local order and appends only new remote tracks`() {
        val merged = RemoteQueueSyncPolicy.mergeQueueAppend(queue("a", "b"), queue("b", "c", "d"))
        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.id })
    }

    @Test
    fun `merge dedupes repeated ids inside the remote list`() {
        val merged = RemoteQueueSyncPolicy.mergeQueueAppend(queue("a"), queue("x", "x", "y", "x"))
        assertEquals(listOf("a", "x", "y"), merged.map { it.id })
    }

    @Test
    fun `merge with no new remote tracks returns the local queue untouched`() {
        val local = queue("a", "b")
        assertEquals(local, RemoteQueueSyncPolicy.mergeQueueAppend(local, queue("b", "a")))
    }

    @Test
    fun `merge onto an empty local queue yields the deduped remote queue`() {
        val merged = RemoteQueueSyncPolicy.mergeQueueAppend(emptyList(), queue("x", "y", "x"))
        assertEquals(listOf("x", "y"), merged.map { it.id })
    }

    @Test
    fun `merge with an empty remote queue is the identity on local`() {
        val local = queue("a", "b")
        assertEquals(local, RemoteQueueSyncPolicy.mergeQueueAppend(local, emptyList()))
    }

    @Test
    fun `merge keeps full track metadata of the first occurrence`() {
        val localTrack = MusicTrack(id = "a", title = "Local Title", artist = "Local Artist")
        val remoteVariant = MusicTrack(id = "a", title = "Remote Title", artist = "Remote Artist")
        val merged = RemoteQueueSyncPolicy.mergeQueueAppend(listOf(localTrack), listOf(remoteVariant))
        assertEquals(listOf(localTrack), merged)
    }

    @Test
    fun `merge of the diff-added tracks reproduces the remote superset`() {
        val local = queue("a", "b")
        val remote = queue("a", "b", "c")
        val diff = RemoteQueueSyncPolicy.calculateDiff(local, remote)
        assertEquals(remote, RemoteQueueSyncPolicy.mergeQueueAppend(local, diff.addedTracks))
    }
}
