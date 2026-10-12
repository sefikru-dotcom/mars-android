package ru.mars.mobile

/**
 * Pure-JVM policy that maps UI motion events to durations and honors the system
 * "reduce motion" accessibility setting. No Android imports on purpose: the view layer
 * reads [durationFor] and applies it to its animator; everything here is unit-testable.
 *
 * Contract:
 * - [isReducedMotion] interprets Settings.Global.ANIMATOR_DURATION_SCALE: 0f disables
 *   animation system-wide, and negative/NaN values are treated as "disabled" too
 *   (defensive: a broken scale must never produce animation);
 * - with reduced motion every duration collapses to 0 (instant state change);
 * - otherwise durations come from [MarsMotion] tokens — short, decelerate-only motion
 *   as described there.
 */

/** UI events that carry motion in the Mars mobile client. */
enum class MotionEvent {
    /** A new assistant/user message appears in the transcript. */
    MESSAGE_REVEAL,

    /** A collapsed card (thinking, tool call, code) expands. */
    CARD_EXPAND,

    /** The "scroll to bottom" FAB shows or hides. */
    SCROLL_TO_BOTTOM_SHOW,

    /** The offline/network banner slides in or out. */
    NETWORK_BANNER,

    /** The navigation drawer opens or closes. */
    DRAWER,

    /** Micro tap feedback on interactive rows and chips. */
    MICRO_TAP,
}

object MotionPolicy {

    /**
     * True when animations must be suppressed: the system animator duration scale is 0
     * ("animations off"), negative, or not a number.
     */
    fun isReducedMotion(animatorDurationScale: Float): Boolean =
        animatorDurationScale.isNaN() || animatorDurationScale <= 0f

    /**
     * Duration in milliseconds for [event]. Returns 0 when [reducedMotion] is true so
     * callers can always animate unconditionally — the collapse happens here.
     */
    fun durationFor(event: MotionEvent, reducedMotion: Boolean): Int {
        if (reducedMotion) return 0
        return when (event) {
            MotionEvent.MESSAGE_REVEAL -> MarsMotion.baseMs
            MotionEvent.CARD_EXPAND -> MarsMotion.baseMs
            MotionEvent.NETWORK_BANNER -> MarsMotion.baseMs
            MotionEvent.SCROLL_TO_BOTTOM_SHOW -> MarsMotion.fastMs
            MotionEvent.MICRO_TAP -> MarsMotion.fastMs
            MotionEvent.DRAWER -> MarsMotion.slowMs
        }
    }
}
