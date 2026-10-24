package ru.mars.tv.insights

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ru.mars.network.api.MarsApi
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.InsightsSnapshot
import ru.mars.network.model.ServerLogFile
import ru.mars.network.model.ServerLogTail
import ru.mars.network.model.ServerLogWindow
import ru.mars.network.repository.InsightsRepository
import ru.mars.network.repository.LogsRepository
import ru.mars.tv.R
import ru.mars.tv.TvFocusLift
import ru.mars.tv.TvOfflineCacheStatus
import ru.mars.tv.tvTextSize

/**
 * TV-экран «Аналитика» (read-only паритет мобильного InsightsActivity): табы
 * «Аналитика» (GET /api/insights, чипы периода 1/7/30/90) и «Логи»
 * (GET /api/logs, чипы файла Агент/Ошибки/Шлюз и хвоста 100/200/500/1000).
 * Оба маршрута — в read-only белом списке нативного device-токена; никаких
 * мутаций на экране нет.
 *
 * Каждая вкладка имеет собственные состояния loading/error/data и свой
 * автообновляемый цикл (60 с для активной вкладки). [R.id.statusView] —
 * accessibility live region (polite). D-pad: «Назад»/«Обновить» в шапке, табы и
 * чипы (left/right), ряды данных фокусируемые ([TvFocusLift]); в ошибке фокус
 * на «Повторить» своей вкладки.
 *
 * Data/policy слои отделены от UI: [RepositoryTvInsightsGateway] и
 * [RepositoryTvLogsGateway] (транспорт), [TvInsightsPolicy]/[TvLogsPolicy]
 * (чистые решения), [TvInsightsController] (оркестрация, независимые generation
 * guards, кэш последнего удачного ответа на вкладку, destroy) — JVM-тесты.
 *
 * Offline: при сетевом сбое вкладка рисует последний удачный снимок/окно со
 * статусом [TvOfflineCacheStatus.CACHED] и плашкой возраста вместо ошибки;
 * явная ошибка — только когда показывать нечего (кэш пуст/просрочен:
 * аналитика 24 ч, логи 12 ч — [ru.mars.tv.TvOfflineSnapshotPolicy]).
 */
class TvInsightsActivity : AppCompatActivity() {

    private enum class Tab { INSIGHTS, LOGS }

    private lateinit var store: AndroidKeyStoreCredentialStore
    private lateinit var statusView: TextView
    private lateinit var backButton: Button
    private lateinit var refreshButton: Button
    private lateinit var tabInsights: Button
    private lateinit var tabLogs: Button

    private lateinit var insightsPane: View
    private lateinit var insightsLoading: View
    private lateinit var insightsError: View
    private lateinit var insightsErrorText: TextView
    private lateinit var insightsRetryButton: Button
    private lateinit var insightsScroll: View
    private lateinit var insightsRowsContainer: LinearLayout
    private lateinit var periodChipsContainer: LinearLayout
    private lateinit var periodChips: List<Button>

    private lateinit var logsPane: View
    private lateinit var logsLoading: View
    private lateinit var logsError: View
    private lateinit var logsErrorText: TextView
    private lateinit var logsRetryButton: Button
    private lateinit var logsScroll: View
    private lateinit var logsMeta: TextView
    private lateinit var logsEmptyText: TextView
    private lateinit var logsLinesContainer: LinearLayout
    private lateinit var fileChips: List<Button>
    private lateinit var tailChips: List<Button>

    private lateinit var controller: TvInsightsController
    private val handler = Handler(Looper.getMainLooper())

    private var currentTab = Tab.INSIGHTS
    private var currentDays = TvInsightsPolicy.DEFAULT_DAYS
    private var currentFile = TvLogsPolicy.DEFAULT_FILE
    private var currentTail = TvLogsPolicy.DEFAULT_TAIL

    private var hasInsights = false
    private var hasLogs = false
    private var insightsBadge: String? = null
    private var logsBadge: String? = null

    private val refreshTick = object : Runnable {
        override fun run() {
            reloadActiveTab()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val surface = object : TvInsightsSurface {
        override fun showInsightsLoading() {
            if (hasInsights) {
                statusView.text = "Обновление…"
                return
            }
            insightsLoading.visibility = View.VISIBLE
            insightsError.visibility = View.GONE
            insightsScroll.visibility = View.GONE
            statusView.text = "Загрузка аналитики…"
        }

        override fun showInsights(snapshot: InsightsSnapshot, status: TvOfflineCacheStatus, badgeText: String?) {
            hasInsights = true
            insightsBadge = badgeText
            insightsLoading.visibility = View.GONE
            insightsError.visibility = View.GONE
            insightsScroll.visibility = View.VISIBLE
            renderInsights(snapshot)
        }

        override fun showInsightsError(message: String) {
            statusView.text = message
            if (hasInsights) return
            insightsLoading.visibility = View.GONE
            insightsError.visibility = View.VISIBLE
            insightsScroll.visibility = View.GONE
            insightsErrorText.text = message
            tabInsights.nextFocusDownId = R.id.insightsRetryButton
            insightsRetryButton.requestFocus()
        }

        override fun showLogsLoading() {
            if (hasLogs) {
                statusView.text = "Обновление…"
                return
            }
            logsLoading.visibility = View.VISIBLE
            logsError.visibility = View.GONE
            logsScroll.visibility = View.GONE
            statusView.text = "Загрузка логов…"
        }

        override fun showLogs(window: ServerLogWindow, status: TvOfflineCacheStatus, badgeText: String?) {
            hasLogs = true
            logsBadge = badgeText
            logsLoading.visibility = View.GONE
            logsError.visibility = View.GONE
            logsScroll.visibility = View.VISIBLE
            renderLogs(window)
        }

        override fun showLogsError(message: String) {
            statusView.text = message
            if (hasLogs) return
            logsLoading.visibility = View.GONE
            logsError.visibility = View.VISIBLE
            logsScroll.visibility = View.GONE
            logsErrorText.text = message
            tabLogs.nextFocusDownId = R.id.logsRetryButton
            logsRetryButton.requestFocus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_insights)
        store = AndroidKeyStoreCredentialStore(this)

        backButton = findViewById(R.id.backButton)
        refreshButton = findViewById(R.id.refreshButton)
        statusView = findViewById(R.id.statusView)
        tabInsights = findViewById(R.id.tabInsights)
        tabLogs = findViewById(R.id.tabLogs)
        insightsPane = findViewById(R.id.insightsPane)
        insightsLoading = findViewById(R.id.insightsLoading)
        insightsError = findViewById(R.id.insightsError)
        insightsErrorText = findViewById(R.id.insightsErrorText)
        insightsRetryButton = findViewById(R.id.insightsRetryButton)
        insightsScroll = findViewById(R.id.insightsScroll)
        insightsRowsContainer = findViewById(R.id.insightsRowsContainer)
        periodChipsContainer = findViewById(R.id.periodChipsContainer)
        logsPane = findViewById(R.id.logsPane)
        logsLoading = findViewById(R.id.logsLoading)
        logsError = findViewById(R.id.logsError)
        logsErrorText = findViewById(R.id.logsErrorText)
        logsRetryButton = findViewById(R.id.logsRetryButton)
        logsScroll = findViewById(R.id.logsScroll)
        logsMeta = findViewById(R.id.logsMeta)
        logsEmptyText = findViewById(R.id.logsEmptyText)
        logsLinesContainer = findViewById(R.id.logsLinesContainer)

        // Чипы периода — из whitelist политики (1/7/30/90), стиль XML-чипов «Задач».
        periodChips = TvInsightsPolicy.PERIOD_DAYS.map { days ->
            chip(TvInsightsPolicy.daysLabel(days)).also { button ->
                button.setOnClickListener { selectPeriod(days) }
                periodChipsContainer.addView(button)
            }
        }
        fileChips = TvLogsPolicy.FILE_OPTIONS.map { file ->
            chip(TvLogsPolicy.fileLabel(file)).also { button ->
                button.setOnClickListener { selectFile(file) }
                findViewById<LinearLayout>(R.id.logFileChipsContainer).addView(button)
            }
        }
        tailChips = TvLogsPolicy.TAIL_OPTIONS.map { tail ->
            chip(TvLogsPolicy.tailLabel(tail)).also { button ->
                button.setOnClickListener { selectTail(tail) }
                findViewById<LinearLayout>(R.id.logTailChipsContainer).addView(button)
            }
        }
        renderChipSelection()
        tabInsights.isSelected = true

        backButton.setOnClickListener { finish() }
        refreshButton.setOnClickListener { reloadActiveTab() }
        insightsRetryButton.setOnClickListener { controller.loadInsights(currentDays) }
        logsRetryButton.setOnClickListener { controller.loadLogs(currentFile, currentTail) }
        tabInsights.setOnClickListener { selectTab(Tab.INSIGHTS) }
        tabLogs.setOnClickListener { selectTab(Tab.LOGS) }

        controller = TvInsightsController(
            surface,
            RepositoryTvInsightsGateway(InsightsRepository(), MarsApi.BASE_URL) { store.read() },
            RepositoryTvLogsGateway(LogsRepository(), MarsApi.BASE_URL) { store.read() },
        )
        TvFocusLift.attachAll(
            backButton, refreshButton, tabInsights, tabLogs,
            insightsRetryButton, logsRetryButton,
            *periodChips.toTypedArray(), *fileChips.toTypedArray(), *tailChips.toTypedArray(),
        )
        backButton.requestFocus()

        controller.loadInsights(currentDays)
        handler.postDelayed(refreshTick, REFRESH_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTick)
        if (::controller.isInitialized) controller.destroy()
        super.onDestroy()
    }

    /** «Обновить» и авто-refresh перезагружают только активную вкладку. */
    private fun reloadActiveTab() {
        when (currentTab) {
            Tab.INSIGHTS -> controller.loadInsights(currentDays)
            Tab.LOGS -> controller.loadLogs(currentFile, currentTail)
        }
    }

    private fun selectTab(tab: Tab) {
        if (currentTab == tab) return
        currentTab = tab
        insightsPane.visibility = if (tab == Tab.INSIGHTS) View.VISIBLE else View.GONE
        logsPane.visibility = if (tab == Tab.LOGS) View.VISIBLE else View.GONE
        tabInsights.isSelected = tab == Tab.INSIGHTS
        tabLogs.isSelected = tab == Tab.LOGS
        // Логи грузятся лениво — при первом открытии вкладки.
        if (tab == Tab.LOGS && !hasLogs) controller.loadLogs(currentFile, currentTail)
    }

    private fun selectPeriod(days: Int) {
        val sanitized = TvInsightsPolicy.sanitizePeriod(days)
        if (sanitized == currentDays) return
        currentDays = sanitized
        renderChipSelection()
        controller.loadInsights(currentDays)
    }

    private fun selectFile(file: ServerLogFile) {
        if (file == currentFile) return
        currentFile = file
        renderChipSelection()
        controller.loadLogs(currentFile, currentTail)
    }

    private fun selectTail(tail: ServerLogTail) {
        if (tail == currentTail) return
        currentTail = tail
        renderChipSelection()
        controller.loadLogs(currentFile, currentTail)
    }

    private fun renderChipSelection() {
        periodChips.forEach { chip ->
            chip.isSelected = chip.text == TvInsightsPolicy.daysLabel(currentDays)
        }
        fileChips.forEachIndexed { index, chip -> chip.isSelected = TvLogsPolicy.FILE_OPTIONS[index] == currentFile }
        tailChips.forEachIndexed { index, chip -> chip.isSelected = TvLogsPolicy.TAIL_OPTIONS[index] == currentTail }
    }

    /** Программный чип — визуальный паритет XML-чипов ряда фильтров «Задач». */
    private fun chip(label: String): Button {
        val button = Button(this)
        button.text = label
        button.tvTextSize(R.dimen.tv_text_body)
        button.setTextColor(ContextCompat.getColor(this, R.color.mars_text))
        button.setBackgroundResource(R.drawable.bg_focus_chip)
        button.minWidth = 0
        button.minimumWidth = 0
        button.minHeight = 0
        button.minimumHeight = 0
        button.isAllCaps = false
        val padH = dpToPx(24)
        val padV = dpToPx(10)
        button.setPadding(padH, padV, padH, padV)
        button.isFocusable = true
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.marginEnd = dpToPx(12)
        button.layoutParams = params
        return button
    }

    private fun renderInsights(snapshot: InsightsSnapshot) {
        val focusedIndex = insightsRowsContainer.indexOfChild(insightsRowsContainer.findFocus())
        insightsRowsContainer.removeAllViews()

        val summary = TvInsightsPolicy.rows(snapshot)
            .filterIsInstance<TvInsightRow.Stat>()
            .firstOrNull { it.label == TvInsightsPolicy.LABEL_SESSIONS }
        val badge = insightsBadge
        val base = summary?.let { "Сессий: ${it.value}" } ?: TvInsightsPolicy.daysLabel(snapshot.periodDays)
        statusView.text = if (badge != null) "$badge · $base" else base

        TvInsightsPolicy.rows(snapshot).forEach { row -> insightsRowsContainer.addView(insightRow(row)) }

        if (focusedIndex >= 0) {
            val target = TvInsightsPolicy.restoreSelection(focusedIndex, insightsRowsContainer.childCount)
            if (target != TvInsightsPolicy.NO_SELECTION) {
                insightsRowsContainer.getChildAt(target)?.requestFocus()
            }
        }
    }

    /** Один ряд [TvInsightRow] в универсальной карточке item_tv_insight_row. */
    private fun insightRow(row: TvInsightRow): View {
        val view = layoutInflater.inflate(R.layout.item_tv_insight_row, insightsRowsContainer, false)
        val sectionTitle = view.findViewById<TextView>(R.id.rowSectionTitle)
        val sectionSubtitle = view.findViewById<TextView>(R.id.rowSectionSubtitle)
        val mainLine = view.findViewById<LinearLayout>(R.id.rowMainLine)
        val label = view.findViewById<TextView>(R.id.rowLabel)
        val value = view.findViewById<TextView>(R.id.rowValue)
        val meta = view.findViewById<TextView>(R.id.rowMeta)
        val barTrack = view.findViewById<LinearLayout>(R.id.rowBarTrack)
        val barFill = view.findViewById<View>(R.id.rowBarFill)
        // Показываем только views своего типа — остальные GONE.
        listOf(sectionTitle, sectionSubtitle, mainLine, meta, barTrack).forEach { it.visibility = View.GONE }
        when (row) {
            is TvInsightRow.Section -> {
                sectionTitle.visibility = View.VISIBLE
                sectionTitle.text = row.title
                if (row.subtitle != null) {
                    sectionSubtitle.visibility = View.VISIBLE
                    sectionSubtitle.text = row.subtitle
                }
            }
            is TvInsightRow.Stat -> {
                mainLine.visibility = View.VISIBLE
                label.text = row.label
                value.text = row.value
            }
            is TvInsightRow.ModelRow -> {
                mainLine.visibility = View.VISIBLE
                label.text = row.title
                value.text = row.cost
                if (row.meta != null) {
                    meta.visibility = View.VISIBLE
                    meta.text = row.meta
                }
            }
            is TvInsightRow.BarRow -> {
                mainLine.visibility = View.VISIBLE
                label.text = row.label
                value.text = row.value
                barTrack.visibility = View.VISIBLE
                barFill.layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, row.percentOfMax.coerceAtLeast(0.02f),
                )
            }
            is TvInsightRow.Note -> {
                meta.visibility = View.VISIBLE
                meta.text = row.text
                meta.setTextColor(ContextCompat.getColor(this, R.color.mars_muted))
            }
        }
        TvFocusLift.attach(view)
        return view
    }

    private fun renderLogs(window: ServerLogWindow) {
        val focusedIndex = logsLinesContainer.indexOfChild(logsLinesContainer.findFocus())
        logsLinesContainer.removeAllViews()

        val badge = logsBadge
        val title = TvLogsPolicy.windowTitle(window)
        statusView.text = if (badge != null) "$badge · $title" else title

        val metaParts = buildList {
            add(title)
            TvLogsPolicy.meta(window).takeIf { it.isNotBlank() }?.let { add(it) }
            if (window.truncated) add(TvLogsPolicy.NOTICE_TRUNCATED)
        }
        logsMeta.text = metaParts.joinToString(" · ")

        if (window.isEmpty) {
            logsEmptyText.text = TvLogsPolicy.emptyMessage(window)
            logsEmptyText.visibility = View.VISIBLE
        } else {
            logsEmptyText.visibility = View.GONE
            window.lines.forEach { line -> logsLinesContainer.addView(logLine(line)) }
        }

        if (focusedIndex >= 0) {
            val target = TvLogsPolicy.restoreSelection(focusedIndex, logsLinesContainer.childCount)
            if (target != TvLogsPolicy.NO_SELECTION) {
                logsLinesContainer.getChildAt(target)?.requestFocus()
            }
        }
    }

    /** Строка лога: моноширинная, фокусируемая (D-pad скролл окна). */
    private fun logLine(line: String): View {
        val view = TextView(this)
        view.text = line.ifBlank { " " }
        view.typeface = Typeface.MONOSPACE
        view.tvTextSize(R.dimen.tv_text_label)
        view.setTextColor(ContextCompat.getColor(this, R.color.mars_text))
        view.setBackgroundResource(R.drawable.bg_focus_chip)
        view.isFocusable = true
        val padH = dpToPx(14)
        val padV = dpToPx(6)
        view.setPadding(padH, padV, padH, padV)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = dpToPx(4)
        view.layoutParams = params
        TvFocusLift.attach(view)
        return view
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        /** Интервал автообновления активной вкладки — аналитика/логи не чат, минута достаточна. */
        private const val REFRESH_MS = 60_000L
    }
}
