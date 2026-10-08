package ru.mars.mobile

import ru.mars.network.model.MusicTrack
import kotlin.random.Random

/**
 * Pure playback-queue logic for the Mars music player: current index, next/previous
 * navigation, shuffle order, repeat mode and queue growth.
 *
 * Android-free so it is unit-testable on the JVM; [MarsMusicPlayer] owns one instance and
 * the UI mirrors its state.
 *
 * Repeat semantics:
 * - [RepeatMode.OFF]: navigation stops at the queue ends ([next]/[previous] return null).
 * - [RepeatMode.ALL]: navigation wraps around both ends.
 * - [RepeatMode.ONE]: [next] and [previous] keep replaying the current track.
 *
 * Shuffle semantics: toggling shuffle on rebuilds the play order with the current track
 * first and everything else in random order (seeded via [random] for deterministic tests);
 * toggling it off restores the natural queue order from the current track. Tracks added
 * while shuffled are scheduled at the end of the current pass.
 *
 * Session lifecycle: when [next] runs past the end of the queue (repeat OFF) the session
 * finishes — [isFinished] becomes true and the stale selection is dropped, so the finished
 * track can never resurface as [currentTrack]. The player mirrors this via [markFinished]
 * when it releases the media player (exhaustion or an unwinnable failure streak). Adding
 * tracks to a finished (or empty) queue starts a fresh session on the first added track —
 * the decision lives in [QueueResumePolicy] and is returned by [addToQueue]/[addAllToQueue].
 */
class MusicQueueManager(private val random: Random = Random.Default) {

    enum class RepeatMode { OFF, ALL, ONE }

    /** All queued tracks in queue order; replaced via [setQueue], grown via [addToQueue]. */
    var queue: List<MusicTrack> = emptyList()
        private set

    /** Queue index of the current track, or -1 when nothing is selected. */
    var currentIndex: Int = -1
        private set

    var isShuffled: Boolean = false
        private set

    var repeatMode: RepeatMode = RepeatMode.OFF
        private set

    /**
     * True when the playback session ended: the queue ran out (repeat OFF) or the player
     * was stopped. The queue keeps its tracks (for the UI) but nothing is current until an
     * explicit selection ([playAt]/[setQueue]) or an addition resumes playback.
     */
    var isFinished: Boolean = false
        private set

    /** Queue indexes in the order they will play; identity permutation unless shuffled. */
    private var playOrder: List<Int> = emptyList()

    /** Position of [currentIndex] inside [playOrder]; -1 when nothing is selected. */
    private var orderPosition: Int = -1

    val currentTrack: MusicTrack? get() = queue.getOrNull(currentIndex)
    val size: Int get() = queue.size
    val isEmpty: Boolean get() = queue.isEmpty()

    /** Replaces the queue and selects [startIndex] (clamped) as the current track. */
    fun setQueue(tracks: List<MusicTrack>, startIndex: Int = 0) {
        queue = tracks.toList()
        currentIndex = if (queue.isEmpty()) -1 else startIndex.coerceIn(0, queue.lastIndex)
        isFinished = false
        rebuildOrder()
    }

    /**
     * Ends the session (player released): drops the current selection so the finished
     * track does not resurface, keeps the queued tracks. Idempotent.
     */
    fun markFinished() {
        isFinished = true
        currentIndex = -1
        orderPosition = -1
    }

    /** Selects an arbitrary track without changing the queue; null when out of range. */
    fun playAt(index: Int): MusicTrack? = advanceTo(index)

    /**
     * Appends one track. Returns true when playback must (re)start on it: the queue was
     * empty or the previous session finished (see [QueueResumePolicy]).
     */
    fun addToQueue(track: MusicTrack): Boolean = addAllToQueue(listOf(track))

    /**
     * Appends tracks. Returns true when playback must (re)start on the first added track:
     * the queue was empty or the previous session finished (see [QueueResumePolicy]).
     */
    fun addAllToQueue(tracks: List<MusicTrack>): Boolean {
        if (tracks.isEmpty()) return false
        val firstNew = queue.size
        val shouldStart = QueueResumePolicy.shouldStartPlayback(
            queueWasEmpty = queue.isEmpty(),
            queueFinished = isFinished
        )
        queue = queue + tracks
        when {
            // Empty or finished session: the first added track becomes the new current one;
            // a finished queue keeps its old tracks but never resumes from them.
            shouldStart -> {
                isFinished = false
                currentIndex = firstNew
                rebuildOrder()
            }
            // Shuffle: keep the scheduled order and schedule the new tracks after it.
            isShuffled -> playOrder = playOrder + (firstNew until queue.size)
            // Sequential: natural order already covers the appended tail.
            else -> playOrder = queue.indices.toList()
        }
        return shouldStart
    }

    /** Index [next] would advance to, or null when the session finished or the queue is exhausted in this mode. */
    fun nextIndex(): Int? {
        if (queue.isEmpty() || isFinished) return null
        if (repeatMode == RepeatMode.ONE) return currentIndex.takeIf { it in queue.indices }
        if (isShuffled) {
            if (playOrder.isEmpty()) return null
            val nextPos = orderPosition + 1
            return when {
                nextPos < playOrder.size -> playOrder[nextPos]
                repeatMode == RepeatMode.ALL -> playOrder.first()
                else -> null
            }
        }
        if (currentIndex !in queue.indices) return 0
        val next = currentIndex + 1
        return when {
            next < queue.size -> next
            repeatMode == RepeatMode.ALL -> 0
            else -> null
        }
    }

    /** Index [previous] would return to, or null when there is nothing before the current track. */
    fun previousIndex(): Int? {
        if (queue.isEmpty() || isFinished) return null
        if (repeatMode == RepeatMode.ONE) return currentIndex.takeIf { it in queue.indices }
        if (isShuffled) {
            if (playOrder.isEmpty()) return null
            val prevPos = orderPosition - 1
            return when {
                prevPos >= 0 -> playOrder[prevPos]
                repeatMode == RepeatMode.ALL -> playOrder.last()
                else -> null
            }
        }
        if (currentIndex !in queue.indices) return null
        val prev = currentIndex - 1
        return when {
            prev >= 0 -> prev
            repeatMode == RepeatMode.ALL -> queue.lastIndex
            else -> null
        }
    }

    /**
     * Advances to the next track; returns the new current track. When the queue is
     * exhausted (repeat OFF) the session finishes: null is returned, the stale selection
     * is dropped and [isFinished] becomes true.
     */
    fun next(): MusicTrack? {
        val track = advanceTo(nextIndex())
        if (track == null && queue.isNotEmpty()) markFinished()
        return track
    }

    /** Steps back one track; returns the new current track, or null when there is no previous. */
    fun previous(): MusicTrack? = advanceTo(previousIndex())

    fun hasNext(): Boolean = nextIndex() != null

    fun hasPrevious(): Boolean = previousIndex() != null

    /** Toggles shuffle keeping the current track selected; returns the new state. */
    fun toggleShuffle(): Boolean {
        isShuffled = !isShuffled
        rebuildOrder()
        return isShuffled
    }

    fun setShuffled(enabled: Boolean) {
        if (isShuffled == enabled) return
        isShuffled = enabled
        rebuildOrder()
    }

    /** Cycles OFF -> ALL -> ONE -> OFF and returns the new mode. */
    fun cycleRepeatMode(): RepeatMode {
        repeatMode = when (repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        return repeatMode
    }

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
    }

    /** Tracks that will play after the current one within this pass, in play order. */
    fun upcoming(): List<MusicTrack> {
        if (isFinished || queue.isEmpty() || playOrder.isEmpty()) return emptyList()
        val start = (orderPosition + 1).coerceIn(0, playOrder.size)
        return playOrder.subList(start, playOrder.size).mapNotNull { queue.getOrNull(it) }
    }

    /** Empties the queue and drops the current selection. */
    fun clear() {
        queue = emptyList()
        currentIndex = -1
        playOrder = emptyList()
        orderPosition = -1
        isFinished = false
    }

    private fun advanceTo(index: Int?): MusicTrack? {
        if (index == null || index !in queue.indices) return null
        currentIndex = index
        isFinished = false
        orderPosition = if (isShuffled) playOrder.indexOf(index) else index
        return queue[index]
    }

    private fun rebuildOrder() {
        if (queue.isEmpty()) {
            playOrder = emptyList()
            orderPosition = -1
            return
        }
        if (!isShuffled) {
            playOrder = queue.indices.toList()
            orderPosition = currentIndex
            return
        }
        if (currentIndex in queue.indices) {
            val others = queue.indices.filter { it != currentIndex }.shuffled(random)
            playOrder = listOf(currentIndex) + others
            orderPosition = 0
        } else {
            playOrder = queue.indices.shuffled(random)
            orderPosition = -1
        }
    }
}
