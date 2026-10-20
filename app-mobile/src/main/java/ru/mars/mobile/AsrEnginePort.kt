package ru.mars.mobile

/**
 * Порт к движку потокового ASR — ровно те вызовы, которые нужны циклу диалога.
 *
 * Зачем порт, а не прямой вызов `StreamingAsrEngine`: движок живёт в отдельной ветке
 * (`v1-asr-engine`) и тянет нативную библиотеку. Цикл и его тесты не должны от него зависеть —
 * иначе JVM-тесты не соберутся без `.so`, а смена движка (whisper.cpp → sherpa → что угодно)
 * потащила бы за собой политику диалога. Здесь только контракт, который движок обязан
 * удовлетворить:
 *
 * ```kotlin
 * // В ветке движка (одна тонкая адаптация, ~15 строк):
 * class StreamingAsrEnginePort(private val engine: StreamingAsrEngine) : AsrEnginePort {
 *     override var events: AsrEnginePort.Events? = null
 *     init {
 *         engine.onEvent = { event ->
 *             when (event) {
 *                 is AsrEvent.Partial -> events?.onPartial(event.text)
 *                 is AsrEvent.Final -> events?.onFinal(event.text)
 *                 AsrEvent.SpeechStarted -> events?.onSpeechStarted()
 *             }
 *         }
 *     }
 *     override val isReady: Boolean get() = engine.isReady
 *     override fun load(): Boolean = engine.load()
 *     override fun startUtterance() = engine.startUtterance()
 *     override fun acceptPcm(pcm: ByteArray, length: Int) = engine.acceptPcm(pcm, length)
 *     override fun finishUtterance(): String = engine.finishUtterance()
 *     override fun release() = engine.release()
 * }
 * ```
 *
 * `AsrEvent` движка НЕ дублируется здесь намеренно: два одинаковых `sealed class` в одном пакете
 * не соберутся, поэтому события движка отображаются на три колбэка [Events] — этого хватает
 * циклу, и адаптация остаётся однострочной на каждое событие. Точное имя точки подписки
 * (`onEvent`/`setListener`) берётся из ветки движка: на политику цикла это не влияет.
 *
 * Семантика [acceptPcm]/[finishUtterance] — как у [SpeechToText]: звук идёт с потока чтения
 * микрофона, конец реплики определяет сам движок.
 */
interface AsrEnginePort {

    /** Движок загружен и готов принимать звук. */
    val isReady: Boolean

    /**
     * Загрузить модель. Возвращает `false`, если модель/библиотека недоступны на этом
     * устройстве — тогда цикл работает через whisper и не пытается грузить снова на каждой фразе.
     */
    fun load(): Boolean

    /** Начать реплику. */
    fun startUtterance()

    /** Принять буфер PCM16 16 кГц моно LE. Буфер не сохранять — он переиспользуется. */
    fun acceptPcm(pcm: ByteArray, length: Int)

    /** Закончить реплику и отдать финальный текст (может быть пустым). */
    fun finishUtterance(): String

    /** Освободить модель и нативные ресурсы. */
    fun release()

    /** Куда движок отдаёт события; ставит [StreamingStt]. */
    var events: Events?

    /** События движка. */
    interface Events {
        fun onSpeechStarted() {}

        fun onPartial(text: String) {}

        fun onFinal(text: String) {}
    }
}
