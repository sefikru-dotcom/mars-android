package ru.mars.mobile

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.PowerManager
import android.provider.Settings
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View

/**
 * Орб полноэкранного голосового режима: единственное «живое» пятно экрана.
 *
 * ## Зачем своя View, а не картинка и не готовая точка
 * Орб отвечает на вопрос «слышит ли он меня прямо сейчас» без единого слова: пока
 * человек говорит, пятно дышит в такт фазе, а в покое стоит. Готовая статус-точка
 * [MarsTheme.glowingStatusDot] для этого не годится — она 12 dp и её задача другая
 * (точка в строке), а растянутая картинка потеряла бы градиент. Поэтому круг
 * рисуется на `Canvas`: три концентрических круга — ореол с радиальным градиентом,
 * плотное ядро и hairline-обводка.
 *
 * ## Почему в onDraw нет ни одной аллокации
 * Это экран разговора: он перерисовывается 60 раз в секунду, пока идёт реплика, и
 * живёт на телефоне вместе с распознаванием и синтезом. Кадр, создающий `Paint`,
 * `RadialGradient` или `RectF`, даёт мусор в молодом поколении -> GC-паузы ->
 * подёргивание орба ровно в тот момент, когда человек ждёт реакции. Поэтому:
 * - кисти создаются один раз в полях класса;
 * - градиент пересобирается только в `onSizeChanged`/при смене тона ([buildHalo]),
 *   а не на кадр;
 * - «дыхание» радиуса делается через `canvas.scale` (аффинное преобразование без
 *   выделения памяти), а не через пересоздание шейдера под новый радиус;
 * - альфа кадра выставляется в уже существующие `Paint.alpha`.
 * В `onDraw` остаются только чтения полей, арифметика и `canvas.drawCircle`.
 *
 * ## Почему Choreographer, а не `ValueAnimator` с `INFINITE`
 * Бесконечный `ValueAnimator` — самый частый способ утечки: его забывают отменить в
 * `onDetachedFromWindow`, и он держит View (а через неё Activity) вечно. Здесь цикл
 * кадров — явный [Choreographer.FrameCallback]: он ставится только пока вьюха
 * прикреплена и фаза требует движения, и снимается в [stopBreathing], которую
 * вызывает [onDetachedFromWindow]. Ни одного объекта на кадр при этом тоже не
 * создаётся: колбэк — поле класса, `Choreographer` берётся один раз.
 *
 * ## Почему доступность важнее красоты
 * При `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` (и при выключенных аниматорах,
 * и в режиме экономии батареи) орб рисуется одним статичным кадром: решение
 * принимает существующая [MarsPulsePolicy.modeFor], а не своя копия условий.
 * Пульсация для такого человека — не «мягкое дыхание», а раздражитель, и цикл
 * кадров ещё и жжёт батарею на экране, который и так держит микрофон.
 */
class VoiceModeOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // --- кисти: созданы один раз, onDraw их только читает -----------------------------------

    /** Ореол: радиальный градиент «тон -> прозрачный». Альфа кадра — в [Paint.alpha]. */
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Ядро: плотный круг в тон фазы, чтобы центр не растворялся в ореоле. */
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Hairline-обводка: 1 dp, тон фазы на 20% альфы (для рубинового это [MarsTheme.ACCENT_BORDER]). */
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = MarsTheme.dp(context, 1).toFloat().coerceAtLeast(1f)
    }

    /** Градиент ореола: пересобирается только при смене размера или тона. */
    private var halo: RadialGradient? = null

    private var centerX = 0f
    private var centerY = 0f

    /** Радиус ореола в пикселях: 48% меньшей стороны, то есть свет гаснет до краёв вьюхи. */
    private var radius = 0f

    /** Текущая роль цвета. Меняется только через [render]. */
    private var tone: VoiceModeTone = VoiceModeTone.IDLE

    /** Просили ли движение. Само движение дополнительно зависит от системных флагов. */
    private var pulses = false

    /** Амплитуда микрофона 0..1: человек говорит громче — орб крупнее и ярче. */
    private var level = 0f

    /** Фаза дыхания 0..1 внутри периода; в статичном режиме — [REST_PHASE]. */
    private var phase = REST_PHASE

    /** Идёт ли сейчас цикл кадров. Единственный флаг, которым управляет [startBreathing]/[stopBreathing]. */
    private var breathing = false

    /**
     * Разрешено ли движение системой — посчитанное заранее.
     *
     * Чтение `Settings.Global` и опрос `PowerManager` идут через `ContentResolver`:
     * это единицы миллисекунд, и на каждую дельту распознавания (хост может звать
     * [render] десятки раз в секунду) такая проверка стала бы заметной. Поэтому
     * решение принимается при смене фазы и при переподключении вьюхи, а не на кадр.
     */
    private var motionAllowed = false

    /** Отсчёт периода: время первого кадра цикла. 0 — цикл ещё не начался. */
    private var originNanos = 0L

    /** Берётся один раз на цикл, а не на кадр: `getInstance()` — обращение к ThreadLocal. */
    private var choreographer: Choreographer? = null

    /**
     * Кадр дыхания.
     *
     * Объект-поле, а не лямбда на кадр: аллокация в цикле перерисовки недопустима
     * (см. KDoc класса). Фаза считается от времени кадра, а не накоплением `+= dt`:
     * накопление уплывает при пропущенных кадрах и после долгой паузы даёт рывок.
     */
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!breathing) return
            if (originNanos == 0L) originNanos = frameTimeNanos
            val elapsedMs = (frameTimeNanos - originNanos) / 1_000_000L
            phase = (elapsedMs % PERIOD_MS).toFloat() / PERIOD_MS.toFloat()
            invalidate()
            // Переставляем кадр только если цикл всё ещё нужен: иначе после
            // отсоединения вьюхи он жил бы до следующего кадра вхолостую.
            if (breathing) scheduleFrame()
        }
    }

    init {
        // Орб — индикатор, а не кнопка: нажатия ловит кнопка под ним, а фокус
        // скринридера должен идти по тексту фазы, а не по пятну.
        isClickable = false
        isFocusable = false
        // Кисти обязаны получить цвет ДО первого кадра: `render` перекрашивает их только
        // при СМЕНЕ тона, и без этой строки первый кадр в покое рисовал бы ядро
        // дефолтным чёрным (Paint по умолчанию чёрный), пока фаза не сменится.
        applyTone()
    }

    /**
     * Показать фазу.
     *
     * @param tone роль цвета: покой/слушаю — рубин, думаю — предупреждение, говорю — инфо
     *   (те же роли, что у [LocalVoiceButton], чтобы кнопка композера и полный экран
     *   не спорили о цвете одной и той же фазы);
     * @param pulses нужен ли цикл дыхания. `false` — статичный кадр, цикл кадров не
     *   заводится вовсе.
     */
    fun render(tone: VoiceModeTone, pulses: Boolean) {
        val toneChanged = this.tone != tone
        val pulsesChanged = this.pulses != pulses
        this.tone = tone
        this.pulses = pulses
        if (pulsesChanged) motionAllowed = pulses && animationAllowed()
        if (toneChanged) {
            applyTone()
            invalidate()
        }
        syncBreathing()
    }

    /**
     * Амплитуда микрофона 0..1: «дышит по речи».
     *
     * Значение зажимается и защищено от NaN: битый уровень (например, деление на ноль
     * в анализаторе) не должен превратить радиус в NaN — тогда `drawCircle` не рисует
     * ничего и орб просто исчезает без единой ошибки в логе.
     *
     * Сглаживание сознательно не делается здесь: источник уровня знает свою частоту
     * обновления, а вьюха — нет. Резкий шаг радиуса на кадр читается как «вспышка», а
     * не как речь, поэтому сглаживать должен тот, кто считает RMS.
     */
    fun setLevel(level: Float) {
        val next = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
        if (next == this.level) return
        this.level = next
        // В статичном режиме кадров нет — перерисовку заказываем сами.
        if (!breathing) invalidate()
    }

    // --- геометрия и цвет ---------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = (if (w < h) w else h) / 2f * HALO_FILL
        // Градиент привязан к центру и радиусу, поэтому пересобирается здесь, а не на кадр.
        buildHalo()
    }

    /**
     * Пересобрать градиент под текущий тон и радиус.
     *
     * Три стопа, а не два: чистый переход «тон -> прозрачный» даёт видимую границу
     * там, где гаснет насыщенность. Средний стоп на 55% держит цвет до половины
     * радиуса, и ореол читается как свет, а не как размытый круг.
     */
    private fun buildHalo() {
        if (radius <= 0f) {
            halo = null
            haloPaint.shader = null
            return
        }
        val color = colorFor(tone)
        halo = RadialGradient(
            centerX,
            centerY,
            radius,
            intArrayOf(color, MarsGradients.withAlpha(color, HALO_MID_ALPHA), MarsGradients.withAlpha(color, 0x00)),
            floatArrayOf(0f, HALO_MID_STOP, 1f),
            Shader.TileMode.CLAMP,
        )
        haloPaint.shader = halo
    }

    /** Кисти под новый тон. Вызывается на смену фазы, не на кадр. */
    private fun applyTone() {
        val color = colorFor(tone)
        corePaint.color = color
        // Цвет обводки — НЕПРОЗРАЧНЫЙ тон фазы, а нужную прозрачность несёт альфа кадра
        // (`ringPaint.alpha`). Полупрозрачный цвет ЗДЕСЬ умножался бы на альфу кадра:
        // 20% × 30% = 6%, то есть обводки не видно вовсе. Для рубинового итог кадра
        // (22%) совпадает с брендовым hairline [MarsTheme.ACCENT_BORDER].
        ringPaint.color = color
        buildHalo()
    }

    /**
     * Цвет роли. Палитра остаётся в [MarsTheme]: здесь только выбор токена, ни одного
     * литерала — иначе у орба появилась бы своя палитра и она разошлась бы с темой.
     */
    private fun colorFor(tone: VoiceModeTone): Int = when (tone) {
        VoiceModeTone.IDLE -> MarsTheme.ACCENT
        VoiceModeTone.LISTENING -> MarsTheme.ACCENT
        VoiceModeTone.THINKING -> MarsTheme.STATUS_WARNING
        VoiceModeTone.SPEAKING -> MarsTheme.STATUS_INFO
    }

    // --- кадр ---------------------------------------------------------------------------------

    /**
     * Кадр орба.
     *
     * Ни одной аллокации: только чтения полей, арифметика, `canvas.scale` и три
     * `drawCircle`. `save`/`restoreToCount` памяти не выделяют (это счётчик в
     * внутреннем стеке Canvas).
     */
    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f || haloPaint.shader == null) return

        // Дыхание: альфа берётся из общей политики пульсации, а не из своей косинусной
        // формулы, — тогда период и «мягкость» у орба те же, что у остальных живых
        // индикаторов приложения, и выключенные анимации гасятся в одном месте.
        val glowAlpha = if (breathing) {
            MarsPulsePolicy.alphaAt(phase)
        } else {
            MarsPulsePolicy.MIN_ALPHA + (MarsPulsePolicy.MAX_ALPHA - MarsPulsePolicy.MIN_ALPHA) * REST_BREATH
        }
        val breath = ((glowAlpha - MarsPulsePolicy.MIN_ALPHA) / (MarsPulsePolicy.MAX_ALPHA - MarsPulsePolicy.MIN_ALPHA))
            .coerceIn(0f, 1f)

        // Радиус «дышит» вместе с альфой: один источник правды на оба параметра, иначе
        // пятно рассинхронизировалось бы само с собой.
        val scale = (MIN_SCALE + BREATH_SCALE * breath) * (1f + LEVEL_SCALE * level)

        haloPaint.alpha = ((glowAlpha + LEVEL_ALPHA * level).coerceIn(0f, 1f) * 255f).toInt()
        corePaint.alpha = ((CORE_ALPHA + CORE_LEVEL_ALPHA * level).coerceIn(0f, 1f) * 255f).toInt()
        ringPaint.alpha = ((RING_FRAME_ALPHA + LEVEL_RING_ALPHA * level).coerceIn(0f, 1f) * 255f).toInt()

        val saved = canvas.save()
        canvas.scale(scale, scale, centerX, centerY)
        // Ореол: шейдер привязан к полному радиусу, поэтому масштаб холста масштабирует
        // и его — пересобирать градиент под каждый кадр не нужно.
        canvas.drawCircle(centerX, centerY, radius, haloPaint)
        canvas.drawCircle(centerX, centerY, radius * CORE_RATIO, corePaint)
        canvas.drawCircle(centerX, centerY, radius * RING_RATIO, ringPaint)
        canvas.restoreToCount(saved)
    }

    // --- жизненный цикл дыхания ---------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Вьюха могла быть прикреплена уже после render(): здесь догоняем состояние.
        // Здесь же перечитываем системные флаги: человек мог вернуться на экран после
        // того, как выключил анимации в настройках.
        motionAllowed = pulses && animationAllowed()
        syncBreathing()
    }

    override fun onDetachedFromWindow() {
        // Сначала гасим цикл, потом отдаём вьюху системе: иначе следующий кадр
        // пришёл бы на отсоединённую вьюху и держал её (и Activity) до своего конца.
        stopBreathing()
        super.onDetachedFromWindow()
    }

    /**
     * Согласовать цикл кадров с желанием вызывающего и системными флагами.
     *
     * Единственное место, где решается «дышать или стоять», поэтому здесь же
     * снимаются кадры при выключенных анимациях — иначе решение пришлось бы
     * дублировать в [render] и в [onAttachedToWindow].
     */
    private fun syncBreathing() {
        if (!motionAllowed) {
            stopBreathing()
            // Статичный кадр — не «пустой»: орб остаётся видимым, просто не дышит.
            // Фаза сбрасывается в опорную точку, чтобы картинка не зависела от того,
            // на каком кадре человека выключили анимации.
            phase = REST_PHASE
            invalidate()
            return
        }
        if (isAttachedToWindow) startBreathing() else stopBreathing()
    }

    private fun startBreathing() {
        if (breathing) return
        breathing = true
        originNanos = 0L
        scheduleFrame()
        invalidate()
    }

    private fun stopBreathing() {
        if (!breathing) return
        breathing = false
        choreographer?.removeFrameCallback(frameCallback)
    }

    private fun scheduleFrame() {
        val frames = choreographer ?: Choreographer.getInstance().also { choreographer = it }
        frames.postFrameCallback(frameCallback)
    }

    /**
     * Разрешено ли движение системой.
     *
     * Условия не пишутся заново: их набор и порядок — [MarsPulsePolicy.modeFor], та же
     * политика, что гасит пульсацию статус-точек. Своя проверка «только
     * ANIMATOR_DURATION_SCALE == 0» пропустила бы выключенные аниматоры и режим
     * экономии батареи, и орб дышал бы у человека, который просил покой.
     */
    private fun animationAllowed(): Boolean {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        val powerSave = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isPowerSaveMode == true
        return MarsPulsePolicy.modeFor(
            animationsRequested = true,
            animatorsEnabled = ValueAnimator.areAnimatorsEnabled(),
            powerSaveMode = powerSave,
            animatorDurationScale = scale,
        ) == MarsPulsePolicy.PulseMode.PULSE
    }

    private companion object {
        /**
         * Период дыхания: тот же спокойный темп, что у «онлайн»-точки в шапке.
         *
         * `val`, а не `const`: `PERIOD_ONLINE_MS` — чужая константа, и приведение
         * `toLong()` в инициализаторе `const` не допускается компилятором.
         */
        val PERIOD_MS: Long = MarsPulsePolicy.PERIOD_ONLINE_MS.toLong()

        /** Опорная фаза статичного кадра: середина подъёма — орб выглядит «живым», но стоит. */
        const val REST_PHASE = 0.32f

        /** Та же точка в долях амплитуды — для ветки без цикла кадров. */
        const val REST_BREATH = 0.32f

        /** Ореол занимает 48% меньшей стороны: габарит вьюхи — это «воздух» вокруг света. */
        const val HALO_FILL = 0.96f

        /** Радиус ядра от радиуса ореола. */
        const val CORE_RATIO = 0.34f

        /** Радиус обводки: между ядром и краем ореола — там, где гаснет цвет. */
        const val RING_RATIO = 0.62f

        /** Альфа ядра: чуть меньше единицы, иначе центр выглядит наклейкой. */
        const val CORE_ALPHA = 0.92f

        /** Альфа средней точки градиента и её позиция. */
        const val HALO_MID_ALPHA = 0x66
        const val HALO_MID_STOP = 0.55f

        /**
         * Альфа обводки на кадре: 22% — брендовый hairline (MarsTheme.ACCENT_BORDER — те же
         * 20% плюс запас на субпиксель); добавка от громкости делает обводку заметнее,
         * когда человек говорит.
         */
        const val RING_FRAME_ALPHA = 0.22f
        const val LEVEL_RING_ALPHA = 0.35f

        /** Доля радиуса, которую добавляет полная громкость: заметно, но не «прыжок». */
        const val LEVEL_SCALE = 0.16f

        /** Добавка к альфе ореола от громкости. */
        const val LEVEL_ALPHA = 0.25f

        /** Добавка к альфе ядра от громкости. */
        const val CORE_LEVEL_ALPHA = 0.08f

        /** Нижняя граница масштаба дыхания. */
        const val MIN_SCALE = 0.94f

        /** Размах масштаба дыхания: 6% — «дыхание», а не пульсация. */
        const val BREATH_SCALE = 0.06f
    }
}