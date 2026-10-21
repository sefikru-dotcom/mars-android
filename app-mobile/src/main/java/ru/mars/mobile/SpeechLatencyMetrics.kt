package ru.mars.mobile

/**
 * Метрики задержки голосового ответа: дельта → запрос синтеза → звук.
 *
 * Зачем отдельный тип: задержка первого звука — единственная метрика, по которой видно,
 * помогает ли потоковая озвучка вообще. Три точки замера:
 * 1. [onFirstDelta] — пришла первая дельта текста ответа (LLM начал отвечать);
 * 2. [onSynthesisRequest] — ушёл первый запрос `api/tts` (чанкер решил, что фраза готова);
 * 3. [onAudioStart] — первое аудио реально пошло в динамик.
 *
 * Приватность: класс НЕ принимает и не хранит текст, голос, токен или URL — только
 * временные отметки и счётчики. Поэтому [logLine] можно писать в лог без риска утечки
 * содержимого ответа.
 *
 * Часы подменяются ([clockMs]) — тесты не зависят от реального времени. Потокобезопасен:
 * дельты приходят с IO-потока стрима, а звук стартует с главного.
 */
class SpeechLatencyMetrics(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    private val lock = Any()
    private var firstDeltaAt: Long? = null
    private var firstRequestAt: Long? = null
    private var firstAudioAt: Long? = null
    private var requests: Int = 0
    private var retries: Int = 0
    private var failures: Int = 0
    private var rateLimited: Int = 0

    /** Первая дельта ответа этого хода (повторные вызовы игнорируются). */
    fun onFirstDelta() {
        synchronized(lock) { if (firstDeltaAt == null) firstDeltaAt = clockMs() }
    }

    /** Ушёл запрос синтеза; первый запрос фиксируется как точка «tts request». */
    fun onSynthesisRequest() {
        val now = clockMs()
        synchronized(lock) {
            requests++
            if (firstRequestAt == null) firstRequestAt = now
        }
    }

    /** Синтез повторили после `429`/`5xx`: считаем повторы и отдельно — лимитер. */
    fun onSynthesisRetry(reason: String) {
        synchronized(lock) {
            retries++
            if (reason == "rate_limited") rateLimited++
        }
    }

    /** Фраза так и не зазвучала (синтез или плеер отказали). */
    fun onPhraseFailed(reason: String) {
        synchronized(lock) {
            failures++
            if (reason == "rate_limited") rateLimited++
        }
    }

    /** Первое аудио пошло в динамик (повторные вызовы игнорируются). */
    fun onAudioStart() {
        synchronized(lock) { if (firstAudioAt == null) firstAudioAt = clockMs() }
    }

    /** Снимок метрик хода. null в разнице — соответствующая точка ещё не наступила. */
    fun snapshot(): SpeechLatencySnapshot = synchronized(lock) {
        SpeechLatencySnapshot(
            firstDeltaToRequestMs = diff(firstDeltaAt, firstRequestAt),
            firstDeltaToAudioMs = diff(firstDeltaAt, firstAudioAt),
            requestToAudioMs = diff(firstRequestAt, firstAudioAt),
            synthesisRequests = requests,
            synthesisRetries = retries,
            phraseFailures = failures,
            rateLimited = rateLimited,
        )
    }

    /** Новый ход: обнулить все точки и счётчики. */
    fun reset() {
        synchronized(lock) {
            firstDeltaAt = null
            firstRequestAt = null
            firstAudioAt = null
            requests = 0
            retries = 0
            failures = 0
            rateLimited = 0
        }
    }

    private fun diff(from: Long?, to: Long?): Long? =
        if (from == null || to == null) null else (to - from).coerceAtLeast(0L)
}

/** Неизменяемый снимок [SpeechLatencyMetrics] — без текста и секретов. */
data class SpeechLatencySnapshot(
    /** Первая дельта → первый запрос синтеза. */
    val firstDeltaToRequestMs: Long? = null,
    /** Первая дельта → первое аудио в динамике. */
    val firstDeltaToAudioMs: Long? = null,
    /** Запрос синтеза → аудио (стоимость самого TTS). */
    val requestToAudioMs: Long? = null,
    val synthesisRequests: Int = 0,
    val synthesisRetries: Int = 0,
    val phraseFailures: Int = 0,
    val rateLimited: Int = 0,
) {

    /**
     * Строка для лога: только числа и имена счётчиков.
     *
     * Пример: `speech delta->tts=310ms tts->audio=540ms delta->audio=850ms
     * requests=3 retries=1 failed=0 limited=0`.
     */
    fun logLine(): String = buildString {
        append("speech")
        append(" delta->tts=").append(firstDeltaToRequestMs.msOrDash())
        append(" tts->audio=").append(requestToAudioMs.msOrDash())
        append(" delta->audio=").append(firstDeltaToAudioMs.msOrDash())
        append(" requests=").append(synthesisRequests)
        append(" retries=").append(synthesisRetries)
        append(" failed=").append(phraseFailures)
        append(" limited=").append(rateLimited)
    }

    private fun Long?.msOrDash(): String = if (this == null) "-" else "${this}ms"
}
