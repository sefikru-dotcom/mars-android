package ru.mars.mobile

/**
 * Pure seek-bar drag policy for [MusicActivity]: while the user's finger owns the thumb,
 * the periodic progress ticker and player callbacks must not snap it back (on a touch
 * screen the thumb visibly fights the drag), and the player is seeked exactly once — on
 * release — instead of on every intermediate progress change. Android-free for JVM tests.
 */
object SeekDragPolicy {
    /** Initial/reset value of the pending drag position: nothing has been dragged. */
    const val NO_PENDING_SEEK = -1

    /** The ticker may move the thumb and time label only when no drag is in progress. */
    fun tickerMayUpdate(isDragging: Boolean): Boolean = !isDragging

    /**
     * The position to seek to on release: [pendingMs] clamped into the track, or null when
     * nothing is pending (any negative value) — then the player must not be touched.
     */
    fun pendingSeekTarget(pendingMs: Int, durationMs: Int): Int? =
        if (pendingMs < 0) null else pendingMs.coerceIn(0, durationMs.coerceAtLeast(0))
}
