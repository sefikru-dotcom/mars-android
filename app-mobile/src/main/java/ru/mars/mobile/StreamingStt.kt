package ru.mars.mobile

import android.os.Handler
import android.os.Looper

/**
 * Потоковый ASR на устройстве: тонкая обёртка над движком ([AsrEnginePort]).
 *
 * Задача обёртки — не «умная» логика, а два инварианта, без которых потоковый путь ломается:
 *
 * 1. **Звук вне реплики отбрасывается.** Микрофон в hands-free открыт всегда, в том числе пока
 *    Марс читает ответ вслух. Если скормить этот звук движку, он услышит собственный динамик и
 *    выдаст `Final` по озвучке. Поэтому PCM принимается только между [startUtterance] и
 *    [finishUtterance]/[cancelUtterance].
 * 2. **Готовность проверяется на каждом буфере.** Движок может отвалиться посреди сессии
 *    (нехватка памяти, выгрузка модели); тогда [isReady] становится `false`, цикл уходит на
 *    whisper, а не молчит.
 *
 * Обёртка НЕ решает, когда реплика закончилась: endpoint-детекция — работа движка, он сам
 * выдаёт [SpeechToText.Events.onFinal]. Именно поэтому потоковый путь не платит фиксированные
 * 2.8–5.3 с серверного whisper и не нуждается в [VoiceSegmenter].
 *
 * @param engine порт движка; `null` — движка нет (например, нативная библиотека не собралась),
 *   [isReady] == false, цикл работает через whisper.
 */
class StreamingStt(
    engine: AsrEnginePort? = null,
) : SpeechToText {

    override val id: String get() = ID

    /**
     * Порт движка. Объявлен ДО `init`-блока намеренно: инициализаторы свойств и `init`
     * выполняются в порядке объявления, и поздний `= null` затёр бы привязку из конструктора.
     */
    @Volatile
    private var engine: AsrEnginePort? = null

    /** Открыта ли реплика: читается с потока чтения, пишется с главного. */
    @Volatile
    private var utteranceOpen: Boolean = false

    override var events: SpeechToText.Events? = null

    /**
     * Мост «события движка → события цикла». Один объект на всю жизнь обёртки.
     *
     * ВАЖНО: движок зовёт эти колбэки с потока чтения микрофона (`mars-voice-hands-free`),
     * а подписчик ([VoiceDialogLoop]) меняет состояние и трогает TextView. Без перекладки
     * на главный поток Android бросает CalledFromWrongThreadException, и реплика теряется.
     */
    private val engineEvents = object : AsrEnginePort.Events {
        override fun onSpeechStarted() = postToMain { events?.onSpeechStarted() }

        override fun onPartial(text: String) = postToMain { events?.onPartial(text) }

        override fun onFinal(text: String) = postToMain { events?.onFinal(text) }
    }

    /** Главный Looper: создаётся лениво, чтобы обёртка не требовала Android в JVM-тестах. */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Выполнить на главном потоке. В JVM-тестах Looper'а нет — выполняем сразу, иначе
     * события не дошли бы вообще (тесты гоняют чтение и проверку в одном потоке).
     */
    private fun postToMain(block: () -> Unit) {
        val looper = runCatching { Looper.getMainLooper() }.getOrNull()
        if (looper == null) {
            block()
        } else if (Looper.myLooper() == looper) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    init {
        bind(engine)
    }

    /**
     * Привязать (или заменить) движок — например, после асинхронной загрузки модели. `null`
     * отвязывает движок и закрывает реплику: цикл увидит [isReady] == false и уйдёт на whisper.
     */
    fun bind(next: AsrEnginePort?) {
        engine?.events = null
        utteranceOpen = false
        engine = next
        next?.events = engineEvents
    }

    override val isReady: Boolean
        get() = engine?.isReady == true

    /**
     * Загрузить модель через порт. Хост зовёт один раз при входе в голосовой режим (загрузка
     * нативная и небыстрая, поэтому не в конструкторе); `false` — движка нет или модель не
     * встала, цикл остаётся на whisper.
     */
    fun load(): Boolean = engine?.load() == true

    /** Сколько байт PCM реально ушло в движок (диагностика/тесты). */
    @Volatile
    var acceptedBytes: Long = 0L
        private set

    override fun startUtterance() {
        val target = engine ?: return
        if (!target.isReady) return
        utteranceOpen = true
        target.startUtterance()
    }

    override fun acceptPcm(pcm: ByteArray, length: Int) {
        if (length <= 0) return
        if (!utteranceOpen) return
        val target = engine ?: return
        if (!target.isReady) {
            utteranceOpen = false
            return
        }
        val valid = minOf(length, pcm.size)
        if (valid <= 0) return
        target.acceptPcm(pcm, valid)
        acceptedBytes += valid
    }

    /**
     * Конец реплики. Текст берётся у движка синхронно (он уже посчитал финал или посчитает его
     * в `finishUtterance`). Пустая строка — распознавать нечего.
     */
    override fun finishUtterance(): String? {
        if (!utteranceOpen) return ""
        utteranceOpen = false
        val target = engine ?: return ""
        if (!target.isReady) return ""
        return try {
            target.finishUtterance()
        } catch (ignored: Exception) {
            // Движок упал на финале: реплика теряется, но цикл обязан остаться живым.
            ""
        }
    }

    override fun cancelUtterance() {
        if (!utteranceOpen) return
        utteranceOpen = false
        val target = engine ?: return
        // Отдельного cancel в контракте движка нет: закрываем реплику и выбрасываем её текст.
        try {
            target.finishUtterance()
        } catch (ignored: Exception) {
            // Текст всё равно не нужен.
        }
    }

    override fun release() {
        utteranceOpen = false
        val target = engine
        engine = null
        target?.events = null
        try {
            target?.release()
        } catch (ignored: Exception) {
            // Освобождение нативных ресурсов не должно ронять выход из режима.
        }
    }

    private companion object {
        const val ID = "streaming-asr"
    }
}
