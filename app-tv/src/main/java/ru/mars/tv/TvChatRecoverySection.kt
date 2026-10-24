package ru.mars.tv

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.SessionDetail
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.SessionRepository

/**
 * Секция «Устойчивость хода» экрана ТВ-чата.
 *
 * Вынесено из [TvChatActivity] (декомпозиция хоста, W2).
 *
 * Reattach к живому серверному ходу, единственная job восстановления после обрыва
 * ([TvChatStreamRecoveryLoop]) и честные реакции хоста на её вердикты — вплоть до
 * переавторизации при мёртвом креденшеле.
 *
 * Форма переноса: extension-функции на Activity — состояние (View-поля, job-ы,
 * биндинг сессии) остаётся в хосте, тела перенесены дословно. Члены хоста, которые
 * читают секции, подняты `private` -> `internal`.
 */

/**
 * Starts the SINGLE recovery job of a broken turn. At most one recovery job runs at
 * a time; a stale generation is never scheduled. All decisions (park/retry/reload/
 * give-up) belong to the core coordinator inside [TvChatStreamRecoveryLoop].
 */
internal fun TvChatActivity.scheduleStreamRecovery(
    sessionId: String,
    streamId: String?,
    errorMessage: String?,
    generation: Int,
) {
    if (generation != streamGeneration) return
    // finishTurn runs INSIDE the dying stream job, so the only meaningful guard is
    // the single-recovery-job invariant itself.
    if (recovery?.isActive == true) return
    sessionFlow.markRecovering(TvChatStreamRecoveryLoop.STATUS_STARTING)
    renderStatus()
    recovery = lifecycleScope.launch {
        recoveryLoop.run(sessionId, streamId, errorMessage, recoveryHooks(generation))
    }
}

/** Host reactions of the recovery loop; every one is generation-guarded. */
internal fun TvChatActivity.recoveryHooks(generation: Int) = object : TvChatStreamRecoveryLoop.Hooks {
    override fun onStatus(status: String) {
        if (generation != streamGeneration) return
        sessionFlow.markRecovering(status)
        renderStatus()
    }

    override fun onAttachStarting(streamId: String) {
        if (generation != streamGeneration) return
        beginReattachedTurn()
    }

    override fun onStreamEvent(event: ChatEvent) {
        handleStreamEvent(event, generation)
    }

    override fun onReloadHistory(detail: SessionDetail?) {
        if (generation != streamGeneration) return
        if (detail == null || detail.messages.isEmpty()) {
            // detail == null: the reload fetch itself failed (server unreachable) —
            // report honestly instead of pretending history was refreshed.
            // Empty history: nothing ever settled server-side — the turn (and possibly
            // the message itself) never reached the server. Keep the local transcript
            // honest — wiping the user's own bubble for an undelivered message would
            // be worse.
            sessionFlow.markRecovering(TvChatSessionFlow.RECOVERY_FAILED)
            sessionFlow.endTurn()
            renderStatus()
            renderSendControl()
            return
        }
        renderSessionDetailHistory(detail)
        sessionFlow.markRecovering(TvChatSessionFlow.HISTORY_REFRESHED)
        sessionFlow.endTurn()
        renderStatus()
        renderSendControl()
        // Диалог перечитан из истории: квота провайдера (числа аккаунта) обновляется
        // тем же проходом — mobile parity, сбой сети чип не роняет.
        refreshProviderQuota()
    }

    override fun onGiveUp(reason: String) {
        if (generation != streamGeneration) return
        if (reason.startsWith(TvChatActivity.GIVE_UP_AUTH_PREFIX)) {
            forceReauth()
            return
        }
        sessionFlow.markRecovering(TvChatSessionFlow.RECOVERY_FAILED)
        sessionFlow.endTurn()
        renderStatus()
        renderSendControl()
    }
}

/**
 * Prepares the answer bubble of a reattached turn: a still-STREAMING bubble is reused
 * (a cursor-resumed attach CONTINUES the same answer — the transcript is never
 * duplicated), a locked (Done/Error) or missing bubble gets a fresh placeholder.
 */
internal fun TvChatActivity.beginReattachedTurn() {
    if (liveAccumulator?.state != TvAnswerAccumulator.State.STREAMING || liveAnswer == null) {
        val answer = appendLivePlaceholder()
        liveAnswer = answer
        liveAccumulator = TvAnswerAccumulator(answer.text.toString())
        cancelLiveFlush(applyPending = false)
        lastLiveRenderMs = SystemClock.uptimeMillis()
    }
    sessionFlow.markReattaching()
    renderStatus()
}

/**
 * Opens the session's live server turn with `attachToStream` (+ the `Last-Event-ID`
 * cursor when one is known) — it NEVER cancels the turn and NEVER re-POSTs
 * chat/start. A mid-attach drop cascades into the single recovery job.
 */
internal fun TvChatActivity.reattachActiveTurn(sessionId: String, activeTurn: ActiveTurnInfo, generation: Int) {
    if (generation != streamGeneration) return
    if (stream?.isActive == true || recovery?.isActive == true) return
    val plan = TvStreamReattachPolicy.planFor(activeTurn) as? TvReattachPlan.Attach ?: return
    val credential = store.read() ?: return
    beginReattachedTurn()
    stream = lifecycleScope.launch {
        try {
            val outcome = collectTurn(
                ChatRepository().attachToStream(
                    TvChatActivity.BASE_URL, credential, plan.streamId,
                    lastEventId = resumeTracker.lastEventId,
                    onSideEvent = sideEventCallback(sessionId, generation),
                    onEventId = { resumeTracker.onFrameId(it) },
                ),
                generation,
            )
            finishTurn(generation, outcome)
        } finally {
            if (!stopping && generation == streamGeneration) {
                renderStatus()
                renderSendControl()
            }
        }
    }
}

/** The journal died server-side (anchored 404): one live history reload, no reconnect. */
internal fun TvChatActivity.reloadHistoryAfterGone(sessionId: String, generation: Int) {
    val credential = store.read() ?: return
    lifecycleScope.launch {
        val detail = runCatching {
            SessionRepository().getSessionDetail(TvChatActivity.BASE_URL, credential, sessionId)
        }.getOrNull()
        if (generation != streamGeneration) return@launch
        if (detail == null) {
            sessionFlow.markRecovering(TvChatSessionFlow.RECOVERY_FAILED)
        } else {
            renderSessionDetailHistory(detail)
            sessionFlow.markRecovering(TvChatSessionFlow.HISTORY_REFRESHED)
        }
        sessionFlow.endTurn()
        renderStatus()
        renderSendControl()
    }
}

/** Сброс пер-тёрн состояния ответа (новый чат/история/открытый диалог). */
internal fun TvChatActivity.resetLiveAnswerState() {
    cancelLiveFlush(applyPending = false)
    liveAnswer = null
    liveAccumulator = null
    lastLiveEvent = null
    explicitStatus = null
    // Состояние хода и доска детей принадлежат ТЕКУЩЕМУ ходу: новый ход/другой
    // диалог не должны показывать чужие «ждёт подтверждения» и живых детей.
    turnWatchJob?.cancel()
    turnWatchJob = null
    currentNotice = null
    subagentBoardState = emptyMap()
    applyTurnNotice()
    renderSubagentBoard()
}

/**
 * Explicit local teardown of the stream work («Новый чат», opening another dialog,
 * выход/BACK, logout): invalidates the generation (late callbacks are dropped) and
 * cancels the single stream job AND the single recovery job. The SERVER turn is never
 * touched here — an explicit exit/new-chat cancels it separately via
 * [cancelServerTurnBestEffort]; navigation and a technical recreate leave it running.
 */
internal fun TvChatActivity.cancelLocalStreamJobs() {
    streamGeneration++
    stream?.cancel()
    stream = null
    recovery?.cancel()
    recovery = null
}

/** Recovery verdict "auth rejected": the credential is dead — re-pair (dashboard logout parity). */
internal fun TvChatActivity.forceReauth() {
    cancelLocalStreamJobs()
    store.clear()
    startActivity(Intent(this, PairingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
    finish()
}
