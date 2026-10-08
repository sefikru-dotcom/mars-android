package ru.mars.mobile

/**
 * Pure auto-scroll decision policy for the chat transcript (platform independent, unit-tested).
 *
 * Problem it solves: the transcript is re-rendered on every SSE token; an unconditional
 * "scroll to bottom" inside the render path yanks the viewport down while the user reads
 * history mid-stream. The policy keeps a single piece of state — whether the viewport is
 * *pinned* to the bottom — and the render path scrolls only while pinned.
 *
 * Wiring contract (ChatActivity):
 * - before mutating the transcript, feed the current distance between the content bottom and
 *   the viewport bottom via [onViewportDistanceFromBottom] (measured synchronously, so
 *   transient layout states during the rebuild never flip the pin);
 * - scroll events may feed the same method to keep the pin fresh between renders;
 * - user-initiated jumps to the latest (sending a message, switching sessions, reattaching
 *   to an active turn) call [pinToBottom];
 * - after new content is rendered, scroll only when [shouldScrollOnContentChanged] is true.
 */
class AutoScrollPolicy(private val bottomThresholdPx: Int = DEFAULT_BOTTOM_THRESHOLD_PX) {

    /** True while the viewport counts as "at the latest message". Starts pinned. */
    var pinnedToBottom: Boolean = true
        private set

    /**
     * Records a viewport observation: [distancePx] is how far the content bottom is below the
     * viewport bottom (0 = exactly at the bottom). Values within [bottomThresholdPx] count as
     * pinned, so a small overscroll or a not-fully-flushed tail does not unpin the reader.
     */
    fun onViewportDistanceFromBottom(distancePx: Int) {
        pinnedToBottom = distancePx <= bottomThresholdPx
    }

    /** Explicit pin: the user asked for the latest (sent a message, opened a session, reattached). */
    fun pinToBottom() {
        pinnedToBottom = true
    }

    /** The render path asks this after appending/rebuilding content: scroll or leave the reader alone. */
    fun shouldScrollOnContentChanged(): Boolean = pinnedToBottom

    companion object {
        /** ~56dp on a 2.75x display — a comfortable "still reading the tail" window. */
        const val DEFAULT_BOTTOM_THRESHOLD_PX = 154
    }
}
