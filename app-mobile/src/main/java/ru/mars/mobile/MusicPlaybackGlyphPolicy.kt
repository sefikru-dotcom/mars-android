package ru.mars.mobile

/**
 * Pure policy for the play/pause glyph crossfade in the music flow: alpha only — never
 * scale, never bounce/overshoot, capped at [MarsMotion.fastMs] (120 ms) so the effect
 * stays a discreet micro-feedback, and collapsed to an instant swap under reduced motion.
 * Android-free for JVM tests.
 */
object PlayPauseIconPolicy {
    /**
     * Crossfade duration in ms: [MarsMotion.fastMs] normally, 0 (instant swap) when
     * [reducedMotion] — routed through [MotionPolicy] like every other effect.
     */
    fun durationMs(reducedMotion: Boolean): Int =
        MotionPolicy.durationFor(MotionCue.MICRO_TAP, reducedMotion).coerceIn(0, MarsMotion.fastMs)

    /**
     * Crossfade only a real glyph change on a button that already rendered its first
     * glyph ([currentResId] != null — the first render is instant, there is nothing to
     * fade from) and only while animation is allowed (duration > 0).
     */
    fun shouldCrossfade(currentResId: Int?, targetResId: Int, durationMs: Int): Boolean =
        currentResId != null && currentResId != targetResId && durationMs > 0
}
