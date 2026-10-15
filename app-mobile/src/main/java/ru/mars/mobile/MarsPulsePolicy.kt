package ru.mars.mobile

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Чистая JVM-политика мягкой неоновой пульсации статус-индикаторов
 * (паритет с WebUI `mars-neon-pulse`, 3s ease-in-out infinite).
 *
 * Здесь только ДАННЫЕ и математика: ни одного Android-класса, поэтому период,
 * амплитуда и форма кривой проверяются обычным JUnit без Robolectric.
 * View-слой ([MarsTheme.glowingStatusDot]) берёт отсюда период и альфу кадра —
 * решение о виде анимации принимается в политике, а не в UI-коде.
 *
 * Стойка motion-проекта (см. [MarsMotion]): бесконечные ДЕКОРАТИВНЫЕ циклы
 * запрещены. Пульсация — не декорация, а индикатор живого состояния (идёт ход,
 * узел онлайн) и допущена явным исключением с жёсткими границами:
 * - период не короче [MIN_PERIOD_MS] (1.2 s) — никакого стробоскопа;
 * - альфа ореола всегда внутри [[MIN_ALPHA], [MAX_ALPHA]] (0.25..0.65);
 * - кривая симметричная ease-in-out, 0 → 1 → 0, без overshoot;
 * - при отключённых анимациях пульсации нет вовсе ([PulseMode.STATIC]).
 *
 * Пульсация СОЗНАТЕЛЬНО не проведена через [MotionPolicy.durationFor] и не
 * добавлена в [MotionCue]: тот перечень описывает ПЕРЕХОДЫ (появилось,
 * раскрылось, уехало) с верхней границей [MarsMotion.slowMs], а пульсация —
 * ЦИКЛ. Смешивать их нельзя, иначе «reduce motion» начнёт резать период с
 * 3 s до нуля и точка станет мигать. Гашение в статику выполняется здесь
 * ([modeFor]), где [MotionPolicy.isReducedMotion] выступает одним из условий.
 *
 * Пульсирует ТОЛЬКО альфа ореол-слоя: геометрия, размер и текст не трогаются,
 * иначе перерисовка выходит за границы точки.
 */
object MarsPulsePolicy {

    /** Мягкий период статус-точки «выполняется» (WebUI `wlpulse` ≈1.3 s). */
    const val PERIOD_RUNNING_MS: Int = 1300

    /** Мягкий период точки «онлайн» в шапке (WebUI `mars-neon-pulse` 3 s). */
    const val PERIOD_ONLINE_MS: Int = 3000

    /** Период точек прогресса/шагов хода внутри карточки. */
    const val PERIOD_PROGRESS_MS: Int = 2600

    /** Нижний предел «мягкости»: короче — уже мигание, а не дыхание. */
    const val MIN_PERIOD_MS: Int = 1200

    /** Верхний предел: слишком медленная пульсация читается как «зависло». */
    const val MAX_PERIOD_MS: Int = 4000

    /** Минимальная альфа ореола в самой тёмной фазе цикла. */
    const val MIN_ALPHA: Float = 0.25f

    /** Максимальная альфа ореола в пике (WebUI: border-color 0.65 на 50%). */
    const val MAX_ALPHA: Float = 0.65f

    /** Максимально мягкая амплитуда точки «онлайн» в шапке — без «фонаря». */
    const val ONLINE_MAX_ALPHA: Float = 0.5f

    /**
     * Альфа ореола в фазе [phase] ∈ [0,1): 0 и 1 — самая тёмная точка (min),
     * 0.5 — пик. Косинусный ease-in-out: симметрично относительно 0.5, без
     * overshoot и без излома на стыке цикла (производная на 0 и 1 равна нулю).
     *
     * @param maxAlpha пик амплитуды; зажимается в [[MIN_ALPHA], [MAX_ALPHA]].
     * @return альфа внутри [MIN_ALPHA, maxAlpha] — выход за пределы невозможен.
     */
    fun alphaAt(phase: Float, maxAlpha: Float = MAX_ALPHA): Float {
        val span = clampAlpha(maxAlpha) - MIN_ALPHA
        // Неконечная/битая фаза трактуется как начало цикла: NaN, доехавший до
        // drawable.setAlpha, оставил бы ореол в случайном виде.
        val p = if (phase.isFinite()) phase - floor(phase) else 0f
        val raised = (1.0 - cos(2.0 * PI * p)) / 2.0
        return MIN_ALPHA + span * raised.toFloat()
    }

    /** Альфа ореола в байтах для [LayerDrawable.setLayerAlpha]; всегда 0..255. */
    fun alphaByteAt(phase: Float, maxAlpha: Float = MAX_ALPHA): Int =
        (alphaAt(phase, maxAlpha) * 255f).roundToInt().coerceIn(0, 255)

    /** Зажим пиковой альфы в допустимый коридор [[MIN_ALPHA], [MAX_ALPHA]]. */
    fun clampAlpha(maxAlpha: Float): Float = when {
        maxAlpha.isNaN() -> MIN_ALPHA
        maxAlpha < MIN_ALPHA -> MIN_ALPHA
        maxAlpha > MAX_ALPHA -> MAX_ALPHA
        else -> maxAlpha
    }

    /** Назначение статус-точки: от него зависят период и амплитуда. */
    enum class PulseRole(
        /** Период полного цикла, мс. */
        val periodMs: Int,
        /** Пиковая альфа ореола — задаёт «мягкость» конкретной точки. */
        val maxAlpha: Float,
    ) {
        /** Идёт ход: WebUI `wlpulse` ≈1.3 s, ореол заметно дышит. */
        RUNNING(PERIOD_RUNNING_MS, MAX_ALPHA),

        /** Узел онлайн: WebUI `mars-neon-pulse` 3 s, максимально мягко. */
        ONLINE(PERIOD_ONLINE_MS, ONLINE_MAX_ALPHA),

        /** Шаги/прогресс внутри карточки: спокойный средний темп. */
        PROGRESS(PERIOD_PROGRESS_MS, MAX_ALPHA);

        /** Период роли лежит в разрешённом коридоре «мягкости». */
        val isCalmPeriod: Boolean get() = periodMs in MIN_PERIOD_MS..MAX_PERIOD_MS
    }

    /**
     * Режим индикатора. [STATIC] — не «быстро», а «без анимации»: точка
     * рисуется в одном кадре, аниматор не заводится вообще.
     */
    enum class PulseMode {
        STATIC,
        PULSE,
    }

    /**
     * Нужно ли анимировать ореол. Пульсация гасится в [PulseMode.STATIC], когда:
     * - пульсация выключена вызывающим ([animationsRequested] = false);
     * - системно выключены аниматоры (`ValueAnimator.areAnimatorsEnabled()`);
     * - включён режим экономии батареи;
     * - сработал «reduce motion» ([MotionPolicy.isReducedMotion]: duration scale 0/NaN/отрицательный).
     *
     * Условия приходят «снаружи», но решение принимается здесь: так escape-hatch
     * покрыт чистым JVM-тестом, а не только поведением на устройстве.
     */
    fun modeFor(
        animationsRequested: Boolean,
        animatorsEnabled: Boolean,
        powerSaveMode: Boolean,
        animatorDurationScale: Float,
    ): PulseMode {
        if (!animationsRequested) return PulseMode.STATIC
        if (!animatorsEnabled) return PulseMode.STATIC
        if (powerSaveMode) return PulseMode.STATIC
        if (MotionPolicy.isReducedMotion(animatorDurationScale)) return PulseMode.STATIC
        return PulseMode.PULSE
    }

    /**
     * Период цикла для [role] с учётом [mode]. В [PulseMode.STATIC] цикла нет и
     * возвращается 0 — вызывающий код не должен заводить аниматор.
     */
    fun periodFor(role: PulseRole, mode: PulseMode): Int =
        if (mode == PulseMode.PULSE) role.periodMs else 0

    /**
     * Начальное состояние ореола (альфа-байт) для [mode]: пульсация стартует с
     * самой тёмной точки цикла, статика остаётся ровно в сегодняшнем ореоле
     * ([MarsGradients.GLOW_RING_ALPHA]) — выключенная пульсация не меняет вид.
     */
    fun startAlphaByte(role: PulseRole, mode: PulseMode): Int =
        if (mode == PulseMode.PULSE) alphaByteAt(0f, role.maxAlpha) else MarsGradients.GLOW_RING_ALPHA

    /**
     * Роль точки по её тону, либо `null` — тон не описывает живое состояние и
     * пульсировать НЕ должен (ошибка, предупреждение, инфо, приглушённый, акцент).
     * Так пульсация включается ровно у индикаторов «выполняется» и «онлайн»;
     * остальные точки экранов сохраняют прежний статичный вид.
     *
     * `STATUS_DONE` совпадает по значению со `STATUS_ONLINE` (та же зелёная
     * точка «готово» в списках) и наследует мягкий онлайн-период.
     */
    fun roleForColor(color: Int): PulseRole? = when (color) {
        MarsGradients.STATUS_RUNNING -> PulseRole.RUNNING
        MarsGradients.STATUS_ONLINE -> PulseRole.ONLINE
        else -> null
    }
}
