package ru.mars.mobile

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.ClipboardManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mars.network.cache.SessionOfflineCache
import ru.mars.network.cache.SharedPreferencesKeyValueStore
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.ChatAttachment
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.ContextStatusUpdate
import ru.mars.network.model.MusicTrack
import ru.mars.network.model.SessionItem
import ru.mars.network.model.SessionDetail
import ru.mars.network.model.SessionUsage
import ru.mars.network.model.SessionAttention
import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.SubagentStatusUpdate
import ru.mars.network.repository.ChatRepository
import ru.mars.network.repository.ApprovalChoice
import ru.mars.network.repository.CompressionSummary
import ru.mars.network.repository.ContextCompressionRepository
import ru.mars.network.repository.InteractiveRequestsRepository
import ru.mars.network.repository.ModelsRepository
import ru.mars.network.repository.PairingRepository
import ru.mars.network.repository.PendingApprovalLookup
import ru.mars.network.repository.PendingClarifyLookup
import ru.mars.network.repository.ReasoningRepository
import ru.mars.network.repository.ReasoningStatusResult
import ru.mars.network.repository.ProviderQuotaOutcome
import ru.mars.network.repository.SessionToolsetsOutcome
import ru.mars.network.repository.SessionRepository
import ru.mars.network.repository.SessionAutonomyRepository
import ru.mars.network.repository.AutonomyLookup
import ru.mars.network.repository.TranscribeOutcome
import ru.mars.network.repository.UploadOutcome
import ru.mars.network.repository.UploadTooLargeException
import ru.mars.network.repository.WorkspaceRepository
import ru.mars.network.repository.readBoundedUploadPayload

/**
 * Инициализация экрана чата: хранилища, голосовой стек, view-контроллеры, back-стек.
 *
 * Вынесено из `ChatActivity.onCreate` (W4): Activity остаётся точкой входа жизненного
 * цикла, а порядок создания зависимостей читается здесь. Порядок вызовов — часть
 * контракта (recovery создаётся до networkObserver.start(), view-контроллеры — после
 * buildView()), поэтому он сохранён ровно как был.
 */

/** Хранилища, селекторы, офлайн-кэш, очередь и контроллеры, не зависящие от view. */
internal fun ChatActivity.initializeChatStores() {
        store = AndroidKeyStoreCredentialStore(this)
        // FCM push (волна 4): регистрируем токен на сервере, если есть credential
        // (покрывает первый вход, перезапуск и пропущенную офлайн ротацию), и
        // запрашиваем POST_NOTIFICATIONS на Android 13+ — без него баннеры молчат.
        PushTokenSync.syncNow(this)
        ru.mars.mobile.service.PushNotifications.ensurePostPermission(this)
        interactiveView = MarsInteractiveView(this)
        interactiveController = InteractiveRequestController(
            view = interactiveView,
            gateway = RepositoryInteractiveGateway(InteractiveRequestsRepository(), ChatActivity.BASE_URL) { store.read() },
            sessionProvider = { currentSessionId },
            state = uiState,
            uiDispatcher = MainThreadDispatcher(),
        ).also { interactiveView.listener = it }

        interactiveController.onClarifyAnswered = { answer -> echoClarifyAnswer(answer) }
        interactiveView.autonomyEnabledProvider = { uiState.autonomyEnabled }
        interactiveController.onAutonomyChanged = { enabled ->
            Toast.makeText(this, if (enabled) "Автономность включена" else "Автономность выключена", Toast.LENGTH_SHORT).show()
        }
        modelSelection = ModelSelection(PrefsModelChoiceStorage(getSharedPreferences("mars-model-choice", Context.MODE_PRIVATE)))
        reasoningSelection = ReasoningEffortSelection(PrefsReasoningChoiceStorage(getSharedPreferences("mars-reasoning-choice", Context.MODE_PRIVATE)))
        activityModeSelection = ActivityModeSelection(PrefsActivityModeStorage(getSharedPreferences("mars-activity-mode", Context.MODE_PRIVATE)))
        workspaceSelection = WorkspaceSelection(PrefsWorkspaceChoiceStorage(getSharedPreferences("mars-workspace-choice", Context.MODE_PRIVATE)))
        autoCompressionStorage = PrefsAutoCompressionStorage(getSharedPreferences("mars-auto-compression", Context.MODE_PRIVATE))
        drafts = SessionDraftController(PrefsSessionDraftStore(getSharedPreferences(ChatActivity.DRAFTS_PREFS, Context.MODE_PRIVATE)))
        // Офлайн-устойчивость: кэш сессий/транскриптов (core-network) и персистентная очередь
        // неотправленных сообщений. Оба хранилища — приватные plaintext-данные, поэтому входят
        // в cleanup при logout/реавторизации вместе с черновиками.
        val offlineCachePrefs = getSharedPreferences(ChatActivity.OFFLINE_CACHE_PREFS, Context.MODE_PRIVATE)
        val pendingQueuePrefs = getSharedPreferences(ChatActivity.PENDING_QUEUE_PREFS, Context.MODE_PRIVATE)
        offlineCache = SessionOfflineCache(SharedPreferencesKeyValueStore(offlineCachePrefs))
        val pendingMessageStore = PrefsPendingMessageStore(pendingQueuePrefs)
        offlineQueue = OfflineMessageQueuePolicy(pendingMessageStore)
        // Выход из авторизации (logout/реавторизация) обязан стереть локальные plaintext-данные
        // до finish(), и после этого черновики больше не сохраняются (onPause не пишет назад).
        // Набор хранилищ — единая фабрика AuthExitStores (P0): живой экземпляр очереди
        // передаётся override'ом, чтобы cleanup и отправка делили один объект.
        authExitCleanup = AuthExitStores.cleanup(
            context = this,
            overrides = mapOf(AuthExitStores.PENDING_QUEUE_PREFS to pendingMessageStore),
        )
        compressionController = ContextCompressionController(
            host = CompressionHostBridge(),
            gateway = RepositoryContextCompressionGateway(ContextCompressionRepository(), ChatActivity.BASE_URL) { store.read() },
            sessionProvider = { currentSessionId },
            streamingProvider = { uiState.status == ChatStatus.TYPING },
            settings = autoCompressionStorage,
        )
        uiState.setActivityMode(activityModeSelection.current)
        autoScroll = AutoScrollPolicy(bottomThresholdPx = dp(56))
        scrollToBottomPolicy = ScrollToBottomPolicy(showThresholdPx = dp(120), hideThresholdPx = dp(56))
}

/** Голосовой стек: TTS (системный + серверный), потоковая озвучка, запись, hands-free, ASR. */
internal fun ChatActivity.initializeVoiceStack() {
        // Voice-output wiring: the engine reports its async init here; the controller owns
        // every speak/stop decision. The sticky auto-speak toggle is persisted across launches.
        val voiceOutputPrefs = getSharedPreferences(VOICE_OUTPUT_PREFS, Context.MODE_PRIVATE)
        speechEngine = AndroidSpeechEngine(
            context = this,
            onInitFinished = { available ->
                if (::voiceOutput.isInitialized) voiceOutput.onEngineReady(available)
                else speechEngineInitResult = available
            },
            // Движок дочитал: контроллер снимает флаг озвучки, hands-free-цикл снова слушает.
            onSpeechFinished = {
                runOnUiThread { if (::voiceOutput.isInitialized) voiceOutput.notifySpeakingFinished() }
            },
        )
        // Серверный TTS с прозрачным фолбэком на системный: контроллер видит один движок
        // (serverTtsEngine), платформенный speechEngine остаётся путём деградации. 401 на
        // device-Bearer — известный серверный блокер, клиент обходить его не должен: при
        // недоступности сервера голос уходит в системный TTS молча.
        serverTtsPlayback = AndroidServerTtsPlayback(
            context = this,
            // Серверное аудио доиграло — тот же сигнал, что и у системного TTS.
            onFinished = {
                runOnUiThread { if (::voiceOutput.isInitialized) voiceOutput.notifySpeakingFinished() }
            },
        )
        serverTtsEngine = ServerTtsSpeechEngine(
            fallback = speechEngine,
            client = RepositoryServerTtsClient(
                baseUrl = ChatActivity.BASE_URL,
                credentialProvider = { store.read() },
            ),
            playback = serverTtsPlayback,
            scope = lifecycleScope,
            // Платформенного TTS нет, а серверное аудио зазвучало — вывод снова возможен.
            onServerAudioAvailable = { runOnUiThread { voiceOutput.onEngineReady(true) } },
        )
        voiceOutput = VoiceOutputController(
            engine = serverTtsEngine,
            autoSpeakInitially = voiceOutputPrefs.getBoolean(KEY_AUTO_SPEAK, false),
            onAutoSpeakChanged = { enabled -> voiceOutputPrefs.edit().putBoolean(KEY_AUTO_SPEAK, enabled).apply() },
        )
        // Потоковая озвучка ответа: фразы уходят в синтез по мере прихода дельт, первый
        // звук не ждёт конца хода. Тот же серверный синтез и свой плеер — очередь фраз
        // управляет воспроизведением строго по порядку, поэтому общий плеер с
        // serverTtsPlayback ей не годится.
        streamingSpeech = StreamingSpeechController.forAndroid(
            context = this,
            client = RepositoryServerTtsClient(
                baseUrl = ChatActivity.BASE_URL,
                credentialProvider = { store.read() },
            ),
            scope = lifecycleScope,
            onFirstAudio = { runOnUiThread { onStreamSpeechStarted() } },
            onDrained = { runOnUiThread { onStreamSpeechDrained() } },
            // Фраза реально пошла в динамик: цикл получает точный эталон эха именно для неё,
            // а не «весь ответ целиком» (будущий текст ещё не звучал).
            onPhraseStarted = { text -> runOnUiThread { onStreamSpeechPhraseStarted(text) } },
        )
        speechEngineInitResult?.let { available ->
            speechEngineInitResult = null
            voiceOutput.onEngineReady(available)
        }
        // Голосовой ввод: своя запись (16 кГц моно WAV) → серверное распознавание.
        // Микрофон открывается только по явному тапу (и только с разрешения).
        // Авто-стоп по тишине: человек говорит, запись закрывается сама после паузы, и дальше
        // идёт тот же путь, что у второго тапа (transcribe → sendMessage). Колбэк приходит с
        // потока чтения рекордера — на главный поток его перекладываем здесь, чтобы весь UI и
        // voiceSession жили в одном потоке.
        voiceRecorder = VoiceRecorder(
            onAutoStop = { recording -> runOnUiThread { onVoiceAutoStopped(recording) } },
        )
        // Hands-free: микрофон не закрывается между репликами, а сегментер режет поток по
        // тишине. Barge-in (L3) — за флагом и выключен по умолчанию: без AcousticEchoCanceler
        // и AudioSource.VOICE_COMMUNICATION микрофон слышит собственный динамик, и цикл
        // перебивал бы сам себя. Включать только после замеров на устройстве (POCO F5).
        // Потоковый ASR на устройстве: если движок не загрузится, цикл сам уйдёт на whisper
        // (StreamingStt.isReady останется false) — диалог не ломается.
        createAsrPort()
        voiceLoop = VoiceDialogLoop(
            listener = voiceLoopListener,
            // Перебивание включено по умолчанию, но НЕ беззащитно: допуск решает [BargeInGate]
            // вместе с политикой маршрута. На динамике без AEC принимается только текстовое
            // доказательство (partial ASR, слов которого нет в произносимом ответе), на
            // наушниках/Bluetooth — энергия. Голое «включить и слушать» перебивало бы цикл
            // собственным динамиком. Живые замеры на устройстве остаются обязательными.
            bargeInEnabled = voiceOutputPrefs.getBoolean(KEY_VOICE_BARGE_IN, DEFAULT_VOICE_BARGE_IN),
            stt = streamingStt,
            // Ход уже идёт: вторая реплика уходит steer-ом, а не новым сообщением.
            turnActive = { uiState.status == ChatStatus.TYPING },
            // Фактический маршрут вывода и наличие AEC у текущего захвата — политике
            // перебивания, а не догадке «наверное, динамик».
            audioRoute = { audioRouteMonitor?.route ?: AudioRoute.UNKNOWN },
            aecActive = { ::handsFreeCapture.isInitialized && handsFreeCapture.echoCanceled },
        )
        // Слушатель передаётся в start(): у захвата нет «постоянного» получателя.
        handsFreeCapture = HandsFreeCapture()
        // Маршрут может смениться на ходу (воткнули наушники посреди ответа) — наблюдатель
        // пересчитывает его сам, цикл спрашивает актуальное значение через лямбду выше.
        audioRouteMonitor = AndroidAudioRouteMonitor(this)
        // Серверный канал перебивания: voice_id создаётся по фактическому API и живёт до конца
        // голосового режима. Пустой шов здесь означал бы, что сервер продолжает синтез после
        // того, как человек начал говорить.
        voiceInterruptChannel = VoiceInterruptChannel(
            gateway = GatewayVoiceControl(),
            credentialProvider = { store.read() },
            // Не lifecycleScope: `close()` вызывается из onDestroy, а lifecycleScope к этому
            // моменту уже отменяется — запрос закрытия до сервера не дошёл бы и voice_id
            // висел бы до TTL.
            scope = VoiceTeardownScope,
            baseUrl = ChatActivity.BASE_URL,
        )
        voiceInterruptHook = { sessionId -> voiceInterruptChannel.interrupt(sessionId) }
        // «Озвучка закончилась» приходит из движка (Android TTS — UtteranceProgressListener,
        // серверный — конец воспроизведения). Только после этого цикл снова слушает.
        voiceOutput.onSpeakingFinished = {
            runOnUiThread {
                if (::voiceLoop.isInitialized) voiceLoop.onAnswerFinished()
            }
        }
        // Voice-input state machine: guards the capture session, delivers haptic feedback on
        // start/finish and routes results into the composer. Pure logic lives in the controller.
        voiceSession = VoiceSessionController(object : VoiceSessionController.Listener {
            override fun onListeningStarted() {
                voiceBtn?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                statusView.text = VOICE_LISTENING_STATUS
            }

            override fun onResult(text: String) {
                // O7: диктовка — действие пользователя, а не программная перезапись.
                setComposerTextProgrammatically(text, TextApplyOrigin.VOICE_INPUT)
                input.setSelection(text.length)
                // The user explicitly spoke — arm reading of this turn's completed answer.
                voiceOutput.armSpeakNextAnswer()
            }

            override fun onError(message: String) {
                statusView.text = message
            }
        })
}

/** View-контроллеры после buildView(): поиск по диалогу, лайтбокс, recovery, сеть. */
internal fun ChatActivity.initializeScreenControllers() {
        setContentView(buildView())
        // Экранные UI-контроллеры: поиск по диалогу (состояние + подсветка) и просмотрщик
        // картинок (диалог + bounded-загрузка). Создаются после buildView(), потому что
        // получают готовую view-обвязку через узкие адаптеры хоста.
        transcriptSearch = TranscriptSearchHighlightController(transcriptSearchHost())
        lightbox = ChatImageLightboxController(
            context = this,
            scope = lifecycleScope,
            baseUrl = ChatActivity.BASE_URL,
            onError = { message -> statusView.text = message },
        )
        // Recovery-цикл доставленного хода: транспорт — ТОЛЬКО attachToStream (повторный
        // POST chat/start структурно невозможен), listener — тонкий wiring в существующий
        // конвейер событий. Инициализация до networkObserver.start(): первый же снимок
        // сети синхронно вызовет onNetworkTransition → recovery.onNetworkChanged.
        recovery = MidTurnRecoveryController(
            scope = lifecycleScope,
            transport = RecoveryStreamTransport { streamId, lastEventId, onEventId ->
                val attachCredential = store.read()
                if (attachCredential == null) {
                    // Credential исчез прямо во время recovery (logout/принудительная
                    // реавторизация): цикл завершится GiveUp(auth rejected) → reauth.
                    flowOf(ChatEvent.Error("HTTP 401: unauthorized"))
                } else {
                    ChatRepository().attachToStream(
                        ChatActivity.BASE_URL, attachCredential, streamId, lastEventId,
                        onTodoState = todoCallback(currentSessionId),
                        onSideEvent = sideEventCallback(currentSessionId),
                        onEventId = onEventId,
                    )
                }
            },
            listener = RecoveryListener(),
        )
        // Мониторинг сети: детектор стартует после построения UI (start() синхронно публикует
        // снимок текущего состояния — баннер корректен с первого кадра); переходы обновляют
        // баннер и запускают автофлаш офлайн-очереди.
        networkObserver = NetworkConnectivityObserver(this, networkPolicy) { transition ->
            onNetworkTransition(transition)
        }
        networkObserver.start()
        // При старте/пересоздании экрана композитор сразу получает черновик текущего диалога.
        restoreComposerDraft()
}

/** Системный Back: поиск → шторка → список диалогов (NavBackPolicy). */
internal fun ChatActivity.installBackNavigation() {
        // Системный Back — чистая NavBackPolicy (лестница: чат → диалоги → разделы →
        // выход). ChatActivity — уровень диалога: сначала закрывается панель поиска
        // по транскрипту, затем шторка разделов (оверлей над диалогом), иначе back
        // ведёт на уровень списка диалогов (ChatSessionsActivity, корень CHAT —
        // AppNavPolicy.back из ChatLevel.Session). Системный выход из чата невозможен.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (NavBackPolicy.resolve(
                    level = NavBackPolicy.Level.CHAT,
                    searchOpen = ::searchPlate.isInitialized && searchPlate.visibility == View.VISIBLE,
                    drawerOpen = drawer.isDrawerOpen(GravityCompat.START),
                )) {
                    NavBackPolicy.Action.CLOSE_SEARCH -> closeTranscriptSearch()
                    NavBackPolicy.Action.CLOSE_DRAWER -> drawer.closeDrawer(GravityCompat.START)
                    NavBackPolicy.Action.GO_TO_SESSION_LIST -> navigateToSessionList()
                    else -> Unit
                }
            }
        })
}

/** Освобождение ресурсов экрана: сеть, контроллеры, микрофон, TTS, ASR-модель. */
internal fun ChatActivity.destroyChatStack() {
        if (::networkObserver.isInitialized) networkObserver.stop()
        if (::interactiveController.isInitialized) interactiveController.destroy()
        if (::compressionController.isInitialized) compressionController.destroy()
        if (::interactiveView.isInitialized) interactiveView.dismissAllNow()
        if (::miniPlayerBox.isInitialized) miniPlayerBox.animate().cancel()
        MarsMusicPlayer.unregisterListener(this)
        // Живой ход умирает вместе с экраном (destroy — точка прерывания collect send-пути
        // и recovery-цикла).
        teardownLiveTurn()
        // Запись умирает вместе с экраном: AudioRecord освобождается, буфер не держится.
        if (::voiceRecorder.isInitialized) voiceRecorder.cancel()
        // Hands-free-цикл тоже: микрофон не должен остаться открытым после закрытия экрана.
        if (::handsFreeCapture.isInitialized) handsFreeCapture.cancel()
        if (::voiceLoop.isInitialized) voiceLoop.stop()
        // Голосовой канал на сервере и подписка на маршрут: экран ушёл — voice_id закрываем,
        // иначе он висит до TTL, а AudioDeviceCallback держит ссылку на Activity.
        audioRouteMonitor?.stop()
        if (::voiceInterruptChannel.isInitialized) voiceInterruptChannel.close()
        // Нативный движок ASR держит модель в памяти (~50 МБ RSS): освобождаем явно.
        runCatching { streamingStt?.release() }
        runCatching { asrPort?.release() }
        if (::voiceOutput.isInitialized) voiceOutput.onDestroy()
        // Потоковая озвучка владеет своим плеером и временными файлами — освобождается тоже.
        if (::streamingSpeech.isInitialized) streamingSpeech.release()
}

/** Возврат на передний план: озвучка, хвост стрима, мини-плеер, воркспейс. */
internal fun ChatActivity.resumeChat() {
        if (::voiceOutput.isInitialized) voiceOutput.onResume()
        // Возврат на передний план: хвост стрима не должен ждать trailing-окно —
        // отложенная перерисовка выполняется немедленно (renderMessages гасит job сам).
        if (streamFlushJob != null) renderMessages()
        updateMiniPlayer(MarsMusicPlayer.currentTrack, MarsMusicPlayer.isPlaying)
        // Выбор пространства мог измениться на другом экране (общее
        // хранилище mars-workspace-choice): перечитываем chip из storage.
        if (::workspaceSelection.isInitialized) {
            workspaceSelection.reloadFromStorage()
            updateWorkspaceButton()
        }
}

/** Уход в фон: черновик, стоп озвучки, стоп hands-free. */
internal fun ChatActivity.pauseChat() {
        // Черновик текущего диалога сохраняется до потери фокуса/пересоздания экрана.
        persistComposerDraft()
        // Экран скрыт: озвучка останавливается и в фоне ответы не читаются.
        if (::voiceOutput.isInitialized) voiceOutput.onPause()
        /*
         * Потоковая озвучка тоже молчит в фоне: это речь по ходу генерации, и дочитывать
         * её из кармана — не то, чего ждёт пользователь. Ход при этом продолжается, а
         * остаток ответа беззвучен, поэтому следующая реплика снова соберётся с нуля.
         */
        cancelStreamSpeech()
        // Hands-free в фоне не слушает: микрофон без foreground-сервиса всё равно отнимут,
        // а «слушающий» в фоне цикл — это расход батареи и лишние запросы распознавания.
        if (::voiceLoop.isInitialized && voiceLoop.isActive) stopHandsFree()
}

/** Результат запроса микрофона: старт записи либо причина отказа в статусе. */
internal fun ChatActivity.handleVoicePermissionResult(requestCode: Int, grantResults: IntArray) {
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode != recognizeVoice) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        // Разрешение просили для hands-free (долгое нажатие) — продолжаем именно его.
        if (pendingHandsFree) {
            pendingHandsFree = false
            if (granted) startHandsFree() else statusView.text = VOICE_PERMISSION_DENIED_STATUS
            return
        }
        if (granted) beginVoiceRecording() else statusView.text = VOICE_PERMISSION_DENIED_STATUS
}
