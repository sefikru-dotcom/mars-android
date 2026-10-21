package ru.mars.mobile

/**
 * Старый путь распознавания, приведённый к тому же шву [SpeechToText]: реплика → `POST
 * /api/transcribe` → текст.
 *
 * Нужен для двух случаев:
 * - **деградация**: движок потокового ASR не загрузился (нет нативной библиотеки, модель не
 *   влезла в память, устройство не поддерживается) — диалог продолжается, просто медленнее;
 * - **хосты без движка** (TV, старые сборки), которым проще отдать PCM в один объект, чем
 *   держать свой сегментер и свой `requestId`-учёт.
 *
 * Режет поток на реплики тот же [VoiceSegmenter], что и [HandsFreeCapture]: endpoint-детекции у
 * whisper нет, поэтому таймаут тишины здесь — единственный способ понять, что человек закончил.
 * Это и есть источник задержки 2.8–5.3 с (сервер всегда гоняет 30-секундное окно), поэтому
 * основной путь — [StreamingStt], а этот остаётся запасным.
 *
 * Асинхронность: [finishUtterance] не может вернуть текст сразу (сеть), поэтому возвращает
 * `null`, а текст приходит событием [SpeechToText.Events.onFinal]. Цикл это понимает и продолжает
 * ждать финал, а не считает реплику закрытой.
 *
 * @param transcribe отправка WAV в `POST /api/transcribe`; `onResult(null)` — сеть/400/503.
 *   Хост сам перекладывает колбэк на главный поток (он приходит с потока корутины/IO).
 */
class WhisperStt(
    private val transcribe: (wav: ByteArray, onResult: (String?) -> Unit) -> Unit,
    private val segmenter: VoiceSegmenter = VoiceSegmenter(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : SpeechToText {

    override val id: String get() = ID

    /** Whisper доступен всегда: единственная зависимость — сеть, её проверяет сам POST. */
    override val isReady: Boolean get() = true

    override var events: SpeechToText.Events? = null

    private val lock = Any()

    private var utteranceOpen = false

    /** Сколько раз уходил POST — диагностика/тесты. */
    @Volatile
    var requests: Int = 0
        private set

    override fun startUtterance() {
        synchronized(lock) {
            segmenter.reset()
            utteranceOpen = true
        }
    }

    override fun acceptPcm(pcm: ByteArray, length: Int) {
        if (length <= 0) return
        val event = synchronized(lock) {
            if (!utteranceOpen) return
            segmenter.onChunk(pcm, nowMs(), minOf(length, pcm.size))
        }
        deliver(event)
    }

    /** Ручной стоп: хвост реплики уходит в POST, текст придёт в [SpeechToText.Events.onFinal]. */
    override fun finishUtterance(): String? {
        val event = synchronized(lock) {
            if (!utteranceOpen) return ""
            utteranceOpen = false
            segmenter.flush()
        }
        val segment = event as? VoiceSegmenter.Event.Segment ?: return ""
        submit(segment.pcm)
        return null
    }

    override fun cancelUtterance() {
        synchronized(lock) {
            utteranceOpen = false
            segmenter.reset()
        }
    }

    override fun release() = cancelUtterance()

    private fun deliver(event: VoiceSegmenter.Event) {
        when (event) {
            is VoiceSegmenter.Event.None -> Unit
            is VoiceSegmenter.Event.SpeechStarted -> events?.onSpeechStarted()
            is VoiceSegmenter.Event.Segment -> submit(event.pcm)
        }
    }

    private fun submit(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val wav = PcmWavWriter.encode(pcm, VoiceRecorder.SAMPLE_RATE, VoiceRecorder.CHANNELS)
        requests++
        try {
            transcribe(wav) { text -> events?.onFinal(text ?: "") }
        } catch (ignored: Exception) {
            // Транспорт упал до отправки: реплику не отправляем, но цикл не рвём — пусть слушает.
            events?.onFinal("")
        }
    }

    private companion object {
        const val ID = "whisper"
    }
}
