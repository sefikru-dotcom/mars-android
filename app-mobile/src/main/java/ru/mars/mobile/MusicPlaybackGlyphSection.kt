package ru.mars.mobile

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.drawable.LayerDrawable
import android.provider.Settings
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import androidx.core.content.ContextCompat

/**
 * Глиф play/pause: alpha-кроссфейд между иконками кнопок плеера.
 *
 * Решения принимают чистые политики ([PlayPauseIconPolicy], [MotionPolicy]); здесь —
 * только применение к видам и реестр живых аниматоров `iconCrossfades`, который хост
 * гасит на onPause/onStop/onDestroy, чтобы ни одна анимация не пережила экран.
 */

/** System «reduce motion» accessibility setting routed through the pure [MotionPolicy]. */
internal fun MusicActivity.isReducedMotion(): Boolean = MotionPolicy.isReducedMotion(
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
)

/**
 * Sets the play/pause glyph of [button] with an alpha-only crossfade (120 ms,
 * [MarsMotion.fastMs] cap, no scale/bounce — pure [PlayPauseIconPolicy] decides).
 * The glyph res id currently shown is remembered in the button's tag: the first
 * render swaps instantly (nothing to fade from) and unchanged glyphs are no-ops,
 * so list refreshes never restart the effect. Reduced motion → instant swap.
 */
internal fun MusicActivity.setPlayPauseGlyph(button: ImageButton, showPause: Boolean) {
    val targetRes = if (showPause) R.drawable.ic_pause else R.drawable.ic_play
    val currentRes = button.tag as? Int
    if (currentRes == targetRes) return // already showing (or crossfading toward) the target
    button.tag = targetRes
    val duration = PlayPauseIconPolicy.durationMs(isReducedMotion())
    val old = currentRes?.let { ContextCompat.getDrawable(this, it)?.mutate() }
    val new = ContextCompat.getDrawable(this, targetRes)?.mutate()
    if (old == null || new == null || !PlayPauseIconPolicy.shouldCrossfade(currentRes, targetRes, duration)) {
        button.setImageResource(targetRes)
        return
    }
    // True crossfade: both glyphs are layered, the new one fades in while the old
    // fades out (alpha only, decelerate curve); the ImageView applies its tint to
    // the layer stack, so both children render in the button's icon color.
    new.alpha = 0
    button.setImageDrawable(LayerDrawable(arrayOf(old, new)))
    val crossfade = AnimatorSet().apply {
        playTogether(
            ObjectAnimator.ofInt(new, "alpha", 0, 255),
            ObjectAnimator.ofInt(old, "alpha", 255, 0),
        )
        this.duration = duration.toLong()
        interpolator = DecelerateInterpolator()
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Land the final frame: the plain target glyph replaces the two-layer
                // stack. Skipped when a newer toggle already moved the button on.
                if (button.tag == targetRes) {
                    new.alpha = 255
                    button.setImageDrawable(new)
                }
            }
        })
    }
    // One crossfade per button: drop any in-flight one first. Its end listener is
    // guarded by `button.tag`, which now holds the NEW target, so cancelling it is a
    // no-op that just frees the animator — long sessions never accumulate them.
    iconCrossfades.remove(button)?.let { runCatching { it.cancel() } }
    iconCrossfades[button] = crossfade
    crossfade.start()
}

/**
 * Ends every tracked glyph crossfade at its final frame (the end listener lands the
 * target glyph) and drops the references — nothing animated outlives onPause/onStop.
 */
internal fun MusicActivity.finishIconCrossfades() {
    iconCrossfades.values.forEach { runCatching { it.end() } }
    iconCrossfades.clear()
}
