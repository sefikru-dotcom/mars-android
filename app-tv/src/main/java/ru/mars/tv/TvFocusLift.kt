package ru.mars.tv

import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Premium D-pad focus treatment for Android TV (Apple TV-style):
 * on focus a view lifts slightly (scale + elevation) while its
 * StateListDrawable background cross-fades to the ruby highlight.
 * Apply to every focusable element after setContentView.
 *
 * Длительность движения больше НЕ константа этого файла: она приходит из чистой
 * [TvMotionPolicy] по cue [TvMotionCue.FOCUS_LIFT] и схлопывается в 0, когда в системе
 * включено «уменьшить движение» (Settings.Global.ANIMATOR_DURATION_SCALE). При нулевой
 * длительности финальное состояние ставится напрямую — без аниматора и лишнего кадра
 * ожидания, поэтому быстрый проход по D-pad не «плывёт».
 *
 * Флаг [reducedMotion] экраны, читающие настройку сами ([TvChatActivity],
 * [TvMusicActivity], [DashboardActivity]), передают явно; остальные вызовы (в том числе
 * вспомогательные вью-фабрики вроде [ru.mars.tv.chat.TvMarkdownRenderer]) разрешают его
 * через [TvMotion] — один раз на экран по контексту View.
 */
object TvFocusLift {
    private const val SCALE_FOCUSED = 1.04f
    private const val LIFT_DP = 8f

    /** Стандартная кривая движения — [TvMotionTokens.INTERPOLATOR_DECELERATE]. */
    private val focusInterpolator = DecelerateInterpolator()

    /**
     * Вешает фокусную анимацию на [view]. Длительность одинакова для подъёма и для
     * кроссфейда фона: масштаб и подсветка приходят в финальное состояние синхронно.
     */
    fun attach(view: View, reducedMotion: Boolean = TvMotion.reducedMotionFor(view.context)) {
        val liftPx = LIFT_DP * view.resources.displayMetrics.density
        val durationMs = TvMotionPolicy.durationFor(TvMotionCue.FOCUS_LIFT, reducedMotion)
        applyCrossFadeDuration(view, durationMs)
        view.elevation = 0f
        view.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) SCALE_FOCUSED else 1f
            val lift = if (hasFocus) liftPx else 0f
            v.animate().cancel()
            if (durationMs == 0) {
                // «Уменьшить движение»: финальное состояние без аниматора (mobile parity).
                v.scaleX = scale
                v.scaleY = scale
                v.translationZ = lift
            } else {
                v.animate()
                    .scaleX(scale).scaleY(scale).translationZ(lift)
                    .setDuration(durationMs.toLong())
                    .setInterpolator(focusInterpolator)
                    .start()
            }
        }
    }

    /** Тот же флаг для пачки фокусируемых View (экраны, читающие настройку сами). */
    fun attachAll(reducedMotion: Boolean, vararg views: View?) {
        views.filterNotNull().forEach { attach(it, reducedMotion) }
    }

    fun attachAll(vararg views: View?) {
        views.filterNotNull().forEach { attach(it) }
    }

    /**
     * Кроссфейд фона идёт с длительностью cue. Числа в XML остаются дизайн-системным
     * дефолтом (их наличие проверяет TvDesignSystemTest), а рантайм-значение — из
     * политики: под «уменьшить движение» фейд выключается полностью.
     *
     * StateListDrawable делит ConstantState на процесс, и это здесь безопасно: значение
     * всегда одно и то же для всей системы, потому что источник — одна системная
     * настройка. Не селектор или не StateListDrawable — просто ничего не делаем.
     */
    private fun applyCrossFadeDuration(view: View, durationMs: Int) {
        val background = view.background as? StateListDrawable ?: return
        background.setEnterFadeDuration(durationMs)
        background.setExitFadeDuration(durationMs)
    }
}
