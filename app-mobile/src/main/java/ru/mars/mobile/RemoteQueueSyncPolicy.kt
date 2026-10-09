package ru.mars.mobile

import ru.mars.network.model.MusicTrack

/** Outcome of resolving a remote queue update against the local playback state. */
enum class SyncResolution { ADOPT_REMOTE, KEEP_LOCAL, MERGE_REMOTE_APPEND, IGNORE_STALE }

/**
 * What the mobile client should do with its queue after a remote (TV/Connect) state update.
 *
 * @param resolvedQueue the queue the caller should hold after applying [resolution] —
 *   the remote queue for [SyncResolution.ADOPT_REMOTE], the merged queue for
 *   [SyncResolution.MERGE_REMOTE_APPEND], and the untouched local queue otherwise.
 * @param reason short human-readable explanation, safe to log verbatim.
 */
data class QueueSyncDecision(
    val resolution: SyncResolution,
    val resolvedQueue: List<MusicTrack>,
    val reason: String,
)

/**
 * Track-level difference between two queues, compared by [MusicTrack.id].
 *
 * [isIdentical] requires the same ids in the same order; a pure reorder yields no
 * added/removed tracks but is not identical (play order matters for a queue).
 */
data class QueueDiff(
    val addedTracks: List<MusicTrack>,
    val removedTracks: List<MusicTrack>,
    val isIdentical: Boolean,
)

/**
 * Pure decision policy for cross-device music queue sync (mobile ↔ TV/remote via
 * Mars Connect). Resolves "whose queue wins" when a remote player-state frame arrives,
 * without touching Android framework classes or player state.
 *
 * Precedence (first match wins):
 * 1. Echo suppression — an update originating from this same device is stale by definition.
 * 2. Empty local queue — nothing to protect, adopt the remote queue.
 * 3. Playback asymmetry — an actively playing side owns the session.
 * 4. Both idle — a diverged, non-empty remote queue is newer intent; otherwise keep local.
 *
 * [localTrackId]/[remoteTrackId] describe the currently focused track on each side and are
 * accepted for caller completeness and logging context; the resolution rules key off the
 * queues, the playing flags and the device ids.
 */
object RemoteQueueSyncPolicy {

    /**
     * Computes which tracks the remote queue adds to / removes from the local queue.
     * Comparison is by [MusicTrack.id]; duplicates within a side are collapsed (first
     * occurrence wins) so the diff lists never repeat an id.
     */
    fun calculateDiff(localQueue: List<MusicTrack>, remoteQueue: List<MusicTrack>): QueueDiff {
        val localIds = localQueue.mapTo(LinkedHashSet()) { it.id }
        val remoteIds = remoteQueue.mapTo(LinkedHashSet()) { it.id }
        val addedTracks = dedupeById(remoteQueue.filter { it.id !in localIds })
        val removedTracks = dedupeById(localQueue.filter { it.id !in remoteIds })
        val isIdentical = localQueue.map { it.id } == remoteQueue.map { it.id }
        return QueueDiff(addedTracks, removedTracks, isIdentical)
    }

    /**
     * Resolves a remote queue update against the local state. See the object KDoc for the
     * full precedence table. Never returns [SyncResolution.MERGE_REMOTE_APPEND] on its own —
     * callers that want an append-instead-of-replace behavior combine the decision with
     * [mergeQueueAppend]; the resolution is part of the shared [SyncResolution] vocabulary.
     */
    fun decideSync(
        localQueue: List<MusicTrack>,
        remoteQueue: List<MusicTrack>,
        localIsPlaying: Boolean,
        remoteIsPlaying: Boolean,
        localTrackId: String?,
        remoteTrackId: String?,
        localDeviceId: String,
        remoteDeviceId: String?,
    ): QueueSyncDecision {
        // 1. Our own broadcast echoed back — always stale, regardless of flags.
        if (remoteDeviceId != null && remoteDeviceId == localDeviceId) {
            return QueueSyncDecision(SyncResolution.IGNORE_STALE, localQueue, "echo from own device")
        }
        // 2. Nothing local to protect.
        if (localQueue.isEmpty() && remoteQueue.isNotEmpty()) {
            return QueueSyncDecision(SyncResolution.ADOPT_REMOTE, remoteQueue, "local queue empty; adopting remote queue")
        }
        // 3. Playback asymmetry: the playing side owns the session.
        if (remoteIsPlaying && !localIsPlaying) {
            return QueueSyncDecision(SyncResolution.ADOPT_REMOTE, remoteQueue, "remote is playing while local is idle; adopting remote queue")
        }
        if (localIsPlaying && !remoteIsPlaying) {
            return QueueSyncDecision(SyncResolution.KEEP_LOCAL, localQueue, "local playback is active; keeping local queue")
        }
        if (localIsPlaying) { // both playing
            return QueueSyncDecision(SyncResolution.KEEP_LOCAL, localQueue, "both devices are playing; keeping local queue")
        }
        // 4. Both idle: a diverged, non-empty remote queue is the newer intent.
        val diff = calculateDiff(localQueue, remoteQueue)
        return if (!diff.isIdentical && remoteQueue.isNotEmpty()) {
            QueueSyncDecision(SyncResolution.ADOPT_REMOTE, remoteQueue, "both idle with diverged queues; adopting remote queue")
        } else {
            QueueSyncDecision(SyncResolution.KEEP_LOCAL, localQueue, "both idle with equivalent queues; keeping local queue")
        }
    }

    /**
     * Preserves the local queue verbatim (order and contents) and appends remote tracks
     * whose ids are new — deduplicated against both the local queue and earlier remote
     * entries. This is the [SyncResolution.MERGE_REMOTE_APPEND] payload.
     */
    fun mergeQueueAppend(local: List<MusicTrack>, remote: List<MusicTrack>): List<MusicTrack> {
        val knownIds = local.mapTo(mutableSetOf()) { it.id }
        val appended = remote.filter { knownIds.add(it.id) }
        return local + appended
    }

    private fun dedupeById(tracks: List<MusicTrack>): List<MusicTrack> {
        val seen = mutableSetOf<String>()
        return tracks.filter { seen.add(it.id) }
    }
}
