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
 * Голосовой ввод: запись, ASR, hands-free цикл, разрешения.
 *
 * Вынесено из [ChatActivity] (W4, технический долг): те же extension-функции на
 * Activity, что были в God Object, поэтому проводка и поведение не меняются.
 */

/**
 * Кнопка микрофона: тап — начать запись, тап во время записи — остановить, распознать и
 * отправить. Записываем сами ([VoiceRecorder], WAV 16 кГц моно) и распознаём на сервере
 * (`POST /api/transcribe`): системный `RecognizerIntent` больше не используется — он есть
 * не на каждой сборке, а качество записи им не управляется (живой вывод ломался ровно на
 * этом: «Распознавание речи недоступно» и пустой результат).
 *
 * История переходов — в [VoiceSessionController] (LISTENING → PROCESSING → IDLE).
 * Здесь только обвязка: разрешение, микрофон, статус и отправка распознанного текста.
 */
internal fun ChatActivity.startVoiceInput() {
    // Что делает тап — решает [VoiceMicGesturePolicy]: в режиме диалога тап не открывает
    // вторую запись, а перебивает звук (или закрывает текущую реплику), иначе «тап по
    // красной кнопке» означал бы разное в зависимости от того, что успел сделать Марс.
    when (voiceGestures.onTap(
        dialogActive = isVoiceLoopReady() && voiceLoop.isActive,
        speaking = isVoiceSpeakingNow(),
        manualRecording = isVoiceSessionReady() &&
            voiceSession.state == VoiceSessionController.State.LISTENING,
        transcribing = voiceTranscribeJob?.isActive == true,
    )) {
        // Идёт распознавание уже записанного звука — второй записи не начинаем.
        VoiceMicTapAction.IGNORE -> statusView.text = VOICE_TRANSCRIBING_STATUS
        // Перебивание: звук глохнет сразу, ход НЕ отменяется (агент продолжает работу).
        VoiceMicTapAction.STOP_SPEECH -> bargeIn()
        VoiceMicTapAction.STOP_UTTERANCE -> {
            // Реплика закрывается вручную. Движок может отдать текст синхронно (тогда ход
            // уйдёт сразу), иначе текст придёт в Final — оба пути внутри цикла.
            // Статус — из фактического состояния цикла, а не «Слушаю…» на всякий случай:
            // если реплика уже ушла в работу, строка не должна обещать микрофон.
            if (!voiceLoop.stopUtterance()) setStatus()
        }
        // Запись идёт (или уже закончилась по лимиту длительности) — тап её закрывает.
        VoiceMicTapAction.STOP_MANUAL_INPUT -> stopVoiceInputAndTranscribe()
        VoiceMicTapAction.START_MANUAL_INPUT -> startManualVoiceInput()
    }
}

/** Обычный голосовой ввод (режим диалога выключен): разрешение, затем запись одной реплики. */
internal fun ChatActivity.startManualVoiceInput() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED
    ) {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), recognizeVoice)
        return
    }
    beginVoiceRecording()
}

/** Микрофон разрешён: открываем сессию записи (состояние ведёт [VoiceSessionController]). */
internal fun ChatActivity.beginVoiceRecording() {
    if (!voiceSession.start()) {
        statusView.text = VOICE_BUSY_STATUS
        return
    }
    // Микрофон открывается — озвучка обязана замолчать: иначе TTS говорит в микрофон,
    // серверное распознавание получает собственный голос Марса, а человек не слышит себя.
    voiceOutput.stopSpeaking()
    // Микрофон не открылся (занят другим приложением, отозван на уровне системы, сломан
    // вход): сессия честно уходит в ERROR с причиной, а не молчит под «Слушаю…».
    if (!voiceRecorder.start()) voiceSession.onError(VOICE_MIC_UNAVAILABLE_STATUS)
}

/**
 * Останавливает запись, отдаёт WAV на распознавание и отправляет распознанный текст
 * существующим [sendMessage]. Запись не теряется молча: и пустой результат, и любой
 * отказ распознавания ([TranscribeOutcome.Failure]) показываются в статусе.
 */
internal fun ChatActivity.stopVoiceInputAndTranscribe() {
    // Распознавание уже идёт (в том числе начатое авто-стопом): второй раз не запускаем.
    if (voiceTranscribeJob?.isActive == true) {
        statusView.text = VOICE_TRANSCRIBING_STATUS
        return
    }
    voiceBtn?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    val recording = voiceRecorder.stop()
    if (recording == null) {
        // Тап пришёл в момент авто-стопа: запись уже забрал onAutoStop, и отменять сессию
        // здесь нельзя — иначе распознавание вернётся в IDLE и текст потеряется.
        if (voiceRecorder.autoStopTaken) return
        voiceSession.cancel()
        statusView.text = VOICE_EMPTY_STATUS
        return
    }
    transcribeVoiceRecording(recording)
}

/**
 * Запись закончилась сама по тишине ([VoiceRecorder.onAutoStop]): второй тап не нужен.
 *
 * Путь тот же, что у ручного тапа ([transcribeVoiceRecording]); отличается только вход.
 * Поздний колбэк (сессию уже закрыли тапом или отменой) игнорируется — иначе он отправил бы
 * сообщение из записи, которую человек сознательно отменил.
 */
internal fun ChatActivity.onVoiceAutoStopped(recording: Recording) {
    if (isFinishing || isDestroyed) return
    if (voiceSession.state != VoiceSessionController.State.LISTENING) return
    if (voiceTranscribeJob?.isActive == true) return
    transcribeVoiceRecording(recording)
}

/** Состояния цикла → строка статуса. Вызывается с главного потока. */
internal fun ChatActivity.createVoiceLoopListener(): VoiceDialogLoop.Listener = object : VoiceDialogLoop.Listener {
    override fun onStateChanged(state: VoiceDialogLoop.State) {
        // Текст и кнопка берутся из одной политики: смена фазы цикла обновляет и статус,
        // и красную кнопку в одном кадре (раньше статус писался напрямую, а кнопка не
        // менялась вовсе — состояние «слушаю» было видно только по тексту).
        setStatus()
    }

    override fun onTranscribeRequested(requestId: Long, recording: Recording) {
        transcribeLoopSegment(requestId, recording)
    }

    override fun onSendRequested(requestId: Long, text: String, mode: VoiceDialogLoop.SendMode) {
        // Реплика распознана целиком: предпросмотр перестаёт быть «нашим», поле ниже
        // заполняется финальным текстом.
        partialPreview.onFinal()
        input.setText(text)
        input.setSelection(text.length)
        // Голосом спросили — голосом и ответ: озвучиваем именно этот ответ (одноразовый
        // arm, не глобальный тумблер), иначе hands-free превращается в переписку.
        voiceOutput.armSpeakNextAnswer()
        when (mode) {
            // Ход уже идёт (в UI это TYPING): текст дописывается в живой ход через
            // steerMessage. cancelStream() здесь НЕ вызывается — он убил бы ход, и ответ
            // пришлось бы ждать заново; steer по конструкции не создаёт второго сообщения.
            VoiceDialogLoop.SendMode.STEER -> steerMessage()
            VoiceDialogLoop.SendMode.SEND -> sendMessage()
        }
    }

    override fun onStopSpeaking() {
        voiceOutput.stopSpeaking()
        // Перебивание: потоковая очередь фраз тоже глохнет, иначе первое предложение
        // нового вопроса прозвучало бы поверх дочитываемого ответа.
        cancelStreamSpeech()
        // Серверный interrupt по открытому `voice_id` (см. [requestVoiceInterrupt]). Ход при
        // этом НЕ отменяется: агент продолжает работу и дописывает ответ в транскрипт.
        requestVoiceInterrupt()
        setStatus()
    }

    /**
     * Живой транскрипт реплики — предпросмотр в композере.
     *
     * Пишем только «свободное» поле: если человек уже начал печатать, его текст важнее
     * предпросмотра ([VoicePartialPreviewPolicy]). Так видно, что Марс слышит, и при этом
     * ничего не затирается молча.
     */
    override fun onPartialTranscript(text: String) {
        val decision = partialPreview.onPartial(text, input.text?.toString().orEmpty())
        if (decision !is VoicePartialPreviewPolicy.Decision.Apply) return
        setComposerTextProgrammatically(decision.text, TextApplyOrigin.VOICE_INPUT)
        input.setSelection(decision.text.length)
    }

    /**
     * Перебивание подтверждено политикой ([BargeInGate]) — то есть это речь человека, а не
     * эхо динамика. Серверный interrupt уже отправлен из [onStopSpeaking]; здесь только
     * диагностика: отмена хода тут недопустима (агент должен доработать ответ).
     */
    override fun onBargeIn(event: BargeInEvent) {
        lastBargeIn = event
    }

    override fun onSegmentDropped() {
        // Реплика пришла, пока Марс отвечал (barge-in выключен): говорим прямо, иначе
        // человек решит, что его не услышали.
        statusView.text = VOICE_SEGMENT_DROPPED_STATUS
    }
}

/** Колбэки [HandsFreeCapture] приходят с потока чтения микрофона — на UI перекладываем. */
internal fun ChatActivity.createHandsFreeListener(): HandsFreeCapture.Listener = object : HandsFreeCapture.Listener {
    override fun onSpeechStarted() {
        // Колбэки захвата приходят с потока чтения микрофона, а дальше цикл меняет
        // состояние и трогает TextView — только на главном потоке.
        runOnUiThread {
            if (isVoiceLoopReady()) voiceLoop.onSpeechStarted()
        }
    }

    override fun onSegment(recording: Recording) {
        runOnUiThread {
            if (!isVoiceLoopReady() || isFinishing || isDestroyed) return@runOnUiThread
            voiceLoop.onSegment(recording)
        }
    }

    /**
     * Накопленная длительность речи — политике перебивания, а не «в конце фразы».
     *
     * Колбэк идёт с потока чтения микрофона (десятки раз в секунду), а [VoiceDialogLoop]
     * живёт на главном потоке: без перекладывания состояние цикла менялось бы из чужого
     * потока, и SPEAKING/LISTENING гонялись бы с UI. Частота безопасна: onSpeechProgress
     * сам решает по гейту, ничего не рисует и не аллоцирует.
     */
    override fun onSpeechProgress(voicedMs: Long) {
        runOnUiThread {
            if (!isVoiceLoopReady() || isFinishing || isDestroyed) return@runOnUiThread
            voiceLoop.onSpeechProgress(voicedMs)
        }
    }

    override fun onCaptureFailed() {
        runOnUiThread {
            if (!isVoiceLoopReady()) return@runOnUiThread
            voiceLoop.stop()
            statusView.text = VOICE_MIC_UNAVAILABLE_STATUS
        }
    }
}

/** Долгое нажатие на микрофон: включить/выключить hands-free (возвращает новое состояние). */
internal fun ChatActivity.toggleHandsFree(): Boolean {
    // Удержание — единственный жест выключения режима, поэтому оно проверяется политикой
    // первой: пока диалог активен, удержание ВСЕГДА выключает его, в любой фазе (слушаю,
    // думаю, говорю). Потерять этот жест — значит оставить человека в режиме без выхода.
    when (voiceGestures.onHold(
        dialogActive = isVoiceLoopReady() && voiceLoop.isActive,
        permissionGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED,
        manualRecording = isVoiceSessionReady() &&
            voiceSession.state == VoiceSessionController.State.LISTENING,
    )) {
        VoiceMicHoldAction.DISABLE_DIALOG -> {
            stopHandsFree()
            return false
        }
        // Ручная запись уже идёт: второй микрофон не открываем и текущую реплику не рвём.
        VoiceMicHoldAction.IGNORE -> {
            statusView.text = VOICE_BUSY_STATUS
            return false
        }
        // Разрешение спросит startHandsFree(): ответ придёт в onRequestPermissionsResult.
        VoiceMicHoldAction.ENABLE_DIALOG_AFTER_PERMISSION,
        VoiceMicHoldAction.ENABLE_DIALOG -> startHandsFree()
    }
    return isVoiceLoopReady() && voiceLoop.isActive
}

internal fun ChatActivity.startHandsFree() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED
    ) {
        // Разрешение спросим один раз: ответ придёт в onRequestPermissionsResult.
        pendingHandsFree = true
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), recognizeVoice)
        return
    }
    // Ручная запись уже идёт — второй микрофон не открываем (и не рвём текущую реплику).
    if (voiceSession.state == VoiceSessionController.State.LISTENING) {
        statusView.text = VOICE_BUSY_STATUS
        return
    }
    if (!voiceLoop.start()) return
    // Озвучка прошлого ответа не должна попасть в микрофон с первого же чанка.
    voiceOutput.stopSpeaking()
    // Маршрут вывода — до первого ответа: политика перебивания читает его на входе в
    // SPEAKING, и «неизвестно» там означает худший случай (динамик).
    audioRouteMonitor?.start()
    // Модель ASR (23 МиБ) качается при первом запуске: в фоне, чтобы не задерживать
    // открытие микрофона. Пока её нет, цикл уже слушает через whisper — диалог не стоит.
    asrPort?.let { port ->
        if (!port.isReady) {
            Thread({
                val loaded = runCatching { port.load() }.getOrDefault(false)
                // После загрузки цикл переключается на потоковый путь со следующей реплики.
                if (loaded) runOnUiThread { voiceLoop.refreshSttMode() }
            }, "mars-asr-load").start()
        }
    }
    if (!handsFreeCapture.start(
            handsFreeListener,
            // Сырой поток идёт в потоковый ASR параллельно с сегментером: сегментер нужен
            // только для whisper-пути (деградация), движок режет реплики сам по endpoint.
            rawPcm = rawPcmSink(),
        )
    ) {
        voiceLoop.stop()
        statusView.text = VOICE_MIC_UNAVAILABLE_STATUS
        return
    }
    voiceBtn?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}

/**
 * Потребитель сырого PCM для потокового ASR: обёртка над [StreamingStt.acceptPcm].
 *
 * Возвращает `null`, если движка нет — тогда [HandsFreeCapture] работает ровно как раньше
 * (только сегменты), и whisper-путь остаётся нетронутым.
 *
 * Колбэк вызывается с потока чтения микрофона: сам [StreamingStt] потокобезопасен и держит
 * свой lock, поэтому перекладывать на главный поток здесь не нужно и вредно (потеря кадров).
 */
internal fun ChatActivity.rawPcmSink(): HandsFreeCapture.RawPcmListener? {
    val stt = streamingStt ?: return null
    return HandsFreeCapture.RawPcmListener { pcm, length -> stt.acceptPcm(pcm, length) }
}

/**
 * Создать порт потокового ASR (один раз за жизнь Activity).
 *
 * Модель (23 МиБ) НЕ лежит в APK: движок скачивает её при первом [AsrEnginePort.load].
 * Загрузку зовём в фоне при старте hands-free, чтобы не блокировать открытие микрофона.
 */
internal fun ChatActivity.createAsrPort(): AsrEnginePort? {
    asrPort?.let { return it }
    val port = runCatching { StreamingAsrEnginePort.create(applicationContext) }.getOrNull()
    asrPort = port
    streamingStt = port?.let { StreamingStt(it) }
    return port
}

/**
 * Выключить режим. Недоговорённая реплика НЕ отправляется ([HandsFreeCapture.cancel]):
 * человек выключил микрофон — значит, слушать больше нечего.
 */
internal fun ChatActivity.stopHandsFree() {
    if (!isVoiceLoopReady()) return
    voiceLoop.stop()
    handsFreeCapture.cancel()
    voiceOutput.stopSpeaking()
    // Режим выключен — голосовой канал на сервере больше не нужен: закрываем voice_id,
    // иначе он висел бы до TTL и занимал слот потока. Отписка от маршрута — там же:
    // колбэк AudioManager держит ссылку на Activity.
    audioRouteMonitor?.stop()
    if (isVoiceInterruptChannelReady()) voiceInterruptChannel.close()
    partialPreview.reset()
    // Режим выключен: кнопка возвращается в нейтральное состояние тем же кадром, а не
    // остаётся красной до следующего случайного setStatus().
    setStatus()
}

/** Реплика из hands-free-цикла → `POST /api/transcribe` → [VoiceDialogLoop.onTranscript]. */
internal fun ChatActivity.transcribeLoopSegment(requestId: Long, recording: Recording) {
    if (store.read() == null) {
        statusView.text = VOICE_NO_CREDENTIAL_STATUS
        voiceLoop.onTranscriptFailed(requestId)
        return
    }
    statusView.text = VOICE_TRANSCRIBING_STATUS
    lifecycleScope.launch {
        val outcome = ChatRepository(credentialProvider = { store.read() })
            .transcribePcmWav(recording.wav)
        if (isFinishing || isDestroyed) return@launch
        when (outcome) {
            is TranscribeOutcome.Success ->
                // Ход ещё идёт (TYPING) — текст уходит steer, а не вторым сообщением.
                voiceLoop.onTranscript(
                    requestId,
                    outcome.transcript,
                    turnActive = uiState.status == ChatStatus.TYPING,
                )
            is TranscribeOutcome.Failure -> {
                statusView.text = transcribeFailureStatus(outcome)
                // Распознавание упало — цикл остаётся живым и снова слушает.
                voiceLoop.onTranscriptFailed(requestId)
            }
        }
    }
}

/** WAV → `POST /api/transcribe` → композитор → [sendMessage]. Общий хвост тапа и авто-стопа. */
internal fun ChatActivity.transcribeVoiceRecording(recording: Recording) {
    if (store.read() == null) {
        // Тот же отказ, что вернул бы репозиторий, — но без сетевого вызова и понятнее.
        voiceSession.onError(VOICE_NO_CREDENTIAL_STATUS)
        return
    }
    statusView.text = VOICE_TRANSCRIBING_STATUS
    voiceTranscribeJob = lifecycleScope.launch {
        // credentialProvider обязателен: голый ChatRepository() отдал бы
        // Failure("No device credential"), не дойдя до сети.
        val outcome = ChatRepository(credentialProvider = { store.read() })
            .transcribePcmWav(recording.wav)
        when (outcome) {
            is TranscribeOutcome.Success -> {
                // Текст принимается только из LISTENING и попадает в композитор через
                // listener (setText + armSpeakNextAnswer), затем сессия закрывается.
                if (voiceSession.onResult(outcome.transcript)) {
                    sendMessage()
                    voiceSession.onProcessingComplete()
                }
            }
            is TranscribeOutcome.Failure -> voiceSession.onError(transcribeFailureStatus(outcome))
        }
    }
}

/** Отказ распознавания — короткая причина для человека вместо HTTP-кода и стектрейса. */
internal fun ChatActivity.transcribeFailureStatus(outcome: TranscribeOutcome.Failure): String =
    when (outcome.httpCode) {
        null -> VOICE_UNREACHABLE_STATUS
        400 -> VOICE_REJECTED_STATUS
        401, 403 -> VOICE_UNAUTHORIZED_STATUS
        413 -> VOICE_TOO_LONG_STATUS
        503 -> VOICE_DISABLED_STATUS
        else -> VOICE_FAILED_STATUS
    }

/** Режим автоозвучивания виден на композере: акцентная тонировка кнопки, пока включён. */
internal fun ChatActivity.updateVolumeBtn() {
    val enabled = isVoiceOutputReady() && voiceOutput.autoSpeakEnabled
    volumeBtn?.imageTintList = ColorStateList.valueOf(
        if (enabled) MarsTheme.ACCENT_TEXT else MarsTheme.TEXT_MUTED
    )
}

/** Слушает ли цикл прямо сейчас (микрофон может быть открыт, но цикл уже выключен). */
internal fun ChatActivity.voiceLoopActive(): Boolean = isVoiceLoopReady() && voiceLoop.isActive

/**
 * Начат ли текущий ход голосовым диалогом — источник поля `voice_mode` в теле `chat/start`.
 *
 * Берётся из состояния ЦИКЛА, а не из «микрофон когда-либо открывался»: текст, набранный
 * руками при включённом режиме, остаётся текстовым ходом, и наоборот.
 */
internal fun ChatActivity.voiceTurnFromDialog(): Boolean = voiceLoopActive()

/**
 * Сервер отказал голосовому ходу (`409 unsupported_voice_mode_backend`, [VoiceModeRejectionPolicy]).
 *
 * Что делает хост и почему именно это:
 * 1. **Выключает режим.** Бэкенд сервера не умеет `voice_mode`, поэтому каждый следующий
 *    голосовой ход получил бы тот же 409. Оставлять микрофон открытым — приглашение
 *    повторять заведомо провальный сценарий; цикл, микрофон, потоковая озвучка и
 *    голосовой канал закрываются ([stopHandsFree]).
 * 2. **Возвращает текст в поле ввода** (только прямая отправка): человек решает сам —
 *    отправить это как обычный текст или переписать. Никакой автоматической подмены
 *    режима: `voice_mode: false` без его ведома не уходит.
 * 3. **Не трогает транскрипт**: сообщение и объяснение уже добавлены вызывающей стороной.
 *
 * Для сообщения из очереди текст остаётся в карточке недоставленных
 * ([OfflineQueuePresenter.onVoiceModeRejected]) — там же и осознанный повтор.
 */
internal fun ChatActivity.onVoiceModeRejectedTurn(text: String, restoreComposer: Boolean) {
    if (isVoiceLoopReady() && voiceLoop.isActive) stopHandsFree()
    // Озвучка хода, которого не было: очередь фраз могла успеть получить дельты до отказа.
    if (isStreamSpeechReady()) cancelStreamSpeech()
    if (restoreComposer && text.isNotBlank() && isInputReady() && input.text.isNullOrBlank()) {
        // Набранное человеком не затирается: поле пустое — вернуть текст, иначе он
        // остаётся в транскрипте (пузырь с текстом никуда не делся).
        setComposerTextProgrammatically(text, TextApplyOrigin.DRAFT_RESTORE)
    }
    setStatus()
}

/**
 * Привязать голосовой канал к сессии диалога (безопасно до инициализации канала).
 *
 * Живёт в Activity, а не в extension-функции отправки: `lateinit`-поле проверяется только
 * внутри класса, где оно объявлено. До инициализации/без открытого канала вызов ничего не
 * делает — `bindSession` сам выходит, если привязки нет.
 */
internal fun ChatActivity.bindVoiceChannelToSession(sessionId: String?) {
    if (!isVoiceInterruptChannelReady()) return
    voiceInterruptChannel.bindSession(sessionId)
}
