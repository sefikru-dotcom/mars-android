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
 * - размер кнопки не трогаем: layout-параметры задаёт каркас композера (36 dp — единый ряд
 *   со скрепкой и звездой отправки), и менять их при смене фазы значит дёргать строку ввода
 *   на каждом переходе;
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
        // В покое иконка в едином тоне со скрепкой ([MarsTheme.TEXT_EM]): обе второстепенные
        // кнопки композера выглядят одинаково благородно-тёмными.
        button.imageTintList = ColorStateList.valueOf(
            if (ui.active) MarsTheme.TEXT_STRONG else MarsTheme.TEXT_EM
        )
        button.background = background(context, active = ui.active)
        button.contentDescription = ui.contentDescription
        ViewCompat.setStateDescription(button, ui.stateDescription)
    }

    /**
     * Фон кнопки.
     *
     * Неактивная — тёмный стеклянный ореол [MarsTheme.COMPOSER_ACTION_BG] с тонкой гранью
     * [MarsTheme.COMPOSER_ACTION_BORDER]: ровно тот же тон и та же грань, что у скрепки, —
     * две второстепенные кнопки композера обязаны читаться как одна пара. Активная — заливка
     * [MarsTheme.ACCENT] с ПОСТОЯННОЙ обводкой [MarsTheme.ACCENT_HOVER]; маска ripple
     * остаётся круглой в обоих случаях.
     */
    fun background(context: Context, active: Boolean): RippleDrawable {
        val dp = { value: Int -> MarsTheme.dp(context, value) }
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (active) {
                setColor(MarsTheme.ACCENT)
                setStroke(dp(VoiceDialogPresentationPolicy.ACTIVE_STROKE_DP), MarsTheme.ACCENT_HOVER)
            } else {
                setColor(MarsTheme.COMPOSER_ACTION_BG)
                setStroke(dp(INACTIVE_STROKE_DP), MarsTheme.COMPOSER_ACTION_BORDER)
            }
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(MarsTheme.TEXT_STRONG)
        }
        return RippleDrawable(ColorStateList.valueOf(MarsTheme.RIPPLE), normal, mask)
    }

    /** Обводка неактивной кнопки: заметно, но тоньше акцентной — иерархия не перевёрнута. */
    const val INACTIVE_STROKE_DP = 1
}
