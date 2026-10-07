package ru.mars.mobile

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
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
        super.onCreate(savedInstanceState); setContentView(buildView()); loadMetrics()
        refreshJob = lifecycleScope.launch { while (isActive) { delay(25_000); loadMetrics() } }
    }
    override fun onDestroy() { refreshJob?.cancel(); super.onDestroy() }

    private fun buildView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(12)); setBackgroundColor(BACKGROUND) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text = "Проекты"; textSize = 25f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
        progress = ProgressBar(this).apply { visibility = View.GONE; isIndeterminate = true }
        header.addView(progress, LinearLayout.LayoutParams(dp(28), dp(28)))
        header.addView(Button(this).apply { text = "Обновить"; setOnClickListener { loadMetrics() } })
        header.addView(Button(this).apply { text = "Назад"; setOnClickListener { finish() } })
        root.addView(header)
        updated = TextView(this).apply { text = "Загрузка метрик…"; textSize = 14f; setTextColor(MUTED); setPadding(0, dp(8), 0, dp(12)) }; root.addView(updated)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list); isFillViewport = true }, LinearLayout.LayoutParams(-1, 0, 1f)); return root
    }
    private fun loadMetrics() { if (loading || isFinishing) return; loading = true; progress.visibility = View.VISIBLE; updated.text = "Обновление…"; lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { fetch() }.getOrNull() }; loading = false; progress.visibility = View.GONE
        if (result == null) renderFallback() else render(result)
    } }
    private fun fetch(): JSONObject { val c = URL(METRICS_URL).openConnection() as HttpURLConnection; return try { c.connectTimeout = 8000; c.readTimeout = 10000; c.requestMethod = "GET"; c.inputStream.bufferedReader().use { JSONObject(it.readText()) } } finally { c.disconnect() } }
    private fun render(data: JSONObject) { val parsed = ProjectsMetricsParser.parse(data); list.removeAllViews(); parsed.services.forEach { addService(it) }; parsed.host?.let { addHost(it) }; updated.text = "Обновлено в ${clock()}"; if (list.childCount == 0) renderFallback() }
    private fun renderFallback() { list.removeAllViews(); MarsProjects.all.forEach { addCard(it.name, it.description, it.url, it.status, null) }; updated.text = "Нет сети · офлайн-проекты (${MarsProjects.all.size})" }
    private fun addService(s: ParsedService) { val details = listOf(if (s.uptime.isNotBlank()) "Аптайм: ${s.uptime}" else "", if (s.latency.isNotBlank()) "Задержка: ${s.latency} ms" else "").filter { it.isNotBlank() }.joinToString("  "); addCard(s.title, listOf(s.description, details).filter { it.isNotBlank() }.joinToString("\n"), s.url, s.state, s.active) }
    private fun addHost(h: ParsedHost) { val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); background = bg(SURFACE) }; card.addView(TextView(this).apply { text = "Состояние хоста"; textSize = 19f; setTextColor(Color.WHITE) }); card.addView(TextView(this).apply { text = "RAM: ${h.ram}   CPU: ${h.cpu}   Диск: ${h.disk}\nАптайм системы: ${h.uptime}"; textSize = 14f; setTextColor(MUTED); setPadding(0, dp(8), 0, 0) }); list.addView(card, margin()) }
    private fun addCard(title: String, desc: String, url: String, status: String, active: Boolean?) { val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); background = bg(SURFACE) }; val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }; row.addView(TextView(this).apply { text = title; textSize = 19f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(TextView(this).apply { text = "● $status"; textSize = 13f; setTextColor(if (active != false) STATUS else ERROR) }); card.addView(row); if (desc.isNotBlank()) card.addView(TextView(this).apply { text = desc; textSize = 14f; setTextColor(MUTED); setPadding(0, dp(7), 0, dp(5)) }); if (url.isNotBlank() && URL_PATTERN.matches(url)) card.addView(Button(this).apply { text = "Открыть"; setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }); list.addView(card, margin()) }
    private fun bg(color: Int) = GradientDrawable().apply { setColor(color); setStroke(dp(1), BORDER); cornerRadius = dp(14).toFloat() }
    private fun margin() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun clock() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    companion object { private const val METRICS_URL = "https://marscore.ru/dashboard/api/metrics"; private val URL_PATTERN = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE); private const val BACKGROUND = 0xff10131a.toInt(); private const val SURFACE = 0xff1b202b.toInt(); private const val BORDER = 0xff283143.toInt(); private const val MUTED = 0xffaab2c0.toInt(); private const val STATUS = 0xff70d69a.toInt(); private const val ERROR = 0xffff7d87.toInt() }
}
