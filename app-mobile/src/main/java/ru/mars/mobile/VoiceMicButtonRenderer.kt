package ru.mars.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat

/**
 * Рисует состояние голосового диалога на кнопке микрофона.
 *
 * Здесь только Android-детали (drawable, tint, ripple) — все решения (активна ли кнопка,
 * какая иконка, какой текст) принимает чистая [VoiceDialogPresentationPolicy], поэтому
 * поведение проверяется в JVM-тестах, а этот файл остаётся тонким.
 *
 * Что важно и почему:
 * - размер кнопки не трогаем: layout-параметры задаёт каркас композера (34 dp), и менять их
 *   при смене фазы значит дёргать строку ввода на каждом переходе;
 * - активная кнопка красная с ПОСТОЯННОЙ обводкой: заливка [MarsTheme.ACCENT] + обводка
 *   [MarsTheme.ACCENT_HOVER] толщиной [VoiceDialogPresentationPolicy.ACTIVE_STROKE_DP].
 *   Обводка не анимируется и не пульсирует — «постоянная» значит одинаковая все три фазы;
 * - ripple пересобирается вместе с фоном (RippleDrawable держит normal/mask внутри), иначе
 *   после первого нажатия остался бы старый прозрачный фон;
 * - иконка состояния + `stateDescription`: состояние читается и без цвета.
 */
object VoiceMicButtonRenderer {

    fun render(
        context: Context,
        button: ImageButton?,
        ui: VoiceDialogUi,
    ) {
        if (button == null) return
        button.setImageDrawable(ContextCompat.getDrawable(context, ui.iconRes))
        // На красной заливке тёмная иконка не читается — активная фаза рисует светлую.
        button.imageTintList = ColorStateList.valueOf(
            if (ui.active) MarsTheme.TEXT_STRONG else MarsTheme.TEXT_MUTED
        )
        button.background = background(context, active = ui.active)
        button.contentDescription = ui.contentDescription
        ViewCompat.setStateDescription(button, ui.stateDescription)
    }

    /**
     * Фон кнопки. Неактивная — прозрачная, как в каркасе (кнопка не «весит» в композере).
     * Активная — красная заливка с постоянной обводкой; маска ripple остаётся круглой.
     */
    fun background(context: Context, active: Boolean): RippleDrawable {
        val dp = { value: Int -> MarsTheme.dp(context, value) }
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) MarsTheme.ACCENT else android.graphics.Color.TRANSPARENT)
            if (active) {
                setStroke(dp(VoiceDialogPresentationPolicy.ACTIVE_STROKE_DP), MarsTheme.ACCENT_HOVER)
            }
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(MarsTheme.TEXT_STRONG)
        }
        return RippleDrawable(ColorStateList.valueOf(MarsTheme.RIPPLE), normal, mask)
    }
}
