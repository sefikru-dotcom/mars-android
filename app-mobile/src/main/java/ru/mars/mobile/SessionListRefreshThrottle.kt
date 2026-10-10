package ru.mars.mobile

/** Решение [SessionListRefreshThrottle]: что делать с триггером обновления списка сессий. */
sealed interface SessionRefreshDecision {
    /** Обновить сейчас. */
    data object Now : SessionRefreshDecision

    /** Триггер поглощён: обновление уже выполняется (coalesce — см. [SessionListRefreshThrottle.onFinished]). */
    data object Skip : SessionRefreshDecision

    /** Слишком свежее обновление: повторить через [delayMs]. */
    data class Later(val delayMs: Long) : SessionRefreshDecision
}

/**
 * Защита от шторма при обновлении списка сессий на восстановлении сети: сеть после
 * handover/переподключения может «моргать» (restored-переходы серией), и каждое событие
 * запускало бы `GET /api/sessions` — ливень запросов на только что вернувшемся канале.
 *
 * Правила (чистый JVM, время инжектируется):
 * - чаще [minIntervalMs] от последнего ЗАВЕРШЁННОГО обновления — [SessionRefreshDecision.Later];
 * - обновление уже в полёте — [SessionRefreshDecision.Skip], но триггер запоминается и
 *   [onFinished] вернёт `true`: ровно один догоняющий refresh после завершения
 *   (данные, загруженные ДО возврата сети, могут быть устаревшими);
 * - иначе — [SessionRefreshDecision.Now].
 */
class SessionListRefreshThrottle(private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS) {

    private var lastFinishedAtMs: Long? = null
    private var inFlight = false
    private var pendingWhileInFlight = false

    fun onTrigger(nowMs: Long): SessionRefreshDecision {
        if (inFlight) {
            pendingWhileInFlight = true
            return SessionRefreshDecision.Skip
        }
        val last = lastFinishedAtMs
        val since = if (last == null) Long.MAX_VALUE else nowMs - last
        return if (since < minIntervalMs) {
            SessionRefreshDecision.Later(minIntervalMs - since)
        } else {
            SessionRefreshDecision.Now
        }
    }

    /** Refresh стартовал (хост вызывает перед загрузкой). */
    fun onStarted() {
        inFlight = true
    }

    /**
     * Refresh завершился. `true` — во время полёта пришёл ещё один триггер: нужно ровно
     * одно догоняющее обновление (шторм схлопывается в максимум два последовательных запроса).
     */
    fun onFinished(nowMs: Long): Boolean {
        inFlight = false
        lastFinishedAtMs = nowMs
        val pending = pendingWhileInFlight
        pendingWhileInFlight = false
        return pending
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 2_000L
    }
}
