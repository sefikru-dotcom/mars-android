package ru.mars.mobile

import android.content.Context
import android.util.Log
import ru.mars.mobile.asr.AsrBackend
import ru.mars.mobile.asr.AsrConfig
import ru.mars.mobile.asr.AsrModelSource
import ru.mars.mobile.asr.ModelDownloader
import ru.mars.mobile.asr.Pcm16ToFloat
import ru.mars.mobile.asr.SherpaAsrBackend

/**
 * Потоковый распознаватель речи на sherpa-onnx (RU zipformer small int8).
 *
 * Контракт для цикла голосового диалога:
 * - [load] один раз (идемпотентно) — модель при необходимости докачивается из сети;
 * - [startUtterance] — новая реплика;
 * - [acceptPcm] — сырые байты с микрофона (16 кГц, моно, 16-bit LE), сколько угодно раз;
 * - [finishUtterance] — человек нажал «стоп», вернуть финальный текст;
 * - [release] — освободить native-ресурсы.
 *
 * Потокобезопасность: все методы берут внутренний лок, поэтому движок можно звать прямо
 * с потока чтения микрофона. Слушатель вызывается ВНЕ лока (чтобы колбэк мог, например,
 * обновить UI и не поймать дедлок), и его нельзя звать обратно в движок синхронно из
 * другого потока в том же вызове — события доставляются в порядке обработки аудио.
 *
 * Модель в APK не входит: [modelSource] по умолчанию — [ModelDownloader] в filesDir
 * (23 МиБ архив, проверка SHA-256, атомарная установка). В тестах подменяется фейком.
 */
class StreamingAsrEngine(
    private val modelSource: AsrModelSource,
    private val config: AsrConfig = AsrConfig(),
    private val listener: (AsrEvent) -> Unit = {},
    private val backendFactory: () -> AsrBackend = { SherpaAsrBackend() },
) {
    /** Готовый к работе движок для приложения: модель в `<filesDir>/asr`. */
    constructor(
        context: Context,
        config: AsrConfig = AsrConfig(),
        listener: (AsrEvent) -> Unit = {},
    ) : this(ModelDownloader.forApp(context.applicationContext), config, listener)

    private val lock = Any()
    private val pcmConverter = Pcm16ToFloat()

    /** Создаётся лениво: до [load] native-слой не трогаем (в JVM-тестах он недоступен). */
    private var backend: AsrBackend? = null

    @Volatile
    private var ready = false

    @Volatile
    private var released = false

    /** Есть активная реплика (stream создан и ещё не закрыт). */
    private var utteranceActive = false

    /** Последний отданный partial: чтобы не дублировать одинаковые события. */
    private var lastPartial = ""

    /** Речь в текущей реплике уже началась (для одного [AsrEvent.SpeechStarted] на реплику). */
    private var speechSeen = false

    /** true, если модель загружена и движок готов. */
    val isReady: Boolean get() = ready

    /** true, если контекстный биасинг реально включён (не откатились на greedy_search). */
    @Volatile
    var isHotwordsActive: Boolean = false
        private set

    /**
     * Загрузить модель (идемпотентно, потокобезопасно).
     *
     * Первый вызов может скачать 23 МиБ — вызывать не с UI-потока. Если распознаватель
     * не поднялся с hotwords (например, не прочитался bpe.model), один раз пробуем без
     * них: диалог важнее биасинга.
     *
     * @return false при ошибке (нет сети и модели нет, битые файлы, нет arm64-ABI).
     */
    fun load(): Boolean {
        synchronized(lock) {
            if (released) return false
            if (ready) return true

            if (!modelSource.isAvailable() && !modelSource.ensure()) {
                Log.w(TAG, "ASR model is not available")
                return false
            }
            val dir = modelSource.modelDir
            if (tryInit(dir, config)) {
                ready = true
                isHotwordsActive = config.hotwords.isNotEmpty()
                return true
            }
            if (config.hotwords.isNotEmpty() && tryInit(dir, config.withoutHotwords())) {
                Log.w(TAG, "ASR started without hotwords")
                ready = true
                isHotwordsActive = false
                return true
            }
            return false
        }
    }

    /** Начать новую реплику: сбросить состояние распознавателя. */
    fun startUtterance() {
        synchronized(lock) {
            if (!ready || released) return
            beginUtteranceLocked()
        }
    }

    /**
     * Скормить PCM: 16 кГц, моно, 16-bit little-endian. Колбэк вызывается для каждого события.
     *
     * @param pcm буфер записи; валидны первые [length] байт.
     * @param length фактическое число байт (может быть нечётным — младший байт переносится
     *   в следующий вызов).
     */
    fun acceptPcm(pcm: ByteArray, length: Int) {
        if (!ready || released) return

        val events = ArrayList<AsrEvent>(2)
        synchronized(lock) {
            if (!ready || released) return
            val b = backend ?: return
            // Конвертация внутри лока: перенос непарного байта — состояние, а не чистая функция;
            // finishUtterance/release с другого потока не должны его порвать.
            val samples = pcmConverter.convert(pcm, length)
            if (samples.isEmpty()) return
            if (!utteranceActive) beginUtteranceLocked()

            b.acceptSamples(samples)
            drainLocked(b)

            val text = b.result().trim()
            if (text.isNotEmpty()) {
                if (!speechSeen) {
                    speechSeen = true
                    events += AsrEvent.SpeechStarted
                }
                if (text != lastPartial) {
                    lastPartial = text
                    events += AsrEvent.Partial(text)
                }
            }
            if (b.isEndpoint()) {
                if (lastPartial.isNotEmpty()) events += AsrEvent.Final(lastPartial)
                // Endpoint — граница реплики: свежий stream, но микрофон продолжает литься,
                // поэтому utteranceActive остаётся true и следующий текст снова даст SpeechStarted.
                beginUtteranceLocked()
            }
        }
        dispatch(events)
    }

    /**
     * Принудительно завершить реплику (человек нажал «стоп»): вернуть финальный текст.
     *
     * @return распознанный текст реплики; пустая строка, если ничего не распознано
     *   или реплика не начиналась.
     */
    fun finishUtterance(): String {
        var text = ""
        val events = ArrayList<AsrEvent>(1)
        synchronized(lock) {
            if (!ready || released || !utteranceActive) return ""
            val b = backend ?: return ""
            b.finishStream()
            drainLocked(b)
            text = b.result().trim().ifEmpty { lastPartial }
            if (text.isNotEmpty()) events += AsrEvent.Final(text)
            utteranceActive = false
            lastPartial = ""
            speechSeen = false
            pcmConverter.reset()
        }
        dispatch(events)
        return text
    }

    /** Освободить ресурсы. Повторный вызов и вызов до [load] безопасны. */
    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            ready = false
            utteranceActive = false
            lastPartial = ""
            speechSeen = false
            isHotwordsActive = false
            pcmConverter.reset()
            try {
                backend?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "backend release failed", t)
            }
            backend = null
        }
    }

    private fun beginUtteranceLocked() {
        val b = backendOrCreate()
        b.newStream(config.hotwords)
        utteranceActive = true
        lastPartial = ""
        speechSeen = false
    }

    private fun backendOrCreate(): AsrBackend = backend ?: backendFactory().also { backend = it }

    private fun tryInit(dir: java.io.File, cfg: AsrConfig): Boolean = try {
        backendOrCreate().initialize(dir, cfg)
    } catch (t: Throwable) {
        Log.e(TAG, "ASR backend init failed", t)
        false
    }

    /**
     * Вычерпать готовые кадры. Ограничение сверху — страховка от зависшего native-цикла:
     * аудио идёт реального времени, поэтому «вечно ready» означает баг, а не нагрузку.
     */
    private fun drainLocked(b: AsrBackend) {
        var steps = 0
        while (b.isReady() && steps < MAX_DECODE_STEPS) {
            b.decode()
            steps++
        }
    }

    private fun dispatch(events: List<AsrEvent>) {
        for (event in events) {
            try {
                listener(event)
            } catch (t: Throwable) {
                // Исключение в UI-колбэке не должно убивать поток записи.
                Log.w(TAG, "ASR listener failed on $event", t)
            }
        }
    }

    companion object {
        private const val TAG = "MarsAsrEngine"

        /** Максимум декодирований за один кусок аудио (реально — единицы). */
        private const val MAX_DECODE_STEPS = 64
    }
}
