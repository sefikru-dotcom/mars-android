package ru.mars.mobile

import java.io.ByteArrayOutputStream

/**
 * Непрерывная запись для hands-free: микрофон открыт всё время диалога, а не «по тапу».
 *
 * Отличие от [VoiceRecorder] принципиальное: там одна сессия = одна реплика (человек нажал,
 * сказал, запись закрылась по тишине). В диалоге человек не нажимает ничего — он говорит, слушает
 * ответ и говорит снова, поэтому поток микрофона не прерывается, а на реплики его режет
 * [VoiceSegmenter]. Каждый готовый сегмент уходит хосту как отдельная [Recording] (WAV 16 кГц
 * моно — тот же формат, что уходит в `POST /api/transcribe`; контракт сервера не меняется).
 *
 * Почему не переиспользован [VoiceRecorder]: его буфер копит ВСЮ сессию и отдаёт один WAV, а в
 * hands-free сессия длится минутами — это десятки мегабайт и один запрос на весь диалог. Здесь
 * память ограничена одним сегментом, а не длительностью разговора.
 *
 * Поток чтения — отдельный демон (как у [VoiceRecorder]), поэтому [start]/[stop] не блокируют
 * вызывающего на микрофонном I/O. Колбэки приходят С ПОТОКА ЧТЕНИЯ: хост обязан переложить их
 * на главный поток сам (в ChatActivity это `runOnUiThread`).
 *
 * Потеря последней реплики: [stop] НЕ выбрасывает недоговорённый сегмент — он закрывается через
 * [VoiceSegmenter.flush] и отдаётся хосту до возврата из [stop]. Обратная сторона — [cancel]
 * (человек выключил режим, экран ушёл): там flush не делается намеренно, иначе отменённая
 * реплика всё равно ушла бы в STT.
 */
class HandsFreeCapture(
    private val sourceFactory: AudioSourceFactory = AndroidAudioSourceFactory(),
    private val segmenter: VoiceSegmenter = VoiceSegmenter(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** Куда приходят события распознавания. Все методы — с потока чтения микрофона. */
    interface Listener {
        /** Начало речи: хост может показать «слушаю» и (при barge-in) перебить озвучку. */
        fun onSpeechStarted() {}

        /** Готовая реплика в формате серверного контракта. */
        fun onSegment(recording: Recording) {}

        /** Микрофон не открылся: разрешение отозвано, устройство занято, источник сломан. */
        fun onCaptureFailed() {}
    }

    /**
     * Сырой поток PCM, ДО нарезки на реплики: каждый буфер, прочитанный из микрофона.
     *
     * Нужен потоковому ASR: движку требуется непрерывный звук и он сам определяет конец реплики,
     * поэтому отдавать ему сегменты после [VoiceSegmenter] поздно (это +hangover тишины к каждой
     * фразе) и мало (сегмент уже склеен). Колбэк вызывается С ПОТОКА ЧТЕНИЯ, до
     * [Listener.onSegment], поэтому звук уходит в движок с минимальной задержкой.
     *
     * Контракт: [pcm] — тот же буфер, что читается из источника, и он ПЕРЕИСПОЛЬЗУЕТСЯ следующим
     * чтением. Сохранять ссылку нельзя; если реализация не успевает обработать звук синхронно,
     * она обязана скопировать нужные байты. Исключение из колбэка не рвёт захват: микрофон важнее.
     */
    fun interface RawPcmListener {
        fun onRawPcm(pcm: ByteArray, length: Int)
    }

    private val lock = Any()

    private var worker: Thread? = null

    @Volatile private var running = false

    @Volatile private var cancelRequested = false

    /** Слушатель текущей сессии: нужен и потоку чтения, и [finish] (хвост реплики). */
    @Volatile private var listener: Listener? = null

    /** Потребитель сырого потока (потоковый ASR); `null` — движка нет, звук идёт только в сегментер. */
    @Volatile private var rawListener: RawPcmListener? = null

    /** true, пока микрофон открыт (между успешным [start] и выходом потока чтения). */
    val isCapturing: Boolean get() = running

    /**
     * Открыть микрофон и начать резать поток на реплики.
     *
     * @param rawPcm необязательный потребитель НЕнарезанного потока (потоковый ASR). Вызывается с
     *   потока чтения перед [Listener.onSegment]; `null` — только whisper-путь.
     * @return false без побочных эффектов, если захват уже идёт или источник не открылся.
     */
    fun start(listener: Listener, rawPcm: RawPcmListener? = null): Boolean {
        val thread: Thread
        synchronized(lock) {
            if (running || worker != null) return false
            val source = try {
                sourceFactory.create()
            } catch (denied: SecurityException) {
                return false
            } catch (ignored: Exception) {
                return false
            }
            // Новая сессия — новые отсчёты: хвост прошлой реплики не должен склеиться с этой.
            segmenter.reset()
            cancelRequested = false
            running = true
            this.listener = listener
            this.rawListener = rawPcm
            thread = Thread({ readLoop(source) }, THREAD_NAME).apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
            worker = thread
        }
        thread.start()
        return true
    }

    /**
     * Закрыть микрофон, отдав недоговорённую реплику (защита от потери последней фразы).
     * Идемпотентно; повторный вызов ничего не отдаёт.
     */
    fun stop() = finish(flush = true)

    /** Закрыть микрофон, выбросив недоговорённую реплику (режим выключили — слушать нечего). */
    fun cancel() = finish(flush = false)

    private fun finish(flush: Boolean) {
        val thread: Thread
        val target: Listener?
        synchronized(lock) {
            thread = worker ?: return
            if (!flush) cancelRequested = true
            running = false
            target = listener
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
                rawListener = null
            }
        }
        // Хвост отдаём здесь, а не в потоке чтения: [stop] обязан вернуть управление только
        // после того, как последняя фраза уже у хоста (иначе она теряется при выходе из режима).
        if (flush && !cancelRequested) deliver(segmenter.flush(), target)
    }

    private fun readLoop(source: AudioSource) {
        val buf = ByteArray(VoiceRecorder.READ_CHUNK_BYTES)
        try {
            while (running && !cancelRequested) {
                val read = try {
                    source.read(buf)
                } catch (ignored: Exception) {
                    break
                }
                if (read <= 0) break
                feedRaw(buf, read)
                deliver(segmenter.onChunk(buf, nowMs(), read))
            }
        } finally {
            running = false
            releaseQuietly(source)
        }
    }

    /** Сырой поток — до нарезки: движку нужен непрерывный звук и минимальная задержка. */
    private fun feedRaw(buf: ByteArray, read: Int) {
        val sink = rawListener ?: return
        try {
            sink.onRawPcm(buf, read)
        } catch (ignored: Exception) {
            // Движок упал на этом буфере — микрофон и whisper-путь должны пережить это.
        }
    }

    private fun deliver(event: VoiceSegmenter.Event, target: Listener? = listener) {
        val sink = target ?: return
        when (event) {
            is VoiceSegmenter.Event.None -> Unit
            is VoiceSegmenter.Event.SpeechStarted -> notify(sink) { it.onSpeechStarted() }
            is VoiceSegmenter.Event.Segment -> {
                val pcm = event.pcm
                if (pcm.isEmpty()) return
                val recording = Recording(
                    wav = PcmWavWriter.encode(pcm, VoiceRecorder.SAMPLE_RATE, VoiceRecorder.CHANNELS),
                    sampleRate = VoiceRecorder.SAMPLE_RATE,
                    durationMs = event.durationMs,
                )
                notify(sink) { it.onSegment(recording) }
            }
        }
    }

    /** Хост не должен ронять поток чтения: его исключение — не повод потерять микрофон. */
    private inline fun notify(target: Listener, block: (Listener) -> Unit) {
        try {
            block(target)
        } catch (ignored: Exception) {
            // Микрофон уже открыт, сегмент уже собран: молча продолжаем слушать.
        }
    }

    private fun releaseQuietly(source: AudioSource) {
        try {
            source.release()
        } catch (ignored: Exception) {
            // Сбой release не должен маскировать уже отданные сегменты.
        }
    }

    private companion object {
        const val THREAD_NAME = "mars-voice-hands-free"

        /** Больше ждать поток чтения нельзя: UI не должен зависнуть на закрытии режима. */
        const val STOP_JOIN_TIMEOUT_MS = 1_500L
    }
}
