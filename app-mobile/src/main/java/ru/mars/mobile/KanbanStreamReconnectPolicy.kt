package ru.mars.mobile

/**
 * Расписание переподключения живого SSE-потока канбана (долг O2).
 *
 * Сервер закрывает поток по таймауту/рестарту WebUI, а сеть телефона рвётся
 * штатно. Пока поток молчит, экран живёт на таймере polling, но ждать до
 * следующего входа на экран нельзя: одна поездка в метро иначе оставляет доску
 * «полуживой» до onResume. Поэтому поток поднимается сам, с нарастающей паузой —
 * чтобы упавший сервер не получал шквал переподключений.
 *
 * Чистая арифметика без Android: покрыта обычными JVM-тестами.
 */
object KanbanStreamReconnectPolicy {

    /** Первая пауза перед переподключением: мгновенный ретрай только маскирует обрыв. */
    const val BASE_DELAY_MS = 1_000L

    /** Потолок паузы: дальше ждать дольше бессмысленно — экран всё равно на polling. */
    const val MAX_DELAY_MS = 15_000L

    /** Номер последней ступени: BASE * 2^MAX_ATTEMPT уже перекрывает [MAX_DELAY_MS]. */
    const val MAX_ATTEMPT = 4

    /**
     * Пауза перед следующей попыткой. [attempt] — номер неудачи, начиная с 0:
     * 1s → 2s → 4s → 8s → 15s (потолок).
     */
    fun delayMs(attempt: Int): Long {
        val step = attempt.coerceIn(0, MAX_ATTEMPT)
        val delay = BASE_DELAY_MS shl step
        return if (delay > MAX_DELAY_MS) MAX_DELAY_MS else delay
    }

    /**
     * Номер попытки для следующего круга. Чистое закрытие потока сервером (не
     * ошибка) счётчик сбрасывает: это штатный конец стрима, а не серия отказов.
     */
    fun nextAttempt(attempt: Int, failed: Boolean): Int =
        if (failed) (attempt + 1).coerceAtMost(MAX_ATTEMPT) else 0
}
