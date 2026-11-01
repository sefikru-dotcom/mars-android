package ru.mars.mobile

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import ru.mars.mobile.asr.ModelStore
import ru.mars.mobile.asr.WakeWordConfig
import ru.mars.mobile.asr.WakeWordDetector
import ru.mars.mobile.asr.WakeWordPolicy
import ru.mars.mobile.service.MarsVoiceService
import ru.mars.mobile.service.VoiceEnginePort
import ru.mars.mobile.voice.VoiceGate
import ru.mars.mobile.voice.VoiceSessionDecision
import ru.mars.mobile.voice.VoiceSessionRejection
import ru.mars.mobile.tts.OfflineTtsBackend
import ru.mars.mobile.tts.OfflineTtsPort

/**
 * Мост между голосовым сервисом и существующими движками распознавания/синтеза.
 *
 * Зачем отдельный класс. Волна 3 дала три независимых куска: сервис
 * ([MarsVoiceService]) со своим портом, офлайн-синтез ([OfflineTtsBackend]) и готовый
 * тракт микрофона ([HandsFreeCapture] + [StreamingStt]). Каждый компилируется сам по
 * себе, но **ни один не ссылается на другой** — и R8 честно вырезал из release всё, на
 * что не было живых ссылок: `OfflineTts*` и `VoiceSessionPolicy` не попали в mapping
 * вообще. Фича при этом выглядела готовой: классы написаны, тесты зелёные, а в
 * приложении их нет.
 *
 * Этот класс и есть та единственная живая ссылка. Он же — место, где сходятся три
 * разных представления о состоянии, поэтому только здесь понятно, почему что-то молчит.
 *
 * ## Что здесь НЕ делается
 * Диалог с провайдером сервис не ведёт (так задумано): распознанный текст уходит в
 * приложение через [MarsVoiceService.VoiceCallback.onUtterance], ответ возвращается
 * в [MarsVoiceService.deliverAnswer]. Локальный чат остаётся единственным владельцем
 * истории и ключа, а сервис — только «уши и рот».
 *
 * ## Микрофон один на всех
 * Захват ([HandsFreeCapture]) открывается ОДИН раз на сессию и не переоткрывается при
 * переходе «поиск фразы → реплика»: переоткрытие даёт щелчок и теряет первый слог, а он в
 * короткой команде часто единственный. Поэтому звук маршрутизируется флагом [mode]:
 *
 * ```
 * Mode.WAKE       сырой PCM → очередь → WakeWordDetector
 * Mode.UTTERANCE  сырой PCM → StreamingStt.acceptPcm
 * Mode.IDLE       сырой PCM никому (сервис думает/говорит)
 * ```
 *
 * Сырой PCM приходит вторым аргументом `capture.start(listener, rawPcm)`. Без него захват
 * режет звук на сегменты для whisper-пути и ни в ASR, ни в детектор не попадает ни байта —
 * именно поэтому распознавание не работало никогда.
 *
 * ## Потоки
 * `HandsFreeCapture` отдаёт PCM с потока чтения, `StreamingStt` перекладывает события
 * на главный поток, детектор фразы декодирует на своём потоке. Наружу (в сервис) события
 * уходят как есть — сервис сам переносит их на main, это зафиксировано в его контракте.
 * Поток чтения микрофона не блокируется тяжёлой работой: в ASR байты уходят синхронно
 * (движок конвертирует их в float сразу), в детектор — через очередь с копией.
 */
class LocalVoiceEngine(
    context: Context,
    private val tts: OfflineTtsPort? = null,
) : VoiceEnginePort {

    private val appContext: Context = context.applicationContext

    /** Ворота микрофона: чтение окружения Android → чистая политика. */
    private val gate = VoiceGate(appContext)

    private var events: VoiceEnginePort.Events? = null

    /** Тракт микрофона создаётся лениво: до первого «слушай» нативный движок не нужен. */
    private var capture: HandsFreeCapture? = null
    private var stt: StreamingStt? = null

    /**
     * Детектор фразы-активатора. Один на движок и живёт между сессиями поиска: создание
     * поднимает native-порт только при [WakeWordDetector.start], поэтому держать объект дешевле,
     * чем пересобирать его на каждый тумблер.
     */
    private var detector: WakeWordDetector? = null

    /**
     * Очередь PCM текущей сессии поиска фразы. `null` — детектор не слушает, звуку идти некуда.
     * Поле меняется на каждой сессии: закрытая очередь прошлой сессии не должна съесть звук новой.
     */
    @Volatile
    private var wakeFeed: PcmFeed? = null

    /** Куда сейчас идёт звук с микрофона. Меняется с потока чтения, из main и из потока детектора. */
    @Volatile
    private var mode: Mode = Mode.IDLE

    /** Реплика открыта в ASR (был `startUtterance` без `finish`). */
    @Volatile
    private var utteranceOpen: Boolean = false

    /** Движок освобождён: поздние колбэки движков не должны дёргать сервис. */
    @Volatile
    private var released: Boolean = false

    /** Синтез готовится один раз; `prepare` качает модель и грузит .so. */
    private var ttsReady = false
    private var speaking = false

    /** Главный поток: сигнал синтеза приходит с потока записи в `AudioTrack`. */
    private val speechHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Главный поток: тик счёта кадров и переоткрытие заглушённого захвата. */
    private val frameHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // --- кадры микрофона: идут ли байты вообще -------------------------------------------------

    /**
     * Сколько байт пришло с микрофона за сессию и сколько чанков было полной тишиной.
     *
     * Зачем: на устройстве в логе видно `AudioRecordImpl: [audioRecordData][mute] 32s(f:37189
     * m:32002)` — счётчик `f` (полученные байты) ПЕРЕСТАЁТ расти. Это не тишина в комнате, а
     * признак, что байты не приходят вовсе (система заглушила захват). Без собственного счётчика
     * это видно только через `dumpsys`, поэтому раз в 5 с печатается
     * `MarsVoice: frames f=N m=M` — по нему сразу понятно, жив ли микрофон.
     */
    private val frameBytes = AtomicLong(0L)

    /** Чанки, в которых не было ни одного ненулевого отсчёта (система отдаёт нули). */
    private val frameMutedChunks = AtomicLong(0L)

    /** Сколько байт было на прошлом тике: неизменность = микрофон заглушён. */
    private var lastFrameBytes = 0L

    /** Когда байты последний раз росли (монотонные мс); 0 — ещё не росли. */
    private var framesGrewAtMs = 0L

    /** Сколько неудачных переоткрытий подряд: защита от бесконечного цикла. */
    private var reopenAttempts = 0

    /** Тик счёта кадров: лог раз в 5 с и переоткрытие заглушённого захвата. */
    private val frameTick = object : Runnable {
        override fun run() {
            if (released) return
            val bytes = frameBytes.get()
            val muted = frameMutedChunks.get()
            Log.i(VOICE_TAG, "frames f=$bytes m=$muted mode=$mode")
            val now = SystemClock.elapsedRealtime()
            val capturing = capture?.isCapturing == true
            if (bytes != lastFrameBytes) {
                framesGrewAtMs = now
                reopenAttempts = 0
            } else if (capturing && mode == Mode.WAKE && framesGrewAtMs > 0L &&
                now - framesGrewAtMs >= MIC_STALL_RESTART_MS
            ) {
                // Захват «открыт», а байты не идут: система заглушила запись. Переоткрываем
                // микрофон сами — иначе прослушка висит мёртвой до выключения сервиса.
                Log.w(VOICE_TAG, "mic stalled ${now - framesGrewAtMs}ms (f=$bytes m=$muted), reopening")
                framesGrewAtMs = now
                reopenCapture("stalled")
            }
            lastFrameBytes = bytes
            frameHandler.postDelayed(this, FRAME_LOG_MS)
        }
    }

    private fun startFrameTicks() {
        frameHandler.removeCallbacks(frameTick)
        lastFrameBytes = frameBytes.get()
        framesGrewAtMs = SystemClock.elapsedRealtime()
        frameHandler.postDelayed(frameTick, FRAME_LOG_MS)
    }

    private fun stopFrameTicks() {
        frameHandler.removeCallbacks(frameTick)
    }

    /**
     * Переоткрыть микрофон: закрыть текущий захват и открыть заново.
     *
     * Идёт на своём потоке: `HandsFreeCapture.cancel()` ждёт поток чтения до 1.5 с, и делать это
     * на главном потоке значило бы ANR ради восстановления звука.
     *
     * @param reason почему переоткрываем — уходит в лог.
     * @param delayMs через сколько пробовать открыть снова (для паузы политики — её cooldown).
     */
    private fun reopenCapture(reason: String, delayMs: Long = REOPEN_DELAY_MS) {
        if (released) return
        Thread({
            val previous = capture
            capture = null
            runCatching { previous?.cancel() }
            frameHandler.postDelayed({ attemptReopen(reason, delayMs) }, delayMs)
        }, "mars-mic-reopen").start()
    }

    /**
     * Попытка открыть микрофон заново. Ошибка не уходит в сервис сразу: после заглушения системе
     * нужно несколько кадров, чтобы отдать устройство, поэтому пробуем несколько раз.
     */
    private fun attemptReopen(reason: String, delayMs: Long) {
        if (released) return
        // Реплику не рвём: если человек говорит, переоткрытие съест её начало, а реплика всё
        // равно закрывается через ASR.
        if (mode == Mode.UTTERANCE) return
        if (startWakeWord(silent = true)) {
            reopenAttempts = 0
            Log.i(VOICE_TAG, "mic reopened ($reason)")
            return
        }
        if (++reopenAttempts > MAX_REOPEN_ATTEMPTS) {
            Log.w(VOICE_TAG, "mic did not reopen after $reopenAttempts attempts ($reason)")
            events?.onFailed(MIC_FAILED)
            return
        }
        frameHandler.postDelayed({ attemptReopen(reason, delayMs) }, delayMs)
    }

    /**
     * Подписка на «реплика дочитана».
     *
     * Раньше её не было вовсе, и это была главная причина «фоновый голос не работает»:
     * [speak] ставил `speaking = true` и уведомлял сервис, а обратного события не приходило
     * никогда — порт синтеза сообщает конец реплики только колбэком в конструкторе, который
     * при создании бэкенда никто не передавал. Сервис оставался в SPEAKING, микрофон открыт,
     * следующая фраза не ловится.
     */
    private val speechSignal: () -> Unit = OfflineVoiceSignal.attach(::onSpeechSignal)

    /** Синтез сообщил о себе. `true` не нужно: сервис и так знает, что говорит. */
    private fun onSpeechSignal(engineSpeaking: Boolean) {
        if (engineSpeaking) return
        speechHandler.post { finishSpeech("engine reported end") }
    }

    /** Конец реплики: единственное место, где `speaking` снимается и сервис узнаёт о тишине. */
    private fun finishSpeech(by: String) {
        if (!speaking) return
        speaking = false
        Log.i(TAG, "tts: speech finished ($by)")
        events?.onSpeakingChanged(false)
    }

    /** Защита от двойной выдачи одной и той же реплики: финал приходит и от движка, и из [endUtterance]. */
    private val utteranceLock = Any()
    private var lastUtterance: String = ""
    private var lastUtteranceAtNs: Long = 0L

    override fun attach(events: VoiceEnginePort.Events) {
        this.events = events
    }

    /**
     * Открыть микрофон и искать ключевую фразу.
     *
     * Возвращает `false`, если модель распознавания не установлена: качать 23 МиБ
     * молча из фонового сервиса нельзя — это неожиданный трафик и разряд.
     *
     * Повторный вызов (сервис зовёт его после каждой озвучки) микрофон НЕ переоткрывает:
     * захват уже идёт, возвращается только режим поиска фразы.
     */
    override fun startWakeWord(): Boolean = startWakeWord(silent = false)

    /**
     * Открыть микрофон на поиск фразы.
     *
     * @param silent `true` — не сообщать сервису об отказе. Нужно автоматическому переоткрытию:
     *   заглушённый или только что отпущенный микрофон отказывает пару попыток подряд, и каждая
     *   такая ошибка выключала бы сервис целиком.
     */
    private fun startWakeWord(silent: Boolean): Boolean {
        val stt = ensureStt() ?: return false
        if (!stt.isReady) {
            if (!silent) events?.onFailed(NOT_READY)
            return false
        }
        // Правила безопасности ПЕРЕД открытием микрофона: заряд, «не беспокоить», звонок,
        // разрешение, установленная модель. Без этой строки микрофон открывался во время
        // звонка и на 10% заряда — 590 строк политики были написаны, покрыты тестами и
        // не вызывались никем (проверено грепом).
        gate.openMic()?.let { denial ->
            if (!silent) events?.onFailed(denial.notice)
            return false
        }
        utteranceOpen = false
        val capture = ensureCapture()
        if (!capture.isCapturing) {
            // Второй аргумент — сырой поток PCM. Без него звук уходит только в сегментер
            // whisper-пути, и ни ASR, ни детектор фразы его не видят.
            if (!capture.start(listener = captureListener, rawPcm = rawPcmListener)) {
                mode = Mode.IDLE
                // У HandsFreeCapture этот колбэк не вызывается никогда: отказ открытия
                // виден только по false, поэтому причину сообщаем здесь.
                if (!silent) events?.onFailed(MIC_FAILED)
                return false
            }
        }
        mode = Mode.WAKE
        startDetector()
        startGateTicks()
        startFrameTicks()
        return true
    }

    /** Закрыть микрофон поиска фразы и освободить детектор. Идемпотентно. */
    override fun stopWakeWord() {
        mode = Mode.IDLE
        utteranceOpen = false
        stopDetector()
        stopGateTicks()
        stopFrameTicks()
        capture?.stop()
    }

    /**
     * Пока микрофон открыт, раз в 10 с спрашиваем политику: звонок, разряд, «не беспокоить»
     * могут начаться посреди сессии. Без этого микрофон закрылся бы только по таймауту.
     *
     * Лимит длительности сессии — особый случай. Раньше он приходил сюда как обычный отказ, и
     * сервис выключался целиком: в логе `MarsVoice: phase=IDLE (Лимит длительности сессии —
     * пауза)`, и после этого фоновое прослушивание не возвращалось никогда. Теперь лимит — это
     * пауза политики: микрофон переоткрывается сам, сервис живёт.
     */
    private val gateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val gateTick = object : Runnable {
        override fun run() {
            val decision = gate.tickDecision()
            if (decision is VoiceSessionDecision.Deny && !decision.keepsMicOpen) {
                if (decision.reason == VoiceSessionRejection.SESSION_LIMIT_REACHED) {
                    Log.i(
                        VOICE_TAG,
                        "session limit reached — pause ${decision.retryAfterMs}ms, then listen again",
                    )
                    // Пауза политики + секунда запаса: открывать микрофон раньше, чем вышел
                    // cooldown, значит получить COOLDOWN и выключить сервис по ошибке.
                    reopenCapture("session limit", decision.retryAfterMs + 1_000L)
                    return
                }
                stopWakeWord()
                events?.onFailed(decision.reason.notice)
                return
            }
            gateHandler.postDelayed(this, GATE_TICK_MS)
        }
    }

    private fun startGateTicks() {
        gateHandler.removeCallbacks(gateTick)
        gateHandler.postDelayed(gateTick, GATE_TICK_MS)
    }

    private fun stopGateTicks() {
        gateHandler.removeCallbacks(gateTick)
    }

    /**
     * Начать реплику после ключевой фразы.
     *
     * Микрофон не переоткрывается: тот же захват уже идёт, меняется только адресат
     * звука. Переоткрытие дало бы щелчок и потеряло первый слог — а он в короткой
     * команде часто единственный.
     *
     * Идемпотентно: после срабатывания детектора реплика уже открыта (чтобы не потерять
     * первый слог на перекладке события через main), и повторный вызов её не перезапускает.
     */
    override fun beginUtterance(): Boolean {
        val stt = ensureStt() ?: return false
        if (!stt.isReady) return false
        if (!utteranceOpen) {
            stt.startUtterance()
            utteranceOpen = true
        }
        mode = Mode.UTTERANCE
        // Детектору звук больше не нужен: его поток освобождает native KeywordSpotter,
        // микрофон при этом остаётся у захвата.
        stopDetector()
        return capture?.isCapturing == true
    }

    override fun endUtterance() {
        val target = stt ?: return
        if (!utteranceOpen) return
        utteranceOpen = false
        mode = Mode.IDLE
        emitUtterance(target.finishUtterance().orEmpty())
    }

    /**
     * Озвучить ответ.
     *
     * Если офлайн-синтез не готов, честно возвращаем `false`: сервис по этому признаку
     * решает, показывать ли «нет синтеза», а не делает вид, что говорит.
     */
    override fun speak(text: String): Boolean {
        if (text.isBlank()) return false
        val engine = tts ?: run {
            // Порт офлайн-синтеза не передан: сервису нечем говорить. Это не «робот» —
            // роботом звучит системный TTS экрана, а здесь ответ просто молчит.
            Log.w(VOICE_TAG, "tts: no offline port — answer cannot be spoken")
            return false
        }
        if (!ttsReady && !engine.isReady()) {
            events?.onFailed(TTS_NOT_READY)
            return false
        }
        val accepted = engine.speak(text)
        if (accepted) {
            speaking = true
            // Имя класса в строке — доказательство, ЧЕМ говорят: OfflineTtsBackend = Piper
            // (`ru_RU-irina-medium`), а не системный `TextToSpeech` (`Connected to TTS engine`).
            Log.i(
                TAG,
                "tts: speaking (${text.length} chars, engine=${engine.javaClass.name})",
            )
            events?.onSpeakingChanged(true)
        }
        return accepted
    }

    override fun stopSpeaking() {
        tts?.stop()
        speechHandler.post { finishSpeech("stopped") }
    }

    /**
     * Освободить всё: детектор, микрофон, распознавание, синтез. Повторный вызов безопасен.
     *
     * `released` ставится первым: закрытие реплики в ASR может выдать финал, и он не должен
     * уйти в сервис, который уже уходит.
     */
    override fun release() {
        released = true
        mode = Mode.IDLE
        utteranceOpen = false
        stopFrameTicks()
        frameHandler.removeCallbacksAndMessages(null)
        // Отписка от сигнала синтеза: движок умирает раньше бэкенда, и позднее «дочитал»
        // не должно дёргать сервис, которого уже нет.
        speechSignal.invoke()
        stopDetector()
        capture?.cancel()
        capture = null
        stt?.cancelUtterance()
        stt = null
        speaking = false
        runCatching { tts?.stop() }
        // Порт синтеза НЕ освобождаем: он не наш. Его создаёт и держит
        // [ru.mars.mobile.tts.OfflineTtsWarmup] экрана, и `release()` здесь убивал бы офлайн-голос
        // всего приложения — после «Стоп» в фоне переставало работать и «Озвучить» в чате
        // (порт освобождён навсегда, `prepare()` после этого отказывает). Гасим только звук.
        ttsReady = false
        events = null
    }

    /**
     * Подготовить синтез заранее.
     *
     * Звать из активного экрана при включении голосового режима: скачивание модели
     * (~21 МиБ) не должно происходить в момент, когда человек уже ждёт ответа вслух.
     */
    fun warmUpTts(onDone: (Boolean) -> Unit = {}) {
        val engine = tts ?: return onDone(false)
        if (engine.isReady()) {
            ttsReady = true
            return onDone(true)
        }
        // Офлайн-бэкенд качает модель сам внутри prepare(); здесь только фиксируем факт.
        ttsReady = engine.isReady()
        onDone(ttsReady)
    }

    private fun ensureStt(): StreamingStt? {
        stt?.let { return it }
        val port = StreamingAsrEnginePort.create(appContext) ?: run {
            events?.onFailed(ASR_UNAVAILABLE)
            return null
        }
        return StreamingStt(port).also { created ->
            created.events = sttEvents
            created.load()
            stt = created
        }
    }

    private fun ensureCapture(): HandsFreeCapture =
        capture ?: HandsFreeCapture().also { capture = it }

    // ------------------------------------------------------------------ детектор фразы

    /**
     * Детектор фразы-активатора на той же модели, что и ASR.
     *
     * Конструктор `WakeWordDetector(context)` здесь не годится: он открывает СВОЙ `AudioRecord`,
     * а микрофон в hands-free уже держит [HandsFreeCapture] — второй захват на одном устройстве
     * либо не откроется, либо отберёт звук у первого, и тогда ломается ASR. Поэтому детектор
     * получает ту же модель и ту же политику, но источник звука — очередь, в которую пишет поток
     * чтения захвата ([relaySourceFactory]). Сам [WakeWordDetector] используется как есть: его
     * поток, дебаунс, keywords-файл и native-порт не переписываются.
     */
    private fun ensureDetector(): WakeWordDetector = detector ?: WakeWordDetector(
        modelStore = ModelStore(ModelStore.rootForAppFilesDir(appContext.filesDir)),
        policyFactory = { modelDir -> WakeWordPolicy.forModelDir(modelDir, WakeWordConfig()) },
        sourceFactory = relaySourceFactory,
    ).also { created -> detector = created }

    /**
     * Источник звука для детектора: не микрофон, а очередь текущей сессии поиска.
     *
     * Фабрика вызывается из [WakeWordDetector.start] на вызывающем потоке, поэтому очередь
     * успевает появиться до старта его потока чтения.
     */
    private val relaySourceFactory = object : AudioSourceFactory {
        override fun create(): AudioSource = PcmRelaySource(
            wakeFeed ?: PcmFeed().also { it.close() },
        )
    }

    /** Поднять поиск фразы на текущем захвате. Неудача не рвёт захват — она видна в [onDetectorState]. */
    private fun startDetector() {
        val target = ensureDetector()
        if (target.isListening) {
            // Уже слушаем: сбрасываем только накопленное, новую сессию не открываем.
            target.reset()
            return
        }
        val feed = PcmFeed()
        wakeFeed = feed
        if (!target.start(onDetection = ::onWakeWordDetected, onState = ::onDetectorState)) {
            wakeFeed = null
        }
    }

    private fun stopDetector() {
        wakeFeed = null
        val target = detector ?: return
        if (!target.isListening) return
        target.stop()
    }

    /**
     * Смена состояния детектора. `MODEL_MISSING`/`UNAVAILABLE` — не повод держать микрофон
     * открытым «в никуда»: сервис должен узнать, что фраза-активатор не работает, иначе
     * foreground-сервис с индикатором записи живёт вечно и ничего не делает.
     */
    private fun onDetectorState(state: WakeWordDetector.State) {
        when (state) {
            WakeWordDetector.State.MODEL_MISSING,
            WakeWordDetector.State.UNAVAILABLE,
            -> {
                wakeFeed = null
                Log.w(TAG, "wake word: detector is $state")
                events?.onFailed(WAKE_WORD_UNAVAILABLE)
            }
            WakeWordDetector.State.IDLE,
            WakeWordDetector.State.LISTENING,
            -> Unit
        }
    }

    /**
     * Фраза найдена. Вызывается С ПОТОКА ДЕТЕКТОРА.
     *
     * Сначала событие наружу (сервис переложит его на main и придёт в [beginUtterance]), затем
     * переключение режима. Реплика открывается здесь же, на этом потоке: если ждать main,
     * первый слог после «Марс, слушай» уйдёт в пустоту — движок ещё не начал реплику.
     */
    private fun onWakeWordDetected(phrase: String) {
        if (mode == Mode.UTTERANCE) return
        events?.onWakeWordDetected(phrase)
        val target = stt
        if (target != null && target.isReady) {
            target.startUtterance()
            utteranceOpen = true
        }
        mode = Mode.UTTERANCE
        // Детектор больше не нужен, а его native-порт держит модель в памяти.
        stopDetector()
    }

    // ------------------------------------------------------------------ события движков

    /** События распознавания → события сервиса. Тексты реплики идут как есть. */
    private val sttEvents = object : SpeechToText.Events {
        override fun onSpeechStarted() = Unit

        override fun onPartial(text: String) {
            if (released) return
            events?.onPartial(text)
        }

        /**
         * Движок сам решил, что человек договорил. Здесь же возвращаемся в «никому»: пока
         * сервис думает и говорит, звук в ASR идти не должен — он услышит собственный динамик.
         *
         * Режим трогаем только если реплика ещё открыта: поздний финал не должен погасить
         * уже начатый поиск фразы.
         */
        override fun onFinal(text: String) {
            if (utteranceOpen) {
                utteranceOpen = false
                mode = Mode.IDLE
            }
            emitUtterance(text)
        }

        override fun onFailed(reason: String) {
            if (released) return
            events?.onFailed(reason)
        }
    }

    /**
     * Слушатель захвата: сегменты whisper-пути здесь не нужны — реплику режет сам движок ASR,
     * поэтому сегменты отбрасываются, а отказ захвата уходит в сервис.
     */
    private val captureListener = object : HandsFreeCapture.Listener {
        override fun onSpeechStarted() = Unit

        override fun onSpeechProgress(voicedMs: Long) = Unit

        override fun onSegment(recording: Recording) = Unit

        /**
         * Микрофон не открылся. Причина у этого колбэка не передаётся — чаще всего это
         * отозванное разрешение или занятое устройство, поэтому текст нейтральный:
         * выдумывать конкретную причину значит врать в уведомлении.
         */
        override fun onCaptureFailed() {
            events?.onFailed(MIC_FAILED)
        }
    }

    /**
     * Сырой поток микрофона → адресат по режиму. Вызывается С ПОТОКА ЧТЕНИЯ, на каждый
     * прочитанный буфер, ДО нарезки на сегменты.
     *
     * Буфер `pcm` переиспользуется следующим чтением:
     *  - в ASR байты уходят синхронно — [StreamingStt.acceptPcm] конвертирует их в float внутри
     *    своего лока и ссылку на буфер не держит, копия не нужна;
     *  - в детектор — через очередь, и там байты КОПИРУЮТСЯ ([PcmFeed.offer]): детектор
     *    декодирует на своём потоке, и отдавать ему буфер захвата нельзя.
     *
     * Исключения отсюда не выходят: [HandsFreeCapture] оборачивает вызов, но и сами мы не
     * бросаем — микрофон важнее любого движка.
     */
    private val rawPcmListener = HandsFreeCapture.RawPcmListener { pcm, length ->
        // Счёт кадров до маршрутизации: он отвечает на вопрос «байты вообще идут?» независимо
        // от того, слушает сейчас детектор, ASR или никто.
        if (length > 0) {
            frameBytes.addAndGet(length.toLong())
            if (isSilent(pcm, length)) frameMutedChunks.incrementAndGet()
        }
        when (mode) {
            Mode.UTTERANCE -> stt?.acceptPcm(pcm, length)
            Mode.WAKE -> wakeFeed?.offer(pcm, length)
            Mode.IDLE -> Unit
        }
    }

    /**
     * Чанк без единого ненулевого отсчёта — это и есть «система отдаёт нули» (`m:` в логе
     * `AudioRecordImpl`). Дешёвая проверка: 100 мс звука = 1600 отсчётов, скан на кадр
     * несопоставим по цене с самим чтением микрофона.
     */
    private fun isSilent(pcm: ByteArray, length: Int): Boolean {
        var i = 0
        val limit = minOf(length, pcm.size)
        while (i + 1 < limit) {
            if (pcm[i].toInt() != 0 || pcm[i + 1].toInt() != 0) return false
            i += 2
        }
        return true
    }

    /**
     * Единственная точка выдачи реплики в сервис.
     *
     * Один и тот же текст приходит дважды: из [StreamingStt.finishUtterance] (вызывающий поток)
     * и из события движка `onFinal` (main). Без склейки сервис отправил бы реплику провайдеру
     * дважды, поэтому одинаковый текст в пределах [UTTERANCE_DEDUPE_NS] выдаётся один раз.
     */
    private fun emitUtterance(text: String) {
        if (released) return
        val body = text.trim()
        if (body.isEmpty()) return
        val now = System.nanoTime()
        synchronized(utteranceLock) {
            val recent = now - lastUtteranceAtNs < UTTERANCE_DEDUPE_NS
            if (body == lastUtterance && recent) return
            lastUtterance = body
            lastUtteranceAtNs = now
        }
        events?.onUtterance(body)
    }

    /** Куда идёт звук с микрофона. */
    private enum class Mode {
        /** Микрофон может быть открыт, но звук никому не нужен (сервис думает или говорит). */
        IDLE,

        /** Ищем фразу-активатор: PCM идёт в детектор. */
        WAKE,

        /** Идёт реплика: PCM идёт в потоковый ASR. */
        UTTERANCE,
    }

    companion object {
        private const val TAG = "MarsVoiceEngine"

        /**
         * Тег счёта кадров: ровно `MarsVoice`, как у сервиса. Строка
         * `MarsVoice: frames f=N m=M` должна читаться тем же фильтром `adb logcat -s MarsVoice`,
         * что и `phase=WAKE`/`back to WAKE`.
         */
        private const val VOICE_TAG = "MarsVoice"

        /** Период строки `frames f=N m=M`: раз в 5 с видно, растут ли байты. */
        private const val FRAME_LOG_MS = 5_000L

        /** Сколько секунд без новых байт терпим, прежде чем переоткрыть микрофон. */
        private const val MIC_STALL_RESTART_MS = 15_000L

        /** Пауза перед переоткрытием захвата: системе нужно отдать устройство. */
        private const val REOPEN_DELAY_MS = 800L

        /** Сколько переоткрытий подряд, прежде чем честно сказать сервису «микрофон недоступен». */
        private const val MAX_REOPEN_ATTEMPTS = 5

        /** Модель распознавания не установлена — сервис покажет «нет модели». */
        const val NOT_READY = "распознавание недоступно: модель не установлена"

        /** Офлайн-синтез не готов — сервис покажет «нет синтеза». */
        const val TTS_NOT_READY = "синтез недоступен: модель не установлена"

        private const val GATE_TICK_MS = 10_000L

        const val ASR_UNAVAILABLE = "распознавание недоступно на этом устройстве"

        /** Микрофон не открылся (разрешение отозвано или устройство занято). */
        const val MIC_FAILED = "микрофон не открылся: проверьте разрешение на запись"

        /** Фразу-активатор искать нечем: native KeywordSpotter не поднялся. */
        const val WAKE_WORD_UNAVAILABLE = "фраза-активатор недоступна: движок не поднялся"

        /** Окно склейки двойного финала: одна и та же реплика приходит из двух мест подряд. */
        private const val UTTERANCE_DEDUPE_NS = 2_000_000_000L

        /**
         * Готовая фабрика для сервиса.
         *
         * Ставится один раз при старте приложения: `MarsVoiceService.installEngineFactory`
         * вызывает её из `onCreate`, то есть на главном потоке — создание захвата и
         * загрузка `.so` внутри неё были бы фризом, поэтому [LocalVoiceEngine] ленив.
         */
        fun factory(ttsProvider: () -> OfflineTtsPort?): ru.mars.mobile.service.VoiceEngineFactory =
            ru.mars.mobile.service.VoiceEngineFactory { context ->
                LocalVoiceEngine(context, ttsProvider())
            }
    }
}

/**
 * Очередь PCM между потоком чтения микрофона и потоком детектора фразы.
 *
 * Зачем очередь, а не прямой вызов: детектор декодирует 100 мс звука на своём потоке, и делать
 * это внутри колбэка захвата значило бы останавливать чтение микрофона на время decode — с
 * переполнением буфера `AudioRecord` и потерей звука.
 *
 * Контракт: [offer] никогда не блокируется (поток чтения важнее) и КОПИРУЕТ байты, потому что
 * буфер захвата переиспользуется; при переполнении выбрасывается самый старый кусок — для поиска
 * фразы свежий звук полезнее пропущенного. [take] блокируется до появления данных или [close].
 */
private class PcmFeed {

    private val chunks = LinkedBlockingDeque<ByteArray>(CAPACITY_CHUNKS)

    @Volatile
    private var closed: Boolean = false

    /** Положить кусок в очередь. Копия обязательна: [pcm] принадлежит вызывающему. */
    fun offer(pcm: ByteArray, length: Int) {
        if (length <= 0 || closed) return
        val count = minOf(length, pcm.size)
        if (count <= 0) return
        val copy = pcm.copyOfRange(0, count)
        // Поток чтения не блокируем: при переполнении освобождаем место самым старым куском.
        while (!chunks.offerLast(copy)) {
            if (chunks.pollFirst() == null) break
        }
    }

    /** Забрать кусок в [dst]. Возвращает число байт или -1, когда очередь закрыта. */
    fun take(dst: ByteArray): Int {
        while (true) {
            if (closed) return -1
            val chunk = try {
                chunks.pollFirst(TAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (ignored: InterruptedException) {
                // Детектор останавливают через interrupt: выходим так же, как по close().
                Thread.currentThread().interrupt()
                return -1
            }
            // null — истёк тайм-аут ожидания: проверяем closed и ждём дальше.
            if (chunk == null) continue
            val count = minOf(chunk.size, dst.size)
            System.arraycopy(chunk, 0, dst, 0, count)
            return count
        }
    }

    /** Закрыть очередь: чтение вернёт -1, накопленное выбрасывается. Идемпотентно. */
    fun close() {
        closed = true
        chunks.clear()
    }

    private companion object {
        /** 8 кусков по 100 мс: детектору хватает запаса, а задержка остаётся незаметной. */
        const val CAPACITY_CHUNKS = 8

        /** Период проверки [closed] в ожидании: выход по close() не должен ждать данных. */
        const val TAKE_TIMEOUT_MS = 100L
    }
}

/**
 * [AudioSource] поверх [PcmFeed]: детектор фразы читает из неё так же, как читал бы из
 * `AudioRecord`, но звук приходит с единственного настоящего захвата.
 */
private class PcmRelaySource(private val feed: PcmFeed) : AudioSource {

    override fun read(buf: ByteArray): Int = feed.take(buf)

    override fun release() = feed.close()
}
