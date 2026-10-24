package ru.mars.tv

import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.mars.network.model.ContextStatusUpdate
import ru.mars.network.model.StreamSideEvent
import ru.mars.network.model.SubagentStatusUpdate
import ru.mars.network.repository.SubagentStatusPolicy
import ru.mars.tv.chat.TvActivityDisplayMode
import ru.mars.tv.chat.TvActivityDisplayPolicy
import ru.mars.tv.chat.TvContextStatusPresenter
import ru.mars.tv.chat.TvContextTone
import ru.mars.tv.chat.TvSubagentBoardPolicy

/**
 * Секция «Шапка и статус» экрана ТВ-чата.
 *
 * Вынесено из [TvChatActivity] (декомпозиция хоста, W2).
 *
 * Быстрые чипы, мини-плеер, доска субагентов, бейдж префилл-контекста и строка
 * статуса хода: всё, что показывает состояние вокруг ленты.
 *
 * Форма переноса: extension-функции на Activity — состояние (View-поля, job-ы,
 * биндинг сессии) остаётся в хосте, тела перенесены дословно. Члены хоста, которые
 * читают секции, подняты `private` -> `internal`.
 */

/**
 * Quick-chip row above the composer: one D-pad tap sends a canned full turn.
 * Buttons are built once; activation and LEFT/RIGHT stepping follow
 * [TvQuickChipsPolicy] (clamped at the edges, no wrap).
 */
internal fun TvChatActivity.buildQuickChips() {
    quickChipsRow.removeAllViews()
    chipButtons.clear()
    val padH = dp(20)
    val padV = dp(10)
    TvQuickChipsPolicy.chips.forEachIndexed { index, chip ->
        val button = Button(this).apply {
            text = chip.label
            textSize = 18f
            isAllCaps = false
            setTextColor(ContextCompat.getColor(this@buildQuickChips, R.color.mars_text))
            setBackgroundResource(R.drawable.bg_focus_card)
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(padH, padV, padH, padV)
            isFocusable = true
            setOnClickListener { activateQuickChip(chip) }
            // D-pad LEFT/RIGHT stepping inside the row (TvQuickChipsPolicy.move).
            setOnKeyListener { _, keyCode, event ->
                val delta = when {
                    event.action != KeyEvent.ACTION_DOWN -> null
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                    keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> -1
                    else -> null
                }
                if (delta == null) return@setOnKeyListener false
                val next = TvQuickChipsPolicy.move(index, chipButtons.size, delta)
                val target = chipButtons.getOrNull(next) ?: return@setOnKeyListener false
                target.requestFocus()
                true
            }
        }
        TvFocusLift.attach(button, reducedMotion)
        quickChipsRow.addView(button, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(12) })
        chipButtons += button
    }
    renderChips()
}

/** A chip is a full turn: its text goes into the composer and is sent immediately. */
internal fun TvChatActivity.activateQuickChip(chip: QuickChip) {
    if (!chipsLive()) return
    input.setText(chip.text)
    sendMessage()
}

/** Chips follow the send rule: dead while a turn streams or the input is blocked. */
internal fun TvChatActivity.chipsLive(): Boolean = TvQuickChipsPolicy.chipsEnabled(
    streamActive = sessionFlow.canStop || stream?.isActive == true,
    inputBlocked = !input.isEnabled,
)

internal fun TvChatActivity.renderChips() {
    val live = chipsLive()
    chipButtons.forEach { button ->
        button.isEnabled = live
        button.alpha = if (live) 1f else 0.4f
    }
}

/** Mirrors the global transport into the header plate (rules: [TvMiniPlayerSpec]). */
internal fun TvChatActivity.renderMiniPlayer() {
    val track = TvMusicPlayer.currentTrack
    val visible = TvMiniPlayerSpec.visible(track)
    miniPlayerRow.visibility = if (visible) View.VISIBLE else View.GONE
    if (!visible) return
    miniPlayerText.text = TvMiniPlayerSpec.line(track)
    val playing = TvMusicPlayer.isPlaying
    miniPlayerToggle.text = TvMiniPlayerSpec.toggleLabel(playing)
    miniPlayerToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
        if (playing) R.drawable.ic_pause else R.drawable.ic_play, 0, 0, 0)
    miniPlayerToggle.compoundDrawableTintList =
        ContextCompat.getColorStateList(this, R.color.icon_focus_tint)
}

/**
 * Side-канал SSE-метаданных ([StreamSideEvent]) живого хода: ТВ-чат реагирует
 * на `context_status` (бейдж префилл-контекста) и на жизненный цикл субагентов.
 * `title`/`goal`/`goal_continue` доставляются, но остаются без реакции (паритет
 * с мобильным чатом) — и именно поэтому они ПИШУТСЯ в лог на debug-уровне:
 * side-канал намеренно не sealed, сервер может добавить кадр без правки
 * транспорта, и молчаливый `else` сделал бы «на ТВ не работает» неотличимым
 * от «сервер не прислал».
 *
 * Кадр приходит на IO-контексте потока — прыжок на UI-поток здесь.
 * [boundSessionId] фиксирует сессию на момент старта хода, [boundGeneration] —
 * поколение стрима: опоздавший кадр не перекрасит уже другой диалог/экран.
 */
internal fun TvChatActivity.sideEventCallback(
    boundSessionId: String?,
    boundGeneration: Int,
): (StreamSideEvent) -> Unit = { event ->
    when (event) {
        is ContextStatusUpdate -> runOnUiThread {
            if (boundGeneration == streamGeneration) onContextStatus(event, boundSessionId)
        }
        // Доска субагентов (P0-2): раньше кадры детей доезжали только трейсом
        // «Мыслей», и с дивана было не понять, работает ли делегирование вообще.
        is SubagentStatusUpdate -> runOnUiThread {
            if (boundGeneration == streamGeneration) onSubagentUpdate(event, boundSessionId)
        }
        else -> TvSseFrameTrace.unhandledSideEvent(event)?.let { line ->
            Log.d(TvSseFrameTrace.TAG, line)
        }
    }
}

/**
 * Один кадр жизненного цикла ребёнка -> редьюсер core [SubagentStatusPolicy] ->
 * строка доски ([TvSubagentBoardPolicy]). Кадр чужой сессии игнорируется: доска
 * не протекает между диалогами.
 */
internal fun TvChatActivity.onSubagentUpdate(update: SubagentStatusUpdate, boundSessionId: String?) {
    if (!subagentBoardReady) return
    val activeSessionId = sessionFlow.sessionId
    if (boundSessionId != null && activeSessionId != boundSessionId) return
    subagentBoardState = SubagentStatusPolicy.apply(subagentBoardState, update.update)
    renderSubagentBoard()
}

/** Строка доски (текст и тон — из чистой политики); пустая доска прячет View. */
internal fun TvChatActivity.renderSubagentBoard() {
    if (!subagentBoardReady) return
    val board = TvSubagentBoardPolicy.present(subagentBoardState)
    if (!board.visible) {
        subagentBoard.visibility = View.GONE
        return
    }
    subagentBoard.text = board.text
    subagentBoard.setTextColor(ContextCompat.getColor(this, when (board.tone) {
        TvSubagentBoardPolicy.Tone.RUNNING -> R.color.mars_status_online
        TvSubagentBoardPolicy.Tone.WARNING -> R.color.mars_status_warning
        TvSubagentBoardPolicy.Tone.ERROR -> R.color.mars_status_error
        TvSubagentBoardPolicy.Tone.DONE -> R.color.mars_muted
    }))
    subagentBoard.visibility = View.VISIBLE
}

/**
 * Бейдж префилл-контекста: каждый кадр ПЕРЕЗАПИСЫВАЕТ предыдущий (не
 * накапливает), виден [TvContextStatusPresenter.DISPLAY_DURATION_MS] и гаснет.
 * Кадр, который показывать нечего (`not_configured`/чужая сессия), гасит
 * бейдж сразу — статус чужого диалога не протекает в текущий экран.
 */
internal fun TvChatActivity.onContextStatus(update: ContextStatusUpdate, boundSessionId: String?) {
    if (!contextStatusBadgeReady) return
    val activeSessionId = sessionFlow.sessionId
    if (boundSessionId != null && activeSessionId != boundSessionId) return
    contextBadgeJob?.cancel()
    val badge = TvContextStatusPresenter.badge(update, activeSessionId)
    if (badge == null) {
        contextBadgeJob = null
        hideContextBadge()
        return
    }
    contextBadge = badge
    contextStatusBadge.text = badge.text
    contextStatusBadge.setTextColor(
        ContextCompat.getColor(this, when (badge.tone) {
            TvContextTone.LOADED -> R.color.mars_status_online
            TvContextTone.COMPACTED -> R.color.mars_status_warning
            TvContextTone.ERROR -> R.color.mars_status_error
        })
    )
    contextStatusBadge.visibility = View.VISIBLE
    contextBadgeJob = lifecycleScope.launch {
        delay(TvContextStatusPresenter.DISPLAY_DURATION_MS)
        contextBadgeJob = null
        hideContextBadge()
    }
}

/** Снятие бейджа контекста (таймер, смена диалога, «Новый чат»). */
internal fun TvChatActivity.hideContextBadge() {
    contextBadge = null
    if (contextStatusBadgeReady) contextStatusBadge.visibility = View.GONE
}

/** Сброс бейджа при любом уходе с контекста диалога (таймер гасится, вид скрывается). */
internal fun TvChatActivity.clearContextStatus() {
    contextBadgeJob?.cancel()
    contextBadgeJob = null
    hideContextBadge()
}

/**
 * Header status strip: live reasoning/tool status line plus the context-fill pill
 * (green <=50%, orange <=75%, red above — WebUI ring parity). Both hide when unknown.
 * Режим показа хода ([TvActivityDisplayPolicy.status]) глушит детали хода в «Тишине»;
 * явный статус (исход steer, смена режима) имеет приоритет до следующего события.
 */
internal fun TvChatActivity.renderStatus() {
    val mode = if (activityModeReady) {
        activityMode.current
    } else {
        TvActivityDisplayMode.DEFAULT
    }
    val status = explicitStatus
        ?: TvActivityDisplayPolicy.status(mode, sessionFlow.canStop, lastLiveEvent, sessionFlow.statusText)
    statusView.text = status
    statusView.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
    val usageText = sessionFlow.contextUsageText
    contextView.text = usageText.orEmpty()
    contextView.visibility = if (usageText == null) View.GONE else View.VISIBLE
    val percent = sessionFlow.contextUsage?.percent ?: 0
    contextView.setTextColor(ContextCompat.getColor(this, when {
        percent > 75 -> R.color.mars_status_error
        percent > 50 -> R.color.mars_status_warning
        else -> R.color.mars_status_online
    }))
}
