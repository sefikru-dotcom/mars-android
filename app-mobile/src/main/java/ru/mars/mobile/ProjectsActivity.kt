package ru.mars.mobile

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProjectsActivity : ComponentActivity() {
    private lateinit var list: LinearLayout
    private lateinit var updated: TextView
    private lateinit var progress: ProgressBar
    private var loading = false
    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
        loadMetrics()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(25_000)
                loadMetrics()
            }
        }
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        super.onDestroy()
    }

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MarsTheme.BG)
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16), systemBars.top + dp(10), dp(16), systemBars.bottom + dp(10))
            insets
        }

        // Header
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }

        val backBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_arrow_back,
            "Назад",
            bg = MarsTheme.SURFACE,
            iconTint = MarsTheme.TEXT
        ).apply {
            setOnClickListener { finish() }
        }
        header.addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })

        val title = TextView(this).apply {
            text = "Проекты"
            textSize = 22f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))

        progress = ProgressBar(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        header.addView(progress, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(8) })

        val refreshBtn = MarsTheme.circleIconButton(
            this,
            R.drawable.ic_refresh,
            "Обновить",
            bg = MarsTheme.SURFACE,
            iconTint = MarsTheme.TEXT
        ).apply {
            setOnClickListener { loadMetrics() }
        }
        header.addView(refreshBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        root.addView(header)

        updated = TextView(this).apply {
            text = "Загрузка метрик…"
            textSize = 13f
            setTextColor(MarsTheme.TEXT_MUTED)
            setPadding(0, 0, 0, dp(14))
        }
        root.addView(updated)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            ScrollView(this).apply {
                addView(list, ViewGroup.LayoutParams(-1, -2))
                isFillViewport = true
            },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        return root
    }

    private fun loadMetrics() {
        if (loading || isFinishing) return
        loading = true
        progress.visibility = View.VISIBLE
        updated.text = "Обновление…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { fetch() }.getOrNull() }
            loading = false
            progress.visibility = View.GONE
            if (result == null) renderFallback() else render(result)
        }
    }

    private fun fetch(): JSONObject {
        val c = URL(METRICS_URL).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.requestMethod = "GET"
            c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            c.disconnect()
        }
    }

    private fun render(data: JSONObject) {
        val parsed = ProjectsMetricsParser.parse(data)
        list.removeAllViews()
        parsed.host?.let { addHost(it) }
        parsed.services.forEach { addService(it) }
        val summary = ProjectsSummary.of(parsed.services)
        updated.text = ProjectsSummary.statusLine(summary, clock())
        updated.setTextColor(if (summary.hasOffline) MarsTheme.STATUS_WARNING else MarsTheme.TEXT_MUTED)
        if (list.childCount == 0) renderFallback()
    }

    private fun renderFallback() {
        list.removeAllViews()
        MarsProjects.all.forEach { addCard(it.name, it.description, it.url, it.status, null) }
        updated.text = ProjectsSummary.offlineLine(MarsProjects.all.size)
        updated.setTextColor(MarsTheme.STATUS_WARNING)
    }

    private fun addService(s: ParsedService) {
        val details = listOf(
            if (s.uptime.isNotBlank()) "Аптайм: ${s.uptime}" else "",
            if (s.latency.isNotBlank()) "Пинг: ${s.latency} ms" else ""
        ).filter { it.isNotBlank() }.joinToString("   ")

        val desc = listOf(s.description, details).filter { it.isNotBlank() }.joinToString("\n")
        addCard(s.title, desc, s.url, s.state, s.active)
    }

    private fun addHost(h: ParsedHost) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = MarsTheme.card(this@ProjectsActivity, MarsTheme.SURFACE_CARD, radiusDp = 14)
        }
        val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = "Хост Aeza Mars"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MarsTheme.TEXT_STRONG)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        titleRow.addView(statusBadge("Онлайн", MarsTheme.STATUS_ONLINE))
        card.addView(titleRow)

        val metricsText = TextView(this).apply {
            text = "RAM: ${h.ram}    CPU: ${h.cpu}    Диск: ${h.disk}\nАптайм: ${h.uptime}"
            textSize = 13f
            setTextColor(MarsTheme.TEXT_MUTED)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(8), 0, 0)
        }
        card.addView(metricsText)

        list.addView(card, margin())
    }

    private fun addCard(title: String, desc: String, url: String, status: String, active: Boolean?) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = MarsTheme.card(this@ProjectsActivity, MarsTheme.SURFACE, radiusDp = 14)
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            text = title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MarsTheme.TEXT_STRONG)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        val isOnline = active != false
        row.addView(statusBadge(status, if (isOnline) MarsTheme.STATUS_ONLINE else MarsTheme.STATUS_ERROR))
        card.addView(row)

        if (desc.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = desc
                textSize = 13f
                setTextColor(MarsTheme.TEXT_MUTED)
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(6), 0, dp(4))
            })
        }

        if (url.isNotBlank() && URL_PATTERN.matches(url)) {
            val openBtn = Button(this).apply {
                text = "Открыть сервис"
                textSize = 13f
                setTextColor(Color.WHITE)
                background = MarsTheme.button(this@ProjectsActivity, MarsTheme.SURFACE_SUBTLE, radiusDp = 10, strokeColor = MarsTheme.BORDER)
                setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
            card.addView(openBtn, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        }
        list.addView(card, margin())
    }

    /** Status badge: themed circular dot (vector-drawn, not the font `●` glyph) + bold label. */
    private fun statusBadge(label: String, color: Int): View {
        val badge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        badge.addView(
            MarsTheme.statusDot(this, color),
            LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) }
        )
        badge.addView(TextView(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
        })
        return badge
    }

    private fun margin() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    private fun dp(v: Int) = MarsTheme.dp(this, v)
    private fun clock() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    companion object {
        private const val METRICS_URL = "https://marscore.ru/dashboard/api/metrics"
        private val URL_PATTERN = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
    }
}
