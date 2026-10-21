package ru.mars.mobile

import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.model.ChatAttachment
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.repository.ChatRepository

/**
 * Отправка хода и стрим: маршрут тапа, запуск хода, steer/cancel и офлайн-очередь.
 * 
 * Вынесено из [ChatActivity] при разбиении файла (W2). Решения, которые раньше были
 * inline-ветвлениями, живут в чистых политиках [ChatTurnDispatcher] и
 * [ChatOfflineQueuePolicy] (без Android, покрыты unit-тестами); здесь остались только
 * эффекты — сеть, корутина хода, статусы и отрисовка. Activity остаётся хостом: функции
 * ниже — её internal-расширения, поэтому вызовы из Activity не меняются.
 */

internal fun ChatActivity.sendMessage() {
    if (currentSessionReadOnly) {
        statusView.text = "Сессия доступна только для чтения"
        return
    }
    if (uiState.status == ChatStatus.TYPING) { steerMessage(); return }
    val rawInput = input.text.toString().trim()
    val text = composerLayoutPresenter.outgoingText(rawInput)
    val outgoingAttachments = attachments.toList()
    // Команда `/goal` (O14) обслуживается локально: в модель как промпт не уходит,
    // иначе сервер просто ответил бы текстом, а цель не изменилась бы.
    if (GoalLoopPolicy.isCommand(text)) {
        handleGoalCommand(text)
        return
    }
    // Офлайн-буферизация (OfflineMessageQueuePolicy): без сети обычный текст уходит в
    // персистентную очередь с локальным эхом и будет отправлен автоматически после
    // восстановления связи. Вложения не буферизуются — multipart-загрузку офлайн не
    // повторить, чипы остаются в композиторе до восстановления.
    if (!networkPolicy.state.sendAllowed) {
        if (outgoingAttachments.isNotEmpty()) {
            explicitStatus = ChatActivity.OFFLINE_ATTACHMENT_STATUS
            setStatus()
            return
        }
        when (val decision = offlineQueue.onSendRequested(canSend = false, text = text, sessionId = currentSessionId)) {
            is SendDecision.Buffer -> { onMessageBuffered(decision.message); return }
            else -> return // Пустой текст — отправлять/буферизовать нечего
        }
    }
    if (!uiState.begin(text, outgoingAttachments.map { it.name })) return
    attachments.clear()
    renderAttachmentChips()
    composerLayoutPresenter.clearQuote()
    updateQuotePlate()
    val credential = store.read() ?: run { uiState.cancel(); leaveChat(); return }
    hideKeyboard()
    input.text.clear()
    // O7: композер очищен отправкой — поле снова ничьё, следующий черновик применим.
    composerGuard.onSendReset()
    // Отправка принята локально (текст ушёл в поток) — черновик диалога очищается.
    drafts.onSendAccepted(currentSessionId)
    explicitStatus = null
    // Own outgoing message always takes the reader to the latest.
    autoScroll.pinToBottom()
    renderMessages()
    updateControlsFromSection()
    stream = startSendStream(text, outgoingAttachments, currentSessionId, credential)
}

/**
 * Общая корутина отправки для прямого send и флаша офлайн-очереди (раньше — inline
 * в [sendMessage]). [onTurnFailed] вызывается, только если сообщение НЕ дошло до
 * сервера (сессия не создана / стрим не стартовал / ошибка до первого события):
 * очередь возвращает такое сообщение на повтор, а доставленное не дублирует.
 */

/**
 * Общая корутина отправки для прямого send и флаша офлайн-очереди (раньше — inline
 * в [sendMessage]). [onTurnFailed] вызывается, только если сообщение НЕ дошло до
 * сервера (сессия не создана / стрим не стартовал / ошибка до первого события):
 * очередь возвращает такое сообщение на повтор, а доставленное не дублирует.
 */
internal fun ChatActivity.startSendStream(
    text: String,
    outgoingAttachments: List<ChatAttachment>,
    targetSessionId: String?,
    credential: ConsumedDeviceCredential,
    onTurnFailed: (() -> Unit)? = null,
    /** Ход доставлен и завершился без провала: очередь снимает строку (O8). */
    onTurnSucceeded: (() -> Unit)? = null,
): Job {
    // Новый пользовательский ход: предыдущий recovery-job прерывается (generation
    // guard), курсор Last-Event-ID и серии отказов координатора сбрасываются.
    if (isRecoveryReady()) recovery.onNewTurn()
    // Новый ход: озвучка предыдущего финального ответа обрывается, не перетекая в новый.
    if (isVoiceOutputReady()) voiceOutput.onNewTurn()
    // Решение об озвучке фиксируется здесь: все дельты хода пойдут в потоковую озвучку
    // (или ни одна), а терминальный Done этот ответ уже не озвучит повторно.
    if (isStreamSpeechReady()) beginStreamSpeech()
    // Токен выдаётся синхронно с регистрацией хода: любой последующий уход с контекста
    // (teardownLiveTurn) гасит его — поздние SSE-кадры этого collect не перекрасят новый экран.
    val turnToken = liveTurnGuard.beginTurn()
    return lifecycleScope.launch {
    var credentialRejected = false
    var turnFailed = false
    var delivered = false
    // Итоги первичного collect для передачи хода в recovery-цикл: терминальный Done
    // (никогда не ретраится), серверная app/tool-ошибка (терминальна для хода) и
    // подавленная ТРАНСПОРТНАЯ ошибка обрыва (recoverable — в транскрипт не пишется).
    var sawDone = false
    var sawAppError = false
    var suppressedTransportError: ChatEvent.Error? = null
    try {
        if (targetSessionId == null) {
            explicitStatus = "Создаю сессию…"
            setStatus()
        }
        when (val ensured = firstSessionFlow.ensureSession(targetSessionId, credential)) {
            is EnsureSessionResult.Ready -> {
                currentSessionId = ensured.sessionId
                if (targetSessionId == null) {
                    modelSelection.setSessionModel(ensured.model, ensured.modelProvider)
                    modelBtn?.text = modelSelection.buttonLabel() ?: "Модель"
                }
                explicitStatus = null
                // Первая отправка нового чата создала сессию: остальные сообщения
                // офлайн-всплеска (ключ null) перепривязываются к ней — продолжают
                // тот же диалог вместо плодения отдельных сессий.
                if (targetSessionId == null && isOfflineQueueReady()) {
                    offlineQueue.adoptSession(null, ensured.sessionId)
                }
            }
            is EnsureSessionResult.Failed -> {
                uiState.cancel()
                turnFailed = true
                // A 401 here means the stored credential is dead server-side: route
                // to login instead of leaving the user retrying the same failure.
                if (AuthRejectionPolicy.isCredentialRejected(ensured.message)) {
                    credentialRejected = true
                    requestReauthentication()
                } else {
                    explicitStatus = "Сессия не создана: ${ensured.message}"
                }
                return@launch
            }
        }
        val choice = modelSelection.displayedChoice()
        // WebUI parity: the wire message carries the `[Workspace::v1: <path>]`
        // sentinel; the transcript above keeps showing the raw text.
        val wireText = workspaceSelection.outgoingMessage(text)
        ChatRepository().sendMessage(
            ChatActivity.BASE_URL, credential, wireText, currentSessionId, outgoingAttachments,
            model = choice?.modelId,
            modelProvider = choice?.providerId,
            explicitModelPick = choice?.explicitPick == true,
            onTodoState = todoCallback(currentSessionId),
            onSideEvent = sideEventCallback(currentSessionId),
            // Курсор Last-Event-ID продвигается на КАЖДОМ id-кадре (до маппинга),
            // чтобы оборвавшийся доставленный ход восстанавливался без потерь.
            onEventId = { id -> if (isRecoveryReady()) recovery.resumeTracker.onFrameId(id) },
        ).collect { event ->
            // Смена контекста убила ход (переключение диалога/«новый чат»/«стоп»/logout):
            // поздние кадры структурно не допускаются в новый экран — collect сразу
            // завершается, а finally видит отменённый job и не запускает автофлаш очереди.
            if (!liveTurnGuard.isCurrent(turnToken)) throw CancellationException("live turn superseded")
            // Любое не-ошибочное событие доказывает: сервер принял запрос, сообщение
            // доставлено. Ошибка ДО доставки (HTTP-отказ вместо стрима) — turnFailed.
            if (event !is ChatEvent.Error) delivered = true
            if (event is ChatEvent.Done) sawDone = true
            // Транспортный обрыв ПОСЛЕ доставки: не пишем «Ошибка: …» в пузырь и не
            // роняем статус в ERROR — ход жив на сервере, его подхватит recovery-цикл
            // через attachToStream. Ошибка всплывёт, только если восстановление
            // окончательно провалится (Listener.onGiveUp). 401 не подавляется: это
            // существующий путь принудительной реавторизации. Серверные tool/app-ошибки
            // (журнал хода) классификатором НЕ подавляются и проходят в транскрипт.
            val suppressAsTransportDrop = event is ChatEvent.Error && delivered &&
                currentStreamId != null &&
                transportFailureClassifier.isTransportShaped(event.message) &&
                !AuthRejectionPolicy.isCredentialRejected(event.message)
            if (suppressAsTransportDrop) {
                suppressedTransportError = event as ChatEvent.Error
                explicitStatus = ChatActivity.STREAM_RECONNECT_STATUS
            } else {
                uiState.onEvent(event)
                // Дельта текста ассистента: потоковая озвучка получает её сразу, поэтому
                // первый звук не ждёт конца хода. Решение «звучит ли дельта» — в
                // VoiceTurnSpeechPolicy; размышления идут отдельным событием ниже.
                if (event is ChatEvent.Chunk && isStreamSpeechReady()) onAssistantDelta(event.text)
                // Мысли — черновик модели, в динамик не попадают никогда (и это не «фильтр
                // на всякий случай»: в живом ходе Thought идёт между Chunk-ами ответа).
                if (event is ChatEvent.Thought) onAssistantThought(event.text)
                // Вызов инструмента: одна короткая «Проверяю…» на ход, без имени инструмента
                // и без результата (результата ещё нет — придумывать его нельзя).
                if (event is ChatEvent.ToolCall) announceToolProgress(event.name)
                // Терминальный Done: ход завершён — дочитываем остаток потоковой озвучки и
                // НЕ читаем финал повторно, если ответ уже звучал.
                if (event is ChatEvent.Done) finishVoiceTurn(speakFinal = true)
                if (event is ChatEvent.Error) {
                    sawAppError = true
                    if (AuthRejectionPolicy.isCredentialRejected(event.message)) credentialRejected = true
                    if (!delivered) turnFailed = true
                }
                if (event is ChatEvent.SessionBound) {
                    currentSessionId = event.sessionId
                    refreshAutonomyState()
                }
                if (event is ChatEvent.StreamStarted) currentStreamId = event.streamId
                if (event is ChatEvent.ContextUsage) updateContextIndicator()
                if (event is ChatEvent.SteerLeftover) restoreSteerText(event.text)
                interactiveController.onEvent(event)
                if (isCompressionControllerReady()) compressionController.onEvent(event)
                // ANR-фикс: НЕ на каждый токен — троттлинг кадров стрима (StreamRenderThrottle).
                // Терминальные события (Done/Error → status != TYPING) рисуются немедленно внутри.
                scheduleStreamRender()
                setStatus()
            }
        }
        if (credentialRejected) { requestReauthentication(); return@launch }
        // Доставленный ход оборвался без терминального Done → ЕДИНСТВЕННЫЙ recovery-job:
        // только attach c Last-Event-ID, никогда повторный POST chat/start и никогда
        // повторная отправка сообщения. Серверная ошибка, закрывшая ход (app-ошибка без
        // транспортного сбоя), — терминальна: reconnect лишь отреплеил бы её же.
        val recoveryStreamId = currentStreamId
        val appErrorTerminal = sawAppError && suppressedTransportError == null
        if (delivered && !sawDone && !appErrorTerminal && recoveryStreamId != null &&
            isRecoveryReady()
        ) {
            explicitStatus = ChatActivity.STREAM_RECONNECT_STATUS
            stream = recovery.beginRecoveryAfterDrop(recoveryStreamId, suppressedTransportError)
            return@launch
        }
        if (uiState.status == ChatStatus.TYPING) {
            uiState.complete()
            // Стрим закрылся без терминального Done и без ухода в recovery: ход завершён
            // здесь — голосовой режим озвучивает финальный ответ (Done-путь уже озвучил).
            // Тот же finishVoiceTurn, что и на Done: два пути к концу хода не должны
            // расходиться в том, читать финал или нет.
            finishVoiceTurn(speakFinal = true)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        if (!delivered) turnFailed = true
        uiState.fail()
        if (credentialRejected) { requestReauthentication(); return@launch }
    } finally {
        renderMessages()
        setStatus()
        updateControlsFromSection()
        updateNetworkBanner()
        // Отменённый пользователем стрим (кнопка «стоп», выход с экрана) автофлаш НЕ
        // запускает: осознанная остановка не должна продолжать очередь — остальные
        // сообщения ждут следующего триггера (переход сети, открытие диалога, отправка).
        if (coroutineContext[Job]?.isCancelled != true) {
            if (turnFailed && !credentialRejected) onTurnFailed?.invoke()
            if (!turnFailed && !credentialRejected) onTurnSucceeded?.invoke()
            flushOfflineQueue()
            // A14: расход сессии меняется только ходами — обновляем счётчик по завершении.
            refreshSessionUsage()
        }
    }
    }
}

// --- Сеть и офлайн-очередь -------------------------------------------------------------------

/** Обработчик перехода сети: баннер обновляется всегда, восстановление связи запускает флаш. */

/**
 * Автоотправка буферизованных сообщений ТЕКУЩЕГО диалога (FIFO, по одному; следующее
 * диспетчеризуется из finally предыдущего хода). Не выполняется до первичного
 * восстановления диалога ([initialRestoreDone]), без сети и во время активного хода.
 */
internal fun ChatActivity.flushOfflineQueue() {
    if (!initialRestoreDone || !isOfflineQueueReady()) return
    if (!networkPolicy.state.sendAllowed) return
    if (uiState.status == ChatStatus.TYPING) return
    val queued = offlineQueue.nextFor(currentSessionId) ?: run { updateNetworkBanner(); return }
    dispatchQueuedMessage(queued)
}

/** Флаш одного сообщения очереди: PENDING-эхо становится обычным ходом; недоставка — обратно в очередь. */

/** Флаш одного сообщения очереди: PENDING-эхо становится обычным ходом; недоставка — обратно в очередь. */
internal fun ChatActivity.dispatchQueuedMessage(queued: QueuedMessage) {
    // Нет креденшелов (logout/реавторизация): отправлять некому. Сообщение сбрасывается
    // БЕЗ persist — иначе requeue перезапишет хранилище поверх logout-cleanup.
    val credential = store.read() ?: return
    if (!uiState.beginQueuedSend(queued.text)) {
        offlineQueue.requeue(queued)
        return
    }
    explicitStatus = null
    autoScroll.pinToBottom()
    renderMessages()
    setStatus()
    updateControlsFromSection()
    updateNetworkBanner()
    // Строка карточки очереди переходит в SENDING (OfflineQueuePresenter).
    offlineQueuePresenter.onSendStarted(queued.id)
    updateOfflineQueuePanel()
    stream = startSendStream(
        text = queued.text,
        outgoingAttachments = emptyList(),
        targetSessionId = queued.sessionId,
        credential = credential,
        onTurnFailed = {
            // Сессия могла создаться до сбоя потока: возврат в очередь под актуальным
            // ключом, иначе сообщение застрянет под null и не дождётся флаша диалога.
            val retry = queued.copy(sessionId = currentSessionId ?: queued.sessionId)
            if (!offlineQueue.requeue(retry)) {
                explicitStatus = ChatActivity.MESSAGE_NOT_DELIVERED_STATUS
                setStatus()
                // Лимит попыток исчерпан: ход переезжает в карточку с ручным
                // повтором и удалением, а не теряется в FIFO.
                offlineQueuePresenter.onTurnFailed(retry)
            } else {
                offlineQueuePresenter.onSendSucceeded(queued.id)
            }
            updateOfflineQueuePanel()
        },
        onTurnSucceeded = {
            offlineQueuePresenter.onSendSucceeded(queued.id)
            updateOfflineQueuePanel()
        },
    )
}

/** Сообщение ушло в очередь: локальное PENDING-эхо в транскрипте, композитор чист, баннер со счётчиком. */

/** Сообщение ушло в очередь: локальное PENDING-эхо в транскрипте, композитор чист, баннер со счётчиком. */
internal fun ChatActivity.onMessageBuffered(message: QueuedMessage) {
    uiState.beginQueuedEcho(message.text)
    composerLayoutPresenter.clearQuote()
    updateQuotePlate()
    hideKeyboard()
    input.text.clear()
    // O7: буферизованное сообщение тоже очищает поле — текст снова ничей.
    composerGuard.onSendReset()
    drafts.onSendAccepted(currentSessionId)
    explicitStatus = null
    autoScroll.pinToBottom()
    renderMessages()
    updateControlsFromSection()
    updateNetworkBanner()
}

/** Локальное эхо сообщений очереди текущего диалога (после привязки транскрипта/«нового чата»). */

/** Локальное эхо сообщений очереди текущего диалога (после привязки транскрипта/«нового чата»). */
internal fun ChatActivity.echoPendingOfflineMessages() {
    if (!isOfflineQueueReady()) return
    offlineQueue.pendingFor(currentSessionId).forEach { uiState.beginQueuedEcho(it.text) }
}

internal fun ChatActivity.hideKeyboard() {
    (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
        ?.hideSoftInputFromWindow(input.windowToken, 0)
}

/**
 * Единый локальный разрыв живого хода при любом уходе из контекста диалога: гасит
 * токен send-пути ([LiveTurnGuard] — поздние SSE-кадры убитого хода не попадут в новый
 * экран), прерывает recovery-цикл и отменяет collect отправочного стрима. Серверный ход
 * при этом не трогается: при возвращении в диалог его подхватит reattachActiveTurn.
 */

/**
 * Единый локальный разрыв живого хода при любом уходе из контекста диалога: гасит
 * токен send-пути ([LiveTurnGuard] — поздние SSE-кадры убитого хода не попадут в новый
 * экран), прерывает recovery-цикл и отменяет collect отправочного стрима. Серверный ход
 * при этом не трогается: при возвращении в диалог его подхватит reattachActiveTurn.
 */
internal fun ChatActivity.teardownLiveTurn() {
    liveTurnGuard.invalidate()
    if (isRecoveryReady()) recovery.cancelRecovery()
    // Отложенный trailing-flush и фоновый парсинг убитого хода отменяются: контекст
    // (сессия/экран) сменился, публиковать их результат больше некуда и незачем.
    streamFlushJob?.cancel()
    streamFlushJob = null
    streamThrottle.onTrailingCancelled()
    markdownParseJob?.cancel()
    markdownParseJob = null
    // Смена диалога / «Стоп» / новый чат: озвучка предыдущего ответа не должна перетекать
    // в следующий контекст (one-shot «ответ на голос» тоже сбрасывается).
    if (isVoiceOutputReady()) voiceOutput.onSessionSwitch()
    // Потоковая озвучка умершего хода замолкает сразу: перебивание не должно догонять
    // пользователя хвостом старого ответа в новом контексте.
    if (isStreamSpeechReady()) cancelStreamSpeech()
    stream?.cancel()
    stream = null
    currentStreamId = null
    // Строка статуса префилл-контекста принадлежит убитому ходу/диалогу — в новый
    // контекст она перетекать не должна (сброс, не накопление).
    clearContextStatus()
}

internal fun ChatActivity.cancelStream() {
    val credential = store.read()
    val id = currentStreamId
    if (credential != null && id != null) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { ChatRepository().executeControl(ChatRepository().cancelRequest(ChatActivity.BASE_URL, id, credential.accessToken)) }
        }
    }
    // «Стоп» убивает ход локально целиком: токен send-пути гаснет, recovery-цикл
    // прерывается — после осознанной остановки reattach не должен «воскрешать» стрим.
    teardownLiveTurn()
    interactiveController.dismissAll()
    uiState.cancel()
    explicitStatus = null
    setStatus()
    updateControlsFromSection()
    renderMessages()
}

internal fun ChatActivity.steerMessage() {
    if (currentSessionReadOnly) {
        statusView.text = "Сессия доступна только для чтения"
        return
    }
    val text = input.text.toString().trim()
    val credential = store.read()
    val id = currentSessionId
    if (text.isBlank() || credential == null || id == null) return
    lifecycleScope.launch {
        val accepted = withContext(Dispatchers.IO) {
            runCatching {
                val repository = ChatRepository()
                repository.executeControl(repository.steerRequest(ChatActivity.BASE_URL, id, text, credential.accessToken))
            }.getOrDefault(false)
        }
        if (accepted) {
            if (input.text.toString().trim() == text) input.text.clear()
            drafts.onSendAccepted(id)
            statusView.text = "Направление отправлено"
        } else {
            statusView.text = "Направление не принято — текст сохранён"
        }
    }
}
