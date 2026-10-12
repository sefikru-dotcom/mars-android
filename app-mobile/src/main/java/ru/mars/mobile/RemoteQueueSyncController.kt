package ru.mars.mobile

import ru.mars.network.model.MusicPlayerState
import ru.mars.network.model.MusicTrack
import ru.mars.network.model.PlaybackStatus

/** The local side of a remote-sync comparison, snapshotted when a remote frame arrives. */
data class LocalPlaybackSnapshot(
    val queue: List<MusicTrack>,
    val isPlaying: Boolean,
    /** Id of the locally focused track (policy/logging context). */
    val currentTrackId: String?,
    /** This client's Mars Connect device id — the echo-suppression key. */
    val deviceId: String,
)

/**
 * What the app must do after one remote state frame. The decision is pure; applying an
 * outcome goes through [MarsMusicPlayer], whose listeners refresh every UI surface
 * (MusicActivity, notification service, chat mini player) exactly as for local changes.
 */
sealed interface RemoteSyncOutcome {
    /** Replace the local queue (policy: ADOPT_REMOTE). Never auto-starts audio. */
    data class Adopted(val queue: List<MusicTrack>, val startIndex: Int, val reason: String) : RemoteSyncOutcome

    /**
     * Keep the local queue and append remote-only tracks (policy: MERGE_REMOTE_APPEND).
     * [queue] is the full merged result; [appendedTracks] is what the player must add —
     * the current track and position stay untouched.
     */
    data class Merged(val queue: List<MusicTrack>, val appendedTracks: List<MusicTrack>, val reason: String) : RemoteSyncOutcome

    /** Local queue wins; nothing to apply. */
    data class Kept(val reason: String) : RemoteSyncOutcome

    /** Echo or out-of-order frame; nothing to apply. */
    data class IgnoredStale(val reason: String) : RemoteSyncOutcome
}

/**
 * Glue between the real Mars Connect event stream ([ru.mars.network.repository.MusicRepository.subscribeRemoteState])
 * and the pure [RemoteQueueSyncPolicy]: validates each frame, enforces the stale-event
 * watermark, asks the policy for the decision and maps it onto an applicable
 * [RemoteSyncOutcome]. No sync logic is duplicated here — every "whose queue wins" rule
 * lives in the policy; this controller only guards the wire payload and the timeline.
 *
 * Layering: data (repository parses frames into [MusicPlayerState]) → domain (this
 * controller + [RemoteQueueSyncPolicy]) → UI state (caller applies the outcome through
 * [MarsMusicPlayer]; listeners redraw MusicActivity and the mini player).
 *
 * Payload safety (contract: `queue`/`currentIndex` are OPTIONAL on broadcasts):
 * - a frame without a queue payload can never adopt or clear the local queue;
 * - an out-of-range `currentIndex` falls back to the remote track's position, then 0;
 * - non-monotonic server timestamps are dropped as stale (the watermark only advances
 *   on frames that were actually processed).
 *
 * Adopting never starts audio by itself: the phone must not begin playing because a
 * remote device did — starting playback stays a local user action (existing behavior).
 */
class RemoteQueueSyncController {

    /** Highest remote session timestamp processed so far; frames at/below it are stale. */
    private var lastRemoteTimestampMs: Long? = null

    /**
     * Resolves one remote frame against the local snapshot. Pure apart from the
     * stale-event watermark; safe to call sequentially from the app's main thread.
     */
    fun onRemoteState(remote: MusicPlayerState, local: LocalPlaybackSnapshot): RemoteSyncOutcome {
        // 1. Timeline guard: a frame older-or-equal to the last processed one is stale
        //    (late reorder or duplicate broadcast). Null timestamps cannot be dated and
        //    pass through to the policy, which still suppresses own-device echoes.
        val timestamp = remote.timestampMs
        val watermark = lastRemoteTimestampMs
        if (timestamp != null && watermark != null && timestamp <= watermark) {
            return RemoteSyncOutcome.IgnoredStale("remote timestamp $timestamp <= last processed $watermark")
        }

        // 2. Payload guard: queue/currentIndex are optional on the wire, so a frame
        //    without a queue must never wipe or replace the local one.
        if (remote.queue.isEmpty()) {
            advanceWatermark(timestamp)
            return RemoteSyncOutcome.Kept(
                if (remote.track == null) "remote payload carries neither queue nor track"
                else "remote state without queue payload; keeping local queue",
            )
        }

        // 3. Domain decision — the single source of truth for queue ownership.
        val remoteIsPlaying = remote.playback == PlaybackStatus.PLAYING
        val decision = RemoteQueueSyncPolicy.decideSync(
            localQueue = local.queue,
            remoteQueue = remote.queue,
            localIsPlaying = local.isPlaying,
            remoteIsPlaying = remoteIsPlaying,
            localTrackId = local.currentTrackId,
            remoteTrackId = remote.track?.id,
            localDeviceId = local.deviceId,
            remoteDeviceId = remote.activeDeviceId,
        )
        advanceWatermark(timestamp)

        return when (decision.resolution) {
            SyncResolution.IGNORE_STALE -> RemoteSyncOutcome.IgnoredStale(decision.reason)
            SyncResolution.ADOPT_REMOTE -> RemoteSyncOutcome.Adopted(
                queue = decision.resolvedQueue,
                startIndex = resolveStartIndex(decision.resolvedQueue, remote),
                reason = decision.reason,
            )
            SyncResolution.MERGE_REMOTE_APPEND -> RemoteSyncOutcome.Merged(
                queue = decision.resolvedQueue,
                appendedTracks = RemoteQueueSyncPolicy.calculateDiff(local.queue, decision.resolvedQueue).addedTracks,
                reason = decision.reason,
            )
            SyncResolution.KEEP_LOCAL -> {
                // Local playback owns the session; genuinely new remote tracks are still
                // absorbed (append-only) so a remote-side enqueue shows up here without
                // interrupting the current track.
                val added = RemoteQueueSyncPolicy.calculateDiff(local.queue, remote.queue).addedTracks
                if (local.isPlaying && added.isNotEmpty()) {
                    RemoteSyncOutcome.Merged(
                        queue = RemoteQueueSyncPolicy.mergeQueueAppend(local.queue, added),
                        appendedTracks = added,
                        reason = "local playback active; appending ${added.size} new remote track(s)",
                    )
                } else {
                    RemoteSyncOutcome.Kept(decision.reason)
                }
            }
        }
    }

    private fun advanceWatermark(timestamp: Long?) {
        val current = lastRemoteTimestampMs
        if (timestamp != null && (current == null || timestamp > current)) {
            lastRemoteTimestampMs = timestamp
        }
    }

    /** In-range `currentIndex` wins; else the position of the remote's current track; else 0. */
    private fun resolveStartIndex(queue: List<MusicTrack>, remote: MusicPlayerState): Int {
        if (remote.currentIndex in queue.indices) return remote.currentIndex
        // Local copy: remote.track is a cross-module property, no smart cast.
        val remoteTrack = remote.track ?: return 0
        return queue.indexOfFirst { it.id == remoteTrack.id }.takeIf { it >= 0 } ?: 0
    }
}
