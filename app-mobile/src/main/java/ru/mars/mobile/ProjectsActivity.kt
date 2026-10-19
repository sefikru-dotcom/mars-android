package ru.mars.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
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
 * Экран «Проекты»: метрики хостов и сервисов. Волна 3 — общий shell разделов
 * [SectionScaffold] (safe-area, заголовок, «Назад», спиннер, «Обновить»);
 * офлайн-кэш и статусная семантика экрана сохранены без изменений: мгновенный
 * показ свежего снимка (24 ч) с бейджем возраста, сбой сети НЕ затирает
 * показанный кэш (бейдж + ненавязчивый баннер), без годного снимка — честный
 * фолбэк [MarsProjects] с offline-строкой ([OfflineCacheUiPolicy]).
 *
 * P0-4 (волна 4): каждый хост показывается ПОД СВОИМ именем — Aeza из блока
 * «local», прод 45.129.2.36 из «prod.host» (раньше прод-метрики подписывались
 * чужим именем); мёртвая строка про пинг удалена (сервер поле задержки не
 * отдаёт никогда), вместо неё реальные restarts/http; статус-строка показывает
 * возраст ДАННЫХ по «ts» ответа ([MetricsTimestamp]), а не время запроса.
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
    private val progress: View get() = scaffold.progress

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
        renderSections(parsed)
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
        renderSections(parsed)
        val summary = ProjectsSummary.of(parsed.services)
        updated.text = ProjectsSummary.statusLine(summary, freshnessOf(parsed.timestamp))
        updated.setTextColor(if (summary.hasOffline) MarsTheme.STATUS_WARNING else MarsTheme.TEXT_MUTED)
        if (list.childCount == 0) renderFallback()
    }

    /** A12: секции полного дашборда в порядке показа; пустые секции не рисуются. */
    private fun renderSections(parsed: ParsedMetrics) {
        parsed.hosts.forEach { addHost(it) }
        parsed.services.forEach { addService(it) }
        addVpnSection(parsed.vpn)
        addTunnelsSection(parsed.tunnels)
        addHostServicesSection(parsed.hostServices, parsed.radarProducts)
        addAlertsSection(parsed.alerts)
        addBackupsSection(parsed.backups)
        addRestartsSection(parsed.restartLog)
    }

    /**
     * Честная свежесть данных: «ts» ответа (когда метрики сняты) плюс давность
     * «N мин назад»; время запроса — только фолбэк, когда в ответе нет «ts».
     */
    private fun freshnessOf(ts: String): String = ProjectsSummary.freshnessText(
        dataTs = ts,
        ageText = MetricsTimestamp.ageText(ts, System.currentTimeMillis()),
        clock = clock(),
    )

    private fun renderFallback() {
        list.removeAllViews()
        MarsProjects.all.forEach { addCard(it.name, it.description, it.url, it.status, null) }
        updated.text = ProjectsSummary.offlineLine(MarsProjects.all.size)
        updated.setTextColor(MarsTheme.STATUS_WARNING)
    }

    private fun addService(s: ParsedService) {
        // Детали — только из реально отданных сервером полей (чистая ProjectsSummary):
        // аптайм/рестарты/HTTP-код проверки; мёртвой ветки про пинг больше нет.
        val desc = listOf(s.description, ProjectsSummary.serviceDetails(s))
            .filter { it.isNotBlank() }.joinToString("\n")
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
            // Честное имя хоста из парсера: Aeza — из блока local, прод — из prod.host
            // (P0-4: прод-данные больше не подписываются чужим именем).
            text = h.title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MarsTheme.TEXT_STRONG)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        titleRow.addView(statusBadge("Онлайн", MarsTheme.STATUS_ONLINE))
        card.addView(titleRow)

        val lines = ProjectsSummary.hostLines(h)
        if (lines.isNotBlank()) {
            val metricsText = TextView(this).apply {
                text = lines
                textSize = 13f
                setTextColor(MarsTheme.TEXT_MUTED)
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(8), 0, 0)
            }
            card.addView(metricsText)
        }

        list.addView(card, margin())
    }

    // --- A12: секции полного дашборда (VPN, туннели, службы хоста, алерты, бэкапы, рестарты) ---

    /**
     * Компоненты VPN-узла. Метрики его железа (vpn.host) уже рисуются отдельной
     * карточкой хоста в parsed.hosts. Вердикт «В сети» — по эталонному /dashboard/:
     * xray И caddy активны. Пустая/отсутствующая секция — карточки нет.
     */
    private fun addVpnSection(vpn: ParsedVpn?) {
        if (vpn == null || vpn.states.isEmpty()) return
        val online = ProjectsSummary.vpnOnline(vpn.states)
        addSectionCard(
            title = "VPN-узел STV",
            body = ProjectsSummary.stateLines(vpn.states),
            badge = if (online) "В сети" to MarsTheme.STATUS_ONLINE else "Сбой" to MarsTheme.STATUS_ERROR,
        )
    }

    /** Туннели связи: честный бейдж каждому — «Ожидание» (warning) вместо «Онлайн» для offline. */
    private fun addTunnelsSection(tunnels: List<ParsedTunnel>) {
        if (tunnels.isEmpty()) return
        val card = newSectionCard()
        val online = tunnels.count { it.online }
        card.addView(sectionHeader(
            "Туннели связи",
            "$online из ${tunnels.size}",
            if (online == tunnels.size) MarsTheme.STATUS_ONLINE else MarsTheme.STATUS_WARNING,
        ))
        tunnels.forEach { t ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            line.addView(TextView(this).apply {
                text = t.name
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(MarsTheme.TEXT_STRONG)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(statusBadge(
                if (t.online) "Подключен" else "Ожидание",
                if (t.online) MarsTheme.STATUS_ONLINE else MarsTheme.STATUS_WARNING,
            ))
            row.addView(line)
            val details = ProjectsSummary.tunnelDetails(t)
            if (details.isNotBlank()) row.addView(sectionBody(details, topPadDp = 2))
            card.addView(row)
        }
        list.addView(card, margin())
    }

    /** Службы хоста Aeza (local.services) + число товаров прайс-радара (local.radar_products). */
    private fun addHostServicesSection(states: List<ParsedStateEntry>, radarProducts: Int?) {
        val body = listOf(
            ProjectsSummary.stateLines(states).takeIf { it.isNotBlank() },
            ProjectsSummary.radarLine(radarProducts),
        ).filterNotNull().joinToString("\n")
        if (body.isBlank()) return
        val badge = when {
            states.isEmpty() -> null
            states.all { it.active } -> "Онлайн" to MarsTheme.STATUS_ONLINE
            else -> "${states.count { it.active }} из ${states.size}" to MarsTheme.STATUS_WARNING
        }
        addSectionCard("Службы хоста Aeza", body, badge)
    }

    /** Алерты: пустой список — карточки нет; текст подсвечен предупреждением. */
    private fun addAlertsSection(alerts: List<String>) {
        if (alerts.isEmpty()) return
        addSectionCard(
            title = "Алерты",
            body = alerts.joinToString("\n"),
            badge = alerts.size.toString() to MarsTheme.STATUS_WARNING,
            bodyColor = MarsTheme.STATUS_WARNING,
        )
    }

    /** Бэкапы prod-сервера (prod.backups): свежие сверху, не больше четырёх. */
    private fun addBackupsSection(backups: List<ParsedBackup>) {
        val body = ProjectsSummary.backupLines(backups)
        if (body.isBlank()) return
        addSectionCard("Бэкапы (прод)", body)
    }

    /** Журнал рестартов прод-сервисов (prod.restart_log), дубли схлопнуты в «xN». */
    private fun addRestartsSection(log: List<ParsedRestart>) {
        val body = ProjectsSummary.restartLines(log)
        if (body.isBlank()) return
        addSectionCard("Журнал рестартов (прод)", body)
    }

    /** Универсальная карточка секции: заголовок с бейджем + многострочный текст. */
    private fun addSectionCard(
        title: String,
        body: String,
        badge: Pair<String, Int>? = null,
        bodyColor: Int = MarsTheme.TEXT_MUTED,
    ) {
        val card = newSectionCard()
        card.addView(sectionHeader(title, badge?.first, badge?.second ?: MarsTheme.TEXT_MUTED))
        if (body.isNotBlank()) card.addView(sectionBody(body, topPadDp = 8, color = bodyColor))
        list.addView(card, margin())
    }

    private fun newSectionCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = MarsTheme.card(this@ProjectsActivity, MarsTheme.SURFACE_CARD, radiusDp = 14)
    }

    private fun sectionHeader(title: String, badgeLabel: String? = null, badgeColor: Int = MarsTheme.TEXT_MUTED): LinearLayout =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@ProjectsActivity).apply {
                text = title
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(MarsTheme.TEXT_STRONG)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (badgeLabel != null) addView(statusBadge(badgeLabel, badgeColor))
        }

    private fun sectionBody(text: String, topPadDp: Int, color: Int = MarsTheme.TEXT_MUTED): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(color)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(topPadDp), 0, 0)
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
                setTextColor(MarsTheme.TEXT_STRONG)
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
