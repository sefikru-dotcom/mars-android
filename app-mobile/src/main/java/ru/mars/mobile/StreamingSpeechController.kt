package ru.mars.mobile

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * Речевой слой ответа целиком: дельты стрима → фразы → нормализованный текст → очередь.
 *
 * Хост (ChatActivity) делает ровно три вещи: [onDelta] на каждую дельту ответа,
 * [onTurnFinished] в конце хода и [stop] при перебивании. Всё остальное — решения о
 * границах предложений ([SentenceChunker]), произносимой форме ([VoiceNormalizer]) и
 * порядке звука ([SpeechQueue]) — здесь, поэтому проводка в Activity остаётся тонкой, а
 * поведение проверяется юнит-тестами без устройства.
 *
 * Зачем три шага не слиты: у каждого своя причина и свой тест. Чанкер решает «что считать
 * фразой», нормализатор — «как это произнести», очередь — «когда и в каком порядке звучать».
 * Слить их в один класс значило бы проверять задержку первого звука вместе с грамматикой
 * числительных.
 *
 * Инварианты хода:
 * - первая фраза уходит в синтез СРАЗУ, как только в дельте появилась граница предложения
 *   (не ждём конца хода и не ждём абзаца);
 * - синтез следующей фразы идёт, пока играет предыдущая, но не глубже [SpeechQueue.lookahead]
 *   фраз вперёд — иначе на длинном ответе в сеть улетал бы весь текст сразу;
 * - после [stop] звука не остаётся: очередь чистится, синтезы в полёте отменяются, поздний
 *   ответ сервера уже не зазвучит (проверка поколения в очереди);
 * - [onTurnFinished] идемпотентен: повторный вызов не читает финал второй раз. Новый ход
 *   начинается с первой дельты — она и снимает флаг завершения.
 *
 * Класс чистый: зависимости — только интерфейсы синтеза и воспроизведения, поэтому
 * [forAndroid] (единственное место, где появляется Android) вынесен в фабрику.
 */
class StreamingSpeechController(
    private val chunker: SentenceChunker = SentenceChunker(),
    private val normalizer: VoiceNormalizer = VoiceNormalizer(),
    private val queue: SpeechQueue,
    /** Метрики задержки: только числа (дельта → запрос → звук), без текста и секретов. */
    val metrics: SpeechLatencyMetrics = SpeechLatencyMetrics(),
) {

    /** Ход уже завершён: финал прочитан, повторный flush не нужен. */
    private var turnFinished = false

    /**
     * Текст, который в этом ходе РЕАЛЬНО ушёл в озвучку (после нормализации, фразами).
     *
     * Зачем хосту: политика перебивания ([BargeInGate]) отличает эхо динамика от речи человека
     * сравнением partial ASR с произносимым текстом. Придумать этот текст нельзя — нужен именно
     * тот, что ушёл в синтез; иначе на динамике либо не работает перебивание, либо цикл
     * перебивает сам себя.
     */
    private val spoken = StringBuilder()

    /** Что прозвучало/звучит в текущем ходе; `null` — ещё ничего не ушло в синтез. */
    val spokenText: String?
        get() = synchronized(spoken) { spoken.toString().ifBlank { null } }

    /** Пришла очередная дельта ответа: готовые фразы сразу уходят в синтез. */
    fun onDelta(delta: String) {
        if (delta.isEmpty()) return
        // Дельта после завершённого хода — это уже НОВЫЙ ход: снимаем флаг, иначе его финал
        // остался бы неозвученным. Текст прошлого хода при этом не переезжает в новый.
        val startingNewTurn = turnFinished
        turnFinished = false
        if (startingNewTurn) clearSpoken()
        metrics.onFirstDelta()
        chunker.feed(delta).forEach { phrase -> enqueue(phrase) }
    }

    /**
     * Ход закончился: дочитать остаток (даже короткий) и дать ему зазвучать.
     *
     * Идемпотентно: второй вызов подряд ничего не делает. Это важно, потому что хост может
     * позвать метод и по терминальному `Done`, и по концу потока — финал должен прозвучать
     * один раз.
     */
    fun onTurnFinished() {
        if (turnFinished) return
        turnFinished = true
        chunker.flush().forEach { phrase -> enqueue(phrase) }
    }

    /**
     * Ответить целиком одним куском (ручная озвучка готового сообщения). Внутри тот же путь,
     * что у стрима, поэтому разметка, код и таблицы фильтруются одинаково.
     */
    fun speakWhole(text: String) {
        onDelta(text)
        onTurnFinished()
    }

    /**
     * Перебивание: тишина немедленно, очередь и синтезы в полёте сброшены, накопленный
     * текст выброшен. Следующий [onDelta] начнёт с чистого листа.
     */
    fun stop() {
        turnFinished = false
        chunker.reset()
        clearSpoken()
        queue.stopAll()
    }

    /** Хост уничтожен: остановить и освободить плеер. */
    fun release() {
        chunker.reset()
        clearSpoken()
        queue.release()
    }

    /** Что-то звучит прямо сейчас. */
    val isSpeaking: Boolean
        get() = queue.isSpeaking

    /** Снимок метрик текущего хода (без текста ответа). */
    fun latencySnapshot(): SpeechLatencySnapshot = metrics.snapshot()

    /** Фраза проходит нормализацию и, если в ней остался произносимый текст, встаёт в очередь. */
    private fun enqueue(phrase: String) {
        val normalized = normalizer.normalize(phrase)
        if (normalized.isNotBlank()) {
            queue.speak(normalized)
            recordSpoken(normalized)
        }
    }

    /** Запомнить реально произносимый текст хода (для echo-гейта перебивания). */
    private fun recordSpoken(text: String) {
        synchronized(spoken) {
            if (spoken.isNotEmpty()) spoken.append(' ')
            spoken.append(text.trim())
        }
    }

    private fun clearSpoken() {
        synchronized(spoken) { spoken.setLength(0) }
    }

    companion object {
        /**
         * Боевая сборка для Android: серверный синтез, ExoPlayer-воспроизведение и область
         * жизни экрана. Единственное место, где речевой слой знает про Android.
         *
         * Метрики задержки собираются здесь же: [onFirstAudio] вызывается очередью только
         * когда аудио РЕАЛЬНО пошло в динамик, поэтому «first speech» нельзя подделать
         * первым текстом.
         */
        fun forAndroid(
            context: Context,
            client: ServerTtsClient,
            scope: CoroutineScope,
            onFirstAudio: () -> Unit = {},
            onDrained: () -> Unit = {},
            onPhraseFailed: (text: String, reason: String) -> Unit = { _, _ -> },
            metrics: SpeechLatencyMetrics = SpeechLatencyMetrics(),
            retryPolicy: SpeechRetryPolicy = SpeechRetryPolicy(),
        ): StreamingSpeechController {
            val queue = SpeechQueue(
                client = client,
                playback = AndroidServerTtsPlayback(context),
                scope = scope,
                onFirstAudio = {
                    metrics.onAudioStart()
                    runCatching { onFirstAudio() }
                },
                onDrained = onDrained,
                onPhraseFailed = { text, reason ->
                    // Метрику отказа считает сама очередь (см. notifyPhraseFailed): здесь только
                    // проброс хосту, иначе один пропуск считался бы дважды.
                    runCatching { onPhraseFailed(text, reason) }
                },
                retryPolicy = retryPolicy,
                metrics = metrics,
            )
            return StreamingSpeechController(queue = queue, metrics = metrics)
        }
    }
}
