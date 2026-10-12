package ru.mars.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.mars.network.api.MarsApi
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.repository.KanbanBoardResult
import ru.mars.network.repository.KanbanRepository
import ru.mars.network.repository.KanbanStatsResult
import ru.mars.network.repository.KanbanTaskDetailResult

/**
 * Read-only экран «Канбан»: доска `GET /api/kanban/board` (колонки, задачи,
 * статусы) + статистика `GET /api/kanban/stats` + детальная карточка задачи
 * `GET /api/kanban/task/:id` в модальном диалоге.
 *
 * Слои: UI (этот класс) -> [KanbanPresenter] (состояния, polling-конверт
 * `changed:false`, offline-safe) -> [KanbanPolicy] (чистые строки/тексты/тоны,
 * JVM-тесты) -> [KanbanRepository] (сеть, core-network, без правок).
 *
 * Контракт read-only: экран — витрина. Никаких create/edit/move/delete/
 * dispatch ни в UI, ни в презентере; серверный Bearer allowlist допускает
 * только GET/HEAD /api/kanban/. Диалог задачи — только чтение.
 *
 * Переход из меню: намеренно НЕ правит ChatActivity/SidebarNavPolicy (их
 * трогает другая волна). Публичная точка входа для интегратора —
 * [KanbanActivity.newIntent]; документ — docs/kanban-screen.md.
 * Кнопка «Назад»/системный back — finish() и возврат в вызвавший экран.
 */
class KanbanActivity : ComponentActivity() {

    private lateinit var store: AndroidKeyStoreCredentialStore
    private lateinit var presenter: KanbanPresenter

    private lateinit var progress: ProgressBar
    private lateinit var noticeView: TextView
    private lateinit var summaryView: TextView
    private lateinit var statsRow: LinearLayout
    private lateinit var boardRow: LinearLayout
    private lateinit var statusBox: LinearLayout
    private lateinit var readOnlyBadge: TextView

    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AndroidKeyStoreCredentialStore(this)
        val repository = KanbanRepository()
        presenter = KanbanPresenter(
            hasCredential = { store.read() != null },
            fetchBoard = { since ->
                val credential = store.read()
                if (credential == null) null
                else when (val result = repository.getBoard(MarsApi.BASE_URL, credential, since = since)) {
                    is KanbanBoardResult.Board -> result.board
                    else -> null
                }
            },
            fetchStats = {
                val credential = store.read()
                if (credential == null) null
                else when (val result = repository.getStats(MarsApi.BASE_URL, credential)) {
                    is KanbanStatsResult.Stats -> result.stats
                    else -> null
                }
            },
            fetchTaskDetail = { taskId ->
                val credential = store.read()
                if (credential == null) null
                else when (val result = repository.getTaskDetail(MarsApi.BASE_URL, credential, taskId)) {
                    is KanbanTaskDetailResult.Detail -> result.detail
                    else -> null
                }
            },
            isOffline = ::isDeviceOffline,
            nowEpochSeconds = { System.currentTimeMillis() / 1000L },
        )
        setContentView(buildView())
        presenter.subscribe { render(it) }
        lifecycleScope.launch { presenter.load() }
    }

    /** Лёгкий polling доски: пока экран видим, `since=<курсор>` раз в интервал. */
    override fun onResume() {
        super.onResume()
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                presenter.refresh()
            }
        }
    }

    override fun onPause() {
        pollJob?.cancel()
        pollJob = null
        super.onPause()
    }

    private fun buildView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MarsTheme.BG)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16), systemBars.top + dp(10), dp(16), systemBars.bottom + dp(10))
            insets
        }

        // Header: back / title / progress / refresh (каркас как у SpacesActivity).
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        header.addView(
            MarsTheme.circleIconButton(this, R.drawable.ic_arrow_back, "Назад", bg = MarsTheme.SURFACE, iconTint = MarsTheme.TEXT)
                .apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
        )
        header.addView(TextView(this).apply {
            text = KanbanPolicy.SCREEN_TITLE
            textSize = 22f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            maxLines = 1
        }, LinearLayout.LayoutParams(0, -2, 1f))
        progress = ProgressBar(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        header.addView(progress, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(8) })
        header.addView(
            MarsTheme.circleIconButton(this, R.drawable.ic_refresh, "Обновить", bg = MarsTheme.SURFACE, iconTint = MarsTheme.TEXT)
                .apply { setOnClickListener { lifecycleScope.launch { presenter.load() } } },
            LinearLayout.LayoutParams(dp(40), dp(40))
        )
        root.addView(header)

        // Бейдж «доска только для чтения» (server read_only=true или всегда —
        // мобильный клиент не выполняет мутации kanban по контракту волны).
        readOnlyBadge = TextView(this).apply {
            visibility = View.GONE
            text = KanbanPolicy.NOTICE_READ_ONLY
            textSize = 11f
            setTextColor(MarsTheme.TEXT_MUTED)
            background = MarsTheme.pill(this@KanbanActivity, MarsTheme.SURFACE_SUBTLE, MarsTheme.BORDER_SUBTLE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        root.addView(readOnlyBadge, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })

        // Offline/notice-плашка (видна только при state.notice).
        noticeView = TextView(this).apply {
            visibility = View.GONE
            textSize = 13f
            setTextColor(MarsTheme.STATUS_WARNING)
            background = MarsTheme.pill(this@KanbanActivity, MarsTheme.SURFACE_SUBTLE, MarsTheme.BORDER_MUTED)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            maxLines = 2
        }
        root.addView(noticeView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // Статистика: summary + горизонтальная лента чипов by_status/by_assignee.
        summaryView = TextView(this).apply {
            visibility = View.GONE
            textSize = 13f
            setTextColor(MarsTheme.TEXT_EM)
            maxLines = 2
        }
        root.addView(summaryView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        statsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(statsRow, ViewGroup.LayoutParams(-2, -2))
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        )

        // Доска: горизонтальный скролл колонок, каждая колонка — свой
        // вертикальный скролл карточек (мобильный канбан-паттерн).
        boardRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(boardRow, ViewGroup.LayoutParams(-2, -1))
            },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        // Статус-блок: центральное сообщение фазы + «Повторить».
        statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(24), 0, 0)
        }
        root.addView(statusBox, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    private fun render(state: KanbanUiState) {
        progress.visibility = if (state.phase == KanbanPhase.LOADING) View.VISIBLE else View.GONE
        readOnlyBadge.visibility = if (state.serverReadOnly) View.VISIBLE else View.GONE

        if (state.notice != null) {
            noticeView.visibility = View.VISIBLE
            noticeView.text = state.notice
        } else {
            noticeView.visibility = View.GONE
        }

        renderStats(state.stats)

        val revealed = state.columns.isNotEmpty() && boardRow.childCount == 0
        boardRow.removeAllViews()
        state.columns.forEach { column ->
            boardRow.addView(columnView(column, reveal = revealed), columnMargin())
        }

        statusBox.removeAllViews()
        state.message?.let { message ->
            statusBox.addView(TextView(this).apply {
                text = message
                textSize = 14f
                setTextColor(
                    if (state.phase == KanbanPhase.ERROR || state.phase == KanbanPhase.NO_AUTH) {
                        MarsTheme.STATUS_WARNING
                    } else {
                        MarsTheme.TEXT_MUTED
                    }
                )
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(16), 0, dp(16), 0)
            })
        }
        if (state.canRetry) {
            statusBox.addView(TextView(this).apply {
                text = KanbanPolicy.RETRY_LABEL
                textSize = 14f
                setTextColor(MarsTheme.ACCENT_TEXT)
                gravity = Gravity.CENTER
                background = MarsTheme.button(this@KanbanActivity, MarsTheme.ACCENT_BG, radiusDp = 999, strokeColor = MarsTheme.ACCENT_BORDER)
                setPadding(dp(22), dp(10), dp(22), dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener { lifecycleScope.launch { presenter.load() } }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        }
    }

    private fun renderStats(stats: KanbanStatsUi?) {
        statsRow.removeAllViews()
        if (stats == null || stats.chips.isEmpty()) {
            summaryView.visibility = View.GONE
            return
        }
        summaryView.visibility = View.VISIBLE
        summaryView.text = stats.summary ?: ""
        stats.chips.forEach { chip ->
            statsRow.addView(chipView(chip), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
    }

    /** Чип статистики: цветной dot + «N · label» в тон статуса. */
    private fun chipView(chip: KanbanStatChipUi): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = MarsTheme.pill(this@KanbanActivity, MarsTheme.SURFACE_SUBTLE, MarsTheme.BORDER)
            setPadding(dp(10), dp(6), dp(12), dp(6))
            addView(MarsTheme.statusDot(this@KanbanActivity, toneColor(chip.tone)),
                LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
            addView(TextView(this@KanbanActivity).apply {
                text = "${chip.value} · ${chip.label}"
                textSize = 12f
                setTextColor(MarsTheme.TEXT_EM)
                maxLines = 1
            })
        }

    /** Колонка доски: заголовок (имя + счётчик) и вертикальный список карточек. */
    private fun columnView(column: KanbanColumnUi, reveal: Boolean): View {
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = MarsTheme.card(this@KanbanActivity, MarsTheme.SURFACE, radiusDp = 14, strokeColor = MarsTheme.BORDER)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        head.addView(TextView(this).apply {
            text = column.name
            textSize = 14f
            setTextColor(MarsTheme.TEXT_STRONG)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(this).apply {
            text = column.count.toString()
            textSize = 11f
            setTextColor(MarsTheme.TEXT_MUTED)
            background = MarsTheme.pill(this@KanbanActivity, MarsTheme.SURFACE_SUBTLE, MarsTheme.BORDER_MUTED)
            setPadding(dp(8), dp(2), dp(8), dp(2))
        })
        shell.addView(head, LinearLayout.LayoutParams(-1, -2))

        val cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (column.cards.isEmpty()) {
            cards.addView(TextView(this).apply {
                text = KanbanPolicy.COLUMN_EMPTY_HINT
                textSize = 12f
                setTextColor(MarsTheme.TEXT_MUTED)
                setPadding(dp(6), dp(4), dp(6), dp(4))
            })
        } else {
            column.cards.forEach { card ->
                cards.addView(taskCardView(card), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }
        shell.addView(
            ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                addView(cards, ViewGroup.LayoutParams(-1, -2))
            },
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
        if (reveal) applyRevealMotion(shell)
        return shell
    }

    /** Карточка задачи: тап — read-only диалог [showTaskDetail]. */
    private fun taskCardView(card: KanbanTaskCardUi): View {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = MarsTheme.card(
                this@KanbanActivity,
                if (card.isBlocked) BLOCKED_CARD_BG else MarsTheme.SURFACE_CARD,
                radiusDp = 12,
                strokeColor = if (card.isBlocked) MarsTheme.ACCENT_BORDER else MarsTheme.BORDER_SUBTLE,
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { showTaskDetail(card) }
        }
        view.addView(TextView(this).apply {
            text = card.title
            textSize = 14f
            setTextColor(MarsTheme.TEXT)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        })

        // Строка статуса: dot + статус + возраст.
        val statusLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        statusLine.addView(MarsTheme.statusDot(this, toneColor(card.tone)),
            LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
        statusLine.addView(TextView(this).apply {
            text = card.status.ifBlank { KanbanPolicy.STATUS_UNKNOWN_LABEL }
            textSize = 11f
            setTextColor(MarsTheme.TEXT_MUTED)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.ageLabel?.let { age ->
            statusLine.addView(TextView(this).apply {
                text = age
                textSize = 11f
                setTextColor(MarsTheme.TEXT_MUTED)
                maxLines = 1
            })
        }
        view.addView(statusLine, LinearLayout.LayoutParams(-1, -2))

        // Футер: исполнитель + бейджи (приоритет/цель/блокировка/комментарии/дети).
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        footer.addView(TextView(this).apply {
            text = card.assigneeLabel
            textSize = 11f
            setTextColor(if (card.assigneeLabel == KanbanPolicy.UNASSIGNED_LABEL) MarsTheme.TEXT_MUTED else MarsTheme.TEXT_EM)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.priorityLabel?.let { footer.addView(cardBadge(it, MarsTheme.STATUS_INFO)) }
        if (card.isGoalMode) footer.addView(cardBadge(KanbanPolicy.GOAL_BADGE, MarsTheme.ACCENT_TEXT))
        if (card.isBlocked) footer.addView(cardBadge(KanbanPolicy.BLOCKED_BADGE, MarsTheme.STATUS_ERROR))
        if (card.commentCount > 0) {
            footer.addView(cardBadge(KanbanPolicy.commentsLabel(card.commentCount), MarsTheme.TEXT_MUTED))
        }
        if (card.childCount > 0) {
            footer.addView(cardBadge(KanbanPolicy.childrenLabel(card.childCount), MarsTheme.TEXT_MUTED))
        }
        view.addView(footer, LinearLayout.LayoutParams(-1, -2))
        return view
    }

    /** Мелкий бейдж футера карточки (приоритет/goal/блокировка/счётчики). */
    private fun cardBadge(label: String, color: Int): TextView = TextView(this).apply {
        text = label
        textSize = 10f
        setTextColor(color)
        background = MarsTheme.pill(this@KanbanActivity, MarsTheme.SURFACE, MarsTheme.BORDER_MUTED)
        setPadding(dp(7), dp(2), dp(7), dp(2))
        maxLines = 1
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) }
    }

    /**
     * Read-only диалог задачи (тап по карточке): заголовок, статус, тело,
     * факты, комментарии, хвост ленты событий, связи. Единственное действие —
     * «Закрыть»: мутации kanban мобильным клиентом не выполняются.
     */
    private fun showTaskDetail(card: KanbanTaskCardUi) {
        lifecycleScope.launch {
            val detail = presenter.loadTaskDetail(card.id)
            if (detail == null) {
                MarsDialog.show(this@KanbanActivity) { root ->
                    root.addView(MarsDialog.header(this@KanbanActivity, card.title, null))
                    root.addView(MarsDialog.paragraph(this@KanbanActivity, KanbanPolicy.MSG_DETAIL_ERROR))
                    listOf(MarsDialog.quietButton(this@KanbanActivity, KanbanPolicy.CLOSE_LABEL) {})
                }
                return@launch
            }
            MarsDialog.show(this@KanbanActivity) { root ->
                root.addView(MarsDialog.header(this@KanbanActivity, detail.title, detail.status.ifBlank { null }))
                detail.body?.let { body -> root.addView(MarsDialog.paragraph(this@KanbanActivity, body)) }
                detail.rows.forEach { row ->
                    root.addView(MarsDialog.caption(this@KanbanActivity, row.label))
                    root.addView(detailValue(row.value))
                }
                if (detail.comments.isNotEmpty()) {
                    root.addView(MarsDialog.caption(this@KanbanActivity,
                        KanbanPolicy.commentsLabel(detail.comments.size)))
                    detail.comments.forEach { comment ->
                        root.addView(commentBlock(comment))
                    }
                }
                if (detail.parentIds.isNotEmpty() || detail.childIds.isNotEmpty()) {
                    root.addView(MarsDialog.caption(this@KanbanActivity, "Связи"))
                    root.addView(detailValue(
                        "родители: ${detail.parentIds.size}, подзадачи: ${detail.childIds.size}"
                    ))
                }
                if (detail.eventLabels.isNotEmpty()) {
                    root.addView(MarsDialog.caption(this@KanbanActivity, "Последние события"))
                    root.addView(MarsDialog.pillRow(this@KanbanActivity, detail.eventLabels))
                }
                listOf(MarsDialog.quietButton(this@KanbanActivity, KanbanPolicy.CLOSE_LABEL) {})
            }
        }
    }

    private fun detailValue(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(MarsTheme.TEXT)
        setPadding(0, 0, 0, dp(8))
    }

    private fun commentBlock(comment: KanbanDetailCommentUi): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = MarsTheme.card(this@KanbanActivity, MarsTheme.SURFACE_SUBTLE, radiusDp = 10, strokeColor = MarsTheme.BORDER_SUBTLE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(TextView(this@KanbanActivity).apply {
                text = buildString {
                    append(comment.author)
                    comment.whenLabel?.let { append(" · ").append(it) }
                }
                textSize = 11f
                setTextColor(MarsTheme.TEXT_MUTED)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(this@KanbanActivity).apply {
                text = comment.body
                textSize = 13f
                setTextColor(MarsTheme.TEXT_EM)
            })
        }.let { MarsDialog.block(this, it, bottomDp = 6) }

    private fun toneColor(tone: KanbanStatusTone): Int = when (tone) {
        KanbanStatusTone.READY -> MarsTheme.STATUS_INFO
        KanbanStatusTone.RUNNING -> MarsTheme.STATUS_WARNING
        KanbanStatusTone.BLOCKED -> MarsTheme.STATUS_ERROR
        KanbanStatusTone.DONE -> MarsTheme.STATUS_ONLINE
        KanbanStatusTone.NEUTRAL -> MarsTheme.TEXT_MUTED
    }

    /**
     * Короткий reveal колонок (fade + slide-up) строго через [MotionPolicy]:
     * при системном «уменьшить движение» длительность схлопывается в 0 и
     * анимация не запускается вовсе. Кривая — decelerate (MarsMotion stance).
     */
    private fun applyRevealMotion(view: View) {
        val duration = MotionPolicy.durationFor(MotionCue.MESSAGE_REVEAL, isReducedMotion())
        if (duration <= 0) return
        view.alpha = 0f
        view.translationY = dp(8).toFloat()
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(duration.toLong())
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Settings.Global.ANIMATOR_DURATION_SCALE — тот же источник, что в ChatActivity. */
    private fun isReducedMotion(): Boolean = MotionPolicy.isReducedMotion(
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    )

    /**
     * Синхронный снимок «нет сети» для offline-safe состояний: нет активного
     * network или у него нет INTERNET-способности (как в SpacesActivity).
     */
    private fun isDeviceOffline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun columnMargin() = LinearLayout.LayoutParams(dp(COLUMN_WIDTH_DP), -1).apply { marginEnd = dp(10) }
    private fun dp(v: Int) = MarsTheme.dp(this, v)

    companion object {
        /** Интервал фонового polling доски (GET board?since=...) пока экран видим. */
        const val POLL_INTERVAL_MS = 30_000L

        /** Ширина колонки: мобильный канбан — горизонтальный свайп между колонками. */
        const val COLUMN_WIDTH_DP = 272

        /** Подложка заблокированной карточки: тёплый warn-тон 10% поверх поверхности. */
        val BLOCKED_CARD_BG = 0x1af59e0b.toInt()

        /**
         * Публичная точка входа для интегратора (например, пункт меню
         * ChatActivity/SidebarNavPolicy будущей волны):
         * `startActivity(KanbanActivity.newIntent(this))`. Back из KanbanActivity
         * возвращает в вызвавший экран.
         */
        fun newIntent(context: Context): Intent = Intent(context, KanbanActivity::class.java)
    }
}
