package ru.mars.mobile

import android.os.Bundle
import android.os.SystemClock
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.cache.SessionOfflineCache
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.SessionDetail
import ru.mars.network.repository.SessionRepository

/**
 * Загрузка и восстановление диалога: список сессий, открытие, reattach живого хода.
 * 
 * Вынесено из [ChatActivity] (W2) как internal-расширения: тело и поведение прежние,
 * Activity остаётся хостом эффектов (сеть, состояние, отрисовка).
 */

internal fun ChatActivity.loadSessions(onFinished: (() -> Unit)? = null) {
    val c = store.read() ?: run { onFinished?.invoke(); return }
    lifecycleScope.launch {
        try {
            val fresh = SessionRepository().getSessions(ChatActivity.BASE_URL, c)
            // Пустой ответ (сбой сети — репозиторий не бросает исключение, — или диалогов
            // действительно нет) сверяется с офлайн-кэшем: на сбое состояние уровня
            // держит последний сохранённый снимок вместо пустого списка.
            uiState.setSessions(withContext(Dispatchers.IO) { offlineCache.reconcile(fresh) })
        } finally {
            // Throttle обновления списка (защита от шторма) отмечает завершение полёта.
            onFinished?.invoke()
        }
    }
}

/**
 * Открывает диалог по id — клик по drawer'у или восстановление после пересоздания экрана.
 */

/**
 * Открывает диалог по id — клик по drawer'у или восстановление после пересоздания экрана.
 */
internal fun ChatActivity.loadSessionById(sessionId: String) {
    val c = store.read() ?: return
    // Черновик покидаемого диалога сохраняется до смены текущего session_id.
    persistComposerDraft()
    interactiveController.dismissAll()
    compressionController.onSessionChanged()
    // Токен навигации: устаревший callback (пользователь открыл другой диалог/новый чат
    // или закрыл сессию, пока грузились детали) не перетирает актуальное состояние.
    val token = sessionNavigation.beginNavigation()
    // Смена диалога локально убивает живой ход предыдущего ЦЕЛИКОМ: и collect send-пути,
    // и recovery-цикл. Иначе поздние SSE-кадры старого хода перекрасят только что открытый
    // диалог (чанки/SessionBound/диалоги подтверждения), а живый старый стрим заблокирует
    // reattachActiveTurn новой сессии. Серверный ход не прерывается: при возвращении в
    // диалог его подхватит reattach.
    teardownLiveTurn()
    lifecycleScope.launch {
        val detail = SessionRepository().getSessionDetail(ChatActivity.BASE_URL, c, sessionId)
        if (detail != null) {
            // Успешная загрузка обновляет офлайн-кэш транскрипта диалога.
            withContext(Dispatchers.IO) { offlineCache.saveMessages(detail.id, detail.messages) }
        }
        if (detail != null && sessionNavigation.isCurrent(token)) {
            // Текст, набранный ВО ВРЕМЯ загрузки, сохраняется в черновик старого диалога
            // непосредственно перед присвоением нового session_id.
            drafts.onSessionSwitch(currentSessionId, detail.id, input.text.toString())
            // O7: новый диалог — свой черновик; текст прежнего поля не «принадлежит» ему.
            composerGuard.onSessionSwitch()
            uiState.bindSession(detail.id, detail.title, historyMessages(detail))
            applySessionTitle(uiState.sessionTitle)
            modelSelection.setSessionModel(detail.model, detail.modelProvider)
            // A9: набор инструментов приходит вместе с деталью сессии — привязываем
            // серверное значение до отрисовки чипов, чтобы не мигнуть чужим набором.
            toolsetsPresenter.bind(detail.enabledToolsets)
            uiState.applyEnabledToolsets(detail.enabledToolsets)
            refreshComposerChips()
            refreshProviderQuota()
            currentSessionReadOnly = uiState.sessions.firstOrNull { it.id == detail.id }?.readOnly == true
            currentSessionIsSubagent = uiState.sessions.firstOrNull { it.id == detail.id }?.isSubagent == true
            modelBtn?.text = modelSelection.buttonLabel() ?: "Модель"
            // Active-turn/attention banner state from the freshly loaded detail.
            uiState.setActiveTurn(detail.activeTurn)
            uiState.setTodoState(detail.todoState)
            currentSessionId = detail.id
            // A15: окно дебаунса и «последний записанный» относятся к прежнему диалогу.
            serverDraftSync.onSessionChanged()
            restoreComposerDraft()
            // A15: серверная копия черновика дополняет локальную (и только если пользователь
            // не начал печатать своё после открытия диалога — решает [ServerDraftAdoption]).
            adoptServerDraft(detail.composerDraft)
            // A14: расход открытого диалога.
            sessionUsage = null
            refreshSessionUsage()
            autoScroll.pinToBottom()
            renderMessages()
            setStatus()
            updateTodoWidget()
            refreshAutonomyState()
            // Stream recovery: a session that is still streaming server-side must resume
            // live instead of showing a frozen transcript.
            reattachActiveTurn(detail.activeTurn)
            // Сообщения этого диалога из офлайн-очереди возвращают локальное эхо; если
            // сеть уже есть — сразу отправляются. Первичное восстановление завершено.
            echoPendingOfflineMessages()
            initialRestoreDone = true
            flushOfflineQueue()
        } else if (detail == null && sessionNavigation.isCurrent(token)) {
            showOfflineSessionCopy(sessionId)
        }
    }
}

/**
 * Сбой сети при загрузке деталей диалога: вместо пустого экрана показывается
 * сохранённая копия транскрипта из офлайн-кэша ([SessionOfflineCache]); строка
 * статуса явно говорит, что это сохранённые данные, а не живой диалог.
 */

/**
 * Сбой сети при загрузке деталей диалога: вместо пустого экрана показывается
 * сохранённая копия транскрипта из офлайн-кэша ([SessionOfflineCache]); строка
 * статуса явно говорит, что это сохранённые данные, а не живой диалог.
 */
internal suspend fun ChatActivity.showOfflineSessionCopy(sessionId: String) {
    // Флаг ставится даже без кэша: автофлаш очереди не должен блокироваться вечно.
    initialRestoreDone = true
    val cached = withContext(Dispatchers.IO) { offlineCache.loadMessages(sessionId) } ?: return
    drafts.onSessionSwitch(currentSessionId, sessionId, input.text.toString())
    composerGuard.onSessionSwitch()
    currentSessionReadOnly = uiState.sessions.firstOrNull { it.id == sessionId }?.readOnly == true
    currentSessionIsSubagent = uiState.sessions.firstOrNull { it.id == sessionId }?.isSubagent == true
    modelSelection.setSessionModel(
        uiState.sessions.firstOrNull { it.id == sessionId }?.model,
        uiState.sessions.firstOrNull { it.id == sessionId }?.modelProvider,
    )
    uiState.bindSession(
        sessionId,
        uiState.sessions.firstOrNull { it.id == sessionId }?.title,
        historyMessages(SessionDetail(sessionId, messages = cached)),
    )
    applySessionTitle(uiState.sessionTitle)
    // Кэш намеренно не хранит active_turn: копия загружается idle-состоянием.
    uiState.setActiveTurn(ActiveTurnInfo())
    currentSessionId = sessionId
    restoreComposerDraft()
    echoPendingOfflineMessages()
    autoScroll.pinToBottom()
    explicitStatus = ChatActivity.OFFLINE_SESSION_HINT
    renderMessages()
    setStatus()
    updateControlsFromSection()
    updateTodoWidget()
    flushOfflineQueue()
}

/**
 * Вход в уровень диалога: [EXTRA_OPEN_SESSION_ID] (открыть конкретный чат —
 * переход [NavTransition.OpenSession]) → savedInstanceState (пересоздание) →
 * холодный старт нового чата. При пересоздании чистая политика
 * [SessionRecreationPolicy] решает по savedInstanceState, открывать ли
 * сохранённый session_id заново. Загрузка идёт через общий guard
 * [sessionNavigation], а одноразовость политики исключает повторное
 * восстановление в этом же экземпляре экрана.
 */

/**
 * Вход в уровень диалога: [EXTRA_OPEN_SESSION_ID] (открыть конкретный чат —
 * переход [NavTransition.OpenSession]) → savedInstanceState (пересоздание) →
 * холодный старт нового чата. При пересоздании чистая политика
 * [SessionRecreationPolicy] решает по savedInstanceState, открывать ли
 * сохранённый session_id заново. Загрузка идёт через общий guard
 * [sessionNavigation], а одноразовость политики исключает повторное
 * восстановление в этом же экземпляре экрана.
 */
internal fun ChatActivity.restoreOpenSession(savedInstanceState: Bundle?) {
    val requested = intent?.getStringExtra(ChatActivity.EXTRA_OPEN_SESSION_ID)?.trim().orEmpty()
    val target = requested.ifEmpty { savedInstanceState?.getString(ChatActivity.KEY_OPEN_SESSION_ID) }
    val plan = recreationPolicy.planFor(target)
    if (plan is RecreationPlan.Reopen) {
        loadSessionById(plan.sessionId)
    } else {
        // Холодный старт — новый чат: эхо буферизованных сообщений нового чата и
        // разрешение автофлаша очереди (сам флаш guarded состоянием сети/хода).
        initialRestoreDone = true
        echoPendingOfflineMessages()
        flushOfflineQueue()
    }
}

/**
 * Повторный вход в уровень диалога (корень списка под стеком переиспользуется
 * флагами CLEAR_TOP|SINGLE_TOP): новый [EXTRA_OPEN_SESSION_ID] открывает
 * соответствующий чат; без extra экран остаётся как есть.
 */

/**
 * Reattaches to the session's live stream (`GET /api/chat/stream?stream_id=...`, no
 * `POST /api/chat/start`) when [activeTurn] says one exists — the server replays journaled
 * events missed while the app was closed and continues live. A terminal 404 means the turn
 * settled between the detail fetch and the attach: the placeholder bubble is dropped and the
 * settled history is reloaded instead of surfacing an error.
 */
internal fun ChatActivity.reattachActiveTurn(activeTurn: ActiveTurnInfo) {
    val plan = reattachPolicy.planFor(activeTurn) as? ReattachPlan.Attach ?: return
    if (stream?.isActive == true) return
    if (!isRecoveryReady() || recovery.isRecovering) return
    store.read() ?: return // транспорт recovery читает credential на каждой попытке attach
    if (!uiState.beginReattachedTurn()) return
    autoScroll.pinToBottom()
    currentStreamId = plan.streamId
    explicitStatus = ChatActivity.REATTACH_STATUS
    renderMessages()
    setStatus()
    updateControlsFromSection()
    // Recovery-цикл (MidTurnRecoveryController) владеет всем attach-жизненным циклом:
    // единственный job, backoff/офлайн-парковка, resume по Last-Event-ID, транспортный
    // 404 (стрим умер серверно) → ReloadSession с обновлением истории, терминальный
    // Done не ретраится, серверные tool-ошибки идут в транскрипт и завершают цикл без
    // reconnect'а, 401 → GiveUp(auth) → requestReauthentication (см. RecoveryListener).
    stream = recovery.attachActiveTurn(plan.streamId)
}

/** Завершение recovery-цикла: статус/контролы/офлайн-очередь — паритет с finally прежнего attach. */
internal fun ChatActivity.finishRecoveredTurn(speakFinalAnswer: Boolean) {
    clearRecoveryStatus()
    if (uiState.status == ChatStatus.TYPING) uiState.complete()
    // Восстановленный ход завершился успешно — голосовой режим озвучивает финальный
    // ответ; ход, умерший серверной ошибкой, не озвучивается.
    if (speakFinalAnswer && isVoiceOutputReady()) {
        voiceOutput.onTurnComplete(latestAssistantAnswer())
    }
    onVoiceTurnCompleted()
    renderMessages()
    setStatus()
    updateControlsFromSection()
    flushOfflineQueue()
    // A14: восстановленный ход тоже потратил токены — счётчик перечитывается.
    refreshSessionUsage()
}

/** Снимает статусную строку, только если её поставил recovery-цикл (не пользователь/сервер). */

/** Снимает статусную строку, только если её поставил recovery-цикл (не пользователь/сервер). */
internal fun ChatActivity.clearRecoveryStatus() {
    val status = explicitStatus ?: return
    if (status == ChatActivity.REATTACH_STATUS || status == ChatActivity.STREAM_PARKED_OFFLINE_STATUS ||
        status.startsWith(ChatActivity.STREAM_RECONNECT_STATUS)
    ) {
        explicitStatus = null
    }
}

// --- Обновление списка сессий на восстановлении сети (защита от шторма) -----------------------

// --- Обновление списка сессий на восстановлении сети (защита от шторма) -----------------------

internal fun ChatActivity.requestSessionListRefresh() {
    when (val decision = sessionRefreshThrottle.onTrigger(SystemClock.elapsedRealtime())) {
        SessionRefreshDecision.Now -> runThrottledSessionRefresh()
        is SessionRefreshDecision.Later -> {
            sessionRefreshJob?.cancel()
            sessionRefreshJob = lifecycleScope.launch {
                delay(decision.delayMs)
                runThrottledSessionRefresh()
            }
        }
        SessionRefreshDecision.Skip -> Unit
    }
}

internal fun ChatActivity.runThrottledSessionRefresh() {
    sessionRefreshThrottle.onStarted()
    loadSessions {
        // Триггеры, пришедшие во время полёта, схлопываются в один догоняющий refresh.
        if (sessionRefreshThrottle.onFinished(SystemClock.elapsedRealtime())) runThrottledSessionRefresh()
    }
}

/** Maps a persisted session transcript to bubbles (shared by loadSession and post-compression reload). */

/** Maps a persisted session transcript to bubbles (shared by loadSession and post-compression reload). */
internal fun ChatActivity.historyMessages(detail: SessionDetail): List<ChatMessage> =
    // Чистая HistoryTranscriptPolicy: user/assistant пузыри с серверными метками,
    // служебные role:"tool" строки сворачиваются в карточки на assistant-якоре и
    // НИКОГДА не рендерятся текстом (WebUI parity); прочие роли не показываются.
    HistoryTranscriptPolicy.of(detail.messages)

/**
 * Re-fetches the transcript of [currentSessionId] after compression replaced the server-side
 * history (`/api/session/compress` swaps `context_messages`); the compressed transcript is
 * what the next turn continues from, so the bubbles must show the same view.
 */

/**
 * Re-fetches the transcript of [currentSessionId] after compression replaced the server-side
 * history (`/api/session/compress` swaps `context_messages`); the compressed transcript is
 * what the next turn continues from, so the bubbles must show the same view.
 */
internal fun ChatActivity.reloadCurrentSession() {
    val c = store.read() ?: return
    val id = currentSessionId ?: return
    lifecycleScope.launch {
        val detail = SessionRepository().getSessionDetail(ChatActivity.BASE_URL, c, id)
        if (detail != null && currentSessionId == id) {
            uiState.bindSession(detail.id, detail.title, historyMessages(detail))
            applySessionTitle(uiState.sessionTitle)
            modelSelection.setSessionModel(detail.model, detail.modelProvider)
            // A9: набор инструментов приходит вместе с деталью сессии — привязываем
            // серверное значение до отрисовки чипов, чтобы не мигнуть чужим набором.
            toolsetsPresenter.bind(detail.enabledToolsets)
            uiState.applyEnabledToolsets(detail.enabledToolsets)
            refreshComposerChips()
            refreshProviderQuota()
            currentSessionReadOnly = uiState.sessions.firstOrNull { it.id == detail.id }?.readOnly == true
            currentSessionIsSubagent = uiState.sessions.firstOrNull { it.id == detail.id }?.isSubagent == true
            modelBtn?.text = modelSelection.buttonLabel() ?: "Модель"
            uiState.setActiveTurn(detail.activeTurn)
            uiState.setTodoState(detail.todoState)
            autoScroll.pinToBottom()
            renderMessages()
            updateTodoWidget()
            refreshAutonomyState()
        }
    }
}
