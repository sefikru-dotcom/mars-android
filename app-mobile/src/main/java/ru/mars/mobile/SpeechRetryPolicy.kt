package ru.mars.mobile

import kotlinx.coroutines.delay

/**
 * Политика повтора синтеза ОДНОЙ фразы в очереди озвучки.
 *
 * Почему повтор нужен на уровне фразы, а не только внутри HTTP-клиента: потоковая озвучка
 * отправляет фразы подряд, пока играет предыдущая, а у `api/tts` лимитер **2 секунды на
 * клиента**. Короткая фраза синтезируется быстрее окна лимитера → `429 rate limit exceeded`
 * → без повтора в речи появляется дырка. Бесконечные ретраи недопустимы: звук отстаёт от
 * текста и перебивание перестаёт работать.
 *
 * Правила:
 * - не больше [maxAttempts] попыток, включая первую (по умолчанию 2 — один повтор);
 * - пауза из `Retry-After` (если сервер дал), иначе бэкофф по номеру попытки;
 * - пауза ограничена [maxDelayMs]: ждать «сколько попросили» нельзя, речь не должна
 *   залипать;
 * - ожидание отменяемое ([sleeper] = `delay` в корутине очереди): `stopAll()`/перебивание
 *   прерывает его мгновенно, иначе после «стоп» звук всё равно пришёл бы через 2 с.
 *
 * @param sleeper подмена ожидания в тестах (там передаётся no-op, чтобы тесты не спали).
 */
class SpeechRetryPolicy(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val sleeper: suspend (Long) -> Unit = { millis -> delay(millis) },
) {

    /** Повторять ли синтез с такой причиной отказа. */
    fun isRetryable(reason: String): Boolean = reason == "rate_limited" || reason == "server_error"

    /**
     * Пауза перед повтором, мс: `Retry-After` сервера либо бэкофф, всегда `0..maxDelayMs`.
     *
     * @param attempt номер УЖЕ выполненной попытки (1 после первой).
     */
    fun delayBeforeRetryMs(attempt: Int, retryAfterMs: Long?): Long {
        val requested = retryAfterMs ?: (DEFAULT_BACKOFF_MS * attempt.coerceAtLeast(1))
        return requested.coerceIn(0L, maxDelayMs)
    }

    /** Подождать перед повтором; отмена корутины прерывает ожидание. */
    suspend fun awaitRetry(attempt: Int, retryAfterMs: Long?) {
        val wait = delayBeforeRetryMs(attempt, retryAfterMs)
        if (wait > 0) sleeper(wait)
    }

    companion object {
        /** Две попытки: один повтор закрывает окно лимитера, дальше фразу пропускаем. */
        const val DEFAULT_MAX_ATTEMPTS: Int = 2

        /** Потолок паузы: окно лимитера 2 с, поэтому 2.5 с хватает и не залипает. */
        const val DEFAULT_MAX_DELAY_MS: Long = 2_500L

        /** Бэкофф без `Retry-After`: 1.2 с ≈ окно лимитера. */
        const val DEFAULT_BACKOFF_MS: Long = 1_200L
    }
}
