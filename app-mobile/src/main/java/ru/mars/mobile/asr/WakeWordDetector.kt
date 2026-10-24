package ru.mars.mobile.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import ru.mars.mobile.AndroidAudioSourceFactory
import ru.mars.mobile.AudioSource
import ru.mars.mobile.AudioSourceFactory
import ru.mars.mobile.VoiceRecorder

/**
 * Порт к нативному поиску ключевой фразы.
 *
 * Зачем интерфейс: `KeywordSpotter` требует загруженной `.so` и на JVM не поднимается, поэтому
 * вся логика прослушивания (жизненный цикл, конвертация PCM, дебаунс, состояния) живёт в
 * [WakeWordDetector] и проверяется юнит-тестами с фейковым портом. Реальная реализация —
 * [SherpaWakeWordPort].
 *
 * Контракт: методы вызываются с потока чтения микрофона; реализация обязана быть
 * потокобезопасной и не бросать исключений на «пустых» состояниях.
 */
interface WakeWordPort {
    /**
     * Поднять детектор по каталогу модели и готовому keywords-файлу.
     * @return false, если native-слой не смог загрузить модель (битые файлы, нет ABI).
     */
    fun initialize(modelDir: File, keywordsFile: File, config: WakeWordConfig): Boolean

    /**
     * Скормить float32 -1..1, 16 кГц, моно.
     * @return найденная ключевая фраза (строка из keywords-файла) или пустая строка.
     */
    fun accept(samples: FloatArray): String

    /** Сбросить состояние поиска (после детекции или по требованию хоста). */
    fun reset()

    /** Освободить native-ресурсы. Повторный вызов безопасен. */
    fun release()
}

/**
 * Детектор wake word «Марс, слушай» поверх уже скачанной ASR-модели.
 *
 * Устройство и границы:
 *  - модель НЕ качается: пока [ModelStore.isInstalled] ложно, детектор сразу отвечает
 *    [State.MODEL_MISSING] и микрофон не открывает (тихое скачивание 23 МиБ из-за тумблера
 *    в настройках недопустимо);
 *  - микрофон открывается только на время [start]..[stop], то есть пока экран активен и флаг
 *    включён; фонового захвата здесь нет (для него нужен foreground-сервис с типом microphone —
 *    отдельное решение, не эта волна);
 *  - после срабатывания хост сразу гасит сессию и передаёт микрофон обычному голосовому диалогу
 *    (детектор сам микрофон не закрывает — иначе между «нашёл» и «открыл диалог» был бы разрыв,
 *    в который фраза теряется);
 *
 * Поток чтения — отдельный демон (как у [ru.mars.mobile.HandsFreeCapture]), поэтому [start]/[stop]
 * не блокируют вызывающего на микрофонном I/O. Колбэк [start] приходит С ПОТОКА ЧТЕНИЯ: хост
 * обязан сам переложить его на главный поток.
 *
 * @param modelStore раскладка модели на диске (та же, что у потокового ASR).
 * @param policyFactory сборка политики по каталогу модели (читает `tokens.txt`); в тестах —
 *   политика поверх синтетического словаря.
 * @param portFactory фабрика нативного порта: создаётся на потоке чтения, чтобы загрузка модели
 *   не трогала UI-поток.
 * @param nowMs монотонные миллисекунды для дебаунса (в тестах — управляемые часы).
 */
class WakeWordDetector(
    private val modelStore: ModelStore,
    private val policyFactory: (File) -> WakeWordPolicy,
    private val portFactory: () -> WakeWordPort = { SherpaWakeWordPort() },
    private val sourceFactory: AudioSourceFactory = AndroidAudioSourceFactory(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** Готовый к работе детектор для приложения: модель в `<filesDir>/asr`. */
    constructor(
        context: Context,
        config: WakeWordConfig = WakeWordConfig(),
    ) : this(
        modelStore = ModelStore(ModelStore.rootForAppFilesDir(context.applicationContext.filesDir)),
        policyFactory = { modelDir -> WakeWordPolicy.forModelDir(modelDir, config) },
    )

    /**
     * Состояние детектора — то, что показывает компактный индикатор в чате.
     *
     * [MODEL_MISSING] и [UNAVAILABLE] не ошибки приложения: это честная деградация вместо
     * падения и вместо тихого скачивания модели.
     */
    enum class State {
        /** Не слушаем: выключено, экран в фоне или диалог уже слушает сам. */
        IDLE,

        /** Микрофон открыт, фраза ищется. */
        LISTENING,

        /** Модель ASR не скачана — wake word недоступен до первого голосового диалога. */
        MODEL_MISSING,

        /** Микрофон занят или native-слой не поднялся (нет ABI, битые файлы). */
        UNAVAILABLE,
    }

    private val lock = Any()

    private var worker: Thread? = null

    @Volatile
    private var running = false

    /** Текущая политика сессии: создаётся в [start] вместе с keywords-файлом. */
    @Volatile
    private var activePolicy: WakeWordPolicy? = null

    /** Порт текущей сессии: нужен [reset] с чужого потока (порт потокобезопасен). */
    @Volatile
    private var activePort: WakeWordPort? = null

    @Volatile
    private var listener: ((String) -> Unit)? = null

    @Volatile
    private var stateListener: ((State) -> Unit)? = null

    @Volatile
    var state: State = State.IDLE
        private set

    /** true, пока микрофон открыт (между успешным [start] и выходом потока чтения). */
    val isListening: Boolean get() = running

    /**
     * Открыть микрофон и начать искать фразу.
     *
     * @param onDetection найдена фраза (вызывается С ПОТОКА ЧТЕНИЯ; повторные срабатывания на
     *   хвосте той же фразы отсекает дебаунс [WakeWordConfig.repeatSuppressMs]).
     * @param onState смена состояния; вызывается и при мгновенном отказе, поэтому индикатор
     *   не остаётся в «слушаю», когда слушать нечего.
     * @return false без побочных эффектов, если уже слушаем, нет модели или микрофон не открылся.
     */
    fun start(onDetection: (String) -> Unit, onState: (State) -> Unit = {}): Boolean {
        var thread: Thread? = null
        var failure: State? = null
        synchronized(lock) {
            if (running || worker != null) return false
            if (!modelStore.isInstalled()) {
                Log.w(TAG, "wake word: ASR model is not installed")
                failure = State.MODEL_MISSING
            } else {
                val policy = try {
                    policyFactory(modelStore.modelDir)
                } catch (t: Throwable) {
                    Log.e(TAG, "wake word: model tokens are unreadable", t)
                    null
                }
                val source = if (policy == null) null else openSource()
                if (policy == null || source == null) {
                    failure = State.UNAVAILABLE
                } else if (!writeKeywordsFile(policy)) {
                    releaseQuietly(source)
                    failure = State.UNAVAILABLE
                } else {
                    // Дебаунс живёт в политике: новая сессия не наследует паузу прошлой.
                    policy.reset()
                    activePolicy = policy
                    listener = onDetection
                    stateListener = onState
                    running = true
                    state = State.LISTENING
                    thread = Thread({ readLoop(source) }, THREAD_NAME).apply {
                        isDaemon = true
                        priority = Thread.NORM_PRIORITY
                    }
                    worker = thread
                }
            }
            if (failure != null) {
                state = requireNotNull(failure)
                listener = null
                stateListener = null
            }
        }
        val failed = failure
        if (failed != null) {
            onState(failed)
            return false
        }
        thread?.start()
        // Состояние читаем после старта потока: native мог не подняться мгновенно, и тогда
        // индикатор должен получить уже UNAVAILABLE, а не «слушаю».
        onState(state)
        return true
    }

    /**
     * Закрыть микрофон и освободить native-слой.
     *
     * native освобождается в потоке чтения (в `finally`), а [stop] дожидается его завершения —
     * поэтому к возврату из метода `KeywordSpotter` уже release'нут и микрофон свободен.
     * Идемпотентно.
     */
    fun stop() {
        val thread: Thread
        synchronized(lock) {
            thread = worker ?: return
            running = false
        }
        thread.interrupt()
        if (thread !== Thread.currentThread()) {
            try {
                thread.join(STOP_JOIN_TIMEOUT_MS)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        synchronized(lock) {
            if (worker === thread) {
                worker = null
                listener = null
                stateListener = null
                activePolicy = null
                activePort = null
                state = State.IDLE
            }
        }
    }

    /**
     * Сбросить поиск, не закрывая микрофон: дебаунс и native-поток начинают с чистого листа.
     *
     * Нужен, когда хост знает, что звук перед этим был «свой» (озвучка ответа, музыка), и не
     * хочет, чтобы хвост фразы сложился с новым аудио.
     */
    fun reset() {
        val policy = activePolicy
        val port = activePort
        policy?.reset()
        try {
            port?.reset()
        } catch (t: Throwable) {
            Log.w(TAG, "wake word: port reset failed", t)
        }
    }

    /** Поток чтения: инициализация native, затем непрерывное чтение микрофона. */
    private fun readLoop(source: AudioSource) {
        val port = portFactory()
        activePort = port
        val policy = activePolicy
        val ready = if (policy == null) {
            false
        } else {
            try {
                port.initialize(modelStore.modelDir, keywordsFile(), policy.config)
            } catch (t: Throwable) {
                // Ошибки конфига/файлов приходят исключением; SIGABRT native поймать нельзя, но
                // тогда процесс умирает целиком и деградировать нечему.
                Log.e(TAG, "wake word: keyword spotter failed to start", t)
                false
            }
        }
        if (!ready) {
            Log.w(TAG, "wake word: keyword spotter is unavailable")
            releaseQuietly(source)
            finish(port, State.UNAVAILABLE)
            return
        }

        val raw = ByteArray(VoiceRecorder.READ_CHUNK_BYTES)
        val pcm = Pcm16ToFloat()
        var failed = false
        try {
            while (running) {
                val read = try {
                    source.read(raw)
                } catch (ignored: Exception) {
                    break
                }
                if (read <= 0) break
                val samples = pcm.convert(raw, read)
                if (samples.isEmpty()) continue
                val keyword = try {
                    port.accept(samples)
                } catch (t: Throwable) {
                    Log.e(TAG, "wake word: keyword spotter failed on audio", t)
                    failed = true
                    break
                }
                if (keyword.isEmpty()) continue
                if (policy == null || !policy.acceptDetection(nowMs())) {
                    // Тот же возглас в хвосте аудио: детекцию не отдаём, но поиск продолжаем.
                    port.reset()
                    continue
                }
                port.reset()
                // Фразу отдаём хосту, но микрофон НЕ закрываем сами: сессию гасит хост
                // (в чате — [ru.mars.mobile.ChatActivity.onWakeWordDetected] сразу зовёт stop и
                // передаёт микрофон голосовому диалогу). Пока stop не пришёл, хвост той же фразы
                // продолжает приходить в порт — его отсекает дебаунс политики.
                deliver(keyword)
            }
        } finally {
            releaseQuietly(source)
            finish(port, if (failed) State.UNAVAILABLE else State.IDLE)
        }
    }

    /**
     * Завершение сессии: освободить native и отчитать состояние.
     *
     * Состояние отдаётся ТОЛЬКО если сессия ещё «наша»: после [stop] индикатор уже погашен, и
     * поздний колбэк из умирающего потока не должен вернуть его в «слушаю».
     */
    private fun finish(port: WakeWordPort, terminal: State) {
        try {
            port.release()
        } catch (t: Throwable) {
            Log.w(TAG, "wake word: port release failed", t)
        }
        var sink: ((State) -> Unit)? = null
        synchronized(lock) {
            running = false
            if (worker === Thread.currentThread()) worker = null
            listener = null
            activePolicy = null
            if (activePort === port) activePort = null
            sink = stateListener
            if (sink != null) {
                stateListener = null
                state = terminal
            }
        }
        sink?.let { report ->
            try {
                report(terminal)
            } catch (ignored: Exception) {
                // Индикатор не должен ронять поток чтения: микрофон важнее.
            }
        }
    }

    /** Колбэк детекции: исключение хоста не рвёт закрытие сессии. */
    private fun deliver(keyword: String) {
        val sink = listener ?: return
        try {
            sink(keyword)
        } catch (ignored: Exception) {
            // Фраза уже найдена; сбой UI-обработчика — не повод оставить микрофон открытым.
        }
    }

    private fun openSource(): AudioSource? = try {
        sourceFactory.create()
    } catch (denied: SecurityException) {
        // Разрешение RECORD_AUDIO отозвано между проверкой и стартом.
        Log.w(TAG, "wake word: RECORD_AUDIO is not granted")
        null
    } catch (ignored: Exception) {
        // Устройство занято другим приложением или источник сломан.
        null
    }

    /**
     * Записать keywords-файл рядом с моделью.
     *
     * Файл маленький (три строки) и пересобирается на каждый старт: держать его в APK нельзя
     * (он зависит от словаря скачанной модели), а хранить между версиями модели — значит однажды
     * скормить native токены чужого словаря.
     */
    private fun writeKeywordsFile(policy: WakeWordPolicy): Boolean = try {
        val file = keywordsFile()
        file.parentFile?.mkdirs()
        file.writeText(policy.keywordsFileContent())
        true
    } catch (t: Throwable) {
        Log.e(TAG, "wake word: cannot write keywords file", t)
        false
    }

    private fun keywordsFile(): File = File(modelStore.rootDir, KEYWORDS_FILE_NAME)

    private fun releaseQuietly(source: AudioSource) {
        try {
            source.release()
        } catch (ignored: Exception) {
            // Сбой release не должен маскировать уже отданную детекцию.
        }
    }

    private companion object {
        const val TAG = "MarsWakeWord"

        const val THREAD_NAME = "mars-wake-word"

        /** Больше ждать поток чтения нельзя: onPause не должен зависать на закрытии микрофона. */
        const val STOP_JOIN_TIMEOUT_MS = 1_500L

        /** Собранный keywords-файл: лежит рядом с моделью, переживает перезапуск приложения. */
        const val KEYWORDS_FILE_NAME = "wake-keywords.txt"
    }
}

/**
 * Реализация [WakeWordPort] на sherpa-onnx (JitPack, `v1.13.8`).
 *
 * Ключевые факты, заложенные в код (замер на сервере, та же модель, что у потокового ASR):
 * 1. Отдельная KWS-модель НЕ нужна: `KeywordSpotter` принимает нашу русскую
 *    `zipformer-small-ru-vosk-int8`. Размер APK не растёт.
 * 2. `modelType = "zipformer2"` обязателен: при `"zipformer"` native читает `attention_dims`,
 *    которых у zipformer2 нет, и вызывает `SHERPA_ONNX_EXIT(-1)` — процесс умирает без исключения.
 * 3. `numThreads = 1`: основной ASR держит два потока, и поиск фразы не должен отнимать у него CPU.
 * 4. Порог срабатывания — из [WakeWordConfig] (0.10, а не дефолтные 0.25: на 0.25 не срабатывало
 *    вообще ничего).
 */
class SherpaWakeWordPort : WakeWordPort {

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    @Synchronized
    override fun initialize(modelDir: File, keywordsFile: File, config: WakeWordConfig): Boolean {
        release()
        if (!modelDir.isDirectory || !keywordsFile.isFile) {
            Log.w(TAG, "wake word: model dir or keywords file is missing")
            return false
        }

        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = File(modelDir, ENCODER).absolutePath,
                decoder = File(modelDir, DECODER).absolutePath,
                joiner = File(modelDir, JOINER).absolutePath,
            ),
            tokens = File(modelDir, TOKENS).absolutePath,
            numThreads = config.numThreads,
            debug = false,
            provider = "cpu",
            modelType = "zipformer2",
        )

        val spotterConfig = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = modelConfig,
            maxActivePaths = MAX_ACTIVE_PATHS,
            keywordsFile = keywordsFile.absolutePath,
            keywordsScore = config.score,
            keywordsThreshold = config.threshold,
        )

        return try {
            val created = KeywordSpotter(config = spotterConfig)
            val createdStream = created.createStream()
            spotter = created
            stream = createdStream
            true
        } catch (t: Throwable) {
            Log.e(TAG, "wake word: failed to create KeywordSpotter", t)
            release()
            false
        }
    }

    @Synchronized
    override fun accept(samples: FloatArray): String {
        val spotter = this.spotter ?: return ""
        val stream = this.stream ?: return ""
        if (samples.isEmpty()) return ""
        stream.acceptWaveform(samples, SAMPLE_RATE)
        // Декодируем до опустошения очереди кадров: getResult на недочитанном потоке вернул бы
        // пустую строку, и фраза «потерялась» бы на этом чанке.
        while (spotter.isReady(stream)) spotter.decode(stream)
        return spotter.getResult(stream).keyword
    }

    @Synchronized
    override fun reset() {
        val spotter = this.spotter ?: return
        val stream = this.stream ?: return
        spotter.reset(stream)
    }

    @Synchronized
    override fun release() {
        stream?.release()
        stream = null
        spotter?.release()
        spotter = null
    }

    private companion object {
        const val TAG = "MarsWakeWord"

        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80
        const val MAX_ACTIVE_PATHS = 4

        const val ENCODER = "encoder.int8.onnx"
        const val DECODER = "decoder.onnx"
        const val JOINER = "joiner.int8.onnx"
        const val TOKENS = "tokens.txt"
    }
}
