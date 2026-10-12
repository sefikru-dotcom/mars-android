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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
 * [InsightsPolicy]/[LogsPolicy]. Safe-area через WindowInsets; анимации через
 * [MotionPolicy] с уважением reduce-motion.
 */
class InsightsActivity : ComponentActivity() {

    private lateinit var insightsPresenter: InsightsPresenter
    private lateinit var logsPresenter: LogsPresenter
    private val insightsRepository by lazy { InsightsRepository() }
    private val logsRepository by lazy { LogsRepository() }
    private val credentialStore by lazy { AndroidKeyStoreCredentialStore(this) }

    private var activeTab = MonitorTab.INSIGHTS
    private var logsLoadedOnce = false

    private lateinit var progressView: View
    private lateinit var noticeView: TextView
    private lateinit var tabsContainer: LinearLayout
    private lateinit var contentFrame: FrameLayout

    private lateinit var insightsPanel: LinearLayout
    private lateinit var insightsChips: LinearLayout
    private lateinit var insightsList: LinearLayout
    private lateinit var insightsScroll: ScrollView
    private lateinit var insightsStatusBox: LinearLayout
    private lateinit var insightsStatusMessage: TextView
    private lateinit var insightsStatusIcon: ImageView
    private lateinit var insightsRetryButton: TextView

    private lateinit var logsPanel: LinearLayout
    private lateinit var logsFileChips: LinearLayout
    private lateinit var logsTailChips: LinearLayout
    private lateinit var logsMetaView: TextView
    private lateinit var logsLines: LinearLayout
    private lateinit var logsScroll: ScrollView
    private lateinit var logsStatusBox: LinearLayout
    private lateinit var logsStatusMessage: TextView
    private lateinit var logsStatusIcon: ImageView
    private lateinit var logsRetryButton: TextView

    private var lastInsightsRowCount = 0
    private var lastLogsLineCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = buildRootLayout()
        setContentView(root)
        applyWindowInsets(root)

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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MarsTheme.BG)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(buildHeader())
        noticeView = TextView(this).apply {
            textSize = 12f
            setTextColor(MarsTheme.TEXT_MUTED)
            background = MarsTheme.card(this@InsightsActivity, MarsTheme.SURFACE_SUBTLE)
            val pad = MarsTheme.dp(this@InsightsActivity, 10)
            setPadding(pad, pad / 2, pad, pad / 2)
            visibility = View.GONE
        }
        root.addView(noticeView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = MarsTheme.dp(this@InsightsActivity, 16)
            marginEnd = MarsTheme.dp(this@InsightsActivity, 16)
            topMargin = MarsTheme.dp(this@InsightsActivity, 8)
        })
        root.addView(buildTabs())
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
        root.addView(contentFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        return root
    }

    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = MarsTheme.dp(this@InsightsActivity, 16)
            setPadding(pad, pad, pad, pad / 2)
        }
        val backButton = MarsTheme.circleIconButton(
            this, R.drawable.ic_arrow_back, "Назад", bg = 0, strokeColor = 0,
        ).apply { setOnClickListener { finish() } }
        header.addView(backButton)
        header.addView(TextView(this).apply {
            text = InsightsPolicy.SCREEN_TITLE
            textSize = 17f
            setTextColor(MarsTheme.TEXT_STRONG)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = MarsTheme.dp(this@InsightsActivity, 16)
        })
        progressView = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(MarsTheme.ACCENT)
            }
            visibility = View.INVISIBLE
        }
        val dot = MarsTheme.dp(this, 8)
        header.addView(progressView, LinearLayout.LayoutParams(dot, dot).apply {
            marginEnd = MarsTheme.dp(this@InsightsActivity, 12)
        })
        val refreshButton = MarsTheme.circleIconButton(
            this, R.drawable.ic_refresh, "Обновить", bg = 0, strokeColor = 0,
        ).apply {
            setOnClickListener {
                when (activeTab) {
                    MonitorTab.INSIGHTS -> lifecycleScope.launch { insightsPresenter.load() }
                    MonitorTab.LOGS -> lifecycleScope.launch { logsPresenter.load() }
                }
            }
        }
        header.addView(refreshButton)
        return header
    }

    private fun buildTabs(): LinearLayout {
        tabsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = MarsTheme.dp(this@InsightsActivity, 16)
            updatePadding(left = pad, right = pad, bottom = pad / 2)
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
                addView(ImageView(context).apply {
                    setImageResource(spec.first)
                    imageTintList = android.content.res.ColorStateList.valueOf(MarsTheme.TEXT_MUTED)
                    tag = "icon"
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
            val pad = MarsTheme.dp(this@InsightsActivity, 16)
            updatePadding(left = pad, right = pad, bottom = pad / 2)
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
        insightsStatusBox = buildStatusBox(
            iconRes = R.drawable.ic_insights,
            messageOut = { insightsStatusMessage = it },
            iconOut = { insightsStatusIcon = it },
            retryOut = { insightsRetryButton = it },
            onRetry = { lifecycleScope.launch { insightsPresenter.load() } },
        )
        content.addView(insightsStatusBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            val m = MarsTheme.dp(this@InsightsActivity, 16)
            setMargins(m, m, m, m)
        })
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
            val pad = MarsTheme.dp(this@InsightsActivity, 16)
            updatePadding(left = pad, right = pad, bottom = pad / 2)
        }
        panel.addView(logsMetaView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        logsScroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logsLines = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = MarsTheme.dp(this@InsightsActivity, 16)
            updatePadding(left = pad, right = pad)
        }
        content.addView(logsLines, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        logsStatusBox = buildStatusBox(
            iconRes = R.drawable.ic_logs,
            messageOut = { logsStatusMessage = it },
            iconOut = { logsStatusIcon = it },
            retryOut = { logsRetryButton = it },
            onRetry = { lifecycleScope.launch { logsPresenter.load() } },
        )
        content.addView(logsStatusBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            val m = MarsTheme.dp(this@InsightsActivity, 16)
            setMargins(m, m, m, m)
        })
        logsScroll.addView(content)
        panel.addView(logsScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        return panel
    }

    private fun chipsRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val pad = MarsTheme.dp(this@InsightsActivity, 16)
        updatePadding(left = pad, right = pad, bottom = pad / 2)
    }

    private fun buildStatusBox(
        iconRes: Int,
        messageOut: (TextView) -> Unit,
        iconOut: (ImageView) -> Unit,
        retryOut: (TextView) -> Unit,
        onRetry: () -> Unit,
    ): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(MarsTheme.BORDER_MUTED)
            alpha = 0.6f
        }
        val iconSize = MarsTheme.dp(this, 40)
        box.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize).apply {
            bottomMargin = MarsTheme.dp(this@InsightsActivity, 12)
        })
        iconOut(icon)
        val message = TextView(this).apply {
            textSize = 14f
            setTextColor(MarsTheme.TEXT_MUTED)
            gravity = Gravity.CENTER
        }
        box.addView(message, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = MarsTheme.dp(this@InsightsActivity, 32)
            marginEnd = MarsTheme.dp(this@InsightsActivity, 32)
        })
        messageOut(message)
        val retry = TextView(this).apply {
            text = InsightsPolicy.RETRY_LABEL
            textSize = 13f
            setTextColor(MarsTheme.TEXT)
            gravity = Gravity.CENTER
            background = MarsTheme.button(this@InsightsActivity, MarsTheme.SURFACE, strokeColor = MarsTheme.BORDER)
            val h = MarsTheme.dp(this@InsightsActivity, 16)
            val v = MarsTheme.dp(this@InsightsActivity, 9)
            setPadding(h, v, h, v)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener { onRetry() }
        }
        box.addView(retry, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = MarsTheme.dp(this@InsightsActivity, 14) })
        retryOut(retry)
        return box
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
                progressView.visibility = if (it.phase == InsightsPhase.LOADING) View.VISIBLE else View.INVISIBLE
                renderNotice(it.notice)
            }
        } else {
            logsPresenter.state.let {
                progressView.visibility = if (it.phase == LogsPhase.LOADING) View.VISIBLE else View.INVISIBLE
                renderNotice(it.notice)
            }
        }
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
            progressView.visibility = if (state.phase == InsightsPhase.LOADING) View.VISIBLE else View.INVISIBLE
            renderNotice(state.notice)
        }
        buildPeriodChips(state)
        val reveal = lastInsightsRowCount == 0 && state.rows.isNotEmpty()
        lastInsightsRowCount = state.rows.size
        insightsList.removeAllViews()
        state.rows.forEach { row -> insightsList.addView(buildInsightsRow(row)) }
        val showStatus = state.message != null || state.canRetry
        insightsStatusBox.visibility = if (showStatus) View.VISIBLE else View.GONE
        insightsStatusMessage.text = state.message.orEmpty()
        insightsStatusMessage.visibility = if (state.message != null) View.VISIBLE else View.GONE
        insightsRetryButton.visibility = if (state.canRetry) View.VISIBLE else View.GONE
        if (reveal) revealAnimated(insightsList)
    }

    private fun renderNotice(notice: String?) {
        // Notice общий для обеих вкладок: показываем только активной.
        noticeView.text = notice.orEmpty()
        noticeView.visibility = if (notice != null) View.VISIBLE else View.GONE
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
            progressView.visibility = if (state.phase == LogsPhase.LOADING) View.VISIBLE else View.INVISIBLE
            renderNotice(state.notice)
        }
        buildLogsChips(state)
        logsMetaView.text = state.meta.orEmpty()
        logsMetaView.visibility = if (state.meta != null) View.VISIBLE else View.GONE
        val reveal = lastLogsLineCount == 0 && state.lines.isNotEmpty()
        lastLogsLineCount = state.lines.size
        logsLines.removeAllViews()
        state.lines.forEach { line -> logsLines.addView(buildLogLine(line)) }
        val showStatus = state.message != null || state.canRetry
        logsStatusBox.visibility = if (showStatus) View.VISIBLE else View.GONE
        logsStatusMessage.text = state.message.orEmpty()
        logsStatusMessage.visibility = if (state.message != null) View.VISIBLE else View.GONE
        logsRetryButton.visibility = if (state.canRetry) View.VISIBLE else View.GONE
        if (reveal) revealAnimated(logsLines)
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
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun styleChip(chip: View?, selected: Boolean) {
        chip ?: return
        val textView = chip as? TextView ?: return
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
            updatePadding(left = pad, right = pad, top = pad, bottom = pad / 2)
        }
        is InsightsRow.Note -> TextView(this).apply {
            text = row.text
            textSize = 11.5f
            setTextColor(MarsTheme.TEXT_MUTED)
            val pad = MarsTheme.dp(this@InsightsActivity, 16)
            updatePadding(left = pad, right = pad, bottom = pad / 2)
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
                marginStart = MarsTheme.dp(this@InsightsActivity, 16)
                marginEnd = MarsTheme.dp(this@InsightsActivity, 16)
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

    private fun applyWindowInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            // androidx WindowInsetsCompat: getInsets(Type) работает на всех API,
            // android.graphics.Insets не трогаем (конструктор package-private).
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            insets
        }
    }

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
