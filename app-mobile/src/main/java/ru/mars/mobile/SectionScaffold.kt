package ru.mars.mobile

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Shared shell for section screens: safe-area, header, back and refresh. */
object SectionScaffold {
    data class Parts(
        val root: LinearLayout,
        val content: LinearLayout,
        /** Индикатор загрузки шапки: спиннер (STANDARD) или точка (COMPACT). */
        val progress: View,
        val statusLine: TextView,
    )

    /**
     * Стиль шапки раздела.
     * - [STANDARD] — общий для всех разделов: круглые кнопки на SURFACE,
     *   заголовок 22sp, спиннер 28dp.
     * - [COMPACT] — особая шапка экрана мониторинга: кнопки без фона/обводки
     *   40dp, заголовок 17sp, акцентная точка 8dp вместо спиннера (в покое
     *   INVISIBLE — заголовок не прыгает). Особая шапка остаётся частью общего
     *   shell: safe-area, статус-строка (live-region) и контент-слот единые.
     */
    enum class HeaderStyle { STANDARD, COMPACT }

    fun create(
        activity: ComponentActivity,
        title: String,
        onRefresh: () -> Unit,
        headerStyle: HeaderStyle = HeaderStyle.STANDARD,
    ): Parts {
        val dp = { value: Int -> MarsTheme.dp(activity, value) }
        val compact = headerStyle == HeaderStyle.COMPACT
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MarsTheme.BG)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16), bars.top + dp(10), dp(16), bars.bottom + dp(12))
            insets
        }

        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(if (compact) 8 else 12))
        }
        val buttonSize = if (compact) 40 else 48
        // Тап-цель кнопок шапки — не меньше 48dp. В компактной шапке
        // circleIconButton рисует только иконку (bg = 0, без обводки), поэтому бокс
        // можно расширить до 48dp без видимого изменения круга; прежний marginEnd
        // уменьшен на ту же разницу, чтобы заголовок не съехал.
        val touchSize = 48
        header.addView(
            MarsTheme.circleIconButton(
                activity,
                R.drawable.ic_arrow_back,
                "Назад",
                bg = if (compact) 0 else MarsTheme.SURFACE,
                iconTint = MarsTheme.TEXT,
                sizeDp = buttonSize,
                strokeColor = if (compact) 0 else MarsTheme.BORDER,
            ).apply { setOnClickListener { activity.finish() } },
            LinearLayout.LayoutParams(dp(touchSize), dp(touchSize)).apply {
                marginEnd = dp(8)
            },
        )
        header.addView(TextView(activity).apply {
            text = title
            setTextColor(MarsTheme.TEXT_STRONG)
            if (compact) {
                // Особая шапка монитора: компактный заголовок без эллипсиса.
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
            } else {
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val progress: View = if (compact) {
            MarsTheme.statusDot(activity, MarsTheme.ACCENT).apply { visibility = View.INVISIBLE }
        } else {
            ProgressBar(activity).apply {
                isIndeterminate = true
                visibility = View.GONE
                contentDescription = "Загрузка"
            }
        }
        header.addView(
            progress,
            LinearLayout.LayoutParams(dp(if (compact) 8 else 28), dp(if (compact) 8 else 28)).apply {
                marginEnd = dp(if (compact) 12 else 8)
            },
        )
        header.addView(
            MarsTheme.circleIconButton(
                activity,
                R.drawable.ic_refresh,
                "Обновить",
                bg = if (compact) 0 else MarsTheme.SURFACE,
                iconTint = MarsTheme.TEXT,
                sizeDp = buttonSize,
                strokeColor = if (compact) 0 else MarsTheme.BORDER,
            ).apply { setOnClickListener { onRefresh() } },
            LinearLayout.LayoutParams(dp(touchSize), dp(touchSize)),
        )
        root.addView(header)

        val statusLine = TextView(activity).apply {
            visibility = View.GONE
            textSize = 13f
            setTextColor(MarsTheme.TEXT_MUTED)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(statusLine, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        return Parts(root, content, progress, statusLine)
    }
}
