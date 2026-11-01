package ru.mars.mobile.voice

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import ru.mars.mobile.AudioRoutePolicy
import ru.mars.mobile.asr.WakeWordDetector

/**
 * Порт к хосту сессии — foreground-сервису (`service/MarsVoiceService` из W2).
 *
 * Зачем интерфейс, а не класс сервиса: сервис пишется параллельно и может появиться позже
 * контроллера. Порт — единственная точка связи, поэтому контроллер не импортирует сервис и не
 * блокируется на чужом файле; сервис реализует порт (или передаёт анонимный объект) и
 * подключается через [VoiceSessionController.attachHost].
 *
 * Все методы вызываются на главном потоке, поэтому сервис может трогать уведомление и UI
 * напрямую. Исключение из метода хоста не роняет сессию: микрофон важнее.
 *
 * Соответствие событиям сервиса W2 (`MarsVoiceService`/его `VoiceEnginePort.Events`) — адаптер
 * пишется на стороне сервиса, здесь импорта нет намеренно:
 * `onWakeWord` → `onWakeWordDetected(phrase)`, `onPhaseChanged` → текст уведомления,
 * `onSessionOpened` → `startForeground`, `onSessionClosed` → `shutdown`,
 * `onRejected` → `VoiceCallback.onError(reason.notice)`.
 * Обратный путь: `onUtterance(text)`/`endUtterance()` → [VoiceSessionController.onUtteranceEnded],
 * `deliverAnswer(text)` → [VoiceSessionController.onAnswerReady],
 * `stopSpeaking()` → [VoiceSpeechPort.stop].
 */
interface VoiceSessionHost {

    /** Сессия открыта: сервис обязан поднять foreground с типом `microphone` до первого чтения. */
    fun onSessionOpened(mode: VoiceSessionMode) {}

    /** Сессия закрыта. [reason] `null` — человек выключил режим сам. */
    fun onSessionClosed(reason: VoiceSessionRejection?) {}

    /** Сменилась фаза (для текста уведомления: «Слушаю» / «Думаю» / «Говорю»). */
    fun onPhaseChanged(phase: VoiceSessionPhase) {}

    /** Найдена ключевая фраза: сервис запускает распознавание реплики. */
    fun onWakeWord(keyword: String) {}

    /** Отказ или обрыв по причине, о которой стоит сказать человеку (уведомление/строка статуса). */
    fun onRejected(reason: VoiceSessionRejection) {}
}

/**
 * Порт к синтезу речи (офлайн-TTS из W1 или любой другой).
 *
 * Три метода, потому что контроллеру нужно ровно это: сказать, заткнуться и узнать, звучит ли
 * ещё. Порт `tts/OfflineTtsPort` (W1) ложится на него адаптером в три строки
 * (`speak`/`stop`/`isReady` + признак «звучит»), поэтому второй TTS-путь здесь не заводится.
 */
interface VoiceSpeechPort {

    /** Начать озвучку. `false` — сказать нечем (модель не готова, движок занят). */
    fun speak(text: String): Boolean

    /** Оборвать озвучку немедленно (перебивание, закрытие сессии). */
    fun stop() {}

    /** Звучит ли ещё речь: контроллер ждёт конца озвучки, чтобы вернуться к wake word. */
    val isSpeaking: Boolean get() = false
}

/**
 * Голосовая сессия в фоне: wake word ⇄ foreground-сервис ⇄ синтез.
 *
 * Что связывает: [WakeWordDetector] (существующий, не переписан) с [VoiceSessionPolicy] (решает,
 * можно ли держать микрофон) и с хостом-сервисом через [VoiceSessionHost] и синтезом через
 * [VoiceSpeechPort]. Шов с сервисом — порт, а не импорт класса: сервис W2 пишется параллельно.
 *
 * ## Цикл
 * ```
 * start(env) ─▶ ARMED ──wake word──▶ DIALOGUE ──ответ озвучен──▶ ARMED
 *    │              │                    │
 *    └── Deny ──────┴── лимит/окружение ─┴── IDLE (+ пауза)
 * ```
 * 1. [start] спрашивает политику. `Allow` → детектор открывает микрофон, хост получает
 *    [VoiceSessionHost.onSessionOpened] (там же сервис поднимает foreground).
 * 2. Фраза найдена (колбэк приходит С ПОТОКА ЧТЕНИЯ детектора, поэтому перекладывается на
 *    главный поток): детектор гасится ПЕРВЫМ, чтобы распознавание реплики получило микрофон без
 *    разрыва, затем хост получает [VoiceSessionHost.onWakeWord].
 * 3. Ответ пришёл → [onAnswerReady] отдаёт текст в [VoiceSpeechPort]; конец озвучки определяется
 *    опросом [VoiceSpeechPort.isSpeaking], после чего сессия возвращается в ARMED и детектор
 *    взводится снова.
 * 4. Раз в [tickIntervalMs] политика пересматривает окружение и лимиты. Отказ с
 *    [VoiceSessionDecision.Deny.keepsMicOpen] закрывает только реплику (микрофон остаётся под
 *    wake word), остальные — всю сессию.
 *
 * ## Чего контроллер НЕ делает
 * - не поднимает сервис и не рисует уведомление: это хост;
 * - не ведёт диалог с провайдером: ответ приходит снаружи ([onAnswerReady]);
 * - не качает модель: без неё детектор сам отвечает `MODEL_MISSING`, и сессия закрывается;
 * - не заменяет [ru.mars.mobile.LocalVoiceController] (волна 2): это отдельный фоновый путь.
 *
 * ## Потоки
 * Все публичные методы, кроме отмеченных, обязаны вызываться с главного потока — на нём же
 * приходят колбэки хоста. Время — монотонное ([SystemClock.elapsedRealtime]): `currentTimeMillis`
 * прыгает при смене часов, и таймауты сессии поехали бы вместе с ними.
 *
 * @param detector существующий wake-word детектор (модель и разрешение проверяет он сам);
 * @param policy чистая политика микрофона;
 * @param speech порт синтеза; `null` — озвучивать нечем, диалог закроется сразу после ответа;
 * @param host хост-сервис; можно подключить позже через [attachHost];
 * @param environment поставщик свежего снимка окружения на каждый тик (обычно
 *   `AndroidVoiceSessionEnvironment(context)::read`); `null` — используется последний переданный;
 * @param nowMs монотонные миллисекунды;
 * @param handler главный поток: на нём идут тики, колбэки детектора и ожидание озвучки;
 * @param tickIntervalMs период пересмотра политики;
 * @param speechPollMs период опроса «звучит ли ещё»;
 * @param maxSpeechWaitMs страховка от зависшего порта синтеза, который навсегда отвечает `true`.
 */
class VoiceSessionController(
    private val detector: WakeWordDetector,
    private val policy: VoiceSessionPolicy = VoiceSessionPolicy(),
    private val speech: VoiceSpeechPort? = null,
    host: VoiceSessionHost? = null,
    private val environment: (() -> VoiceSessionEnvironment)? = null,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val tickIntervalMs: Long = 1_000L,
    private val speechPollMs: Long = 200L,
    private val maxSpeechWaitMs: Long = 120_000L,
) {

    /** Хост-сервис. Меняется на ходу: сервис может подключиться после создания контроллера. */
    var host: VoiceSessionHost? = host

    /** Последний снимок окружения (для диагностики и для тика без поставщика). */
    var lastEnvironment: VoiceSessionEnvironment? = null
        private set

    /** Фаза сессии: то же, что видит политика. */
    val phase: VoiceSessionPhase get() = policy.phase

    /** Последняя причина отказа — для уведомления и строки статуса. */
    val lastRejection: VoiceSessionRejection? get() = policy.lastRejection

    /** Открыт ли микрофон детектора прямо сейчас. */
    val isListening: Boolean get() = detector.isListening

    /** Идёт ли диалог (фраза найдена, микрофон у распознавания/озвучки). */
    var isDialogueOpen: Boolean = false
        private set

    private var ticking = false

    /** Мы сами гасим детектор: поздний `IDLE` из его потока не должен закрывать сессию. */
    private var stopRequested = false

    /** Ждём конца озвучки (опрос [VoiceSpeechPort.isSpeaking]). */
    private var awaitingSpeech = false
    private var speechWaitStartedAtMs = 0L

    /** Хост занят ответом: таймер простоя политики продлевается, реплика — нет. */
    private var hostBusy = false

    /**
     * Человек выключил режим сам. Пока флаг стоит, автоматический возврат в прослушивание
     * запрещён: выключенное вручную не включается само.
     */
    private var stoppedByUser = false

    /**
     * Возврат в прослушивание после паузы.
     *
     * Сессию, закрытую политикой (лимит длительности, разряд, занятый микрофон), нельзя
     * оставлять в IDLE навсегда: раньше сервис через 5 минут закрывал микрофон и больше не
     * слушал, а владелец ждал, что фоновая прослушка живёт, пока её не выключат. Поэтому после
     * закрытия по временной причине сессия открывается заново сама.
     */
    private val rearm = object : Runnable {
        override fun run() {
            if (stoppedByUser || policy.phase != VoiceSessionPhase.IDLE) return
            Log.i(TAG, "re-arming after pause (${policy.lastRejection})")
            start()
        }
    }

    // --- публичный API -------------------------------------------------------------------------

    /** Подключить или заменить хост (сервис поднимается позже контроллера). */
    fun attachHost(host: VoiceSessionHost?) {
        this.host = host
    }

    /**
     * Запустить сессию по текущему окружению.
     *
     * Идемпотентно: повторный вызов при открытой сессии ничего не перезапускает.
     *
     * @return `true`, если микрофон открыт (или уже был открыт).
     */
    fun start(env: VoiceSessionEnvironment): Boolean {
        lastEnvironment = env
        stopRequested = false
        stoppedByUser = false
        handler.removeCallbacks(rearm)
        return when (val decision = policy.start(env, nowMs())) {
            is VoiceSessionDecision.Allow -> {
                notifyHost { onSessionOpened(decision.mode) }
                notifyHost { onPhaseChanged(policy.phase) }
                armDetector()
                // Детектор мог отказать мгновенно (нет модели, занят микрофон): тогда сессия уже
                // закрыта, и врать «true» нельзя — сервис поднял бы foreground без микрофона.
                if (policy.phase == VoiceSessionPhase.IDLE) return false
                startTicker()
                true
            }

            is VoiceSessionDecision.Deny -> {
                notifyHost { onRejected(decision.reason) }
                false
            }
        }
    }

    /** Запуск по свежему снимку от поставщика окружения. */
    fun start(): Boolean {
        val env = currentEnvironment() ?: return false
        return start(env)
    }

    /**
     * Остановить сессию (человек выключил режим или сервис умирает).
     *
     * Микрофон закрывается, озвучка обрывается, паузы после явной остановки нет — человек вправе
     * включить режим обратно сразу.
     */
    fun stop() {
        stoppedByUser = true
        handler.removeCallbacks(rearm)
        closeSession(reason = null)
    }

    /** Свежий снимок окружения: применяется сразу, не дожидаясь тика. */
    fun updateEnvironment(env: VoiceSessionEnvironment) {
        lastEnvironment = env
        if (policy.phase != VoiceSessionPhase.IDLE) evaluate(env)
    }

    /**
     * Фраза найдена. Публично, потому что сервис может найти её сам (свой детектор) или
     * продублировать событие.
     */
    fun onWakeWordDetected(keyword: String) {
        if (policy.phase != VoiceSessionPhase.ARMED || isDialogueOpen) return
        // Микрофон детектора гасим ДО старта диалога: AudioRecord в приложении один, и разрыв
        // между «нашёл» и «открыл распознавание» съел бы начало реплики.
        stopRequested = true
        detector.stop()
        if (!policy.onWakeWord(nowMs())) {
            closeSession(VoiceSessionRejection.SESSION_LIMIT_REACHED)
            return
        }
        isDialogueOpen = true
        hostBusy = false
        notifyHost { onPhaseChanged(policy.phase) }
        notifyHost { onWakeWord(keyword) }
    }

    /** В диалоге пошла речь человека (сегментер/ASR хоста). */
    fun onSpeechActivity() {
        if (!isDialogueOpen) return
        policy.onSpeech(nowMs())
    }

    /** Реплика распознана: хост уходит готовить ответ. Таймер простоя больше не тикает. */
    fun onUtteranceEnded() {
        if (!isDialogueOpen) return
        hostBusy = true
        policy.onUtteranceEnd(nowMs())
    }

    /**
     * Ответ готов: озвучить и вернуться к wake word.
     *
     * @return `true`, если озвучка началась; `false` — синтеза нет или он отказал (сессия сразу
     *   возвращается в ARMED, диалог не зависает).
     */
    fun onAnswerReady(text: String): Boolean {
        if (!isDialogueOpen) return false
        hostBusy = true
        val port = speech
        if (port == null || text.isBlank()) {
            finishDialogue()
            return false
        }
        val started = try {
            port.speak(text)
        } catch (t: Throwable) {
            Log.w(TAG, "speech port failed to start", t)
            false
        }
        if (!started) {
            finishDialogue()
            return false
        }
        awaitingSpeech = true
        speechWaitStartedAtMs = nowMs()
        handler.removeCallbacks(speechPoll)
        handler.postDelayed(speechPoll, speechPollMs)
        return true
    }

    /** Хост закончил диалог без ответа (ошибка, отмена): назад к wake word. */
    fun onDialogueEnded() {
        finishDialogue()
    }

    /** Освободить всё (onDestroy сервиса): микрофон, синтез, таймеры. Идемпотентно. */
    fun release() {
        closeSession(reason = null)
        handler.removeCallbacksAndMessages(null)
        ticking = false
    }

    // --- внутреннее ----------------------------------------------------------------------------

    /** Тик политики: окружение и лимиты. */
    private val ticker = object : Runnable {
        override fun run() {
            if (!ticking) return
            tickOnce()
            if (ticking) handler.postDelayed(this, tickIntervalMs)
        }
    }

    /** Опрос «звучит ли ещё»: конец озвучки возвращает сессию к wake word. */
    private val speechPoll = object : Runnable {
        override fun run() {
            if (!awaitingSpeech) return
            val port = speech ?: run {
                awaitingSpeech = false
                finishDialogue()
                return
            }
            val stillSpeaking = try {
                port.isSpeaking
            } catch (t: Throwable) {
                Log.w(TAG, "speech port failed on isSpeaking", t)
                false
            }
            if (!stillSpeaking || nowMs() - speechWaitStartedAtMs >= maxSpeechWaitMs) {
                awaitingSpeech = false
                finishDialogue()
                return
            }
            handler.postDelayed(this, speechPollMs)
        }
    }

    private fun startTicker() {
        if (ticking) return
        ticking = true
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, tickIntervalMs)
    }

    private fun tickOnce() {
        val env = currentEnvironment() ?: return
        evaluate(env)
    }

    /** Пересмотр политики по снимку окружения. */
    private fun evaluate(env: VoiceSessionEnvironment) {
        // Хост занят ответом: «тишина» в это время — не простой человека, а наша работа.
        if (isDialogueOpen && hostBusy) policy.onHostActivity(nowMs())
        when (val decision = policy.tick(env, nowMs())) {
            is VoiceSessionDecision.Allow -> Unit
            is VoiceSessionDecision.Deny -> {
                if (decision.keepsMicOpen) {
                    // Закрывается реплика/диалог, микрофон остаётся под wake word.
                    if (isDialogueOpen) {
                        isDialogueOpen = false
                        hostBusy = false
                        awaitingSpeech = false
                        speech?.stop()
                        notifyHost { onPhaseChanged(policy.phase) }
                        notifyHost { onRejected(decision.reason) }
                        armDetector()
                    }
                } else {
                    closeSession(decision.reason)
                }
            }
        }
    }

    /** Взвести детектор. Ошибки (нет модели, нет ABI, занят микрофон) приходят в [handleDetectorState]. */
    private fun armDetector() {
        if (detector.isListening) return
        stopRequested = false
        val started = detector.start(
            onDetection = { keyword -> handler.post { onWakeWordDetected(keyword) } },
            onState = { state -> handler.post { handleDetectorState(state) } },
        )
        if (!started) {
            Log.w(TAG, "wake word detector did not start: ${detector.state}")
            // Состояние детектор уже выставил; обрабатываем сразу, чтобы отказ не ждал очереди
            // главного потока. Поздний колбэк отсечёт [handleDetectorState].
            handleDetectorState(detector.state)
        }
    }

    /**
     * Состояние детектора (уже на главном потоке).
     *
     * `IDLE` после нашего же [WakeWordDetector.stop] игнорируется: иначе каждая передача
     * микрофона диалогу закрывала бы сессию.
     */
    private fun handleDetectorState(state: WakeWordDetector.State) {
        if (state == WakeWordDetector.State.LISTENING) return
        // Сессия уже закрыта: тот же отказ приходит и синхронно из [armDetector], и колбэком из
        // потока чтения. Второй раз хосту об этом сообщать нельзя.
        if (policy.phase == VoiceSessionPhase.IDLE && !isDialogueOpen) return
        when (state) {
            WakeWordDetector.State.LISTENING -> Unit

            WakeWordDetector.State.IDLE -> {
                if (!stopRequested) closeSession(VoiceSessionRejection.MIC_UNAVAILABLE)
            }

            WakeWordDetector.State.MODEL_MISSING -> closeSession(VoiceSessionRejection.MODEL_MISSING)

            WakeWordDetector.State.UNAVAILABLE -> closeSession(VoiceSessionRejection.MIC_UNAVAILABLE)
        }
    }

    /**
     * Диалог закончился сам: назад в ARMED и, если окружение позволяет, взвести детектор.
     *
     * Политика не закрывается: микрофон остаётся «открытым» по её модели, меняется только фаза.
     */
    private fun finishDialogue() {
        if (!isDialogueOpen) return
        isDialogueOpen = false
        hostBusy = false
        awaitingSpeech = false
        handler.removeCallbacks(speechPoll)
        policy.onDialogueEnd(nowMs())
        notifyHost { onPhaseChanged(policy.phase) }
        val env = currentEnvironment()
        if (env == null) {
            closeSession(VoiceSessionRejection.MIC_UNAVAILABLE)
            return
        }
        when (val decision = policy.tick(env, nowMs())) {
            is VoiceSessionDecision.Allow -> armDetector()
            is VoiceSessionDecision.Deny -> closeSession(decision.reason)
        }
    }

    /** Закрыть сессию целиком: микрофон, озвучка, таймеры, хост. */
    private fun closeSession(reason: VoiceSessionRejection?) {
        stopRequested = true
        ticking = false
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(speechPoll)
        awaitingSpeech = false
        hostBusy = false
        isDialogueOpen = false
        try {
            detector.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "detector stop failed", t)
        }
        try {
            speech?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "speech stop failed", t)
        }
        // policy.stop идемпотентен: после отказа из tick он только уточнит причину и НЕ обнулит
        // назначенную паузу.
        policy.stop(nowMs(), reason)
        notifyHost { onPhaseChanged(policy.phase) }
        notifyHost { onSessionClosed(reason) }
        scheduleRearm(reason)
    }

    /**
     * Вернуть сессию в прослушивание после паузы — если её закрыла политика, а не человек.
     *
     * Возврат делается по временным причинам (лимит длительности, занятый микрофон, разряд):
     * без него сервис через 5 минут выключался и больше не слушал. Явная остановка человеком
     * ([stop]) и постоянные причины (нет разрешения, нет модели) не возвращаются никогда.
     */
    private fun scheduleRearm(reason: VoiceSessionRejection?) {
        if (reason == null || !reason.transient || stoppedByUser) return
        val delay = maxOf(policy.cooldownLeftMs(nowMs()), REARM_MIN_DELAY_MS)
        handler.removeCallbacks(rearm)
        handler.postDelayed(rearm, delay)
        Log.i(TAG, "re-arm in ${delay}ms (${reason.name})")
    }

    private fun currentEnvironment(): VoiceSessionEnvironment? {
        val supplied = try {
            environment?.invoke()
        } catch (t: Throwable) {
            Log.w(TAG, "environment supplier failed", t)
            null
        }
        if (supplied != null) {
            lastEnvironment = supplied
            return supplied
        }
        return lastEnvironment
    }

    /** Колбэк хоста не должен ронять сессию: микрофон важнее уведомления. */
    private inline fun notifyHost(block: VoiceSessionHost.() -> Unit) {
        val target = host ?: return
        try {
            target.block()
        } catch (t: Throwable) {
            Log.w(TAG, "host callback failed", t)
        }
    }

    private companion object {
        const val TAG = "MarsVoiceSession"

        /** Минимальная пауза перед автоматическим возвратом в прослушивание. */
        const val REARM_MIN_DELAY_MS = 2_000L
    }
}

/**
 * Сборщик [VoiceSessionEnvironment] из системных сервисов Android.
 *
 * Зачем отдельный класс: политика обязана остаться чистой, поэтому вся работа с `BatteryManager`,
 * `PowerManager`, `NotificationManager`, `AudioManager` и разрешениями собрана здесь, в одном
 * месте, и отдаётся снимком значений.
 *
 * Значения, которые нельзя получить без разрешений или которые знает только хост
 * (`foregroundServiceRunning`, `otherCaptureActive`, `modelInstalled`), передаются параметрами:
 * сервис знает про себя больше, чем системный API.
 */
class AndroidVoiceSessionEnvironment(context: Context) {

    private val app: Context = context.applicationContext

    /**
     * Свежий снимок окружения.
     *
     * @param foregroundServiceRunning поднят ли foreground-сервис с типом `microphone`;
     * @param otherCaptureActive микрофон занят ДРУГИМ захватом (голосовой диалог в чате);
     * @param modelInstalled лежит ли модель распознавания на диске.
     */
    fun read(
        foregroundServiceRunning: Boolean,
        otherCaptureActive: Boolean = false,
        modelInstalled: Boolean = true,
    ): VoiceSessionEnvironment {
        val power = app.getSystemService(PowerManager::class.java)
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        val audio = app.getSystemService(AudioManager::class.java)
        val notifications = app.getSystemService(NotificationManager::class.java)

        val (percent, charging) = battery()
        val filter = notifications?.currentInterruptionFilter
            ?: NotificationManager.INTERRUPTION_FILTER_ALL
        val doNotDisturb = filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        val mode = audio?.mode ?: AudioManager.MODE_NORMAL
        val outputTypes = try {
            audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.map { it.type }.orEmpty()
        } catch (t: Throwable) {
            // getDevices не бросает по контракту, но политика не должна падать из-за аудиостека.
            Log.w(TAG, "cannot read output devices", t)
            emptyList()
        }

        return VoiceSessionEnvironment(
            batteryPercent = percent,
            charging = charging,
            screenOn = power?.isInteractive ?: true,
            keyguardLocked = keyguard?.isKeyguardLocked ?: false,
            audioRoute = AudioRoutePolicy.routeForDeviceTypes(outputTypes),
            doNotDisturb = doNotDisturb,
            powerSaveMode = power?.isPowerSaveMode ?: false,
            micPermissionGranted = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
            modelInstalled = modelInstalled,
            callActive = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION,
            otherCaptureActive = otherCaptureActive,
            foregroundServiceRunning = foregroundServiceRunning,
        )
    }

    /**
     * Заряд и питание.
     *
     * `BATTERY_PROPERTY_CAPACITY` на части прошивок отдаёт мусор, поэтому есть второй путь —
     * sticky-интент `ACTION_BATTERY_CHANGED` с `EXTRA_LEVEL`/`EXTRA_SCALE`. Если оба не дали
     * процентов, возвращается `-1`: политика считает неизвестный заряд достаточным, а не
     * блокирует режим навсегда.
     */
    private fun battery(): Pair<Int, Boolean> {
        var percent = -1
        var charging = false
        try {
            val manager = app.getSystemService(BatteryManager::class.java)
            val capacity = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (capacity in 0..100) percent = capacity
        } catch (t: Throwable) {
            Log.w(TAG, "cannot read battery capacity", t)
        }
        try {
            val sticky: Intent? = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (sticky != null) {
                if (percent < 0) {
                    val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) percent = level * 100 / scale
                }
                val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                charging = plugged != 0 ||
                    status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cannot read battery intent", t)
        }
        return percent to charging
    }

    private companion object {
        const val TAG = "MarsVoiceSession"
    }
}
