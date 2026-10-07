package ru.mars.mobile

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

class ProjectsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
    }

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(12))
            setBackgroundColor(BACKGROUND)
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text = "Проекты"; textSize = 25f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply { text = "Назад"; setOnClickListener { finish() } })
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "Сервисы Марса и состояние инфраструктуры"
            textSize = 14f; setTextColor(MUTED); setPadding(0, dp(8), 0, dp(16))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        MarsProjects.all.forEach { project ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable().apply { setColor(SURFACE); cornerRadius = dp(14).toFloat() }
            }
            val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            titleRow.addView(TextView(this).apply { text = project.name; textSize = 19f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
            titleRow.addView(TextView(this).apply { text = "● ${project.status}"; textSize = 13f; setTextColor(STATUS) })
            card.addView(titleRow)
            card.addView(TextView(this).apply {
                text = project.description + if (project.url.isNotBlank()) "\n${project.url}" else ""
                textSize = 14f; setTextColor(MUTED); setPadding(0, dp(7), 0, dp(8))
            })
            if (project.url.isNotBlank()) card.addView(Button(this).apply {
                text = "Открыть сайт"; setOnClickListener { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(project.url))) }
            }, LinearLayout.LayoutParams(-2, dp(42)))
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        root.addView(ScrollView(this).apply { addView(list); isFillViewport = true }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { private const val BACKGROUND = 0xff10131a.toInt(); private const val SURFACE = 0xff1b202b.toInt(); private const val MUTED = 0xffaab2c0.toInt(); private const val STATUS = 0xff70d69a.toInt() }
}
