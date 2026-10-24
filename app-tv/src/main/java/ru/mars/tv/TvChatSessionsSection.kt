package ru.mars.tv

import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.SessionDetail
import ru.mars.network.model.SessionItem
import ru.mars.network.repository.SessionRepository
import ru.mars.tv.chat.TvTranscriptRetentionPolicy

/**
 * Секция «Браузер диалогов» экрана ТВ-чата.
 *
 * Вынесено из [TvChatActivity] (декомпозиция хоста, W2).
 *
 * Оверлей «Диалоги»: загрузка списка и деталей, карточки строк, открытие диалога
 * (локальные job-ы снимаются, серверный ход продолжается). Все решения — в
 * [TvSessionBrowserPolicy].
 *
 * Форма переноса: extension-функции на Activity — состояние (View-поля, job-ы,
 * биндинг сессии) остаётся в хосте, тела перенесены дословно. Члены хоста, которые
 * читают секции, подняты `private` -> `internal`.
 */

// --- Session browser («Диалоги») ---------------------------------------------------------------

/**
 * Opens the session dialog: shows the overlay, freezes the chat behind it (focus cannot
 * escape into the transcript/composer) and fetches the session list. All fetch/render
 * decisions come from [TvSessionBrowserPolicy]; a stale fetch (token moved on) is dropped.
 */
internal fun TvChatActivity.openSessionBrowser() {
    // Guard only — the credential the fetch needs is read again inside loadSessionList.
    if (store.read() == null) return
    browserReturnFocus = currentFocus
    chatRoot.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    browserBackCallback.isEnabled = true
    sessionsOverlay.visibility = View.VISIBLE
    loadSessionList()
}

/**
 * One session-list fetch attempt (the first open AND every «Повторить»): resets the
 * dialog into the loading state and renders through [TvSessionBrowserPolicy.listOutcome].
 * core-network answers every failed fetch with an EMPTY list, so the connectivity probe
 * snapshot decides the honest verdict: offline reads «unavailable» + retry, never
 * «Список диалогов пуст» (a dead radio is not evidence that no dialogs exist).
 */
internal fun TvChatActivity.loadSessionList() {
    val credential = store.read() ?: return
    sessionRowViews.clear()
    browserRows = emptyList()
    sessionList.removeAllViews()
    sessionsStatus.text = TvChatActivity.LOADING_SESSIONS
    sessionsStatus.visibility = View.VISIBLE
    closeSessions.requestFocus()
    lifecycleScope.launch {
        val token = ++sessionLoadToken
        val sessions = SessionRepository().getSessions(TvChatActivity.BASE_URL, credential)
        if (token != sessionLoadToken) return@launch
        renderSessionBrowser(sessions)
    }
}

/** Hides the overlay, re-enables chat focus and restores it where the dialog was opened. */
internal fun TvChatActivity.closeSessionBrowser() {
    if (!sessionsOverlayReady || sessionsOverlay.visibility != View.VISIBLE) return
    sessionLoadToken++ // an in-flight list fetch must not re-render a closed dialog
    sessionsOverlay.visibility = View.GONE
    browserBackCallback.isEnabled = false
    chatRoot.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    val restore = browserReturnFocus?.takeIf { it.isAttachedToWindow && it.visibility == View.VISIBLE }
    browserReturnFocus = null
    (restore ?: sessionsButton).requestFocus()
}

/**
 * Renders one fetched list through [TvSessionBrowserPolicy.listOutcome]: visible rows
 * build the D-pad cards (initial focus per policy — current session, else first), the
 * server-confirmed empty state parks the focus on «Закрыть», and the OFFLINE state gets
 * the honest «unavailable» line plus a focusable «Повторить» row ([buildSessionListRetry])
 * — a flaky-TV-radio dead end must stay escapable without closing the dialog.
 */
internal fun TvChatActivity.renderSessionBrowser(sessions: List<SessionItem>) {
    val outcome = TvSessionBrowserPolicy.listOutcome(
        sessions = sessions,
        currentSessionId = sessionFlow.sessionId,
        networkAvailable = connectivityProbe.isNetworkAvailable(),
    )
    sessionList.removeAllViews()
    sessionRowViews.clear()
    browserRows = (outcome as? TvSessionBrowserPolicy.ListOutcome.Show)?.rows.orEmpty()
    val statusMessage = TvSessionBrowserPolicy.listStatusMessage(outcome)
    if (statusMessage == null) {
        sessionsStatus.visibility = View.GONE
    } else {
        sessionsStatus.text = statusMessage
        sessionsStatus.visibility = View.VISIBLE
    }
    if (browserRows.isEmpty()) {
        if (TvSessionBrowserPolicy.shouldOfferRetry(outcome)) {
            val retry = buildSessionListRetry()
            sessionList.addView(retry, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            retry.requestFocus()
        } else {
            closeSessions.requestFocus()
        }
        return
    }
    browserRows.forEachIndexed { index, row ->
        val card = buildSessionCard(
            index, row,
            current = TvSessionBrowserPolicy.isCurrent(row, sessionFlow.sessionId),
        )
        sessionRowViews += card
        sessionList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }
    val selection = TvSessionBrowserPolicy.initialSelection(browserRows, sessionFlow.sessionId)
    sessionRowViews.getOrNull(selection)?.requestFocus() ?: closeSessions.requestFocus()
}

/**
 * The offline state's escape hatch: a focusable «Повторить» row (closeSessionsButton
 * styling: theme focus selector + lift) that re-runs [loadSessionList]. Rendered only
 * for [TvSessionBrowserPolicy.ListOutcome.Unreachable] — never for a server-confirmed
 * empty list, where there is nothing to retry.
 */
internal fun TvChatActivity.buildSessionListRetry(): Button = Button(this).apply {
    text = TvSessionBrowserPolicy.RETRY_LABEL
    tvTextSize(R.dimen.tv_text_body)
    isAllCaps = false
    setTextColor(ContextCompat.getColor(this@buildSessionListRetry, R.color.mars_text))
    setBackgroundResource(R.drawable.bg_focus_card)
    minWidth = 0
    minHeight = 0
    minimumWidth = 0
    minimumHeight = 0
    setPadding(dp(26), dp(10), dp(26), dp(10))
    isFocusable = true
    setOnClickListener { loadSessionList() }
    TvFocusLift.attach(this, reducedMotion)
}

/**
 * One dialog row: title + preview + meta card. The CURRENT dialog is marked
 * visually (ruby-tinted title + «текущий диалог» meta lead — decisions in
 * [TvSessionBrowserPolicy.isCurrent]/[TvSessionBrowserPolicy.metaWithCurrent]):
 * after the initial focus steps away, the user must still see which dialog is live.
 * UP/DOWN stepping is explicit through [TvSessionBrowserPolicy.move] (deterministic,
 * clamped at the edges — focus never escapes the list into the chat behind); at an
 * edge the key falls through to the platform focus search (towards «Закрыть»).
 */
internal fun TvChatActivity.buildSessionCard(index: Int, row: TvSessionBrowserPolicy.SessionRow, current: Boolean): View {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // Карточка диалога — стекло, а не прозрачная строка сайдбара: под ней
        // фон с планетой, и без подложки заголовок терялся на светлых участках.
        setBackgroundResource(R.drawable.bg_card_glass)
        isFocusable = true
        isClickable = true
        setPadding(dp(22), dp(14), dp(22), dp(14))
        setOnClickListener { onSessionRowPicked(row) }
        setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val delta = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> 1
                KeyEvent.KEYCODE_DPAD_UP -> -1
                else -> return@setOnKeyListener false
            }
            val target = TvSessionBrowserPolicy.move(index, sessionRowViews.size, delta)
            val view = sessionRowViews.getOrNull(target)
            if (view != null && target != index) {
                view.requestFocus()
                true
            } else false
        }
        TvFocusLift.attach(this, reducedMotion)
    }
    card.addView(TextView(this).apply {
        text = row.title
        tvTextSize(R.dimen.tv_text_title)
        setTextColor(ContextCompat.getColor(
            context, if (current) R.color.mars_accent_text else R.color.mars_text_strong))
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    })
    row.badge?.let { badge ->
        card.addView(TextView(this).apply {
            text = badge.text
            tvTextSize(R.dimen.tv_text_micro)
            setTextColor(ContextCompat.getColor(context, when (badge.tone) {
                TvSessionBrowserPolicy.BadgeTone.ATTENTION -> R.color.mars_status_warning
                TvSessionBrowserPolicy.BadgeTone.LIVE -> R.color.mars_status_online
                TvSessionBrowserPolicy.BadgeTone.QUEUED -> R.color.mars_status_info
            }))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        })
    }
    // Подпись доски: тема работы субагента (worker_status.goal) — по ней с дивана
    // видно, ЧЕМ занят ребёнок, а не только что он существует.
    row.work?.let { work ->
        card.addView(TextView(this).apply {
            text = work
            tvTextSize(R.dimen.tv_text_label)
            setTextColor(ContextCompat.getColor(context, R.color.mars_text))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })
    }
    row.subtitle?.let { subtitle ->
        card.addView(TextView(this).apply {
            text = subtitle
            tvTextSize(R.dimen.tv_text_label)
            setTextColor(ContextCompat.getColor(context, R.color.mars_muted))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        })
    }
    TvSessionBrowserPolicy.metaWithCurrent(row.meta, current)?.let { meta ->
        card.addView(TextView(this).apply {
            text = meta
            tvTextSize(R.dimen.tv_text_label)
            setTextColor(ContextCompat.getColor(context, R.color.mars_status_info))
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })
    }
    return card
}

/** A row was activated: close the dialog and load that session. */
internal fun TvChatActivity.onSessionRowPicked(row: TvSessionBrowserPolicy.SessionRow) {
    closeSessionBrowser()
    openSession(row.id)
}

/**
 * Loads one session and continues the chat inside it (mobile `loadSessionById` parity):
 * navigation cancels only the LOCAL stream work — NEVER the server turn — the fetched
 * detail binds into [sessionFlow] via [ChatEvent.SessionBound] so the next send reuses
 * that session_id, the persisted transcript renders as bubbles, and when the session's
 * turn is still streaming server-side it is REATTACHED (attachToStream + Last-Event-ID
 * cursor), never cancelled: a technical recreate or reopening an active dialog must
 * continue the same live answer.
 */
internal fun TvChatActivity.openSession(sessionId: String) {
    val credential = store.read() ?: return
    // Navigation: cancel local jobs (stream + recovery), bump the generation so their
    // late callbacks are dropped. The server turn survives and is reattached below.
    cancelLocalStreamJobs()
    resumeTracker.reset()
    recoveryCoordinator.reset()
    stopping = false
    if (interactiveReady) interactive.dismissAll()
    val token = ++sessionLoadToken
    statusView.text = TvChatActivity.OPENING_SESSION
    statusView.visibility = View.VISIBLE
    lifecycleScope.launch {
        val detail = runCatching {
            SessionRepository().getSessionDetail(TvChatActivity.BASE_URL, credential, sessionId)
        }.getOrNull()
        // The user opened something newer mid-fetch («Новый чат», another dialog):
        // that navigation owns the screen now — never clobber it with a stale load.
        if (token != sessionLoadToken) return@launch
        when (val outcome = TvSessionBrowserPolicy.loadOutcome(detail)) {
            is TvSessionBrowserPolicy.LoadOutcome.Failed -> {
                statusView.text = outcome.message
                statusView.visibility = View.VISIBLE
                input.requestFocus()
            }
            is TvSessionBrowserPolicy.LoadOutcome.Open -> {
                sessionFlow.startNewChat()
                sessionFlow.onEvent(ChatEvent.SessionBound(outcome.detail.id))
                // Серверная модель открытого диалога (SessionDetail.model) и его
                // лестница размышлений — оба значения привязаны к сессии.
                modelSelection.setSessionModel(outcome.detail.model, outcome.detail.modelProvider)
                renderModelChip()
                refreshReasoningStatus()
                // Квота провайдера читается вместе с открытием диалога (mobile A10
                // parity): без сессии провайдер неизвестен, а числа относятся к аккаунту.
                refreshProviderQuota()
                resetLiveAnswerState()
                // Внеэкранная сборка + подмена одним проходом (renderTranscriptRows):
                // длинная история не блокирует главный поток и не даёт пустого кадра.
                historyPageRows = TvTranscriptRetentionPolicy.MAX_RENDERED_MESSAGES
                renderTranscriptRows(TvSessionBrowserPolicy.transcript(outcome.detail))
                input.text.clear()
                // A live server turn is reattached (never cancelled): the recreated
                // or reopened screen continues the same dialog.
                if (outcome.detail.activeTurn.canReattach) {
                    reattachActiveTurn(outcome.detail.id, outcome.detail.activeTurn, streamGeneration)
                }
                // Ход, к которому переподключиться нечем, но который ЖДЁТ решения,
                // больше не теряется молча: баннер + фоновая перепроверка.
                renderTurnNotice(outcome.detail.id, outcome.detail.activeTurn)
                renderSendControl()
                renderStatus()
                input.requestFocus()
                pinAndScrollToBottom()
            }
        }
    }
}
