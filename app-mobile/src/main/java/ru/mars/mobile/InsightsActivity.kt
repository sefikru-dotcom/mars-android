package ru.mars.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.mars.network.api.MarsApi
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.repository.InsightsRepository
import ru.mars.network.repository.LogsRepository

/** Вкладки экрана мониторинга; значение имени для [InsightsActivity.newIntent]. */
enum class MonitorTab { INSIGHTS, LOGS }

/**
 * Read-only экран мониторинга: вкладки Insights (`GET /api/insights`, фильтр
 * периода) и Logs (`GET /api/logs`, whitelisted файл + tail). Данные — только
 * через [InsightsRepository]/[LogsRepository]; device Bearer — тот же
 * credential-путь, что и у остальных экранов (Keystore-стор + `MarsApi.BASE_URL`).
 * Никаких мутаций и произвольных путей логов: файл/tail — серверные enum'ы.
 *
 * Слои: UI здесь, состояния — [InsightsPresenter]/[LogsPresenter], тексты —
 * [InsightsPolicy]/[LogsPolicy]. Волна 4: экран на общем shell разделов
 * [SectionScaffold] — safe-area, «Назад», заголовок, «Обновить» (перезагружает
 * активную вкладку) и статус-строка (polite live-region для notice). Особая
 * шапка монитора сохранена вариантом [SectionScaffold.HeaderStyle.COMPACT]:
 * заголовок 17sp, точка-индикатор вместо спиннера, кнопки без фона.
 * Центральные фазы вкладок — общие [SectionStateViews] через statusStateFor-
 * маппинг политик; у каждой вкладки свой презентер и свой статус-бокс.
 * Анимации — [MotionPolicy] с уважением reduce-motion; retry-focus — чистая
 * [RetryFocusPolicy].
 */
class InsightsActivity : ComponentActivity() {

    private lateinit var insightsPresenter: InsightsPresenter
    private lateinit var logsPresenter: LogsPresenter
    private val insightsRepository by lazy { InsightsRepository() }
    private val logsRepository by lazy { LogsRepository() }
    private val credentialStore by lazy { AndroidKeyStoreCredentialStore(this) }

    private var activeTab = MonitorTab.INSIGHTS
    private var logsLoadedOnce = false

    private lateinit var scaffold: SectionScaffold.Parts
    private lateinit var tabsContainer: LinearLayout
    private lateinit var contentFrame: FrameLayout

    private lateinit var insightsPanel: LinearLayout
    private lateinit var insightsChips: LinearLayout
    private lateinit var insightsList: LinearLayout
    private lateinit var insightsScroll: ScrollView
    private lateinit var insightsStatusBox: LinearLayout

    private lateinit var logsPanel: LinearLayout
    private lateinit var logsFileChips: LinearLayout
    private lateinit var logsTailChips: LinearLayout
    private lateinit var logsMetaView: TextView
    private lateinit var logsLines: LinearLayout
    private lateinit var logsScroll: ScrollView
    private lateinit var logsStatusBox: LinearLayout

    private var lastInsightsRowCount = 0
    private var lastLogsLineCount = 0

    /** Был ли «Повторить» виден на вкладке: retry-focus срабатывает на появлении, а не каждый рендер. */
    private val retryVisibleByTab = mutableMapOf<MonitorTab, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRootLayout())

        activeTab = intent.getStringExtra(EXTRA_TAB)
            ?.let { runCatching { MonitorTab.valueOf(it) }.getOrNull() }
            ?: MonitorTab.INSIGHTS

        insightsPresenter = InsightsPresenter(
            hasCredential = { credentialStore.read() != null },
            fetch = { days ->
                val credential = credentialStore.read()
                if (credential == null) {
                    ru.mars.network.repository.InsightsResult.Failed("no credential")
                } else {
                    insightsRepository.getInsights(MarsApi.BASE_URL, credential, days = days)
                }
            },
            isOffline = ::isDeviceOffline,
        )
        logsPresenter = LogsPresenter(
            hasCredential = { credentialStore.read() != null },
            fetch = { file, tail ->
                val credential = credentialStore.read()
                if (credential == null) {
                    ru.mars.network.repository.ServerLogsResult.Failed("no credential")
                } else {
                    logsRepository.getLogs(MarsApi.BASE_URL, credential, file, tail)
                }
            },
            isOffline = ::isDeviceOffline,
        )

        insightsPresenter.subscribe { state -> runOnUiThread { renderInsights(state) } }
        logsPresenter.subscribe { state -> runOnUiThread { renderLogs(state) } }

        switchTab(activeTab, animate = false)
        lifecycleScope.launch { insightsPresenter.load() }
        if (activeTab == MonitorTab.LOGS) {
            logsLoadedOnce = true
            lifecycleScope.launch { logsPresenter.load() }
        }
    }

    // --------------------------------------------------------------- layout

    private fun buildRootLayout(): LinearLayout {
        // Волна 4: safe-area, «Назад», заголовок, индикатор и «Обновить» — общий
        // shell разделов в особом компактном варианте шапки монитора
        // (HeaderStyle.COMPACT); «Обновить» перезагружает активную вкладку.
        scaffold = SectionScaffold.create(
            this,
            InsightsPolicy.SCREEN_TITLE,
            onRefresh = ::refreshActiveTab,
            headerStyle = SectionScaffold.HeaderStyle.COMPACT,
        )
        scaffold.content.addView(buildTabs(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        contentFrame = FrameLayout(this)
        insightsPanel = buildInsightsPanel()
        logsPanel = buildLogsPanel()
        contentFrame.addView(insightsPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        contentFrame.addView(logsPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        scaffold.content.addView(contentFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        return scaffold.root
    }

    /** «Обновить» особой шапки монитора: перезагрузка активной вкладки. */
    private fun refreshActiveTab() {
        when (activeTab) {
            MonitorTab.INSIGHTS -> lifecycleScope.launch { insightsPresenter.load() }
            MonitorTab.LOGS -> lifecycleScope.launch { logsPresenter.load() }
        }
    }

    private fun buildTabs(): LinearLayout {
        tabsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // Горизонтальные отступы даёт root shell (16dp); здесь только ритм снизу.
            updatePadding(bottom = MarsTheme.dp(this@InsightsActivity, 8))
        }
        listOf(
            MonitorTab.INSIGHTS to Pair(R.drawable.ic_insights, TAB_INSIGHTS_LABEL),
            MonitorTab.LOGS to Pair(R.drawable.ic_logs, TAB_LOGS_LABEL),
        ).forEach { (tab, spec) ->
            val view = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                tag = tab
                val h = MarsTheme.dp(this@InsightsActivity, 12)
                val v = MarsTheme.dp(this@InsightsActivity, 9)
                setPadding(h, v, h, v)
                isClickable = true
                isFocusable = true
                // Тап-цель вкладки — не меньше 48dp по высоте: полоса из иконки и
                // 13sp-подписи сама по себе ~34dp, и по ней промахивается палец.
                minimumHeight = MarsTheme.dp(this@InsightsActivity, 48)
                addView(ImageView(context).apply {
                    setImageResource(spec.first)
                    imageTintList = android.content.res.ColorStateList.valueOf(MarsTheme.TEXT_MUTED)
                    tag = "icon"
                    // Иконка декоративна: смысл несёт подпись, и дубль в TalkBack
                    // («вкладка, вкладка») только мешает.
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(
                    MarsTheme.dp(this@InsightsActivity, 16),
                    MarsTheme.dp(this@InsightsActivity, 16),
                ))
                addView(TextView(context).apply {
                    text = spec.second
                    textSize = 13f
                    setTextColor(MarsTheme.TEXT_MUTED)
                    tag = "label"
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = MarsTheme.dp(this@InsightsActivity, 8) })
                setOnClickListener { onTabPicked(tab) }
            }
            tabsContainer.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = MarsTheme.dp(this@InsightsActivity, 8)
            })
        }
        return tabsContainer
    }

    private fun buildInsightsPanel(): LinearLayout {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        insightsChips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            updatePadding(bottom = MarsTheme.dp(this@InsightsActivity, 8))
        }
        chipsScroll.addView(insightsChips)
        panel.addView(chipsScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        insightsScroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        insightsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(insightsList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        insightsStatusBox = statusBox()
        content.addView(insightsStatusBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        insightsScroll.addView(content)
        panel.addView(insightsScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        return panel
    }

    private fun buildLogsPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        logsFileChips = chipsRow()
        logsTailChips = chipsRow()
        panel.addView(logsFileChips, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        panel.addView(logsTailChips, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        logsMetaView = TextView(this).apply {
            textSize = 11.5f
            setTextColor(MarsTheme.TEXT_MUTED)
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
            updatePadding(bottom = MarsTheme.dp(this@InsightsActivity, 8))
        }
        panel.addView(logsMetaView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        logsScroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logsLines = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(logsLines, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        logsStatusBox = statusBox()
        content.addView(logsStatusBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        logsScroll.addView(content)
        panel.addView(logsScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        return panel
    }

    private fun chipsRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        updatePadding(bottom = MarsTheme.dp(this@InsightsActivity, 8))
    }

    /**
     * Контейнер статуса вкладки: заполняется общими [SectionStateViews]
     * (loading/empty/error+retry/offline для NO_AUTH) или отдельным чипом
     * «Повторить» при offline-safe stale-данных (см. [renderStatus]).
     */
    private fun statusBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
    }

    // -------------------------------------------------------------- render

    private fun onTabPicked(tab: MonitorTab) {
        if (tab == activeTab) return
        switchTab(tab, animate = true)
        if (tab == MonitorTab.LOGS && !logsLoadedOnce) {
            logsLoadedOnce = true
            lifecycleScope.launch { logsPresenter.load() }
        }
    }

    private fun switchTab(tab: MonitorTab, animate: Boolean) {
        activeTab = tab
        for (index in 0 until tabsContainer.childCount) {
            val chip = tabsContainer.getChildAt(index) as LinearLayout
            val selected = chip.tag == tab
            // Selected-состояние вкладки — сервисам доступности (TalkBack: «выбрано»).
            chip.isSelected = selected
            // Подпись вкладки для TalkBack: имя + состояние выбора. Контейнер с
            // contentDescription читается одной нодой, без обхода иконки и текста.
            val tabLabel = chip.findViewWithTag<TextView>("label")?.text?.toString().orEmpty()
            chip.contentDescription = if (selected) "$tabLabel, выбрано" else tabLabel
            val color = if (selected) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_MUTED
            chip.background = MarsTheme.pill(
                this,
                if (selected) MarsTheme.ACCENT_BG else MarsTheme.SURFACE,
                if (selected) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER,
            )
            chip.findViewWithTag<ImageView>("icon")
                ?.imageTintList = android.content.res.ColorStateList.valueOf(color)
            chip.findViewWithTag<TextView>("label")?.setTextColor(color)
        }
        val showInsights = tab == MonitorTab.INSIGHTS
        val shown = if (showInsights) insightsPanel else logsPanel
        val hidden = if (showInsights) logsPanel else insightsPanel
        hidden.visibility = View.GONE
        shown.visibility = View.VISIBLE
        // Header (progress/notice) общий — перевыставляем из состояния новой активной вкладки.
        if (showInsights) {
            insightsPresenter.state.let {
                scaffold.progress.visibility = if (it.phase == InsightsPhase.LOADING) View.VISIBLE else View.INVISIBLE
                renderNotice(it.notice)
            }
        } else {
            logsPresenter.state.let {
                scaffold.progress.visibility = if (it.phase == LogsPhase.LOADING) View.VISIBLE else View.INVISIBLE
                renderNotice(it.notice)
            }
        }
        // Retry-focus: при переключении на вкладку с уже показанным «Повторить».
        maybeFocusRetry(
            tab,
            canRetry = if (showInsights) insightsPresenter.state.canRetry else logsPresenter.state.canRetry,
            retryWasVisible = retryVisibleByTab[tab] == true,
            tabSwitched = true,
        )
        if (animate && !isReducedMotion()) {
            shown.alpha = 0f
            shown.animate()
                .alpha(1f)
                .setDuration(MotionPolicy.durationFor(MotionCue.MESSAGE_REVEAL, false).toLong())
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } else {
            shown.alpha = 1f
        }
    }

    private fun renderInsights(state: InsightsUiState) {
        val active = activeTab == MonitorTab.INSIGHTS
        if (active) {
            scaffold.progress.visibility = if (state.phase == InsightsPhase.LOADING) View.VISIBLE else View.INVISIBLE
            renderNotice(state.notice)
        }
        buildPeriodChips(state)
        val reveal = lastInsightsRowCount == 0 && state.rows.isNotEmpty()
        lastInsightsRowCount = state.rows.size
        insightsList.removeAllViews()
        state.rows.forEach { row -> insightsList.addView(buildInsightsRow(row)) }
        renderStatus(
            tab = MonitorTab.INSIGHTS,
            screen = InsightsPolicy.statusStateFor(state),
            loadingMessage = state.message ?: InsightsPolicy.MSG_LOADING,
            retryLabel = InsightsPolicy.RETRY_LABEL,
            canRetry = state.canRetry,
            onRetry = { lifecycleScope.launch { insightsPresenter.load() } },
        )
        if (reveal) revealAnimated(insightsList)
    }

    /** Notice активной вкладки (offline/stale) — в статус-строку shell (polite live-region). */
    private fun renderNotice(notice: String?) {
        if (notice != null) {
            scaffold.statusLine.text = notice
            scaffold.statusLine.setTextColor(MarsTheme.STATUS_WARNING)
            scaffold.statusLine.visibility = View.VISIBLE
        } else {
            scaffold.statusLine.visibility = View.GONE
        }
    }

    private fun buildPeriodChips(state: InsightsUiState) {
        val options = InsightsPolicy.PERIOD_DAYS
        if (insightsChips.childCount != options.size) {
            insightsChips.removeAllViews()
            options.forEach { days ->
                insightsChips.addView(newChip(InsightsPolicy.daysLabel(days)) {
                    lifecycleScope.launch { insightsPresenter.selectPeriod(days) }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = MarsTheme.dp(this@InsightsActivity, 8) })
            }
        }
        options.forEachIndexed { index, days ->
            styleChip(insightsChips.getChildAt(index), selected = days == state.periodDays)
        }
    }

    private fun renderLogs(state: LogsUiState) {
        val active = activeTab == MonitorTab.LOGS
        if (active) {
            scaffold.progress.visibility = if (state.phase == LogsPhase.LOADING) View.VISIBLE else View.INVISIBLE
            renderNotice(state.notice)
        }
        buildLogsChips(state)
        logsMetaView.text = state.meta.orEmpty()
        logsMetaView.visibility = if (state.meta != null) View.VISIBLE else View.GONE
        val reveal = lastLogsLineCount == 0 && state.lines.isNotEmpty()
        lastLogsLineCount = state.lines.size
        logsLines.removeAllViews()
        state.lines.forEach { line -> logsLines.addView(buildLogLine(line)) }
        renderStatus(
            tab = MonitorTab.LOGS,
            screen = LogsPolicy.statusStateFor(state),
            loadingMessage = state.message ?: LogsPolicy.MSG_LOADING,
            retryLabel = LogsPolicy.RETRY_LABEL,
            canRetry = state.canRetry,
            onRetry = { lifecycleScope.launch { logsPresenter.load() } },
        )
        if (reveal) revealAnimated(logsLines)
    }

    /**
     * Статус вкладки: общие виды [SectionStateViews] (loading/empty/error+retry/
     * offline для NO_AUTH) или отдельный чип «Повторить» для offline-safe
     * stale-данных (строки/ряды на экране, центрального сообщения нет).
     * После рендера — retry-focus ([maybeFocusRetry]).
     */
    private fun renderStatus(
        tab: MonitorTab,
        screen: SectionScreenState?,
        loadingMessage: String,
        retryLabel: String,
        canRetry: Boolean,
        onRetry: () -> Unit,
    ) {
        val box = if (tab == MonitorTab.INSIGHTS) insightsStatusBox else logsStatusBox
        val retryWasVisible = retryVisibleByTab[tab] == true
        box.removeAllViews()
        box.visibility = if (screen != null || canRetry) View.VISIBLE else View.GONE
        when (screen) {
            null -> if (canRetry) {
                box.addView(
                    retryChip(retryLabel, onRetry),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = MarsTheme.dp(this@InsightsActivity, 14) },
                )
            }
            SectionScreenState.Loading -> box.addView(SectionStateViews.loading(this, loadingMessage))
            is SectionScreenState.Empty -> box.addView(SectionStateViews.empty(this, screen.message))
            is SectionScreenState.Error -> box.addView(SectionStateViews.error(this, screen.message, onRetry))
            is SectionScreenState.Offline -> box.addView(SectionStateViews.offline(this, screen.message))
        }
        retryVisibleByTab[tab] = canRetry
        maybeFocusRetry(tab, canRetry, retryWasVisible, tabSwitched = false)
    }

    /** «Повторить» отдельным чипом: offline-safe stale-данные без центрального сообщения. */
    private fun retryChip(label: String, onRetry: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(MarsTheme.TEXT)
        gravity = Gravity.CENTER
        background = MarsTheme.button(this@InsightsActivity, MarsTheme.SURFACE, strokeColor = MarsTheme.BORDER)
        val h = MarsTheme.dp(this@InsightsActivity, 16)
        val v = MarsTheme.dp(this@InsightsActivity, 9)
        setPadding(h, v, h, v)
        // Тап-цель «Повторить»: 48dp по высоте и ширине (13sp + 9dp давали ~33dp).
        minimumWidth = MarsTheme.dp(this@InsightsActivity, 48)
        minimumHeight = MarsTheme.dp(this@InsightsActivity, 48)
        isClickable = true
        isFocusable = true
        tag = SectionStateViews.RETRY_TAG
        contentDescription = "Повторить загрузку"
        setOnClickListener { onRetry() }
    }

    /**
     * Retry-focus: при появлении «Повторить» на активной вкладке и активном
     * TalkBack (touch exploration) переносим фокус доступности на контрол,
     * чтобы повтор был доступен сразу после озвученной ошибки. Решение —
     * чистая [RetryFocusPolicy]; контрол ищем по тегу [SectionStateViews.RETRY_TAG].
     */
    private fun maybeFocusRetry(tab: MonitorTab, canRetry: Boolean, retryWasVisible: Boolean, tabSwitched: Boolean) {
        if (tab != activeTab) return
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (!RetryFocusPolicy.shouldMoveFocus(
                accessibilityEnabled = manager?.isEnabled == true,
                touchExplorationEnabled = manager?.isTouchExplorationEnabled == true,
                canRetry = canRetry,
                retryWasVisible = retryWasVisible,
                tabSwitched = tabSwitched,
            )
        ) return
        val box = if (tab == MonitorTab.INSIGHTS) insightsStatusBox else logsStatusBox
        val retry = box.findViewWithTag<View>(SectionStateViews.RETRY_TAG) ?: return
        retry.requestFocus()
        retry.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    private fun buildLogsChips(state: LogsUiState) {
        val files = LogsPolicy.FILE_OPTIONS
        if (logsFileChips.childCount != files.size) {
            logsFileChips.removeAllViews()
            files.forEach { file ->
                logsFileChips.addView(newChip(LogsPolicy.fileLabel(file)) {
                    lifecycleScope.launch { logsPresenter.selectFile(file) }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = MarsTheme.dp(this@InsightsActivity, 8) })
            }
        }
        files.forEachIndexed { index, file ->
            styleChip(logsFileChips.getChildAt(index), selected = file == state.file)
        }
        val tails = LogsPolicy.TAIL_OPTIONS
        if (logsTailChips.childCount != tails.size) {
            logsTailChips.removeAllViews()
            tails.forEach { tail ->
                logsTailChips.addView(newChip(LogsPolicy.tailLabel(tail)) {
                    lifecycleScope.launch { logsPresenter.selectTail(tail) }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = MarsTheme.dp(this@InsightsActivity, 8) })
            }
        }
        tails.forEachIndexed { index, tail ->
            styleChip(logsTailChips.getChildAt(index), selected = tail == state.tail)
        }
    }

    private fun newChip(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12.5f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        val h = MarsTheme.dp(this@InsightsActivity, 12)
        val v = MarsTheme.dp(this@InsightsActivity, 7)
        setPadding(h, v, h, v)
        // Тап-цель чипа: 48dp по обеим сторонам (12.5sp + 7dp давали ~30dp).
        minimumWidth = MarsTheme.dp(this@InsightsActivity, 48)
        minimumHeight = MarsTheme.dp(this@InsightsActivity, 48)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun styleChip(chip: View?, selected: Boolean) {
        chip ?: return
        val textView = chip as? TextView ?: return
        // Selected-состояние чипа — сервисам доступности (TalkBack: «выбрано»).
        textView.isSelected = selected
        textView.setTextColor(if (selected) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_MUTED)
        textView.background = MarsTheme.pill(
            this,
            if (selected) MarsTheme.ACCENT_BG else MarsTheme.SURFACE,
            if (selected) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER,
        )
    }

    private fun buildLogLine(line: String): TextView {
        val pad = MarsTheme.dp(this, 2)
        return TextView(this).apply {
            text = line
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(MarsTheme.TEXT_EM)
            setTextIsSelectable(true)
            val h = MarsTheme.dp(this@InsightsActivity, 10)
            val v = MarsTheme.dp(this@InsightsActivity, 5)
            background = MarsTheme.card(this@InsightsActivity, MarsTheme.BOT_CARD, radiusDp = 8, strokeColor = MarsTheme.CODE_BORDER)
            setPadding(h, v + pad, h, v + pad)
        }
    }

    private fun buildInsightsRow(row: InsightsRow): View = when (row) {
        is InsightsRow.Section -> TextView(this).apply {
            text = row.title
            textSize = 13f
            setTextColor(MarsTheme.TEXT_MUTED)
            setTypeface(typeface, Typeface.BOLD)
            val pad = MarsTheme.dp(this@InsightsActivity, 16)
            // Горизонтальные отступы даёт root shell (16dp).
            updatePadding(top = pad, bottom = pad / 2)
        }
        is InsightsRow.Note -> TextView(this).apply {
            text = row.text
            textSize = 11.5f
            setTextColor(MarsTheme.TEXT_MUTED)
            val pad = MarsTheme.dp(this@InsightsActivity, 16)
            updatePadding(bottom = pad / 2)
        }
        is InsightsRow.Stat -> card(horizontal = true) {
            addView(label(row.label), weightCell())
            addView(TextView(context).apply {
                text = row.value
                textSize = 14f
                setTextColor(statColor(row.tone))
                setTypeface(typeface, Typeface.BOLD)
            })
        }
        is InsightsRow.ModelRow -> card {
            addView(TextView(context).apply {
                text = row.title
                textSize = 13.5f
                setTextColor(MarsTheme.TEXT)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = row.meta
                    textSize = 11.5f
                    setTextColor(MarsTheme.TEXT_MUTED)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(context).apply {
                    text = row.costLabel
                    textSize = 12.5f
                    setTextColor(MarsTheme.ACCENT_TEXT)
                    setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = MarsTheme.dp(context, 10) })
            })
        }
        is InsightsRow.BarRow -> card(horizontal = true) {
            addView(TextView(context).apply {
                text = row.label
                textSize = 12.5f
                setTextColor(MarsTheme.TEXT_EM)
                typeface = Typeface.MONOSPACE
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = MarsTheme.dp(context, 10) })
            val maxBar = MarsTheme.dp(context, 120)
            val bar = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(MarsTheme.ACCENT)
                    cornerRadius = MarsTheme.dp(context, 999).toFloat()
                }
            }
            val width = (maxBar * row.barWeight).toInt().coerceAtLeast(MarsTheme.dp(context, 2))
            addView(bar, LinearLayout.LayoutParams(width, MarsTheme.dp(context, 6)).apply {
                gravity = Gravity.CENTER_VERTICAL
            })
            addView(TextView(context).apply {
                text = row.value
                textSize = 12.5f
                setTextColor(MarsTheme.TEXT_MUTED)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = MarsTheme.dp(context, 10)
                gravity = Gravity.CENTER_VERTICAL
            })
        }
    }

    private fun statColor(tone: StatTone): Int = when (tone) {
        StatTone.NEUTRAL -> MarsTheme.TEXT
        StatTone.GOOD -> MarsTheme.STATUS_ONLINE
        StatTone.WARN -> MarsTheme.STATUS_WARNING
        StatTone.BAD -> MarsTheme.STATUS_ERROR
    }

    private fun card(horizontal: Boolean = false, build: LinearLayout.() -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (horizontal) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
            background = MarsTheme.card(this@InsightsActivity, MarsTheme.SURFACE_CARD)
            val pad = MarsTheme.dp(this@InsightsActivity, 12)
            setPadding(pad, pad, pad, pad)
            build()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                // Горизонтальные отступы даёт root shell (16dp).
                bottomMargin = MarsTheme.dp(this@InsightsActivity, 8)
            }
        }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(MarsTheme.TEXT_MUTED)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun body(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(MarsTheme.TEXT_EM)
        typeface = Typeface.MONOSPACE
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun weightCell(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    /** Появление списка после пустого состояния — коротко и без reduce-motion. */
    private fun revealAnimated(view: View) {
        if (isReducedMotion()) return
        view.alpha = 0f
        view.animate()
            .alpha(1f)
            .setDuration(MotionPolicy.durationFor(MotionCue.MESSAGE_REVEAL, false).toLong())
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun isReducedMotion(): Boolean = MotionPolicy.isReducedMotion(
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    )

    // ------------------------------------------------------------- system

    private fun isDeviceOffline(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return true
        val caps = manager.getNetworkCapabilities(network) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        const val EXTRA_TAB = "ru.mars.mobile.MONITOR_TAB"
        const val TAB_INSIGHTS_LABEL = "Инсайты"
        const val TAB_LOGS_LABEL = "Логи"

        /**
         * Публичная точка входа для интегратора (меню/команды):
         * `startActivity(InsightsActivity.newIntent(this))`, опционально
         * `newIntent(this, MonitorTab.LOGS)` — открыть сразу вкладку логов.
         */
        @JvmStatic
        @JvmOverloads
        fun newIntent(context: Context, tab: MonitorTab = MonitorTab.INSIGHTS): Intent =
            Intent(context, InsightsActivity::class.java).putExtra(EXTRA_TAB, tab.name)
    }
}
