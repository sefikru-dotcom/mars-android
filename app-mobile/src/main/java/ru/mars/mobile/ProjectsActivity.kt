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
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ProjectsActivity : ComponentActivity() {
    private lateinit var list: LinearLayout
    private lateinit var updated: TextView
    private var loading = false
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(buildView()); loadMetrics() }

    private fun buildView(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(12)); setBackgroundColor(BACKGROUND) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text = "Проекты"; textSize = 25f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply { text = "Обновить"; setOnClickListener { loadMetrics() } })
        header.addView(Button(this).apply { text = "Назад"; setOnClickListener { finish() } })
        root.addView(header)
        updated = TextView(this).apply { text = "Загрузка метрик…"; textSize = 14f; setTextColor(MUTED); setPadding(0, dp(8), 0, dp(12)) }; root.addView(updated)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list); isFillViewport = true }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }
    private fun loadMetrics() { if (loading) return; loading = true; updated.text = "Обновление…"; lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { fetch() }.getOrNull() }
        loading = false; if (result == null) { updated.text = "Сеть недоступна · показаны сохранённые проекты"; renderFallback() } else render(result)
    } }
    private fun fetch(): JSONObject { val c = URL(METRICS_URL).openConnection() as HttpURLConnection; c.connectTimeout = 8000; c.readTimeout = 10000; c.requestMethod = "GET"; return c.inputStream.bufferedReader().use { JSONObject(it.readText()) }.also { c.disconnect() } }
    private fun render(data: JSONObject) { list.removeAllViews(); val prod = data.optJSONObject("prod"); val services = prod?.optJSONArray("services")
        if (services != null) for (i in 0 until services.length()) addService(services.getJSONObject(i))
        val host = data.optJSONObject("host") ?: data.optJSONObject("host_metrics"); if (host != null) addHost(host)
        updated.text = "Метрики обновлены: ${data.optString("ts", "только что")}"; if (list.childCount == 0) renderFallback()
    }
    private fun renderFallback() { list.removeAllViews(); MarsProjects.all.forEach { addCard(it.name, it.description, it.url, it.status, null) }; updated.text = "Временный офлайн-режим · ${MarsProjects.all.size} проектов" }
    private fun addService(o: JSONObject) { val active = o.optString("state").lowercase() == "active" || o.optString("state").lowercase() == "ok"; val title = o.optString("title", "Сервис"); val desc = o.optString("desc"); val url = o.optString("url"); val details = buildString { if (o.has("uptime")) append("Аптайм: ${o.optString("uptime")}  "); if (o.has("latency")) append("Задержка: ${o.optString("latency")} ms") }; addCard(title, listOf(desc, details).filter { it.isNotBlank() }.joinToString("\n"), url, if (active) "active" else o.optString("state", "offline"), active) }
    private fun addHost(o: JSONObject) { val ram = o.optString("ram", "—"); val disk = o.optString("disk", "—"); val cpu = o.optString("cpu", "—"); val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); background = bg(SURFACE) }; card.addView(TextView(this).apply { text = "Состояние хоста"; textSize = 19f; setTextColor(Color.WHITE) }); card.addView(TextView(this).apply { text = "RAM: $ram    CPU: $cpu    Диск: $disk"; textSize = 14f; setTextColor(MUTED); setPadding(0, dp(8), 0, 0) }); list.addView(card, margin()) }
    private fun addCard(title: String, desc: String, url: String, status: String, active: Boolean?) { val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); background = bg(SURFACE) }; val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }; row.addView(TextView(this).apply { text = title; textSize = 19f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(TextView(this).apply { text = "● $status"; textSize = 13f; setTextColor(if (active != false) STATUS else ERROR) }); card.addView(row); if (desc.isNotBlank()) card.addView(TextView(this).apply { text = desc; textSize = 14f; setTextColor(MUTED); setPadding(0, dp(7), 0, dp(5)) }); if (url.isNotBlank()) card.addView(Button(this).apply { text = "Открыть сайт"; setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }); list.addView(card, margin()) }
    private fun bg(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
    private fun margin() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    companion object { private const val METRICS_URL = "https://marscore.ru/dashboard/api/metrics"; private const val BACKGROUND = 0xff10131a.toInt(); private const val SURFACE = 0xff1b202b.toInt(); private const val MUTED = 0xffaab2c0.toInt(); private const val STATUS = 0xff70d69a.toInt(); private const val ERROR = 0xffff7d87.toInt() }
}
