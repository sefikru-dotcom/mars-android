package ru.mars.tv

import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.repository.SessionRepository
import ru.mars.tv.chat.TvTurnStatePolicy

/**
 * Секция «Состояние невидимого хода» экрана ТВ-чата.
 *
 * Вынесено из [TvChatActivity] (декомпозиция хоста, W2).
 *
 * Баннер «ход выполняется в другом диалоге» и наблюдение за таким ходом
 * ([TvTurnStatePolicy]): решение, когда баннер показать и когда снять, принимает
 * политика, секция только рисует и держит job.
 *
 * Форма переноса: extension-функции на Activity — состояние (View-поля, job-ы,
 * биндинг сессии) остаётся в хосте, тела перенесены дословно. Члены хоста, которые
 * читают секции, подняты `private` -> `internal`.
 */

// --- состояние невидимого хода (TvTurnStatePolicy) -------------------------------------

/**
 * Показывает баннер непереподключаемого хода ([TvTurnStatePolicy.notice]) и, если
 * ход чего-то ждёт, ставит ограниченную фоновую перепроверку: пользователь может
 * ответить на подтверждение с телефона, и ТВ обязан это увидеть сам, без пульта.
 *
 * `null` от политики — терять нечего: баннер снимается, перепроверка гасится.
 */
internal fun TvChatActivity.renderTurnNotice(sessionId: String, activeTurn: ActiveTurnInfo) {
    turnWatchJob?.cancel()
    turnWatchJob = null
    currentNotice = TvTurnStatePolicy.notice(activeTurn)
    applyTurnNotice()
    if (currentNotice != null) scheduleTurnWatch(sessionId, attempt = 1)
}

/**
 * Ограниченный backoff перепроверки (`GET /api/session`): задержки — из
 * [TvTurnStatePolicy.watchDelayMs] (переиспользован [TvStreamRecoveryPolicy]),
 * после [TvTurnStatePolicy.MAX_WATCH_ATTEMPTS] экран ждёт ручного нажатия на баннер.
 */
internal fun TvChatActivity.scheduleTurnWatch(sessionId: String, attempt: Int) {
    val delayMs = TvTurnStatePolicy.watchDelayMs(attempt) ?: return
    turnWatchJob = lifecycleScope.launch {
        delay(delayMs)
        val credential = store.read() ?: return@launch
        val detail = runCatching {
            SessionRepository().getSessionDetail(TvChatActivity.BASE_URL, credential, sessionId)
        }.getOrNull() ?: return@launch
        // Пока ждали, экран мог уйти в другой диалог — чужое состояние не красим.
        if (sessionFlow.sessionId != sessionId) return@launch
        currentNotice = TvTurnStatePolicy.notice(detail.activeTurn)
        applyTurnNotice()
        if (currentNotice != null) scheduleTurnWatch(sessionId, attempt + 1)
    }
}

/**
 * Баннер — первый ребёнок ленты (выше истории и маркера): с дивана он читается
 * до текста. Фокусируемый и кликабельный: нажатие перечитывает диалог, поэтому
 * «требуется подтверждение» достижимо пультом даже без reattach.
 */
internal fun TvChatActivity.applyTurnNotice() {
    if (!messagesReady) return
    val notice = currentNotice
    if (notice == null) {
        turnNoticeView?.let { view ->
            detachTranscriptView(view)
            messages.removeView(view)
        }
        turnNoticeView = null
        return
    }
    val color = ContextCompat.getColor(this, when (notice.tone) {
        TvTurnStatePolicy.Tone.ATTENTION -> R.color.mars_status_warning
        TvTurnStatePolicy.Tone.LIVE -> R.color.mars_status_online
        TvTurnStatePolicy.Tone.QUEUED -> R.color.mars_status_info
    })
    val view = turnNoticeView ?: TextView(this).apply {
        textSize = 20f
        setPadding(dp(24), dp(12), dp(24), dp(12))
        setBackgroundResource(R.drawable.bg_focus_card)
        isFocusable = true
        isClickable = true
        tag = TvChatActivity.TAG_TURN_NOTICE
        TvFocusLift.attach(this, reducedMotion)
        turnNoticeView = this
    }
    view.text = notice.text
    view.setTextColor(color)
    view.setOnClickListener {
        sessionFlow.sessionId?.let { current -> openSession(current) }
    }
    if (view.parent == null) {
        messages.addView(view, 0, LinearLayout.LayoutParams(-1, -2))
    } else if (messages.indexOfChild(view) != 0) {
        messages.removeView(view)
        messages.addView(view, 0, LinearLayout.LayoutParams(-1, -2))
    }
}
