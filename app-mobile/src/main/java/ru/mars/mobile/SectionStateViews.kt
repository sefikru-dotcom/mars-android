package ru.mars.mobile

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Shared loading/empty/error/offline views for programmatic mobile screens. */
object SectionStateViews {
    fun loading(context: android.content.Context, message: String): View =
        messageView(context, message, MarsTheme.TEXT_MUTED, withProgress = true)

    fun empty(context: android.content.Context, message: String): View =
        messageView(context, message, MarsTheme.TEXT_MUTED)

    fun offline(context: android.content.Context, message: String): View =
        messageView(context, message, MarsTheme.STATUS_WARNING)

    fun error(context: android.content.Context, message: String, onRetry: (() -> Unit)?): View {
        val box = base(context)
        box.addView(messageView(context, message, MarsTheme.STATUS_WARNING))
        onRetry?.let { retry ->
            box.addView(TextView(context).apply {
                text = "Повторить"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(MarsTheme.ACCENT_TEXT)
                background = MarsTheme.button(context, MarsTheme.ACCENT_BG, radiusDp = 999, strokeColor = MarsTheme.ACCENT_BORDER)
                setPadding(MarsTheme.dp(context, 22), MarsTheme.dp(context, 12), MarsTheme.dp(context, 22), MarsTheme.dp(context, 12))
                isClickable = true
                isFocusable = true
                contentDescription = "Повторить загрузку"
                setOnClickListener { retry() }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = MarsTheme.dp(context, 12) })
        }
        return box
    }

    private fun messageView(context: android.content.Context, message: String, color: Int, withProgress: Boolean = false): View {
        val box = base(context)
        if (withProgress) box.addView(ProgressBar(context).apply {
            isIndeterminate = true
            contentDescription = "Загрузка"
        }, LinearLayout.LayoutParams(MarsTheme.dp(context, 28), MarsTheme.dp(context, 28)).apply { bottomMargin = MarsTheme.dp(context, 8) })
        box.addView(TextView(context).apply {
            text = message
            textSize = 14f
            setTextColor(color)
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        return box
    }

    private fun base(context: android.content.Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, MarsTheme.dp(context, 28), 0, MarsTheme.dp(context, 16))
    }
}
