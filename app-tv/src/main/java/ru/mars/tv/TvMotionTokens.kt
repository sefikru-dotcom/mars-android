package ru.mars.tv

/**
 * Токены движения TV-клиента Марса (чистый JVM, без Android-классов; паритет с mobile
 * `ru.mars.mobile.MarsMotion`). Телевизор — «диванная» поверхность под D-pad, поэтому
 * стойка та же, что на телефоне, и держится строго:
 *
 * - только короткие длительности: ни один эффект не длиннее [slowMs];
 * - стандартная кривая — [INTERPOLATOR_DECELERATE]: элемент стартует быстро и мягко
 *   садится, что читается как отзывчивость, а не как игра;
 * - bounce/overshoot запрещены ([INTERPOLATOR_BOUNCE] и [INTERPOLATOR_OVERSHOOT] живут
 *   только как запретные идентификаторы для проверок);
 * - бесконечных декоративных циклов нет;
 * - каждый потребитель обязан проводить длительность через [TvMotionPolicy.durationFor],
 *   чтобы системная настройка «уменьшить движение» схлопывала эффект в мгновенную смену
 *   состояния.
 *
 * Отдельный от mobile источник намеренно: TV-эффекты (подъём под фокусом, «К последнему»)
 * свои, а сверку значений с mobile держит [TvMotionPolicyTest] — расхождение падает тестом,
 * а не «незаметно разной скоростью» на двух клиентах.
 *
 * Интерполяторы — обычные строковые идентификаторы, которые view-слой разрешает сам
 * (DecelerateInterpolator/AccelerateInterpolator), поэтому файл остаётся тестируемым на JVM.
 */
object TvMotionTokens {

    /** Микро-отклик: движение под фокусом D-pad, появление «К последнему». */
    const val fastMs: Int = 120

    /** Стандартное движение контента: появление сообщения, раскрытие карточки, баннер сети. */
    const val baseMs: Int = 200

    /** Крупные поверхности. Дольше этого — нельзя, даже для «важных» экранов. */
    const val slowMs: Int = 300

    /** Стандартная кривая: быстрый старт, мягкая посадка. Дефолт для любого перехода. */
    const val INTERPOLATOR_DECELERATE: String = "decelerate"

    /** Для контента, который физически уходит с экрана. */
    const val INTERPOLATOR_ACCELERATE: String = "accelerate"

    /** Запрещено: bounce ломает спокойную стойку; оставлен только для проверок. */
    const val INTERPOLATOR_BOUNCE: String = "bounce"

    /** Запрещено: overshoot ломает спокойную стойку; оставлен только для проверок. */
    const val INTERPOLATOR_OVERSHOOT: String = "overshoot"

    /** Интерполяторы, которые в этом клиенте не используются никогда. */
    val FORBIDDEN_INTERPOLATORS: Set<String> = setOf(INTERPOLATOR_BOUNCE, INTERPOLATOR_OVERSHOOT)

    /** True, если [interpolator] допустим стойкой движения. */
    fun isAllowedInterpolator(interpolator: String): Boolean = interpolator !in FORBIDDEN_INTERPOLATORS
}
