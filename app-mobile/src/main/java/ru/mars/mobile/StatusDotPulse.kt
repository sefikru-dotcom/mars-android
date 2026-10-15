package ru.mars.mobile

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * View-слой мягкой неоновой пульсации статус-точки: подключает к ореол-слою
 * `LayerDrawable` из [MarsTheme.glowingStatusDot] кадровый аниматор альфы.
 *
 * Вся политика (периоды, амплитуды, кривая, гашение) — в [MarsPulsePolicy];
 * здесь только Android-обвязка: чтение системных флагов и жизненный цикл аниматора.
 *
 * Почему анимируется ореол, а не альфа вьюхи: альфа вьюхи задаёт прозрачность
 * ВСЕГО (ореол + ядро), а пульсировать должен только ореол — ядро остаётся
 * непрозрачным, геометрия и текст не участвуют вовсе, перерисовка не выходит за
 * границы самой точки.
 *
 * Почему альфа слоя, а не `LayerDrawable.setLayerAlpha`: такого метода в
 * платформе нет (скрытый API, в android-34 отсутствует вовсе), а унаследованный
 * `LayerDrawable.setAlpha` бьёт по ВСЕМ слоям сразу и сделал бы ядро мигающим.
 * Публичный путь один — альфа самого drawable-ореола (`GradientDrawable.alpha`).
 *
 * mutate() обязателен: drawable из ресурсов/кэша могут делить ConstantState, и
 * без него выставленная альфа уехала бы на все статус-индикаторы списка сразу,
 * включая те, что обязаны остаться статичными.
 *
 * Отмена: аниматор создаётся вместе с вьюхой и висит на
 * `View.OnAttachStateChangeListener` (`onViewDetachedFromWindow` -> `cancel()`).
 * В чате карточки пересоздаются на каждом `renderMessages`, поэтому без этого
 * слушателя аниматоры копились бы на отсоединённых вьюхах (утечка, батарея, ANR).
 * Отдельного `setTag` не заводим: слушатель держится самой вьюхой и уже хранит
 * ссылку на аниматор — тот же эффект без нового ресурса id и без риска занять
 * слот tag у чужого кода.
 */
internal object StatusDotPulse {

    /** Индекс слоя-ореола в [MarsTheme.glowingStatusDot]; слой 1 — ядро. */
    const val HALO_LAYER_INDEX: Int = 0

    /**
     * Подключает пульсацию к [dot] с фоном [layers] (ореол — слой 0, ядро — 1).
     *
     * @param role назначение точки — задаёт период и амплитуду.
     */
    fun attach(context: Context, dot: View, layers: LayerDrawable, role: MarsPulsePolicy.PulseRole) {
        val halo = layers.getDrawable(HALO_LAYER_INDEX) as? GradientDrawable ?: return
        // Отдельный ConstantState: иначе альфа одного индикатора протечёт на все
        // точки списка, включая статичные.
        halo.mutate()
        val mode = resolveMode(context)
        // Исходная альфа выставляется всегда: даже в статичном режиме ореол
        // должен быть в сегодняшнем оттенке, а не в дефолтном.
        halo.alpha = MarsPulsePolicy.startAlphaByte(role, mode)
        if (mode != MarsPulsePolicy.PulseMode.PULSE) return

        val periodMs = MarsPulsePolicy.periodFor(role, mode)
        if (periodMs <= 0) return

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = periodMs.toLong()
            repeatCount = ValueAnimator.INFINITE
            // Кривая считает политика (косинус, ease-in-out) — аниматор линеен,
            // иначе easing применился бы дважды.
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                // Защита от подмены фона извне: альфу выставляем только своему ореолу.
                if (dot.background === layers) {
                    halo.alpha = MarsPulsePolicy.alphaByteAt(animation.animatedFraction, role.maxAlpha)
                }
            }
        }

        dot.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                // Повторный attach (переезд вьюхи) не должен плодить второй цикл.
                if (!animator.isStarted) animator.start()
            }

            override fun onViewDetachedFromWindow(v: View) {
                animator.cancel()
            }
        })

        // Вьюха, создаваемая уже прикреплённой (частый случай в чате), иначе
        // не получила бы onViewAttachedToWindow и осталась бы статичной.
        if (dot.isAttachedToWindow) animator.start()
    }

    /**
     * Режим пульсации по системному состоянию: аниматоры выключены
     * (`ValueAnimator.areAnimatorsEnabled()`), режим экономии батареи,
     * `ANIMATOR_DURATION_SCALE == 0` (тот же источник, что у остального motion
     * в клиенте). Любое из условий даёт [MarsPulsePolicy.PulseMode.STATIC].
     */
    fun resolveMode(context: Context): MarsPulsePolicy.PulseMode {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        val powerSave = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isPowerSaveMode == true
        return MarsPulsePolicy.modeFor(
            animationsRequested = true,
            animatorsEnabled = ValueAnimator.areAnimatorsEnabled(),
            powerSaveMode = powerSave,
            animatorDurationScale = scale,
        )
    }
}
