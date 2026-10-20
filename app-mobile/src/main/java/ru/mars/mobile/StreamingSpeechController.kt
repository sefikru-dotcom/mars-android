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
 * Класс чистый: зависимости — только интерфейсы синтеза и воспроизведения, поэтому
 * [forAndroid] (единственное место, где появляется Android) вынесен в фабрику.
 */
class StreamingSpeechController(
    private val chunker: SentenceChunker = SentenceChunker(),
    private val normalizer: VoiceNormalizer = VoiceNormalizer(),
    private val queue: SpeechQueue,
) {

    /** Пришла очередная дельта ответа: готовые фразы сразу уходят в синтез. */
    fun onDelta(delta: String) {
        chunker.feed(delta).forEach { phrase -> enqueue(phrase) }
    }

    /** Ход закончился: дочитать остаток (даже короткий) и дать ему зазвучать. */
    fun onTurnFinished() {
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
        chunker.reset()
        queue.stopAll()
    }

    /** Хост уничтожен: остановить и освободить плеер. */
    fun release() {
        chunker.reset()
        queue.release()
    }

    /** Что-то звучит прямо сейчас. */
    val isSpeaking: Boolean
        get() = queue.isSpeaking

    /** Фраза проходит нормализацию и, если в ней остался произносимый текст, встаёт в очередь. */
    private fun enqueue(phrase: String) {
        val spoken = normalizer.normalize(phrase)
        if (spoken.isNotBlank()) queue.speak(spoken)
    }

    companion object {
        /**
         * Боевая сборка для Android: серверный синтез, ExoPlayer-воспроизведение и область
         * жизни экрана. Единственное место, где речевой слой знает про Android.
         */
        fun forAndroid(
            context: Context,
            client: ServerTtsClient,
            scope: CoroutineScope,
            onFirstAudio: () -> Unit = {},
            onDrained: () -> Unit = {},
            onPhraseFailed: (text: String, reason: String) -> Unit = { _, _ -> },
        ): StreamingSpeechController {
            val queue = SpeechQueue(
                client = client,
                playback = AndroidServerTtsPlayback(context),
                scope = scope,
                onFirstAudio = onFirstAudio,
                onDrained = onDrained,
                onPhraseFailed = onPhraseFailed,
            )
            return StreamingSpeechController(queue = queue)
        }
    }
}
