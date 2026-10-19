package ru.mars.mobile

import kotlin.math.sqrt

/**
 * Детектор тишины (energy VAD) для потока PCM16 mono: решает, когда запись пора закончить.
 *
 * Зачем отдельный класс: до него запись жила до второго тапа по микрофону, то есть человек
 * говорил и тянулся к кнопке — это ломало «живой» диалог. Правило «остановиться после паузы
 * речи» — это политика над звуком, а не работа с Android-аудио, поэтому она вынесена в чистую
 * JVM-логику: тестируется на синтетическом PCM без устройства, а [VoiceRecorder] остаётся
 * тонким хостом (читает чанки и спрашивает детектор).
 *
 * Время приходит только параметром [onChunk] (`nowMs`), часы внутрь не зашиты: иначе тест
 * зависел бы от реального времени и «пауза 1.2 с» проверялась бы через `Thread.sleep`.
 *
 * Решение принимается по RMS чанка (энергия), а не по пику: одиночный щелчок даёт высокий пик
 * при почти нулевой энергии и не должен считаться речью. Три защиты от ложных стопов:
 *  - **hangover**: тишина засчитывается, только если она длится непрерывно не меньше
 *    [hangoverMs] — пауза на запятой или вдох не обрывают фразу;
 *  - **защита от старта**: первые [startGuardMs] не считаются тишиной вообще — запись
 *    начинается, пока человек ещё подносит телефон и делает вдох;
 *  - **защита от «тишины с самого начала»**: если речи не было ни разу (микрофон заткнут,
 *    случайный тап), стоп не раньше [minNoSpeechMs] — человек успевает начать говорить.
 *
 * Класс не потокобезопасен: один экземпляр на одну сессию записи, вызовы с одного потока
 * (в [VoiceRecorder] — с потока чтения). [reset] возвращает детектор в исходное состояние,
 * чтобы его можно было переиспользовать для следующей сессии.
 */
class SilenceDetector(
    /**
     * Порог RMS в единицах int16-сэмпла. Тихая комната/вентилятор дают ~50–150, речь у
     * микрофона телефона — ~600–3000 без AGC. 400 выше бытового шума, но ниже тихой речи;
     * риск «тихо говорящего» человека снимается hangover'ом, а ручной тап остаётся всегда.
     */
    private val thresholdRms: Int = DEFAULT_THRESHOLD_RMS,
    /**
     * Сколько миллисекунд тишины нужно накопить подряд, чтобы считать речь законченной.
     * Пауза между словами обычно 100–400 мс, между фразами — 500–900 мс; 1.2 с не режет
     * посреди предложения, но заметно быстрее, чем человек успевает дотянуться до кнопки.
     */
    private val hangoverMs: Long = DEFAULT_HANGOVER_MS,
    /**
     * «Глухие» первые миллисекунды записи: микрофон уже открыт, а речи ещё нет (вдох, телефон
     * подносится). Без этого окна тишина в начале засчитывалась бы сразу и запись обрывалась
     * бы на первом же чанке.
     */
    private val startGuardMs: Long = DEFAULT_START_GUARD_MS,
    /**
     * Минимальная длительность записи, если речи не было вовсе. Даёт человеку время начать
     * говорить после тапа; дольше держать незачем — общий лимит длительности у рекордера
     * ([VoiceRecorder.MAX_DURATION_MS]) всё равно ограничит сессию.
     */
    private val minNoSpeechMs: Long = DEFAULT_MIN_NO_SPEECH_MS,
) {

    /** Что делать с записью после очередного чанка. */
    enum class Decision {
        /** Продолжаем писать. */
        CONTINUE,

        /** Речь закончилась — запись пора завершить. Выдаётся не более одного раза за сессию. */
        STOP_SILENCE,
    }

    init {
        require(thresholdRms > 0) { "thresholdRms должен быть > 0: $thresholdRms" }
        require(hangoverMs >= 0) { "hangoverMs не может быть отрицательным: $hangoverMs" }
        require(startGuardMs >= 0) { "startGuardMs не может быть отрицательным: $startGuardMs" }
        require(minNoSpeechMs >= 0) { "minNoSpeechMs не может быть отрицательным: $minNoSpeechMs" }
    }

    /** Метка времени первого чанка: от неё считаются все длительности. */
    private var startMs: Long? = null

    /** Когда начался текущий непрерывный участок тишины; null — сейчас не тишина. */
    private var silentSinceMs: Long? = null

    /** Была ли за сессию хоть одна речь: от этого зависит защита «тишины с самого начала». */
    private var speechSeen = false

    /** Решение STOP_SILENCE выдаётся один раз: после него детектор молчит до [reset]. */
    private var stopped = false

    /**
     * Обработать очередной чанк PCM16 little-endian.
     *
     * @param chunk буфер чтения; анализируются первые [length] байт (нечётный хвост
     *   игнорируется — половина сэмпла не является сэмплом и не должна ронять чтение);
     * @param nowMs монотонная метка времени чанка, инжектируется хостом (не `System`);
     * @param length сколько байт чанка реально заполнено.
     */
    fun onChunk(chunk: ByteArray, nowMs: Long, length: Int = chunk.size): Decision {
        require(length in 0..chunk.size) { "length=$length вне 0..${chunk.size}" }
        // После стопа решение больше не меняется: хост уже завершает сессию, а повторный
        // STOP_SILENCE означал бы второй WAV и вторую отправку на распознавание.
        if (stopped) return Decision.CONTINUE

        val start = startMs ?: nowMs.also { startMs = it }
        val elapsedMs = (nowMs - start).coerceAtLeast(0)

        if (rms(chunk, length) >= thresholdRms) {
            // Речь (или звук громче порога): обнуляем накопленную тишину.
            speechSeen = true
            silentSinceMs = null
            return Decision.CONTINUE
        }

        // Защита от старта: тишину в этом окне не копим вовсе, иначе к его концу накопилось бы
        // startGuardMs «тишины» и стоп случился бы ровно на выходе из окна.
        if (elapsedMs < startGuardMs) {
            silentSinceMs = null
            return Decision.CONTINUE
        }

        val silentSince = silentSinceMs ?: nowMs.also { silentSinceMs = it }
        val silentForMs = (nowMs - silentSince).coerceAtLeast(0)
        if (silentForMs < hangoverMs) return Decision.CONTINUE
        // Речи не было ни разу — ждём минимум minNoSpeechMs, чтобы человек успел начать.
        if (!speechSeen && elapsedMs < minNoSpeechMs) return Decision.CONTINUE

        stopped = true
        return Decision.STOP_SILENCE
    }

    /** Вернуть детектор в исходное состояние для следующей сессии записи. */
    fun reset() {
        startMs = null
        silentSinceMs = null
        speechSeen = false
        stopped = false
    }

    /**
     * RMS амплитуды сэмплов: sqrt(mean(sample²)). Считаем в Double — сумма квадратов 1600
     * сэмплов по 32767² выходит за Int, а обрезанное переполнение дало бы «тишину» на громком
     * звуке. Неполная пара байт в конце буфера отбрасывается.
     */
    private fun rms(chunk: ByteArray, length: Int): Double {
        val sampleCount = length / 2
        if (sampleCount == 0) return 0.0
        var sumSquares = 0.0
        var index = 0
        while (index < sampleCount) {
            val low = chunk[index * 2].toInt() and 0xFF
            val high = chunk[index * 2 + 1].toInt() shl 8
            val sample = high or low
            sumSquares += sample.toDouble() * sample
            index += 1
        }
        return sqrt(sumSquares / sampleCount)
    }

    companion object {
        /** См. KDoc [thresholdRms]. */
        const val DEFAULT_THRESHOLD_RMS = 400

        /** См. KDoc [hangoverMs]. */
        const val DEFAULT_HANGOVER_MS = 1_200L

        /** См. KDoc [startGuardMs]. */
        const val DEFAULT_START_GUARD_MS = 300L

        /** См. KDoc [minNoSpeechMs]. */
        const val DEFAULT_MIN_NO_SPEECH_MS = 3_000L
    }
}
