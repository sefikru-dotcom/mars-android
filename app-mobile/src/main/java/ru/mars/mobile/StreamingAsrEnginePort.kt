package ru.mars.mobile

import ru.mars.mobile.asr.AsrConfig

/**
 * Адаптер: нативный движок потокового ASR ([StreamingAsrEngine], ветка `v1-asr-engine`)
 * под контракт [AsrEnginePort], на который подписан цикл диалога.
 *
 * Зачем отдельный класс: движок тянет нативную библиотеку sherpa-onnx, поэтому его нельзя
 * упоминать в JVM-тестах политики диалога. Цикл знает только [AsrEnginePort]; этот файл —
 * единственное место, где встречаются обе стороны.
 *
 * События движка приходят через конструктор-callback, а не через `var onEvent`, поэтому
 * адаптер сам регистрирует колбэк в момент создания движка и раскладывает [AsrEvent]
 * на три метода [AsrEnginePort.Events].
 */
class StreamingAsrEnginePort(
    private val engine: StreamingAsrEngine,
) : AsrEnginePort {

    override var events: AsrEnginePort.Events? = null

    override val isReady: Boolean
        get() = engine.isReady

    override fun load(): Boolean = engine.load()

    override fun startUtterance() = engine.startUtterance()

    override fun acceptPcm(pcm: ByteArray, length: Int) = engine.acceptPcm(pcm, length)

    override fun finishUtterance(): String = engine.finishUtterance()

    override fun release() = engine.release()

    companion object {
        /**
         * Создать адаптер, привязав события движка к [AsrEnginePort.Events].
         *
         * Движок принимает колбэк в конструкторе, поэтому создавать его нужно здесь:
         * обратная ссылка на адаптер устанавливается до того, как движок начнёт работу.
         */
        fun create(
            context: android.content.Context,
            config: ru.mars.mobile.asr.AsrConfig = ru.mars.mobile.asr.AsrConfig(),
        ): StreamingAsrEnginePort {
            lateinit var port: StreamingAsrEnginePort
            val engine = StreamingAsrEngine(
                context = context,
                config = config,
                listener = { event ->
                    when (event) {
                        is AsrEvent.Partial -> port.events?.onPartial(event.text)
                        is AsrEvent.Final -> port.events?.onFinal(event.text)
                        AsrEvent.SpeechStarted -> port.events?.onSpeechStarted()
                    }
                },
            )
            port = StreamingAsrEnginePort(engine)
            return port
        }
    }
}
