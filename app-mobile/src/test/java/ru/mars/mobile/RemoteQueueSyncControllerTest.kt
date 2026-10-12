package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.MusicPlayerState
import ru.mars.network.model.MusicTrack
import ru.mars.network.model.PlaybackStatus

/**
 * Unit tests for [RemoteQueueSyncController] — the production glue between the real
 * Mars Connect event stream and [RemoteQueueSyncPolicy]: every [SyncResolution] branch
 * (ADOPT_REMOTE, KEEP_LOCAL, MERGE_REMOTE_APPEND, IGNORE_STALE), the stale-event
 * watermark and erroneous/optional wire payloads.
 */
class RemoteQueueSyncControllerTest {

    private fun track(id: String) = MusicTrack(id = id, title = "Title $id", artist = "Artist $id")
    private fun queue(vararg ids: String): List<MusicTrack> = ids.map { track(it) }

    private val phone = "device-phone"
    private val tv = "device-tv"

    private fun local(
        queue: List<MusicTrack> = queue("a", "b"),
        isPlaying: Boolean = false,
        currentTrackId: String? = queue.firstOrNull()?.id,
        deviceId: String = phone,
    ) = LocalPlaybackSnapshot(queue, isPlaying, currentTrackId, deviceId)

    private fun remote(
        queue: List<MusicTrack> = queue("x", "y"),
        track: MusicTrack? = queue.firstOrNull(),
        isPlaying: Boolean = false,
        currentIndex: Int = 0,
        activeDeviceId: String? = tv,
        timestampMs: Long? = null,
    ) = MusicPlayerState(
        track = track,
        positionSeconds = 0.0,
        durationSeconds = 0.0,
        playback = if (isPlaying) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED,
        volume = 0.8,
        queue = queue,
        currentIndex = currentIndex,
        activeDeviceId = activeDeviceId,
        timestampMs = timestampMs,
    )

    // --- ADOPT_REMOTE ----------------------------------------------------------

    @Test
    fun `adopts remote queue when remote plays and local is idle`() {
        val outcome = RemoteQueueSyncController().onRemoteState(
            remote(queue("x", "y", "z"), isPlaying = true, currentIndex = 2),
            local(queue("a", "b"), isPlaying = false),
        )
        assertTrue(outcome is RemoteSyncOutcome.Adopted)
        outcome as RemoteSyncOutcome.Adopted
        assertEquals(listOf("x", "y", "z"), outcome.queue.map { it.id })
        assertEquals(2, outcome.startIndex)
    }

    @Test
    fun `adopts remote queue when local queue is empty`() {
        val outcome = RemoteQueueSyncController().onRemoteState(
            remote(queue("x", "y")),
            local(emptyList(), currentTrackId = null),
        )
        assertTrue(outcome is RemoteSyncOutcome.Adopted)
        assertEquals(listOf("x", "y"), (outcome as RemoteSyncOutcome.Adopted).queue.map { it.id })
        assertEquals(0, outcome.startIndex)
    }

    @Test
    fun `adopts remote queue when both idle and queues diverged`() {
        val outcome = RemoteQueueSyncController().onRemoteState(
            remote(queue("x", "y")),
            local(queue("a", "b"), isPlaying = false),
        )
        assertTrue(outcome is RemoteSyncOutcome.Adopted)
        assertEquals(listOf("x", "y"), (outcome as RemoteSyncOutcome.Adopted).queue.map { it.id })
    }

    // --- KEEP_LOCAL ------------------------------------------------------------

    @Test
    fun `keeps local queue while local plays and remote adds nothing new`() {
        val outcome = RemoteQueueSyncController().onRemoteState(
            remote(queue("a", "b"), track = track("a")),
            local(queue("a", "b"), isPlaying = true),
        )
        assertTrue(outcome is RemoteSyncOutcome.Kept)
    }

    @Test
    fun `keeps local queue while both devices play`() {
        val outcome = RemoteQueueSyncController().onRemoteState(
            remote(queue("x", "y"), isPlaying = true),
            local(queue("x", "y"), isPlaying = true),
        )
        assertTrue("both playing must never adopt or merge, got $outcome", outcome is RemoteSyncOutcome.Kept)
    }

    @Test
    fun `keeps local queue when both idle and queues equivalent`() {
        val outcome = RemoteQueueSyncController().onRemoteState(
            remote(queue("a", "b")),
            local(queue("a", "b")),
        )
        assertTrue(outcome is RemoteSyncOutcome.Kept)
    }

    // --- MERGE_REMOTE_APPEND ---------------------------------------------------

    @Test
    fun `merges new remote tracks while local playback owns the session`() {
        val outcome = RemoteQueueSyncController().onRemoteState(
            remote(queue("b", "c", "d"), track = track("b")),
            local(queue("a", "b"), isPlaying = true, currentTrackId = "a"),
        )
        assertTrue("expected append-merge while playing, got $outcome", outcome is RemoteSyncOutcome.Merged)
        outcome as RemoteSyncOutcome.Merged
        assertEquals(listOf("c", "d"), outcome.appendedTracks.map { it.id })
        // Local order is preserved verbatim, new remote tracks land at the tail.
        assertEquals(listOf("a", "b", "c", "d"), outcome.queue.map { it.id })
    }

    @Test
    fun `merge dedupes remote tracks already queued locally`() {
        val outcome = RemoteQueueSyncController().onRemoteState(
            remote(queue("a", "b", "c", "b"), track = track("a")),
            local(queue("a", "b"), isPlaying = true),
        )
        assertTrue(outcome is RemoteSyncOutcome.Merged)
        outcome as RemoteSyncOutcome.Merged
        assertEquals(listOf("c"), outcome.appendedTracks.map { it.id })
        assertEquals(listOf("a", "b", "c"), outcome.queue.map { it.id })
    }

    // --- IGNORE_STALE ----------------------------------------------------------

    @Test
    fun `ignores echo from own device even when it claims playback`() {
        val outcome = RemoteQueueSyncController().onRemoteState(
            remote(queue("x", "y"), isPlaying = true, activeDeviceId = phone),
            local(queue("a", "b"), isPlaying = false),
        )
        assertTrue("own-device echo must be stale, got $outcome", outcome is RemoteSyncOutcome.IgnoredStale)
    }

    @Test
    fun `ignores out-of-order and duplicate timestamps but accepts newer ones`() {
        val controller = RemoteQueueSyncController()
        val first = controller.onRemoteState(
            remote(queue("x"), timestampMs = 200),
            local(emptyList(), currentTrackId = null),
        )
        assertTrue("ts=200 must be processed, got $first", first is RemoteSyncOutcome.Adopted)

        val older = controller.onRemoteState(
            remote(queue("stale"), timestampMs = 100),
            local(queue("x")),
        )
        assertTrue("ts=100 after ts=200 must be stale, got $older", older is RemoteSyncOutcome.IgnoredStale)

        val duplicate = controller.onRemoteState(
            remote(queue("stale"), timestampMs = 200),
            local(queue("x")),
        )
        assertTrue("duplicate ts=200 must be stale, got $duplicate", duplicate is RemoteSyncOutcome.IgnoredStale)

        val newer = controller.onRemoteState(
            remote(queue("x", "y"), timestampMs = 300),
            local(queue("x")),
        )
        assertTrue("ts=300 must be processed, got $newer", newer is RemoteSyncOutcome.Adopted)
    }

    @Test
    fun `frames without timestamp are never rejected by the watermark`() {
        val controller = RemoteQueueSyncController()
        controller.onRemoteState(remote(queue("x"), timestampMs = 500), local(emptyList(), currentTrackId = null))
        val undated = controller.onRemoteState(remote(queue("x", "y"), timestampMs = null), local(queue("x")))
        assertTrue("undated frame must reach the policy, got $undated", undated is RemoteSyncOutcome.Adopted)
    }

    // --- erroneous / optional payloads -----------------------------------------

    @Test
    fun `empty payload without queue and track keeps local queue`() {
        val outcome = RemoteQueueSyncController().onRemoteState(
            remote(emptyList(), track = null, isPlaying = true),
            local(queue("a", "b"), isPlaying = false),
        )
        assertTrue("garbage empty frame must never clear local, got $outcome", outcome is RemoteSyncOutcome.Kept)
    }

    @Test
    fun `remote track without queue payload keeps local queue`() {
        // Contract: `queue` is OPTIONAL on broadcasts — a playing remote without a queue
        // payload must not adopt an empty queue over the local one.
        val outcome = RemoteQueueSyncController().onRemoteState(
            remote(emptyList(), track = track("x"), isPlaying = true),
            local(queue("a", "b"), isPlaying = false),
        )
        assertTrue("queue-less frame must keep local, got $outcome", outcome is RemoteSyncOutcome.Kept)
    }

    @Test
    fun `out-of-range currentIndex falls back to the remote track position`() {
        val tooBig = RemoteQueueSyncController().onRemoteState(
            remote(queue("x", "y", "z"), track = track("z"), currentIndex = 99),
            local(emptyList(), currentTrackId = null),
        )
        assertTrue(tooBig is RemoteSyncOutcome.Adopted)
        assertEquals(2, (tooBig as RemoteSyncOutcome.Adopted).startIndex)

        val negative = RemoteQueueSyncController().onRemoteState(
            remote(queue("x", "y"), track = track("y"), currentIndex = -5),
            local(emptyList(), currentTrackId = null),
        )
        assertTrue(negative is RemoteSyncOutcome.Adopted)
        assertEquals(1, (negative as RemoteSyncOutcome.Adopted).startIndex)
    }

    @Test
    fun `out-of-range currentIndex without usable track falls back to zero`() {
        val outcome = RemoteQueueSyncController().onRemoteState(
            remote(queue("x", "y"), track = track("unknown"), currentIndex = 42),
            local(emptyList(), currentTrackId = null),
        )
        assertTrue(outcome is RemoteSyncOutcome.Adopted)
        assertEquals(0, (outcome as RemoteSyncOutcome.Adopted).startIndex)
    }

    @Test
    fun `queue-less payload still advances the stale watermark`() {
        val controller = RemoteQueueSyncController()
        val kept = controller.onRemoteState(
            remote(emptyList(), track = null, timestampMs = 500),
            local(queue("a")),
        )
        assertTrue(kept is RemoteSyncOutcome.Kept)
        val older = controller.onRemoteState(
            remote(queue("x"), timestampMs = 400),
            local(queue("a")),
        )
        assertTrue("frame older than a processed queue-less frame is stale, got $older", older is RemoteSyncOutcome.IgnoredStale)
    }
}
