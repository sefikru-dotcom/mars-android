package ru.mars.tv.chat

/**
 * Чистая политика батчинга перерисовки живого пузыря.
 *
 * SSE-чанки приходят чаще, чем имеет смысл обновлять TextView: каждое присваивание
 * `text` на длинном ответе запускает релayout всего пузыря, а на TV-приставке это
 * главный источник деградации во время стрима. Решение — не чаще одной отрисовки в
 * [MIN_RENDER_INTERVAL_MS]: первый чанк рисуется сразу, следующие копят текст, и
 * отложенный flush рисует ПОСЛЕДНИЙ накопленный текст (промежуточные состояния
 * отбрасываются, а не ставятся в очередь). Терминальные события (Done/Error) хост
 * flush'ит вне очереди — итоговый текст не зависит от батчинга.
 *
 * Без Handler/Choreographer: хост ([ru.mars.tv.TvChatActivity]) владеет таймером и
 * передаёт сюда elapsed/флаг — тестируется на JVM ([TvLiveRenderBatchPolicyTest]).
 */
object TvLiveRenderBatchPolicy {

    /** Минимальный интервал между отрисовками живого пузыря (~15 fps для текста достаточно). */
    const val MIN_RENDER_INTERVAL_MS = 64L

    sealed interface Decision {
        /** Текст рисуется немедленно (интервал выдержан). */
        data object Now : Decision

        /** Flush ещё не запланирован — поставить отложенную отрисовку через [delayMs]. */
        data class Defer(val delayMs: Long) : Decision

        /** Отложенный flush уже стоит и сам нарисует последний накопленный текст. */
        data object Nothing : Decision
    }

    /**
     * @param flushPending стоит ли уже отложенный flush;
     * @param elapsedSinceLastRenderMs сколько прошло с последней фактической отрисовки.
     */
    fun decide(
        flushPending: Boolean,
        elapsedSinceLastRenderMs: Long,
        intervalMs: Long = MIN_RENDER_INTERVAL_MS,
    ): Decision {
        if (flushPending) return Decision.Nothing
        val interval = intervalMs.coerceAtLeast(0L)
        val elapsed = elapsedSinceLastRenderMs.coerceAtLeast(0L)
        return if (elapsed >= interval) Decision.Now else Decision.Defer(interval - elapsed)
    }
}
