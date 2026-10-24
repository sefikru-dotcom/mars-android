package ru.mars.tv

import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.model.ChatEvent
import ru.mars.network.repository.ChatRepository
import ru.mars.tv.chat.TvActivityDisplayPolicy
import ru.mars.tv.chat.TvLiveRenderBatchPolicy
import ru.mars.tv.chat.TvSteerPolicy

/**
 * Секция «Ход» экрана ТВ-чата.
 *
 * Вынесено из [TvChatActivity] (декомпозиция хоста, W2).
 *
 * Отправка и остановка хода, steer, разбор кадров живого стрима и терминальные
 * состояния хода ([TurnOutcome]). Решения — в политиках `Tv*Policy`, здесь только
 * проводка: какой транспорт, какой статус, что писать в ленту.
 *
 * Форма переноса: extension-функции на Activity — состояние (View-поля, job-ы,
 * биндинг сессии) остаётся в хосте, тела перенесены дословно. Члены хоста, которые
 * читают секции, подняты `private` -> `internal`.
 */

internal fun TvChatActivity.sendMessage(): Boolean {
    val text = input.text.toString().trim()
    // Blocked while a turn is live: the local stream is active, recovery is reattaching,
    // OR a restored binding still arms the stop control (technical recreation).
    if (text.isEmpty() || stream?.isActive == true || recovery?.isActive == true || sessionFlow.canStop) return false
    val credential = store.read() ?: return false
    input.text.clear()
    append("Вы", text, isUser = true)
    val answer = appendLivePlaceholder()
    // An outgoing message re-pins: the user always sees their own turn and the
    // answer that streams after it, whatever they read before.
    pinAndScrollToBottom()
    // Per-turn answer state machine: the «Марс: » placeholder is dropped exactly once
    // (first chunk), chunks concatenate verbatim, and the FIRST terminal event (Done/Error)
    // locks the bubble — late frames can no longer clobber the outcome. A new turn builds
    // a fresh accumulator, so the next answer streams normally.
    liveAnswer = answer
    liveAccumulator = TvAnswerAccumulator(answer.text.toString())
    cancelLiveFlush(applyPending = false)
    lastLiveRenderMs = SystemClock.uptimeMillis()
    // A fresh user turn invalidates any stale local stream work and starts the resume
    // cursor + recovery budget from zero (a cursor of the previous stream must never
    // leak into a new Last-Event-ID header).
    cancelLocalStreamJobs()
    resumeTracker.reset()
    recoveryCoordinator.reset()
    stopping = false
    explicitStatus = null
    lastLiveEvent = null
    sessionFlow.beginTurn()
    renderSendControl()
    renderStatus()
    // Выбор модели для этого хода фиксируется ДО запуска потока (collect читает его
    // один раз на chat/start — смена чипа посреди хода на текущий ход не влияет).
    val choice = modelSelection.wireChoice()
    stream = lifecycleScope.launch {
        val generation = streamGeneration
        try {
            // Preflight: the server never creates sessions — a null id must not
            // reach chat/start; the flow reuses the bound id or mints a new one.
            when (val ensured = sessionFlow.ensureSession(credential)) {
                is TvSessionResult.Failed -> {
                    answer.text = "Сессия не создана: ${ensured.message}"
                    return@launch
                }
                is TvSessionResult.Ready -> renderStatus()
            }
            val outcome = collectTurn(
                ChatRepository().sendMessage(
                    TvChatActivity.BASE_URL, credential, text, sessionFlow.sessionId,
                    // Параметры хода (паритет с мобильным chat/start): модель из выбора
                    // чипа; explicitPick=true только при ЯВНОМ выборе пользователя —
                    // сервер фиксирует осознанный выбор и не «чинит» сессию обратно
                    // к сохранённому дефолту. Null-выбор = старт без модельных полей.
                    model = choice?.modelId,
                    modelProvider = choice?.providerId,
                    explicitModelPick = choice?.explicitPick == true,
                    // Side-канал хода: индикатор контекста (context_status).
                    onSideEvent = sideEventCallback(sessionFlow.sessionId, generation),
                    onEventId = { resumeTracker.onFrameId(it) },
                ),
                generation,
            )
            finishTurn(generation, outcome)
        } finally {
            // Stream finished/cancelled: prompts cannot outlive their stream (Done/Error
            // already dismissed via the controller; this covers abnormal termination).
            // Dismissed cards are re-shown by a recovery reattach when the server
            // replays the still-pending approval/clarify from its journal.
            if (interactiveReady) interactive.dismissAll()
            if (!stopping) {
                // An explicit «Остановить» teardown owns the final render (stopCurrentTurn).
                sessionFlow.endTurn()
                renderStatus()
                renderSendControl()
                input.requestFocus()
            }
        }
    }
    return true
}

/** One consumed local stream's outcome (fresh send or a reattach). */
internal class TurnOutcome(
    val cancelled: Boolean,
    val sawDone: Boolean,
    val sawGone: Boolean,
    val errorMessage: String?,
    val streamId: String?,
)

/**
 * Consumes [flow] through the shared event pipeline ([handleStreamEvent]) and reports
 * why it ended. Never throws: a cancellation is reported as [TurnOutcome.cancelled];
 * transport failures arrive as `ChatEvent.Error` from core-network (its resilience
 * contract maps every IOException onto an event instead of a thrown exception).
 */
internal suspend fun TvChatActivity.collectTurn(flow: Flow<ChatEvent>, generation: Int): TurnOutcome {
    var sawDone = false
    var sawGone = false
    var errorMessage: String? = null
    var streamId: String? = null
    try {
        flow.collect { event ->
            when (event) {
                is ChatEvent.StreamStarted -> streamId = event.streamId
                is ChatEvent.Done -> sawDone = true
                is ChatEvent.Error -> {
                    errorMessage = event.message
                    if (TvStreamReattachPolicy.outcomeOf(event) == TvReattachEventOutcome.StreamGone) {
                        sawGone = true
                    }
                }
                else -> Unit
            }
            handleStreamEvent(event, generation)
        }
    } catch (cancelled: CancellationException) {
        return TurnOutcome(true, sawDone, sawGone, errorMessage, streamId)
    }
    return TurnOutcome(false, sawDone, sawGone, errorMessage, streamId)
}

/**
 * Shared per-event pipeline of EVERY stream source (a fresh chat/start, the reattach
 * on session open, the recovery loop's reattaches): session state, interactive gates
 * and the answer bubble. Events of a stale generation are dropped — a late callback
 * of a dead stream must never mutate the transcript, the status strip or re-show
 * cards after the user navigated/stopped.
 */
internal fun TvChatActivity.handleStreamEvent(event: ChatEvent, generation: Int) {
    if (generation != streamGeneration) return
    sessionFlow.onEvent(event)
    // Событие хода снимает явный статус (steer/смена режима) и питает политику «Тишины».
    explicitStatus = null
    lastLiveEvent = event
    // Interactive gates are handled explicitly — they must never fall into
    // a silent `else -> Unit` (P0: the turn would hang with no way to answer).
    if (interactiveReady) interactive.onEvent(event)
    // Режим «Всё»: мысли и инструменты дополнительно дублируются строками в ленту.
    TvActivityDisplayPolicy.transcriptLine(activityMode.current, event)?.let { line ->
        appendActivityLine(line)
        scrollDown()
    }
    when (event) {
        // Terminal lock lives in the accumulator (first Done/Error wins):
        // a late chunk after Error no longer clobbers «Ошибка: …», a late
        // chunk after Done no longer mutates the finished answer, and a
        // tail Error after Done no longer overwrites it. null = ignore.
        // Отрисовка батчится (TvLiveRenderBatchPolicy): не чаще одного обновления
        // текста в интервал — частота чанков не превращается в частоту релayout'ов.
        // Зона ответа: сюда доезжают и токены финального ответа, и промежуточная
        // реплика `interim_assistant` — транспорт отдаёт её тем же [ChatEvent.Chunk]
        // (`StreamEventMapper.interimAssistant`, дубль с `already_streamed=true`
        // отбрасывается транспортом), поэтому мобильный контракт переиспользуется
        // как есть: текст копится в том же пузыре, а [ChatEvent.Done] фиксирует его
        // финальным (`finalizeLiveAnswerRich`) — промежуточная реплика не остаётся
        // отдельным залипшим пузырём.
        is ChatEvent.Chunk -> {
            val accumulator = liveAccumulator
            val rendered = accumulator?.onChunk(event.text)
            if (rendered != null) {
                onLiveAnswerText(rendered)
            } else {
                // Некуда положить текст — это НЕ норма: пузырь уже терминален
                // (Done/Error) либо лента перезагружена. Раньше кадр исчезал молча.
                Log.d(
                    TvSseFrameTrace.TAG,
                    TvSseFrameTrace.droppedChunk(accumulator != null, event.text.length),
                )
            }
        }
        is ChatEvent.Error -> {
            // Недорисованный текст отбрасывается: ошибка заменяет пузырь ЦЕЛИКОМ.
            cancelLiveFlush(applyPending = false)
            liveAccumulator?.onError(event.message)?.let { liveAnswer?.text = it }
            renderSendControl() // terminal event disarms «Остановить»
        }
        is ChatEvent.Done -> {
            // Терминал вне очереди: хвостовой чанк доезжает в пузырь до фиксации.
            cancelLiveFlush(applyPending = true)
            if (liveAccumulator?.onDone() == true) finalizeLiveAnswerRich()
            renderSendControl()
        }
        // Уточнение, которое агент не успел прочитать: текст возвращается в поле
        // ввода (мобильный restoreSteerText паритет) — правка не должна теряться.
        is ChatEvent.SteerLeftover -> restoreSteerText(event.text)
        is ChatEvent.ApprovalRequired -> scrollDown() // card rendered by the surface
        is ChatEvent.ClarifyRequired -> scrollDown()
        // A reattached/recovered turn arms «Остановить» only when its StreamStarted lands.
        // Сервер сообщил фактическую модель хода — привязываем её к сессии (чип «Модель»).
        is ChatEvent.StreamStarted -> {
            bindTurnModel(event)
            renderSendControl()
        }
        // Незнакомое событие больше НЕ глотается молча: тип уходит в logcat
        // на debug-уровне, поведение UI не меняется ([TvSseFrameTrace]).
        else -> TvSseFrameTrace.unhandledChatEvent(event)?.let { line ->
            Log.d(TvSseFrameTrace.TAG, line)
        }
    }
    renderStatus()
}

/**
 * Post-stream verdict (the gate is pure — [TvStreamReattachPolicy.shouldAttemptRecovery]):
 * - a delivered `Done` ends everything: a finished turn NEVER reconnects;
 * - an expired journal (anchored 404) resolves to ONE live history reload;
 * - a transport-class error or a silent end starts the single bounded recovery job
 *   (offline parking + backoff + `Last-Event-ID` reattach — never a chat/start re-POST);
 * - a definitive server verdict (business/auth 4xx) stays terminal: the server already
 *   answered, so no reconnect may follow.
 */
internal fun TvChatActivity.finishTurn(generation: Int, outcome: TurnOutcome) {
    if (outcome.cancelled || stopping || generation != streamGeneration) return
    val sessionId = sessionFlow.sessionId ?: return
    when {
        outcome.sawDone -> Unit
        outcome.sawGone -> reloadHistoryAfterGone(sessionId, generation)
        TvStreamReattachPolicy.shouldAttemptRecovery(outcome.errorMessage) ->
            scheduleStreamRecovery(sessionId, outcome.streamId, outcome.errorMessage, generation)
    }
}

/** «Новый чат» from the header: cancels the live server turn, aborts the stream, resets session + transcript. */
internal fun TvChatActivity.startNewChat() {
    // Any in-flight browser fetch or session load is stale from this point on.
    sessionLoadToken++
    closeSessionBrowser()
    // The session is dropped for good — cancel the server turn first (best-effort) so the
    // agent does not keep running for a transcript nobody will ever see again.
    cancelServerTurnBestEffort()
    // Explicit local teardown: kills the stream AND the recovery job, and invalidates
    // the generation so late callbacks of both are dropped.
    cancelLocalStreamJobs()
    resumeTracker.reset()
    recoveryCoordinator.reset()
    stopping = false
    // Cards of the dead stream go first (invalidates in-flight responds), then the transcript.
    if (interactiveReady) interactive.dismissAll()
    sessionFlow.startNewChat()
    // У нового диалога серверной модели пока нет: чип откатывается к локальному
    // выбору (персистентному) до первого StreamStarted нового хода, лестница
    // размышлений перечитывается — session-override прежнего диалога снят.
    modelSelection.clearSessionModel()
    renderModelChip()
    refreshReasoningStatus()
    clearTranscript()
    pinAndScrollToBottom()
    renderSendControl()
    renderStatus()
    input.text.clear()
    input.requestFocus()
}

/**
 * «Остановить»: cancels the RUNNING SERVER-SIDE turn (mobile cancelStream parity), then
 * tears the local stream down. Dropping only the SSE connection would leave the agent
 * executing invisibly — burning tokens, running commands, or blocked forever on an
 * approval card until the server timeout. The status line reports honestly whether the
 * server confirmed the cancel ([TvChatSessionFlow.markTurnStopped]).
 */
internal fun TvChatActivity.stopCurrentTurn() {
    if (!sessionFlow.canStop || stopping) return
    // Captured up front: startNewChat racing in must not change what we cancel.
    val streamId = sessionFlow.activeStreamId
    stopping = true
    send.isEnabled = false
    statusView.text = TvChatActivity.STOPPING
    statusView.visibility = View.VISIBLE
    // An explicit stop must also kill the recovery job — otherwise it would reattach
    // the very turn the user just asked to cancel.
    recovery?.cancel()
    recovery = null
    lifecycleScope.launch {
        val serverCancelled = streamId?.let { id ->
            withContext(Dispatchers.IO) { controlGateway.cancelStream(id) }
        } ?: false
        stream?.cancel()
        stream?.join()
        stream = null
        stopping = false
        // A «Новый чат» pressed mid-stop owns the screen now — its reset must not be clobbered.
        if (sessionFlow.canStop) {
            sessionFlow.markTurnStopped(serverCancelled)
            renderStatus()
            renderSendControl()
            input.requestFocus()
        }
    }
}

/**
 * Диспетчер основной кнопки (чистое решение — [TvSteerPolicy.actionFor]): живой ход +
 * текст в композиторе -> steer («Уточнить»), живой ход + пустой композитор -> «Остановить»,
 * иначе обычная отправка. IME-«отправить» идёт через тот же диспетчер.
 */
internal fun TvChatActivity.dispatchSend() {
    when (TvSteerPolicy.actionFor(sessionFlow.canStop, input.text.isNullOrBlank())) {
        TvSteerPolicy.SendAction.STEER -> steerCurrentTurn()
        TvSteerPolicy.SendAction.STOP -> stopCurrentTurn()
        TvSteerPolicy.SendAction.SEND -> sendMessage()
    }
}

/**
 * Steer («уточнение поверх активного хода», мобильный `steerMessage` паритет): POST
 * /api/chat/steer НЕ отменяет и не перезапускает ход — агент дочитывает правку внутри
 * текущего стрима. Признак успеха — поле `accepted` ответа (его уже трактует
 * core-network `executeControl`); при отказе текст остаётся в поле ввода.
 */
internal fun TvChatActivity.steerCurrentTurn() {
    val text = input.text.toString().trim()
    if (text.isEmpty()) return
    val id = sessionFlow.sessionId
    if (id == null) {
        explicitStatus = TvSteerPolicy.STATUS_NO_SESSION
        renderStatus()
        return
    }
    send.isEnabled = false
    lifecycleScope.launch {
        val accepted = withContext(Dispatchers.IO) { controlGateway.steer(id, text) }
        if (accepted) {
            // Чистим поле только если пользователь не дописал текст, пока шёл запрос.
            if (input.text.toString().trim() == text) input.text.clear()
            explicitStatus = TvSteerPolicy.STATUS_ACCEPTED
        } else {
            explicitStatus = TvSteerPolicy.STATUS_REJECTED
        }
        renderStatus()
        renderSendControl()
    }
}

/**
 * Недоставленный steer (SSE `pending_steer_leftover` -> [ChatEvent.SteerLeftover]):
 * текст, который агент не успел прочитать, возвращается в поле ввода — правка не
 * теряется (мобильный `restoreSteerText` паритет); ход при этом НЕ отменяется.
 */
internal fun TvChatActivity.restoreSteerText(text: String) {
    if (TvSteerPolicy.canRestoreLeftover(text, input.text?.toString().orEmpty())) {
        input.setText(text)
    }
    explicitStatus = TvSteerPolicy.LEFTOVER_STATUS
    renderStatus()
    renderSendControl()
    input.requestFocus()
}

/**
 * Fire-and-forget cancel of the live server turn when the screen leaves the session
 * («Новый чат», BACK/onDestroy). Runs on [TvChatActivity.applicationScope] so it survives Activity
 * teardown; the outcome is intentionally not surfaced (the transcript is already gone).
 */
internal fun TvChatActivity.cancelServerTurnBestEffort() {
    if (!controlGatewayReady) return
    val streamId = sessionFlow.activeStreamId ?: return
    TvChatActivity.applicationScope.launch { controlGateway.cancelStream(streamId) }
}

/**
 * The primary button mirrors the turn state (mobile parity): on a live turn an empty
 * composer gives «Остановить» + stop icon, a non-empty one gives «Уточнить» (steer);
 * idle it is «Отправить». It stays enabled and focusable in ALL states — a D-pad user
 * must never lose the ability to interrupt or steer a running turn.
 */
internal fun TvChatActivity.renderSendControl() {
    val action = TvSteerPolicy.actionFor(sessionFlow.canStop, input.text.isNullOrBlank())
    send.text = TvSteerPolicy.buttonLabel(action)
    send.setCompoundDrawablesRelativeWithIntrinsicBounds(
        if (TvSteerPolicy.buttonShowsStopIcon(action)) R.drawable.ic_stop else R.drawable.ic_send,
        0, 0, 0)
    send.compoundDrawableTintList = ContextCompat.getColorStateList(this, R.color.mars_on_accent)
    send.isEnabled = true
    renderChips()
}
