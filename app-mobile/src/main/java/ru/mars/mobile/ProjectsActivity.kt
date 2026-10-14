package ru.mars.mobile

import android.content.Context
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.mars.network.cache.ProjectsOfflineCache
import ru.mars.network.cache.SharedPreferencesKeyValueStore
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Экран «Проекты»: метрики хоста и сервисов. Волна 3 — общий shell разделов
 * [SectionScaffold] (safe-area, заголовок, «Назад», спиннер, «Обновить»);
 * офлайн-кэш и статусная семантика экрана сохранены без изменений: мгновенный
 * показ свежего снимка (24 ч) с бейджем возраста, сбой сети НЕ затирает
 * показанный кэш (бейдж + ненавязчивый баннер), без годного снимка — честный
 * фолбэк [MarsProjects] с offline-строкой ([OfflineCacheUiPolicy]).
 */
class ProjectsActivity : ComponentActivity() {
    private lateinit var scaffold: SectionScaffold.Parts
    private lateinit var list: LinearLayout

    /**
     * Статус-строка и спиннер общего shell. В shell статус-строка по умолчанию
     * скрыта — экран метрик показывает её всегда (от «Загрузка метрик…» при
     * старте до кэш-бейджа и offline-строки), поэтому [buildView] делает её
     * видимой один раз, а вся прежняя логика записи текста/цвета сохранена.
     */
    private val updated: TextView get() = scaffold.statusLine
    private val progress: ProgressBar get() = scaffold.progress

    private var loading = false
    private var refreshJob: Job? = null

    /** На экране сейчас метрики из кэша (сбой сети их не затирает, лишь добавляет бейдж). */
    private var cacheShown = false

    /**
     * Офлайн-кэш сырого JSON метрик (core-network): мгновенный показ при старте,
     * фоновый HTTP обновляет снимок; при сбое сети остаёмся на кэше с бейджем
     * «Кэш (N мин назад)» (политика [OfflineCacheUiPolicy]).
     */
    private val offlineCache by lazy {
        ProjectsOfflineCache(
            SharedPreferencesKeyValueStore(
                getSharedPreferences(PROJECTS_OFFLINE_PREFS, Context.MODE_PRIVATE),
            ),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildView())
        showCachedMetrics()
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
        scaffold = SectionScaffold.create(this, "Проекты", ::loadMetrics)

        updated.visibility = View.VISIBLE
        updated.text = "Загрузка метрик…"
        updated.setTextColor(MarsTheme.TEXT_MUTED)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scaffold.content.addView(
            ScrollView(this).apply {
                addView(list, ViewGroup.LayoutParams(-1, -2))
                isFillViewport = true
            },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        return scaffold.root
    }

    private fun loadMetrics() {
        if (loading || isFinishing) return
        loading = true
        progress.visibility = View.VISIBLE
        if (!cacheShown) updated.text = "Обновление…"
        lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) { runCatching { fetch() }.getOrNull() }
            loading = false
            progress.visibility = View.GONE
            if (raw == null) {
                onFetchFailure()
            } else {
                offlineCache.saveProjectsJson(raw)
                renderLive(raw)
            }
        }
    }

    /** Сеть отработала: LIVE-рендер свежего JSON (кэш уже сохранён вызывающим). */
    private fun renderLive(raw: String) {
        val data = runCatching { JSONObject(raw) }.getOrNull()
        if (data == null) {
            onFetchFailure()
            return
        }
        cacheShown = false
        render(data)
    }

    /**
     * Сбой сети: если кэш пригоден — сохраняем отображение кэшированных проектов
     * (не затираем!), подсвечивая бейджем «Кэш (N мин назад)» и отметкой о сбое;
     * иначе прежний статический фолбэк.
     */
    private fun onFetchFailure() {
        val decision = decisionFor(OfflineFetchState.FAILURE)
        if (decision.showCachedContent) {
            if (!cacheShown) renderCachedContent()
            if (cacheShown) {
                updated.text = listOfNotNull(decision.badgeText, decision.bannerText)
                    .joinToString("  ·  ")
                updated.setTextColor(MarsTheme.STATUS_WARNING)
                return
            }
        }
        cacheShown = false
        renderFallback()
    }

    /** Мгновенный показ кэшированного JSON метрик при старте экрана (до ответа сети). */
    private fun showCachedMetrics() {
        val decision = decisionFor(OfflineFetchState.PENDING)
        if (!decision.showCachedContent) return
        renderCachedContent()
        if (cacheShown) {
            updated.text = decision.badgeText ?: ""
            updated.setTextColor(MarsTheme.TEXT_MUTED)
        }
    }

    /** Рендер содержимого из кэша; cacheShown=false, если JSON битый или список пуст. */
    private fun renderCachedContent() {
        val json = offlineCache.loadProjectsJson()
        val data = json?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (data == null) {
            cacheShown = false
            return
        }
        val parsed = ProjectsMetricsParser.parse(data)
        list.removeAllViews()
        parsed.host?.let { addHost(it) }
        parsed.services.forEach { addService(it) }
        cacheShown = list.childCount > 0
        if (!cacheShown) list.removeAllViews()
    }

    /** Решение политики свежести по фактам: фаза запроса + возраст снимка в кэше. */
    private fun decisionFor(state: OfflineFetchState): OfflineUiDecision =
        OfflineCacheUiPolicy.evaluate(
            surface = MobileOfflineSurface.PROJECTS,
            fetchState = state,
            livePayloadEmpty = false,
            snapshotTakenAtMs = offlineCache.getSavedTimestamp(),
            nowMs = System.currentTimeMillis(),
        )

    private fun fetch(): String {
        val c = URL(METRICS_URL).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.requestMethod = "GET"
            c.inputStream.bufferedReader().use { it.readText() }
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
        /** Приватный prefs-файл офлайн-кэша метрик проектов (входит в logout-cleanup). */
        private const val PROJECTS_OFFLINE_PREFS = "mars_projects_offline"
        private val URL_PATTERN = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
    }
}
