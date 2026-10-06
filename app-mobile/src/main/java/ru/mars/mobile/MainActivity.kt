package ru.mars.mobile

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildShellView())
    }

    private fun buildShellView(): LinearLayout {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.parseColor("#10131A"))
        }

        content.addView(textView(R.string.mobile_shell_title, 24f, Color.WHITE))
        content.addView(textView(R.string.shell_note, 16f, Color.parseColor("#AAB2C0"), topMargin = 12))
        content.addView(stateCard(R.string.connection_label, R.string.connection_state, 32))
        content.addView(stateCard(R.string.sessions_label, R.string.sessions_state, 12))
        return content
    }

    private fun stateCard(titleRes: Int, stateRes: Int, marginTop: Int = 0): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.parseColor("#1B202B"))
        }
        card.addView(textView(titleRes, 14f, Color.parseColor("#E76F51")))
        card.addView(textView(stateRes, 16f, Color.WHITE, topMargin = 6))
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(marginTop) }
        return card
    }

    private fun textView(textRes: Int, sizeSp: Float, color: Int, topMargin: Int = 0): TextView {
        return TextView(this).apply {
            setText(textRes)
            textSize = sizeSp
            setTextColor(color)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { this.topMargin = dp(topMargin) }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
