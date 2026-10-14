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
        val progress: ProgressBar,
        val statusLine: TextView,
    )

    fun create(
        activity: ComponentActivity,
        title: String,
        onRefresh: () -> Unit,
    ): Parts {
        val dp = { value: Int -> MarsTheme.dp(activity, value) }
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
            setPadding(0, 0, 0, dp(12))
        }
        header.addView(
            MarsTheme.circleIconButton(
                activity,
                R.drawable.ic_arrow_back,
                "Назад",
                bg = MarsTheme.SURFACE,
                iconTint = MarsTheme.TEXT,
                sizeDp = 48,
            ).apply { setOnClickListener { activity.finish() } },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) },
        )
        header.addView(TextView(activity).apply {
            text = title
            textSize = 22f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val progress = ProgressBar(activity).apply {
            isIndeterminate = true
            visibility = View.GONE
            contentDescription = "Загрузка"
        }
        header.addView(progress, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(8) })
        header.addView(
            MarsTheme.circleIconButton(
                activity,
                R.drawable.ic_refresh,
                "Обновить",
                bg = MarsTheme.SURFACE,
                iconTint = MarsTheme.TEXT,
                sizeDp = 48,
            ).apply { setOnClickListener { onRefresh() } },
            LinearLayout.LayoutParams(dp(48), dp(48)),
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
