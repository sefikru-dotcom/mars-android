package ru.mars.tv

/**
 * Чистая политика движения TV-клиента: отображает событие интерфейса в длительность и
 * уважает системную настройку «уменьшить движение». Android-импортов здесь нет намеренно —
 * слой View читает результат и применяет его к своему аниматору, а сам файл тестируется
 * на JVM ([TvMotionPolicyTest]). Контракт повторяет mobile `ru.mars.mobile.MotionPolicy`:
 *
 * - [isReducedMotion] трактует Settings.Global.ANIMATOR_DURATION_SCALE: 0f выключает
 *   анимации во всей системе, а отрицательное и NaN считаются «выключено» тоже
 *   (защита: сломанный масштаб не должен давать движение);
 * - при уменьшенном движении любая длительность схлопывается в 0 (мгновенная смена
 *   состояния) — вызывающему не нужно ветвиться, схлопывание происходит здесь;
 * - иначе длительности берутся из токенов [TvMotionTokens] — короткое движение с
 *   decelerate-кривой, как описано там.
 */

/**
 * События интерфейса TV-клиента, которые несут движение. Набор — под реальные TV-эффекты:
 * придуманных «на будущее» cue здесь нет, и наоборот: каждому cue соответствует место,
 * где длительность уже применяется.
 */
enum class TvMotionCue {
    /** Появление сообщения в ленте транскрипта (пузырь пользователя или ответа). */
    CHAT_MESSAGE_REVEAL,

    /** Раскрытие карточки: интерактивная карточка approval/clarify, «богатый» пузырь. */
    CARD_EXPAND,

    /** Показ и скрытие управляющей «К последнему» (пользователь ушёл от живого края). */
    SCROLL_TO_BOTTOM_HINT,

    /** Появление баннера сети (офлайн/восстановление связи). */
    NETWORK_BANNER,

    /** Микро-отклик на фокусе: подъём и подсветка под D-pad. */
    FOCUS_LIFT,
}

object TvMotionPolicy {

    /**
     * True, когда движение обязано быть подавлено: системный масштаб длительности
     * аниматоров равен 0 («анимации выключены»), отрицателен или не является числом.
     */
    fun isReducedMotion(animatorDurationScale: Float): Boolean =
        animatorDurationScale.isNaN() || animatorDurationScale <= 0f

    /**
     * Длительность в миллисекундах для [cue]. Возвращает 0 при [reducedMotion] — тогда
     * вызывающий ставит финальное состояние без аниматора.
     */
    fun durationFor(cue: TvMotionCue, reducedMotion: Boolean): Int {
        if (reducedMotion) return 0
        return when (cue) {
            TvMotionCue.CHAT_MESSAGE_REVEAL -> TvMotionTokens.baseMs
            TvMotionCue.CARD_EXPAND -> TvMotionTokens.baseMs
            TvMotionCue.NETWORK_BANNER -> TvMotionTokens.baseMs
            TvMotionCue.SCROLL_TO_BOTTOM_HINT -> TvMotionTokens.fastMs
            TvMotionCue.FOCUS_LIFT -> TvMotionTokens.fastMs
        }
    }
}
