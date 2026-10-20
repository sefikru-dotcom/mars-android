package ru.mars.mobile

import android.view.Gravity
import android.view.View
import android.widget.*

/**
 * Карточка офлайн-очереди: строки PENDING/SENDING/FAILED, повтор и удаление.
 * 
 * Вынесено из [ChatActivity] (W2) как internal-расширения; решения о буферизации и
 * флаше — в [ChatOfflineQueuePolicy], здесь только вид и обработчики.
 */

// --- Карточка неотправленных (OfflineQueuePresenter, O8) --------------------------------------

/**
 * Отрисовка карточки очереди: строки этого диалога (ждут отправки / отправляются /
 * не доставлены) с ручным повтором и удалением. Состояние считает
 * [OfflineQueuePresenter]; Activity только мапит его на view.
 */
internal fun ChatActivity.updateOfflineQueuePanel() {
    if (!isQueuePlateReady() || !isOfflineQueueReady()) return
    val panel = offlineQueuePresenter.panelFor(
        offlineQueue.snapshot(),
        networkPolicy.state,
        currentSessionId,
    )
    if (!panel.visible) {
        queuePlate.visibility = View.GONE
        queueRows.removeAllViews()
        queueDiscardAll.visibility = View.GONE
        return
    }
    queuePlate.visibility = View.VISIBLE
    queueTitle.text = "${panel.title} · ${panel.badge.label}"
    queueDiscardAll.visibility = if (panel.discardAllVisible) View.VISIBLE else View.GONE
    queueRows.removeAllViews()
    panel.rows.forEach { row ->
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        val text = TextView(this).apply {
            textSize = 11.5f
            setTextColor(
                when (row.status) {
                    OfflineQueueRowStatus.FAILED -> MarsTheme.STATUS_ERROR
                    OfflineQueueRowStatus.SENDING -> MarsTheme.TEXT_MUTED
                    OfflineQueueRowStatus.WAITING -> MarsTheme.TEXT
                }
            )
            this.text = "${row.preview}\n${row.statusLabel}"
        }
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        if (row.retryAvailable) {
            line.addView(queueActionButton("Повторить", MarsTheme.ACCENT_TEXT) {
                retryQueuedMessage(row.id)
            }, LinearLayout.LayoutParams(-2, -2))
        }
        if (row.discardAvailable) {
            line.addView(queueActionButton("Удалить", MarsTheme.STATUS_ERROR) {
                requestDiscardQueued(row.id)
            }, LinearLayout.LayoutParams(-2, -2))
        }
        line.contentDescription = row.contentDescription
        queueRows.addView(line)
    }
}

internal fun ChatActivity.queueActionButton(label: String, color: Int, action: () -> Unit): Button =
    Button(this).apply {
        text = label
        textSize = 11f
        setTextColor(color)
        background = null
        setPadding(dp(6), 0, dp(6), 0)
        setOnClickListener { action() }
    }

/**
 * Ручной повтор недоставленного сообщения. Ход возвращается в путь обычной
 * отправки того же диалога: повторный провал снова попадёт в карточку, а успех
 * снимет строку.
 */

/**
 * Ручной повтор недоставленного сообщения. Ход возвращается в путь обычной
 * отправки того же диалога: повторный провал снова попадёт в карточку, а успех
 * снимет строку.
 */
internal fun ChatActivity.retryQueuedMessage(messageId: String) {
    // Попытка повтора берётся из записи презентера: requestRetry сразу снимает её
    // из списка недоставленных и помечает ход как отправляемый.
    val known = offlineQueuePresenter.failed.firstOrNull { it.id == messageId }
    when (val outcome = offlineQueuePresenter.requestRetry(messageId)) {
        is RetryOutcome.Rejected -> {
            // Ход уже в полёте или его нет в списке недоставленных — карточка
            // перерисовывается, чтобы состояние совпало с презентером.
            updateOfflineQueuePanel()
        }
        is RetryOutcome.TriggerSend -> {
            val credential = store.read() ?: run { updateOfflineQueuePanel(); return }
            if (!uiState.beginQueuedSend(outcome.text)) {
                // Ход идёт: повтор вернём в недоставленные, чтобы кнопка не пропала.
                offlineQueuePresenter.onTurnFailed(
                    QueuedMessage(
                        id = outcome.messageId,
                        text = outcome.text,
                        sessionId = outcome.sessionId,
                        enqueuedAtEpochMillis = known?.failedAtEpochMillis ?: System.currentTimeMillis(),
                        attempts = known?.attempts ?: 0,
                    )
                )
                updateOfflineQueuePanel()
                return
            }
            explicitStatus = null
            autoScroll.pinToBottom()
            renderMessages()
            setStatus()
            updateControlsFromSection()
            updateNetworkBanner()
            stream = startSendStream(
                text = outcome.text,
                outgoingAttachments = emptyList(),
                targetSessionId = outcome.sessionId ?: currentSessionId,
                credential = credential,
                onTurnFailed = {
                    offlineQueuePresenter.onTurnFailed(
                        QueuedMessage(
                            id = outcome.messageId,
                            text = outcome.text,
                            sessionId = currentSessionId ?: outcome.sessionId,
                            enqueuedAtEpochMillis = known?.failedAtEpochMillis ?: System.currentTimeMillis(),
                            attempts = (known?.attempts ?: 0) + 1,
                        )
                    )
                    updateOfflineQueuePanel()
                },
                onTurnSucceeded = {
                    offlineQueuePresenter.onSendSucceeded(outcome.messageId)
                    updateOfflineQueuePanel()
                },
            )
        }
    }
}

/** Удаление недоставленного сообщения: подтверждение — часть презентера. */

/** Удаление недоставленного сообщения: подтверждение — часть презентера. */
internal fun ChatActivity.requestDiscardQueued(messageId: String) {
    val prompt = offlineQueuePresenter.requestDiscard(messageId) ?: return
    showDiscardPrompt(prompt)
}

/** Удаление всех недоставленных (кнопка появляется при двух и более). */

/** Удаление всех недоставленных (кнопка появляется при двух и более). */
internal fun ChatActivity.requestDiscardAllQueued() {
    val prompt = offlineQueuePresenter.requestDiscardAll() ?: return
    showDiscardPrompt(prompt)
}

internal fun ChatActivity.showDiscardPrompt(prompt: DiscardPromptUi) {
    android.app.AlertDialog.Builder(this)
        .setTitle(prompt.title)
        .setMessage(prompt.message)
        .setPositiveButton(prompt.confirmLabel) { _, _ -> applyDiscard() }
        .setNegativeButton(prompt.cancelLabel) { _, _ ->
            offlineQueuePresenter.cancelDiscard()
            updateOfflineQueuePanel()
        }
        .setOnCancelListener {
            offlineQueuePresenter.cancelDiscard()
            updateOfflineQueuePanel()
        }
        .show()
}

/**
 * Применение подтверждённого удаления: строки уходят из карточки, а локальные
 * PENDING-эхо этих сообщений снимаются из транскрипта — удалённое не должно
 * остаться в диалоге призраком.
 */

/**
 * Применение подтверждённого удаления: строки уходят из карточки, а локальные
 * PENDING-эхо этих сообщений снимаются из транскрипта — удалённое не должно
 * остаться в диалоге призраком.
 */
internal fun ChatActivity.applyDiscard() {
    when (val outcome = offlineQueuePresenter.confirmDiscard()) {
        is DiscardOutcome.Discard -> {
            uiState.dropQueuedEcho(outcome.turn.text)
            renderMessages()
        }
        is DiscardOutcome.DiscardAll -> {
            outcome.turns.forEach { uiState.dropQueuedEcho(it.text) }
            renderMessages()
        }
        is DiscardOutcome.NothingToDiscard -> Unit
    }
    updateOfflineQueuePanel()
    updateNetworkBanner()
}
