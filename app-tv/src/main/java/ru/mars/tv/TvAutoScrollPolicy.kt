package ru.mars.tv

/**
 * Pure auto-scroll policy of the TV chat transcript (no Android, no I/O — JVM-testable;
 * mobile `AutoScrollPolicy` parity). The transcript must follow a streaming answer ONLY
 * while the user is at the bottom: a reader who scrolled up to reread a line must not be
 * yanked back on every token, and a programmatic scroll must never move the D-pad focus
 * (the screen scrolls with `scrollTo`, not `fullScroll(FOCUS_DOWN)`).
 *
 * State machine (hosted per screen, decisions here):
 * - pinned (initial): every [ru.mars.tv.TvChatActivity] auto-scroll runs; the jump
 *   control stays hidden.
 * - the user scrolls away from the bottom ([onScroll] with a distance above the
 *   threshold): auto-scroll stops, the jump control becomes visible (returned value).
 * - the user returns to the bottom ([onScroll] within the threshold) or activates the
 *   jump control ([pinToBottom]): pinned again, the jump control hides.
 * - an outgoing message / a freshly opened session re-pins unconditionally
 *   ([pinToBottom]): the user always sees their own turn and the answer that follows.
 */
class TvAutoScrollPolicy {

    /** True while the transcript may follow the stream automatically. */
    var pinnedToBottom: Boolean = true
        private set

    /**
     * One scroll observation: re-evaluates the pinned state from the distance to the
     * bottom and returns whether the «К последнему» jump control should be VISIBLE
     * (true = the user is reading away from the live edge).
     */
    fun onScroll(distanceFromBottomPx: Int, thresholdPx: Int): Boolean {
        pinnedToBottom = distanceFromBottomPx <= thresholdPx.coerceAtLeast(0)
        return !pinnedToBottom
    }

    /** True while an incoming chunk/card may scroll the transcript. */
    fun shouldAutoScroll(): Boolean = pinnedToBottom

    /**
     * Re-pins unconditionally (jump control activated, outgoing message, session
     * opened/reloaded). Returns the jump-control visibility — always false: a pinned
     * transcript never shows «К последнему».
     */
    fun pinToBottom(): Boolean {
        pinnedToBottom = true
        return false
    }

    companion object {

        /**
         * Distance (px) between the last content pixel and the visible bottom edge —
         * the value [onScroll] compares against the threshold. Never negative: content
         * shorter than the viewport reads 0 (always at the bottom).
         */
        fun distanceFromBottom(
            contentBottomPx: Int,
            scrollYPx: Int,
            viewportHeightPx: Int,
            viewportBottomPaddingPx: Int,
        ): Int = (contentBottomPx - (scrollYPx + viewportHeightPx - viewportBottomPaddingPx))
            .coerceAtLeast(0)

        /**
         * `scrollTo` target (px) that puts the transcript exactly at the bottom without
         * overscrolling — the focus-stable replacement for `fullScroll(FOCUS_DOWN)`.
         */
        fun scrollTargetForBottom(
            contentBottomPx: Int,
            viewportHeightPx: Int,
            viewportBottomPaddingPx: Int,
        ): Int = distanceFromBottom(contentBottomPx, 0, viewportHeightPx, viewportBottomPaddingPx)
    }
}
