package ru.mars.mobile

/**
 * Политика показа стартовой заставки: сколько её держать, как долго гасить и
 * пульсировать ли ореол.
 *
 * Здесь только ДАННЫЕ и арифметика — ни одного Android-класса, поэтому тайминги
 * проверяются обычным JUnit (см. `SplashPolicyTest`), а не секундомером на
 * устройстве. Экран [SplashActivity] лишь исполняет решения: спрашивает
 * [decisionFor] на каждом тике, берёт [fadeOutMs] для анимации ухода и
 * [shouldPulse] для запуска аниматора.
 *
 * Почему заставка вообще имеет нижнюю границу: чтение credential из
 * AndroidKeyStore на быстром устройстве укладывается в десятки миллисекунд, и без
 * [MIN_VISIBLE_MS] бренд-экран мелькал бы одним кадром — это читается как «мигнуло
 * и сломалось», хуже, чем его отсутствие. Почему есть верхняя: ожидание сети или
 * keystore не должно превращаться в бесконечный логотип — по [MAX_VISIBLE_MS]
 * заставка уходит в логин, даже если цель не готова (логин сам покажет ошибку).
 *
 * Reduce motion уважается грубо и раньше всех прочих условий: при выключенных
 * анимациях заставка не задерживает вообще ([Decision.Go] сразу, [fadeOutMs] = 0).
 * Пользователь, который системно выключил анимации, не должен ждать декоративную
 * паузу.
 */
object SplashPolicy {

    /** Минимальное время на экране: короче — уже «мигание», а не заставка. */
    const val MIN_VISIBLE_MS: Int = 600

    /** Предел ожидания неготовой цели: дольше держать бренд-экран нельзя. */
    const val MAX_VISIBLE_MS: Int = 1800

    /** Длительность ухода в целевую Activity; укладывается в [MarsMotion.slowMs]. */
    const val FADE_OUT_MS: Int = 260

    /** Период неоновой пульсации ореола — тот же, что у статус-точки «онлайн». */
    const val PULSE_PERIOD_MS: Int = 3000

    /**
     * Решение по текущему кадру заставки: держать ещё [Hold.remainingMs] или
     * уходить ([Go]).
     *
     * Отдельный тип вместо `Boolean` нужен, чтобы вызывающий не изобретал
     * собственный таймер: политика сама называет остаток, и экран просто
     * переспрашивает её по истечении этого срока.
     */
    sealed interface Decision {
        /** Продолжаем показывать заставку ещё [remainingMs] миллисекунд. */
        data class Hold(val remainingMs: Int) : Decision

        /** Заставка отработала — можно гасить и открывать целевой экран. */
        data object Go : Decision
    }

    /**
     * Что делать на отметке [elapsedMs] от старта заставки.
     *
     * Порядок условий важен и является контрактом:
     * 1. reduce motion — сразу [Decision.Go]: доступность важнее бренда;
     * 2. меньше [MIN_VISIBLE_MS] — [Decision.Hold] на остаток, даже если цель уже
     *    готова (иначе экран мигает);
     * 3. цель не готова и лимит не выбран — [Decision.Hold] на остаток до
     *    [MAX_VISIBLE_MS] (никогда не 0: нулевой Hold превратился бы в busy-loop);
     * 4. иначе — [Decision.Go].
     *
     * Отрицательный или битый [elapsedMs] трактуется как 0: монотонные часы
     * сбрасываются при перезагрузке, и отрицательная дельта не должна ни
     * ускорять уход, ни вешать заставку.
     *
     * @param elapsedMs время с момента показа экрана, мс.
     * @param targetReady прочитан ли credential (известен ли маршрут).
     * @param reducedMotion выключены ли анимации в системе.
     */
    fun decisionFor(elapsedMs: Long, targetReady: Boolean, reducedMotion: Boolean): Decision {
        if (reducedMotion) return Decision.Go
        val elapsed = elapsedMs.coerceAtLeast(0L)
        if (elapsed < MIN_VISIBLE_MS) return Decision.Hold((MIN_VISIBLE_MS - elapsed).toInt())
        if (!targetReady && elapsed < MAX_VISIBLE_MS) {
            return Decision.Hold(((MAX_VISIBLE_MS - elapsed).toInt()).coerceAtLeast(1))
        }
        return Decision.Go
    }

    /**
     * Длительность гашения заставки. При reduce motion — 0: экран меняется
     * мгновенно, без «проявления» следующего.
     */
    fun fadeOutMs(reducedMotion: Boolean): Int = if (reducedMotion) 0 else FADE_OUT_MS

    /**
     * Период пульсации ореола. 0 при reduce motion — это сигнал вызывающему не
     * заводить аниматор вовсе (нулевой duration в ValueAnimator дал бы вспышку).
     */
    fun pulsePeriodMs(reducedMotion: Boolean): Int = if (reducedMotion) 0 else PULSE_PERIOD_MS

    /**
     * Пульсировать ли ореол в режиме [mode]. Решение о самом режиме принимает
     * [MarsPulsePolicy] (аниматоры, энергосбережение, reduce motion) — здесь
     * только перевод режима в «да/нет», чтобы UI-код не сравнивал enum сам.
     */
    fun shouldPulse(mode: MarsPulsePolicy.PulseMode): Boolean =
        mode == MarsPulsePolicy.PulseMode.PULSE
}
