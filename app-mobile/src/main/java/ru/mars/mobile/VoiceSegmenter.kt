package ru.mars.mobile

import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Нарезка НЕПРЕРЫВНОГО потока PCM16 (16 кГц, моно) на речевые сегменты по тишине — ядро
 * голосового диалога (L1).
 *
 * Зачем отдельный класс: [SilenceDetector] отвечает на вопрос «когда закончить одну запись»
 * (одна реплика на сессию микрофона). Hands-free циклу нужно другое: микрофон открыт долго,
 * а поток режется на реплики «речь → пауза → сегмент», без закрытия микрофона между ними.
 * Обе задачи решаются по одному и тому же сигналу, но состояния разные, поэтому логика
 * разделена: детектор не тронут (его контрактные тесты остаются в силе), сегментер — здесь.
 *
 * Чистый JVM: ни одного импорта Android, время приходит снаружи (`nowMs`), звук — байтами.
 * Поэтому все решения проверяются юнит-тестами на синтетическом PCM без устройства.
 *
 * ## Состояния
 * ```
 * ЖДЁМ ──(200 мс речи подряд)──▶ В РЕЧИ ──(тишина ≥ 800 мс)──▶ сегмент ──▶ ЖДЁМ
 *   │                               │
 *   │                               ├──(20 с речи)──▶ сегмент (форс-рез) и В РЕЧИ дальше
 *   └──(flush)──▶ ничего             └──(flush)──▶ хвост реплики сегментом
 * ```
 *
 * ## Защита от потери реплики
 * - **pre-roll 400 мс**: первые 400 мс до подтверждённого начала речи уже лежат в сегменте —
 *   первый слог не срезается (начало речи подтверждается двумя чанками, оба попадают в pre-roll);
 * - **[flush]**: при остановке цикла активный сегмент закрывается и отдаётся хосту, а не
 *   выбрасывается («пользователь замолчал и сразу нажал стоп» — реплика не теряется);
 * - **стык чанков**: непарный байт (половина сэмпла) переносится в следующий чанк, поэтому
 *   граница буфера чтения не рвёт сэмпл и не сдвигает поток;
 * - **форс-рез на 20 с**: длинная речь режется на 20 с + продолжение, ни один сэмпл не
 *   выбрасывается (STT получает оба куска).
 *
 * ## Защита от дубля
 * - буфер сегмента и pre-roll очищаются в момент закрытия, поэтому один и тот же звук не может
 *   попасть в два сегмента;
 * - pre-roll никогда не содержит звук уже отданного сегмента (иначе следующая реплика начиналась
 *   бы с хвоста предыдущей и STT распознал бы её дважды);
 * - [flush] после [flush] (и после закрытия по тишине) возвращает [Event.None] — повторный
 *   вызов не отдаёт тот же хвост второй раз.
 *
 * ## Адаптивный порог (тихий голос)
 * Фиксированные 400 RMS из [SilenceDetector] годятся для записи «по кнопке», но в hands-free
 * человек может говорить тише. Порог считается от **минимума RMS за последние 5 с** (минимум
 * не поднимается речью, поэтому собственный голос не может «ослепить» детектор):
 * - тихая комната (фон 20) → порог 150: слышно даже очень тихий голос (RMS 250);
 * - обычная комната (фон 120) → порог 360;
 * - потолок — базовые 400: гул 200 даёт порог 400 и в речь не превращается.
 *
 * Потокобезопасности нет намеренно: сегментер живёт на одном потоке чтения микрофона
 * (как и [VoiceRecorder]); хост, которому нужно иначе, синхронизирует сам.
 */
class VoiceSegmenter(
    private val sampleRate: Int = VoiceRecorder.SAMPLE_RATE,
    /** Базовый порог RMS (как у [SilenceDetector]): потолок адаптации и стартовая оценка. */
    private val baseThresholdRms: Int = SilenceDetector.DEFAULT_THRESHOLD_RMS,
    /** Пауза, закрывающая реплику (hangover). 700–900 мс: человек делает паузы на запятой. */
    private val hangoverMs: Long = DEFAULT_HANGOVER_MS,
    /** Защитный интервал в начале сессии: щелчок открытия микрофона не считается речью. */
    private val startGuardMs: Long = DEFAULT_START_GUARD_MS,
    /** Сколько звука ДО подтверждённого начала речи уже принадлежит реплике. */
    private val preRollMs: Long = DEFAULT_PRE_ROLL_MS,
    /** Реплика короче этого не отдаётся в STT (щелчок, кашель, «э»). */
    private val minSegmentMs: Long = DEFAULT_MIN_SEGMENT_MS,
    /** Жёсткий предел одного сегмента: дальше — форс-рез и продолжение. */
    private val maxSegmentMs: Long = DEFAULT_MAX_SEGMENT_MS,
) {

    /** Что сегментер сообщает хосту. Ровно одно событие на чанк. */
    sealed class Event {
        /** Ничего не произошло. */
        object None : Event()

        /** Начало речи (один раз на реплику): хост показывает «Слушаю…» и решает barge-in. */
        object SpeechStarted : Event()

        /**
         * Готовая реплика: WAV-байты не нужны здесь — [pcm] это сырой PCM16 сегмента,
         * [durationMs] — его длительность по сэмплам (не по стенным часам).
         */
        class Segment(val pcm: ByteArray, val durationMs: Long) : Event()
    }

    /** Сколько байт PCM в миллисекунде при текущей частоте (2 байта на сэмпл). */
    private val bytesPerMs: Int = sampleRate * BYTES_PER_SAMPLE / 1000

    private val preRollBytes: Int = preRollMs.toInt() * bytesPerMs

    private val maxSegmentBytes: Int = maxSegmentMs.toInt() * bytesPerMs

    /** Подтверждение начала речи: 200 мс подряд выше порога. */
    private val startConfirmMs: Long = START_CONFIRM_MS

    /** Минимум RMS по последним чанкам: оценка шумового пола (минимум речью не поднимается). */
    private val noiseWindow = ArrayDeque<Double>()

    private var noiseFloorRms: Double = DEFAULT_NOISE_FLOOR_RMS

    /** Активный сегмент: null — ждём речь. */
    private var segment: ByteArrayOutputStream? = null

    private var segmentSamples: Int = 0

    /** Сколько сэмплов внутри сегмента было выше порога (без этого тишина стала бы сегментом). */
    private var speechSamples: Int = 0

    /** Сколько подряд идёт речь выше порога — для подтверждения начала. */
    private var loudRunMs: Long = 0

    /** Когда началась текущая тишина внутри сегмента (null — речь идёт). */
    private var silentSinceMs: Long? = null

    /** Первый чанк сессии: от него считается [startGuardMs]. */
    private var sessionStartMs: Long? = null

    /** Хвост непарного байта: граница чанка не должна рвать сэмпл. */
    private var pendingByte: Byte? = null

    /** Сырой pre-roll (звук ДО подтверждённого начала речи). */
    private val preRoll = ArrayDeque<Buffered>()

    private var preRollHeld: Int = 0

    /** Сколько сэмплов РЕЧИ уже лежит в pre-roll (войдёт в счёт минимальной длины сегмента). */
    private var preRollSpeechSamples: Int = 0

    /** Текущая оценка порога: видна тестам и диагностике. */
    val thresholdRms: Int
        get() = effectiveThreshold().toInt()

    /** Текущая оценка шумового пола (минимум RMS за окно тишины). */
    val noiseFloor: Double
        get() = noiseFloorRms

    /**
     * Идёт ли речь прямо сейчас (активный сегмент открыт). Нужно barge-in'у: хост получает
     * [activeVoicedMs] только пока это `true`, иначе «длительность речи» после закрытия сегмента
     * обнулилась бы раньше, чем политика успела её увидеть.
     */
    val isSpeaking: Boolean
        get() = segment != null

    /**
     * Сколько миллисекунд РЕЧИ (сэмплов выше порога) накоплено в текущем сегменте.
     *
     * Это не длительность сегмента: тишина внутри фразы не считается. Именно поэтому значение
     * годится как мера «человек говорит уже столько-то» для подтверждения перебивания.
     * Вне речи — 0.
     */
    val activeVoicedMs: Long
        get() = if (segment == null) 0L else speechSamples.toLong() * 1000 / sampleRate

    /**
     * Скормить очередной чанк PCM16. [length] — сколько байт в [chunk] валидно (AudioRecord
     * возвращает меньше размера буфера); [nowMs] — монотонное время чанка.
     *
     * Возвращает ровно одно событие: [Event.None], [Event.SpeechStarted] или [Event.Segment].
     */
    fun onChunk(chunk: ByteArray, nowMs: Long, length: Int = chunk.size): Event {
        if (length <= 0) return Event.None
        val audio = normalize(chunk, minOf(length, chunk.size)) ?: return Event.None
        if (sessionStartMs == null) sessionStartMs = nowMs

        val rms = rms(audio)
        val threshold = effectiveThreshold()
        val loud = rms >= threshold

        val current = segment
        if (current == null) {
            // --- ждём речь -------------------------------------------------------------------
            // Окно шумового пола кормится ВСЕМИ чанками, но оценка — МИНИМУМ за окно: громкая
            // речь не может поднять фон, а гул/шипение комнаты — могут (иначе гул 200 при
            // стартовом пороге 150 считался бы речью и порог бы никогда не вырос — дедлок).
            rememberForNoiseFloor(rms)
            val inGuard = nowMs - (sessionStartMs ?: nowMs) < startGuardMs
            loudRunMs = if (loud && !inGuard) loudRunMs + chunkDurationMs(audio) else 0L
            appendPreRoll(audio, if (loud) audio.size / BYTES_PER_SAMPLE else 0)
            if (loudRunMs < startConfirmMs) return Event.None
            // Начало речи подтверждено: pre-roll (включая подтверждающие чанки) — уже реплика.
            val started = ByteArrayOutputStream(preRollHeld + audio.size)
            for (buffered in preRoll) started.write(buffered.audio, 0, buffered.audio.size)
            preRoll.clear()
            preRollHeld = 0
            segment = started
            segmentSamples = started.size() / BYTES_PER_SAMPLE
            // Речью считаем сэмплы, реально бывшие выше порога (включая pre-roll): 800-мс фраза
            // должна проходить порог минимальной длины, а щелчок в 200 мс — нет.
            speechSamples = preRollSpeechSamples
            preRollSpeechSamples = 0
            silentSinceMs = null
            return Event.SpeechStarted
        }

        // --- в речи -------------------------------------------------------------------------
        current.write(audio, 0, audio.size)
        segmentSamples += audio.size / BYTES_PER_SAMPLE
        if (loud) {
            speechSamples += audio.size / BYTES_PER_SAMPLE
            silentSinceMs = null
        } else if (silentSinceMs == null) {
            silentSinceMs = nowMs
        }

        // Жёсткий предел: сегмент отдаётся, а речь продолжается в следующем (ни сэмпла мимо).
        if (segmentSamples * BYTES_PER_SAMPLE >= maxSegmentBytes) {
            val cut = takeSegment()
            if (cut != null) {
                // Продолжение начатой речи: остаёмся «в речи», но без повторного SpeechStarted.
                segment = ByteArrayOutputStream()
                segmentSamples = 0
                speechSamples = 0
                silentSinceMs = null
                return cut
            }
        }

        val silenceSince = silentSinceMs
        if (silenceSince != null && nowMs - silenceSince >= hangoverMs) {
            val closed = takeSegment()
            return closed ?: Event.None
        }
        return Event.None
    }

    /**
     * Закрыть активный сегмент принудительно (остановка цикла, экран ушёл в фон).
     *
     * Хвост реплики отдаётся хосту — это и есть защита от потери последней фразы. Повторный
     * вызов (и вызов сразу после закрытия по тишине) возвращает [Event.None]: один и тот же
     * звук не может уйти в STT дважды.
     */
    fun flush(): Event {
        val current = segment ?: return Event.None
        if (current.size() == 0) {
            resetSegmentState()
            return Event.None
        }
        return takeSegment() ?: Event.None
    }

    /** Полный сброс (новая сессия микрофона): ни буферов, ни оценки шума не остаётся. */
    fun reset() {
        segment = null
        segmentSamples = 0
        speechSamples = 0
        loudRunMs = 0
        silentSinceMs = null
        sessionStartMs = null
        pendingByte = null
        preRoll.clear()
        preRollHeld = 0
        preRollSpeechSamples = 0
        noiseWindow.clear()
        noiseFloorRms = DEFAULT_NOISE_FLOOR_RMS
    }

    // --- внутреннее ---------------------------------------------------------------------------

    /**
     * Отдать текущий сегмент, если в нём достаточно РЕЧИ (а не тишины). Слишком короткий
     * сегмент выбрасывается молча: щелчок не должен уходить в STT и не должен «прилипать»
     * к следующей реплике (иначе дубль).
     */
    private fun takeSegment(): Event? {
        val current = segment
        val speechMs = speechSamples.toLong() * 1000 / sampleRate
        val pcm = current?.toByteArray()
        val durationMs = segmentSamples.toLong() * 1000 / sampleRate
        resetSegmentState()
        if (current == null || pcm == null || pcm.isEmpty()) return null
        if (speechMs < minSegmentMs) return null
        return Event.Segment(pcm, durationMs)
    }

    private fun resetSegmentState() {
        segment = null
        segmentSamples = 0
        speechSamples = 0
        loudRunMs = 0
        silentSinceMs = null
        // Ключевой инвариант: отданный звук не остаётся в pre-roll и не попадёт во второй сегмент.
        preRoll.clear()
        preRollHeld = 0
        preRollSpeechSamples = 0
    }

    private fun appendPreRoll(audio: ByteArray, speechSamples: Int) {
        preRoll.addLast(Buffered(audio, speechSamples))
        preRollHeld += audio.size
        preRollSpeechSamples += speechSamples
        while (preRollHeld > preRollBytes && preRoll.size > 1) {
            val dropped = preRoll.removeFirst()
            preRollHeld -= dropped.audio.size
            preRollSpeechSamples -= dropped.speechSamples
        }
    }

    /** Чанк в pre-roll вместе с числом сэмплов речи в нём. */
    private class Buffered(val audio: ByteArray, val speechSamples: Int)

    /** Шумовой пол = минимум RMS за окно: громкая речь в окне не поднимает порог. */
    private fun rememberForNoiseFloor(rms: Double) {
        noiseWindow.addLast(rms)
        while (noiseWindow.size > NOISE_WINDOW_CHUNKS) noiseWindow.removeFirst()
        noiseFloorRms = noiseWindow.minOrNull() ?: DEFAULT_NOISE_FLOOR_RMS
    }

    private fun effectiveThreshold(): Double {
        val adaptive = noiseFloorRms * NOISE_FLOOR_FACTOR
        // Потолок — базовый порог [SilenceDetector]: адаптация умеет только ОПУСКАТЬ планку
        // (тихий голос), но никогда не поднимает её выше проверенных 400 — иначе гул в комнате
        // «ослепил» бы детектор и человек снова оказался бы не слышен.
        return adaptive.coerceIn(MIN_ADAPTIVE_THRESHOLD_RMS.toDouble(), baseThresholdRms.toDouble())
    }

    private fun chunkDurationMs(audio: ByteArray): Long =
        audio.size.toLong() / BYTES_PER_SAMPLE * 1000 / sampleRate

    /** Склеить непарный байт с новым чанком: сэмпл не должен рваться на границе чтения. */
    private fun normalize(chunk: ByteArray, length: Int): ByteArray? {
        val carried = pendingByte
        val total = length + if (carried != null) 1 else 0
        val even = total - total % BYTES_PER_SAMPLE
        if (even == 0) {
            if (carried == null && length == 1) pendingByte = chunk[0]
            return null
        }
        val out = ByteArray(even)
        var index = 0
        if (carried != null) {
            out[0] = carried
            index = 1
        }
        val copy = even - index
        System.arraycopy(chunk, 0, out, index, copy)
        pendingByte = if (total > even) chunk[length - 1] else null
        return out
    }

    private fun rms(audio: ByteArray): Double {
        if (audio.isEmpty()) return 0.0
        val samples = audio.size / BYTES_PER_SAMPLE
        if (samples == 0) return 0.0
        var sum = 0.0
        var index = 0
        while (index < samples) {
            val low = audio[index * 2].toInt() and 0xFF
            val high = audio[index * 2 + 1].toInt()
            val sample = (high shl 8) or low
            sum += sample.toDouble() * sample
            index++
        }
        return sqrt(sum / samples)
    }

    companion object {
        private const val BYTES_PER_SAMPLE = 2

        /** Пауза, закрывающая реплику: 800 мс — компромисс между «не резать на запятой» и отзывчивостью. */
        const val DEFAULT_HANGOVER_MS = 800L

        /** Подтверждение начала речи двумя чанками по 100 мс. */
        const val START_CONFIRM_MS = 200L

        const val DEFAULT_START_GUARD_MS = 300L

        const val DEFAULT_PRE_ROLL_MS = 400L

        const val DEFAULT_MIN_SEGMENT_MS = 600L

        const val DEFAULT_MAX_SEGMENT_MS = 20_000L

        /** Окно оценки шумового пола: 50 чанков по 100 мс = 5 с. */
        const val NOISE_WINDOW_CHUNKS = 50

        /** Порог = фон × 3: гул заметно ниже речи, но и не «дышит» на паузах. */
        const val NOISE_FLOOR_FACTOR = 3.0

        /** Тише этого порог не опускается, иначе шипение микрофона станет «речью». */
        const val MIN_ADAPTIVE_THRESHOLD_RMS = 150

        /** Стартовая оценка фона до накопления окна (тихая комната). */
        const val DEFAULT_NOISE_FLOOR_RMS = 40.0
    }
}
