package ru.mars.tv

import android.view.View

/**
 * Premium D-pad focus treatment for Android TV (Apple TV-style):
 * on focus a view lifts slightly (scale + elevation) while its
 * StateListDrawable background cross-fades to the ruby highlight.
 * Apply to every focusable element after setContentView.
 */
object TvFocusLift {
    private const val SCALE_FOCUSED = 1.04f
    private const val LIFT_DP = 8f
    private const val ANIM_MS = 180L

    fun attach(view: View) {
        val liftPx = LIFT_DP * view.resources.displayMetrics.density
        view.elevation = 0f
        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().cancel()
                v.animate().scaleX(SCALE_FOCUSED).scaleY(SCALE_FOCUSED)
                    .translationZ(liftPx).setDuration(ANIM_MS).start()
            } else {
                v.animate().cancel()
                v.animate().scaleX(1f).scaleY(1f)
                    .translationZ(0f).setDuration(ANIM_MS).start()
            }
        }
    }

    fun attachAll(vararg views: View?) {
        views.filterNotNull().forEach { attach(it) }
    }
}
