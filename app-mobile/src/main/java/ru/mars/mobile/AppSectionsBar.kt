package ru.mars.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Левый бар разделов — единственный навигационный компонент шторки волны 2.
 *
 * Бар содержит ТОЛЬКО приложенческие разделы [AppDestination] (состав, порядок
 * и иконочные ключи задаёт чистая [AppSectionsPolicy]); список диалогов здесь
 * не живёт — это отдельный уровень ([ChatSessionsActivity]). Компонент
 * разделяют [ChatActivity] и [ChatSessionsActivity], поэтому левый бар
 * одинаков на всех уровнях навигатора; текущий раздел подсвечивается через
 * [currentSection]. Визуальный стиль строк — паритет с прежним sidebar-списком
 * (38dp, [MarsTheme.button], иконка 18dp). Logout из бара недоступен — только
 * внутри «Настроек» ([AppNavPolicy.LOGOUT_HOST]).
 */
class AppSectionsBar(
    private val context: Context,
    /** Живой раздел навигационного состояния (подсветка строки бара). */
    private val currentSection: () -> AppDestination,
    private val onSectionClick: (AppDestination) -> Unit,
) {

    /** Вертикальный список строк разделов (для вставки в панель шторки). */
    fun build(): View {
        val nav = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        AppSectionsPolicy.sections.forEachIndexed { index, destination ->
            val selected = currentSection() == destination
            val iconRes = context.resources
                .getIdentifier(AppSectionsPolicy.iconKeyFor(destination), "drawable", context.packageName)
                .takeIf { it != 0 } ?: R.drawable.ic_mars
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(10), 0)
                background = MarsTheme.button(
                    context,
                    MarsTheme.SURFACE,
                    radiusDp = 8,
                    strokeColor = if (selected) MarsTheme.ACCENT else MarsTheme.BORDER_SUBTLE
                )
                isClickable = true
                isFocusable = true
                contentDescription = destination.title
                setOnClickListener { onSectionClick(destination) }
            }
            row.addView(ImageView(context).apply {
                setImageDrawable(ContextCompat.getDrawable(context, iconRes))
                imageTintList = ColorStateList.valueOf(
                    if (selected) MarsTheme.ACCENT else MarsTheme.TEXT_MUTED
                )
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
            row.addView(TextView(context).apply {
                text = destination.title
                textSize = 12.5f
                setTextColor(if (selected) MarsTheme.ACCENT else MarsTheme.TEXT)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                isSingleLine = true
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            nav.addView(
                row,
                LinearLayout.LayoutParams(-1, dp(38)).apply { if (index > 0) topMargin = dp(4) }
            )
        }
        return nav
    }

    private fun dp(value: Int) = MarsTheme.dp(context, value)
}
