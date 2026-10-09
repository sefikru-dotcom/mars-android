package ru.mars.mobile

/**
 * Pure visibility policy for the "scroll to bottom" FAB over the chat transcript
 * (platform independent, unit-tested).
 *
 * Problem it solves: when the reader scrolls up into history, the transcript keeps growing
 * underneath them (SSE tokens) and the tail silently drifts further away. They need a
 * one-tap affordance back to the latest message — but a button that toggles on a single
 * distance threshold flickers while the viewport hovers near that line (or while the
 * content bottom jitters mid-stream). The policy therefore adds hysteresis: the FAB appears
 * only once the viewport is more than [showThresholdPx] above the content bottom, and
 * disappears only once the reader comes back within [hideThresholdPx] of the bottom.
 *
 * Wiring contract (ChatActivity):
 * - feed [onViewportDistanceFromBottom] from the scroll listener and before each transcript
 *   re-render with the current distance (content bottom minus viewport bottom, px);
 * - call [onJumpToLatest] whenever the reader is taken to the latest message (FAB tap,
 *   sending a message, session switch) so the button hides immediately instead of waiting
 *   for the posted scroll to report back;
 * - [visible] drives the FAB visibility; the FAB tap handler additionally re-pins
 *   [AutoScrollPolicy] so the viewport follows the stream again.
 */
class ScrollToBottomPolicy(
    private val showThresholdPx: Int = DEFAULT_SHOW_THRESHOLD_PX,
    private val hideThresholdPx: Int = DEFAULT_HIDE_THRESHOLD_PX,
) {

    init {
        require(showThresholdPx > hideThresholdPx) {
            "showThresholdPx ($showThresholdPx) must exceed hideThresholdPx ($hideThresholdPx)"
        }
        require(hideThresholdPx >= 0) { "hideThresholdPx must be non-negative" }
    }

    /** True while the FAB should be shown. Starts hidden (fresh sessions open pinned). */
    var visible: Boolean = false
        private set

    /**
     * Records a viewport observation: [distancePx] is how far the content bottom is below the
     * viewport bottom (0 = exactly at the bottom, e.g. also when the transcript fits without
     * scrolling). Inside the hysteresis band (hideThresholdPx, showThresholdPx) the previous
     * state is kept, so small jitters around a single threshold never flicker the button.
     */
    fun onViewportDistanceFromBottom(distancePx: Int) {
        visible = when {
            distancePx >= showThresholdPx -> true
            distancePx <= hideThresholdPx -> false
            else -> visible
        }
    }

    /** The reader was taken to the latest message (FAB tap, send, session switch): hide now. */
    fun onJumpToLatest() {
        visible = false
    }

    companion object {
        /** ~120dp on a 2.75x display — clearly scrolled away from the tail, not just drifting. */
        const val DEFAULT_SHOW_THRESHOLD_PX = 330

        /** ~56dp on a 2.75x display — matches the AutoScrollPolicy re-pin window. */
        const val DEFAULT_HIDE_THRESHOLD_PX = 154
    }
}
