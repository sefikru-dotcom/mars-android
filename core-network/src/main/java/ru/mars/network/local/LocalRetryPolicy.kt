package ru.mars.network.local

import java.io.IOException

/**
 * Политика повторов одного шага стрима.
 *
 * Почему она отдельным чистым объектом: решение «повторить или отдать ошибку» принимается
 * в одном месте и проверяется без сети. Правила контракта §4, п. 5:
 *
 * - повторяем только `429`, `5xx` и обрыв соединения ([IOException]);
 * - **только до первого полученного токена**: если провайдер уже начал отвечать, повтор
 *   склеил бы два разных ответа в один и пользователь увидел бы дубль текста;
 * - не больше [MAX_ATTEMPTS] попыток всего (одна повторная);
 * - паузы — [PAUSES_MS]: 1 с перед первой повторной попыткой, 3 с перед второй.
 *
 * Паузы вынесены таблицей, а не одним числом, потому что при поднятии бюджета попыток
 * вторая пауза обязана быть длиннее первой: провайдер, отдавший 500 дважды подряд,
 * обычно восстанавливается не мгновенно.
 */
object LocalRetryPolicy {

    /** Сколько всего попыток разрешено на один шаг (первая + повторные). */
    const val MAX_ATTEMPTS: Int = 2

    /** Паузы перед повторными попытками, мс: 1 с, затем 3 с. */
    private val PAUSES_MS: LongArray = longArrayOf(1_000L, 3_000L)

    /** `429` и `5xx` — единственные HTTP-коды, которые имеет смысл повторять. */
    fun isRetryableStatus(code: Int): Boolean = code == 429 || code in 500..599

    /** Сетевой обрыв (таймаут, сброс, DNS) — повторяем; логические ошибки — нет. */
    fun isRetryableFailure(failure: Throwable): Boolean = failure is IOException

    /**
     * Стоит ли делать ещё одну попытку.
     *
     * @param attemptsMade сколько попыток уже сделано (1 — после первой неудачи);
     * @param receivedFirstToken успел ли поток отдать текст или размышления;
     * @param retryable признак повторяемости причины (код ответа или [IOException]).
     */
    fun shouldRetry(attemptsMade: Int, receivedFirstToken: Boolean, retryable: Boolean): Boolean =
        retryable && !receivedFirstToken && attemptsMade < MAX_ATTEMPTS

    /** Пауза перед следующей попыткой после [attemptsMade] неудач, мс. */
    fun pauseBeforeRetry(attemptsMade: Int): Long =
        PAUSES_MS.getOrElse(attemptsMade - 1) { PAUSES_MS.last() }
}
