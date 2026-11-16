package ru.mars.mobile

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.PowerManager
import android.provider.Settings
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Орб полноэкранного голосового режима: планета Марс с неоновой короной, дышащей в такт речи.
 *
 * ## Почему планета, а не пятно
 * Первая версия была тремя концентрическими кругами — «живым пятном». Оно отвечало на вопрос
 * «слышит ли он меня», но не создавало ощущения разговора: на экране не было ничего, кроме
 * индикатора. Теперь в центре — та же планета, что на космической заставке приложения
 * ([MarsSpaceBackgroundView]): тот же диск, тот же терминатор, те же кратеры и пылевые бури,
 * та же палитра из [MarsSpaceShaderPolicy]. Голосовой режим перестал быть отдельным миром и
 * стал частью того же космоса.
 *
 * ## Слои кадра (снизу вверх)
 * 1. **Внешняя неоновая корона и звуковые волны.** Радиальное гало в тон фазы
 *    ([NEON_IDLE]/[NEON_LISTENING]/[NEON_THINKING]/[NEON_SPEAKING]) и три концентрических
 *    неоновых кольца, расходящихся от лимба наружу. Скорость и яркость колец зависят от
 *    [phase] (дыхание) и [level] (громкость речи) — это и есть «пульсация в такт».
 * 2. **3D-диск Марса** ([planetR] = 46% радиуса орба): тёмная ночная сторона как база,
 *    объёмный сферический градиент освещённого рельефа со смещением к источнику света,
 *    вращающиеся кратеры (реальная сферическая проекция: долгота растёт со временем, у лимба
 *    детали сжимаются и гаснут), пылевая дымка и полярная шапка.
 * 3. **Неоновый серп-лимб**: тонкое кольцо атмосферы по всему диску и огненный серп со стороны
 *    света, толщина и яркость которого дышат вместе с речью.
 * 4. **Наклонённое орбитальное кольцо** (-24°) с бегущей по нему искрой-спутником.
 *
 * ## Почему в onDraw нет ни одной аллокации
 * Это экран разговора: он перерисовывается 60 раз в секунду, пока идёт реплика, и живёт на
 * телефоне вместе с распознаванием и синтезом. Кадр, создающий `Paint`, `RadialGradient`,
 * `RectF` или `Path`, даёт мусор в молодом поколении -> GC-паузы -> подёргивание орба ровно в
 * тот момент, когда человек ждёт реакции. Поэтому:
 * - все кисти, `Path` клипа планеты и `RectF` дуг — поля класса, созданные один раз;
 * - градиенты пересобираются только в `onSizeChanged`/при смене тона ([buildShaders]);
 * - координаты кратеров предвычислены в [CRATER_LAT]/[CRATER_LON]/[CRATER_SIZE] и не
 *   пересчитываются аллокациями: на кадр только `sin`/`cos` и умножения;
 * - «дыхание» радиуса — `canvas.scale` (аффинное преобразование без выделения памяти);
 * - прозрачность слоёв — `Paint.alpha`, а не новый цвет.
 *
 * ## Почему Choreographer, а не `ValueAnimator` с `INFINITE`
 * Бесконечный `ValueAnimator` — самый частый способ утечки: его забывают отменить в
 * `onDetachedFromWindow`, и он держит View (а через неё Activity) вечно. Здесь цикл кадров —
 * явный [Choreographer.FrameCallback]: он ставится только пока вьюха прикреплена и фаза
 * требует движения, и снимается в [stopBreathing], которую вызывает [onDetachedFromWindow].
 *
 * ## Почему доступность важнее красоты
 * При `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` (и при выключенных аниматорах, и в режиме
 * экономии батареи) орб рисуется одним статичным кадром: решение принимает существующая
 * [MarsPulsePolicy.modeFor], а не своя копия условий.
 */
class VoiceModeOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // --- кисти: созданы один раз, onDraw их только читает -----------------------------------

    /** Внешняя неоновая корона: радиальный градиент «неон -> прозрачный». */
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Тёплое рубиновое свечение вокруг диска планеты — цвет Марса, не фазы. */
    private val planetGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Расходящиеся неоновые кольца: stroke, цвет — неон фазы, альфа — на кадр. */
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** Орбитальное кольцо вокруг планеты. */
    private val orbitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Искра-спутник на орбите и её затухающий хвост. */
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Ночная сторона: база диска, на которой лежит освещённый рельеф. */
    private val nightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = MarsSpaceShaderPolicy.COLOR_NIGHT_SIDE
    }

    /** Освещённый рельеф: сферический градиент, шейдер собирается в [buildShaders]. */
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Кратеры: тень и подсвеченный край — одним цветом, альфа на кадр. */
    private val craterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Пылевая буря и атмосферная дымка над полушарием. */
    private val dustPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Полярная шапка: холодная белая вуаль у полюса. */
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Тонкое неоновое кольцо атмосферы по всему диску. */
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Огненный серп на освещённом лимбе. */
    private val crescentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** Клип диска планеты: `reset` + `addCircle` на кадр памяти не выделяют. */
    private val planetClip = Path()

    /** Габарит серпа и орбиты: те же `RectF` на каждом кадре. */
    private val arcBounds = RectF()
    private val orbitBounds = RectF()

    private var centerX = 0f
    private var centerY = 0f

    /** Радиус короны в пикселях: 96% меньшей стороны — габарит вьюхи это «воздух» вокруг света. */
    private var radius = 0f

    /** Радиус самого диска планеты: 46% короны. */
    private var planetR = 0f

    /** Текущая роль цвета. Меняется только через [render]. */
    private var tone: VoiceModeTone = VoiceModeTone.IDLE

    /** Неон текущей фазы (корона, кольца, орбита, лимб). */
    private var neon = NEON_IDLE

    /** Просили ли движение. Само движение дополнительно зависит от системных флагов. */
    private var pulses = false

    /** Целевая амплитуда речи 0..1 из контроллера. */
    private var level = 0f

    /** Сглаженная амплитуда: атака быстрая, спад плавный — иначе речь читается как вспышки. */
    private var smoothedLevel = 0f

    /** Фаза дыхания 0..1 внутри периода; в статичном режиме — [REST_PHASE]. */
    private var phase = REST_PHASE

    /** Собственный угол вращения планеты в радианах: накапливается по времени кадров. */
    private var rotation = 0f

    /** Время предыдущего кадра: нужно только для приращения вращения. */
    private var lastFrameNanos = 0L

    /** Идёт ли сейчас цикл кадров. Единственный флаг, которым управляет [startBreathing]/[stopBreathing]. */
    private var breathing = false

    /**
     * Разрешено ли движение системой — посчитанное заранее.
     *
     * Чтение `Settings.Global` и опрос `PowerManager` идут через `ContentResolver`: это единицы
     * миллисекунд, и на каждый вызов [render] (хост может звать его десятки раз в секунду) такая
     * проверка стала бы заметной. Поэтому решение принимается при смене фазы и при
     * переподключении вьюхи, а не на кадр.
     */
    private var motionAllowed = false

    /** Отсчёт периода: время первого кадра цикла. 0 — цикл ещё не начался. */
    private var originNanos = 0L

    /** Берётся один раз на цикл, а не на кадр: `getInstance()` — обращение к ThreadLocal. */
    private var choreographer: Choreographer? = null

    /**
     * Кадр дыхания.
     *
     * Объект-поле, а не лямбда на кадр: аллокация в цикле перерисовки недопустима (см. KDoc
     * класса). Фаза считается от времени кадра, а не накоплением `+= dt`: накопление уплывает при
     * пропущенных кадрах и после долгой паузы даёт рывок. Вращение планеты, наоборот, копится по
     * `dt` — ему нужна непрерывность, а не привязка к периоду дыхания.
     */
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!breathing) return
            if (originNanos == 0L) originNanos = frameTimeNanos
            if (lastFrameNanos == 0L) lastFrameNanos = frameTimeNanos
            val elapsedMs = (frameTimeNanos - originNanos) / 1_000_000L
            phase = (elapsedMs % PERIOD_MS).toFloat() / PERIOD_MS.toFloat()

            // Приращение вращения: зажато сверху, чтобы после паузы (или подлагивания) планета
            // не проворачивалась на пол-оборота за один кадр.
            val dtSec = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastFrameNanos = frameTimeNanos
            rotation += dtSec * ROTATION_RAD_PER_SEC
            if (rotation > TWO_PI) rotation -= TWO_PI

            // Атака быстрее спада: слово начинается рывком, а затухает плавно.
            smoothedLevel += (level - smoothedLevel) *
                (if (level > smoothedLevel) LEVEL_ATTACK else LEVEL_RELEASE)

            invalidate()
            // Переставляем кадр только если цикл всё ещё нужен: иначе после отсоединения вьюхи
            // он жил бы до следующего кадра вхолостую.
            if (breathing) scheduleFrame()
        }
    }

    init {
        // Орб — индикатор, но с этой волны он ещё и цель нажатия: слой разговора ставит ему
        // `isClickable = true` и вешает обработчик (см. [VoiceModeOverlay]). Здесь оставляем
        // значение по умолчанию, чтобы вьюха не притворялась кнопкой там, где её не просили.
        isClickable = false
        isFocusable = false
        // Кисти обязаны получить цвет ДО первого кадра: `render` перекрашивает их только при
        // СМЕНЕ тона, и без этого первый кадр в покое рисовался бы дефолтным чёрным.
        applyTone()
    }

    /**
     * Показать фазу.
     *
     * @param tone роль цвета: покой/слушаю — рубин, думаю — янтарь, говорю — лазурь (те же роли,
     *   что у [LocalVoiceButton], чтобы кнопка композера и полный экран не спорили о цвете);
     * @param pulses нужен ли цикл дыхания. `false` — статичный кадр, цикл кадров не заводится.
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
     * Амплитуда речи 0..1: корона, волны и лимб дышат в такт.
     *
     * Значение зажимается и защищено от NaN: битый уровень (например, деление на ноль в
     * анализаторе) не должен превратить радиус в NaN — тогда `drawCircle` не рисует ничего и орб
     * просто исчезает без единой ошибки в логе.
     *
     * Сглаживание живёт в кадре ([smoothedLevel]), а не здесь: источник уровня присылает его
     * пачками по 10–16 Гц, и шаг «как есть» читался бы как мигание, а не как речь.
     */
    fun setLevel(level: Float) {
        val next = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
        if (next == this.level) return
        this.level = next
        // В статичном режиме кадров нет — сглаживать нечем, а перерисовку заказываем сами.
        if (!breathing) {
            smoothedLevel = next
            invalidate()
        }
    }

    // --- геометрия и цвет ---------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = (if (w < h) w else h) / 2f * HALO_FILL
        planetR = radius * PLANET_RATIO
        // Габариты серпа и орбиты привязаны к центру и радиусу — считаются здесь, не на кадр.
        arcBounds.set(centerX - planetR, centerY - planetR, centerX + planetR, centerY + planetR)
        orbitBounds.set(
            centerX - planetR * ORBIT_A,
            centerY - planetR * ORBIT_B,
            centerX + planetR * ORBIT_A,
            centerY + planetR * ORBIT_B,
        )
        // Градиенты привязаны к центру и радиусу, поэтому пересобираются здесь, а не на кадр.
        buildShaders()
    }

    /**
     * Пересобрать градиенты под текущий тон и радиус.
     *
     * Корона — три стопа: чистый переход «неон -> прозрачный» даёт видимую границу там, где
     * гаснет насыщенность. Средний стоп на 55% держит цвет до половины радиуса, и ореол читается
     * как свет, а не как размытый круг. Свечение планеты и её рельеф от фазы не зависят: Марс
     * остаётся Марсом, фазу несёт неон вокруг него.
     */
    private fun buildShaders() {
        if (radius <= 0f) {
            haloPaint.shader = null
            planetGlowPaint.shader = null
            surfacePaint.shader = null
            dustPaint.shader = null
            return
        }
        haloPaint.shader = RadialGradient(
            centerX,
            centerY,
            radius,
            intArrayOf(
                neon,
                MarsGradients.withAlpha(neon, HALO_MID_ALPHA),
                MarsGradients.withAlpha(neon, 0x00),
            ),
            floatArrayOf(0f, HALO_MID_STOP, 1f),
            Shader.TileMode.CLAMP,
        )
        // Тёплая пылевая атмосфера вокруг диска — ровно тот ореол, что у планеты на заставке.
        planetGlowPaint.shader = RadialGradient(
            centerX,
            centerY,
            planetR * 1.75f,
            intArrayOf(
                0x00EB4724,
                0x55EB4724,
                0x2AF55C2E,
                0x00F55C2E,
            ),
            floatArrayOf(0f, 0.62f, 0.80f, 1f),
            Shader.TileMode.CLAMP,
        )
        // Освещённый рельеф: свет смещён влево-вверх, к терминатору уходит почти в чёрный.
        surfacePaint.shader = RadialGradient(
            centerX - planetR * LIGHT_DX,
            centerY - planetR * LIGHT_DY,
            planetR * 1.62f,
            intArrayOf(
                MarsSpaceShaderPolicy.COLOR_SURFACE_LIT,
                MarsSpaceShaderPolicy.COLOR_SURFACE_DARK,
                SURFACE_TERMINATOR,
            ),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
        // Пылевая буря: полупрозрачная взвесь над освещённой частью полушария.
        dustPaint.shader = RadialGradient(
            centerX - planetR * 0.30f,
            centerY + planetR * 0.18f,
            planetR * 0.95f,
            intArrayOf(0x55864214, 0x00000000),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    /** Кисти под новый тон. Вызывается на смену фазы, не на кадр. */
    private fun applyTone() {
        neon = neonFor(tone)
        wavePaint.color = neon
        orbitPaint.color = neon
        sparkPaint.color = neon
        rimPaint.color = neon
        crescentPaint.color = neon
        // Шапка не зависит от фазы: это лёд на полюсе Марса, а не индикатор состояния.
        capPaint.color = CAP_TINT
        buildShaders()
    }

    /**
     * Неон фазы.
     *
     * Это не палитра темы, а именно неоновые акценты короны: у [MarsTheme] нет ролей «рубиновый
     * неон» и «аквамариновая плазма», а выводить их из `STATUS_*` значило бы получить грязные
     * полутона. Базовый Марс при этом остаётся из [MarsSpaceShaderPolicy].
     */
    private fun neonFor(tone: VoiceModeTone): Int = when (tone) {
        VoiceModeTone.IDLE -> NEON_IDLE
        VoiceModeTone.LISTENING -> NEON_LISTENING
        VoiceModeTone.THINKING -> NEON_THINKING
        VoiceModeTone.SPEAKING -> NEON_SPEAKING
    }

    // --- кадр ---------------------------------------------------------------------------------

    /**
     * Кадр орба.
     *
     * Ни одной аллокации: только чтения полей, арифметика, `canvas.save/scale/rotate/clipPath` и
     * `drawCircle`/`drawArc`/`drawOval`. `save`/`restoreToCount` памяти не выделяют (это счётчик
     * во внутреннем стеке Canvas).
     */
    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f || planetR <= 0f || haloPaint.shader == null) return

        // Дыхание: альфа берётся из общей политики пульсации, а не из своей косинусной формулы, —
        // тогда период и «мягкость» у орба те же, что у остальных живых индикаторов приложения, и
        // выключенные анимации гасятся в одном месте.
        val glowAlpha = if (breathing) {
            MarsPulsePolicy.alphaAt(phase)
        } else {
            MarsPulsePolicy.MIN_ALPHA +
                (MarsPulsePolicy.MAX_ALPHA - MarsPulsePolicy.MIN_ALPHA) * REST_BREATH
        }
        val breath = ((glowAlpha - MarsPulsePolicy.MIN_ALPHA) /
            (MarsPulsePolicy.MAX_ALPHA - MarsPulsePolicy.MIN_ALPHA)).coerceIn(0f, 1f)

        // Речь сильнее дыхания: громкое слово должно быть видно даже в «тихой» точке периода.
        val voice = (smoothedLevel * VOICE_WEIGHT + breath * BREATH_WEIGHT).coerceIn(0f, 1f)

        // Радиус короны «дышит» вместе с альфой: один источник правды на оба параметра, иначе
        // пятно рассинхронизировалось бы само с собой.
        val scale = MIN_SCALE + BREATH_SCALE * breath + LEVEL_SCALE * smoothedLevel

        val saved = canvas.save()
        canvas.scale(scale, scale, centerX, centerY)

        // Порядок слоёв: корона -> планета -> волны -> орбита -> лимб. Волны и орбита идут ПОСЛЕ
        // диска и его ореола: иначе рубиновое свечение планеты (55% альфы) замывало бы неоновые
        // кольца, и пульсация читалась бы как «пятно», а не как расходящиеся волны.
        drawHalo(canvas, glowAlpha, voice)
        drawPlanet(canvas, voice)
        drawWaveRings(canvas, voice)
        drawOrbit(canvas)
        drawLimb(canvas, voice)

        canvas.restoreToCount(saved)
    }

    /** Слой 1а: глубокая неоновая корона в тон фазы. */
    private fun drawHalo(canvas: Canvas, glowAlpha: Float, voice: Float) {
        haloPaint.alpha = ((glowAlpha * HALO_BASE_ALPHA + HALO_VOICE_ALPHA * voice).coerceIn(0f, 1f) * 255f)
            .toInt()
        canvas.drawCircle(centerX, centerY, radius, haloPaint)
    }

    /**
     * Слой 1б: три неоновых кольца, расходящихся от лимба наружу.
     *
     * Кольца сдвинуты по фазе на треть периода, поэтому в любой момент одно рождается у лимба,
     * второе в середине пути, третье гаснет на краю — получается непрерывная волна, а не три
     * синхронных вспышки. Чем громче речь, тем дальше и ярче уходит волна.
     */
    private fun drawWaveRings(canvas: Canvas, voice: Float) {
        val baseStroke = MarsTheme.dp(context, 1).toFloat().coerceAtLeast(1f)
        for (index in 0 until WAVE_RINGS) {
            val progress = (phase + index.toFloat() / WAVE_RINGS) % 1f
            // Яркость: рождается ярко у лимба, гаснет к краю короны.
            val fade = (1f - progress) * (1f - progress)
            val alpha = fade * (WAVE_BASE_ALPHA + WAVE_VOICE_ALPHA * voice)
            if (alpha <= 0.01f) continue
            val ringRadius = planetR * (WAVE_START + WAVE_SPAN * progress)
            if (ringRadius >= radius) continue
            wavePaint.strokeWidth = baseStroke * (WAVE_STROKE_MIN + WAVE_STROKE_SPAN * (1f - progress))
            wavePaint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawCircle(centerX, centerY, ringRadius, wavePaint)
        }
    }

    /**
     * Слой 4: наклонённая орбита (-24°) и бегущая по ней искра-спутник.
     *
     * Искра идёт по эллипсу быстрее дыхания и оставляет короткий хвост из трёх затухающих точек:
     * так орбита читается как движение, а не как декоративная линия. Наклон применяется
     * поворотом холста, поэтому и кольцо, и точки считаются в одной системе координат.
     */
    private fun drawOrbit(canvas: Canvas) {
        val saved = canvas.save()
        canvas.rotate(ORBIT_TILT_DEG, centerX, centerY)

        orbitPaint.strokeWidth = MarsTheme.dp(context, 1).toFloat().coerceAtLeast(1f)
        orbitPaint.alpha = (ORBIT_ALPHA * 255f).toInt()
        canvas.drawOval(orbitBounds, orbitPaint)

        val angle = rotation * ORBIT_SPEED
        val a = planetR * ORBIT_A
        val b = planetR * ORBIT_B
        // Хвост: три точки позади искры, альфа падает — направление движения видно без стрелок.
        for (tail in TAIL_STEPS downTo 1) {
            val tailAngle = angle - tail * TAIL_STEP_RAD
            sparkPaint.alpha = (SPARK_TAIL_ALPHA * (1f - tail / (TAIL_STEPS + 1f)) * 255f).toInt()
            canvas.drawCircle(
                centerX + a * cos(tailAngle),
                centerY + b * sin(tailAngle),
                planetR * SPARK_TAIL_RADIUS,
                sparkPaint,
            )
        }
        sparkPaint.alpha = (SPARK_ALPHA * 255f).toInt()
        canvas.drawCircle(
            centerX + a * cos(angle),
            centerY + b * sin(angle),
            planetR * SPARK_RADIUS,
            sparkPaint,
        )
        canvas.restoreToCount(saved)
    }

    /**
     * Слой 2 и 3: сам Марс.
     *
     * Порядок ровно как у заставки: ночная сторона -> клип диска -> освещённый рельеф ->
     * кратеры -> пылевая буря -> полярная шапка -> снятие клипа -> лимб. Кратеры проецируются
     * как на настоящей сфере: долгота растёт со временем, у лимба деталь сжимается по `cos`
     * долготы и гаснет — поэтому планета вращается, а не «слайдят наклейки».
     */
    private fun drawPlanet(canvas: Canvas, voice: Float) {
        // Тёплый ореол вокруг диска: он же отделяет планету от неоновой короны.
        planetGlowPaint.alpha = ((PLANET_GLOW_ALPHA + PLANET_GLOW_VOICE * voice).coerceIn(0f, 1f) * 255f)
            .toInt()
        canvas.drawCircle(centerX, centerY, planetR * 1.75f, planetGlowPaint)

        // Ночная сторона — база диска: терминатор не должен просвечивать фоном.
        canvas.drawCircle(centerX, centerY, planetR, nightPaint)

        val saved = canvas.save()
        planetClip.reset()
        planetClip.addCircle(centerX, centerY, planetR, Path.Direction.CW)
        canvas.clipPath(planetClip)

        surfacePaint.alpha = 255
        canvas.drawCircle(centerX, centerY, planetR, surfacePaint)

        drawCraters(canvas)

        dustPaint.alpha = (DUST_ALPHA * 255f).toInt()
        canvas.drawCircle(centerX, centerY, planetR, dustPaint)

        // Полярная шапка: сжатый эллипс у верхнего полюса, чуть повёрнутый вместе с планетой.
        capPaint.alpha = (CAP_ALPHA * 255f).toInt()
        canvas.drawOval(
            centerX - planetR * CAP_W,
            centerY - planetR * CAP_Y,
            centerX + planetR * CAP_W,
            centerY - planetR * CAP_Y + planetR * CAP_H,
            capPaint,
        )

        canvas.restoreToCount(saved)
    }

    /** Кратеры и пылевые пояса: предвычисленные координаты, проекция на сферу на кадре. */
    private fun drawCraters(canvas: Canvas) {
        craterPaint.color = CRATER_SHADE
        for (index in 0 until CRATER_COUNT) {
            val lat = CRATER_LAT[index]
            val lon = CRATER_LON[index] + rotation
            val facing = cos(lon)
            // Задняя полусфера не рисуется вовсе: иначе кратеры «проступали» бы сквозь диск.
            if (facing <= 0.12f) continue
            // Сферическая проекция: широта сжимается к полюсам, долгота — к лимбу.
            val cosLat = sqrt(1f - lat * lat)
            val x = centerX + sin(lon) * cosLat * planetR
            val y = centerY + lat * planetR
            val r = CRATER_SIZE[index] * planetR * (0.35f + 0.65f * facing)
            // Деталь у лимба почти не видна: там свет скользит по поверхности.
            val alpha = (facing * facing) * CRATER_ALPHA
            if (alpha <= 0.01f) continue
            craterPaint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawCircle(x, y, r, craterPaint)
        }
    }

    /**
     * Слой 3: неоновый лимб.
     *
     * Тонкое кольцо атмосферы по всему диску + огненный серп со стороны света. Толщина и яркость
     * обеих частей дышат с речью: на громком слове кромка вспыхивает, в тишине остаётся тлеющей.
     */
    private fun drawLimb(canvas: Canvas, voice: Float) {
        val baseStroke = MarsTheme.dp(context, 1).toFloat().coerceAtLeast(1f)
        rimPaint.strokeWidth = baseStroke * (RIM_STROKE + RIM_STROKE_VOICE * voice)
        rimPaint.alpha = ((RIM_ALPHA + RIM_VOICE_ALPHA * voice).coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawCircle(centerX, centerY, planetR * RIM_RATIO, rimPaint)

        crescentPaint.strokeWidth = baseStroke * (CRESCENT_STROKE + CRESCENT_STROKE_VOICE * voice)
        crescentPaint.alpha = ((CRESCENT_ALPHA + CRESCENT_VOICE_ALPHA * voice).coerceIn(0f, 1f) * 255f)
            .toInt()
        // Тот же сектор, что у заставки: 148°..260° — левый верхний лимб, где стоит свет.
        canvas.drawArc(arcBounds, CRESCENT_START, CRESCENT_SWEEP, false, crescentPaint)
    }

    // --- жизненный цикл дыхания ---------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Вьюха могла быть прикреплена уже после render(): здесь догоняем состояние. Здесь же
        // перечитываем системные флаги: человек мог вернуться на экран после того, как выключил
        // анимации в настройках.
        motionAllowed = pulses && animationAllowed()
        syncBreathing()
    }

    override fun onDetachedFromWindow() {
        // Сначала гасим цикл, потом отдаём вьюху системе: иначе следующий кадр пришёл бы на
        // отсоединённую вьюху и держал её (и Activity) до своего конца.
        stopBreathing()
        super.onDetachedFromWindow()
    }

    /**
     * Согласовать цикл кадров с желанием вызывающего и системными флагами.
     *
     * Единственное место, где решается «дышать или стоять», поэтому здесь же снимаются кадры при
     * выключенных анимациях — иначе решение пришлось бы дублировать в [render] и в
     * [onAttachedToWindow].
     */
    private fun syncBreathing() {
        if (!motionAllowed) {
            stopBreathing()
            // Статичный кадр — не «пустой»: орб остаётся видимым, просто не дышит. Фаза
            // сбрасывается в опорную точку, чтобы картинка не зависела от того, на каком кадре
            // человек выключил анимации.
            phase = REST_PHASE
            smoothedLevel = level
            invalidate()
            return
        }
        if (isAttachedToWindow) startBreathing() else stopBreathing()
    }

    private fun startBreathing() {
        if (breathing) return
        breathing = true
        originNanos = 0L
        lastFrameNanos = 0L
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
     * Условия не пишутся заново: их набор и порядок — [MarsPulsePolicy.modeFor], та же политика,
     * что гасит пульсацию статус-точек. Своя проверка «только ANIMATOR_DURATION_SCALE == 0»
     * пропустила бы выключенные аниматоры и режим экономии батареи, и орб дышал бы у человека,
     * который просил покой.
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
         * `val`, а не `const`: `PERIOD_ONLINE_MS` — чужая константа, и приведение `toLong()` в
         * инициализаторе `const` не допускается компилятором.
         */
        val PERIOD_MS: Long = MarsPulsePolicy.PERIOD_ONLINE_MS.toLong()

        /** Опорная фаза статичного кадра: середина подъёма — орб выглядит «живым», но стоит. */
        const val REST_PHASE = 0.32f

        /** Та же точка в долях амплитуды — для ветки без цикла кадров. */
        const val REST_BREATH = 0.32f

        /** Корона занимает 96% меньшей стороны: габарит вьюхи — это «воздух» вокруг света. */
        const val HALO_FILL = 0.96f

        /** Радиус диска планеты от радиуса короны: 46% — планета, а не «шарик в рамке». */
        const val PLANET_RATIO = 0.46f

        const val TWO_PI = 6.2831855f

        /** Вращение планеты: полный оборот примерно за 20 секунд — «живая», но не юла. */
        const val ROTATION_RAD_PER_SEC = 0.32f

        // --- неон фаз -----------------------------------------------------------------------

        /** Покой: благородный тёмно-рубиновый неон Марса. */
        val NEON_IDLE = 0xFFE11D48.toInt()

        /** Слушаю: яркий рубиново-алый — микрофон открыт, человек говорит. */
        val NEON_LISTENING = 0xFFFF2E54.toInt()

        /** Думаю: золотисто-янтарная корона. */
        val NEON_THINKING = 0xFFF59E0B.toInt()

        /** Говорю: неоново-лазурная плазма вокруг рубинового Марса. */
        val NEON_SPEAKING = 0xFF38BDF8.toInt()

        /** Терминатор рельефа: почти чёрный с багровым подтоном (как `0xFF1E0604` в макете). */
        val SURFACE_TERMINATOR = 0xFF1E0604.toInt()

        /** Тень кратера: тот же тёплый графит, что у заставки, но плотнее — диск мелкий. */
        val CRATER_SHADE = 0xFF200A06.toInt()

        /** Полярная шапка: холодная белая вуаль. */
        val CAP_TINT = 0xFFFFE6D8.toInt()

        // --- корона -------------------------------------------------------------------------

        const val HALO_BASE_ALPHA = 0.55f
        const val HALO_VOICE_ALPHA = 0.40f
        const val HALO_MID_ALPHA = 0x66
        const val HALO_MID_STOP = 0.55f

        /** Вес речи и дыхания в общей «живости» кадра. */
        const val VOICE_WEIGHT = 0.72f
        const val BREATH_WEIGHT = 0.28f

        /** Нижняя граница и размах масштаба дыхания. */
        const val MIN_SCALE = 0.94f
        const val BREATH_SCALE = 0.06f

        /** Доля радиуса, которую добавляет полная громкость: заметно, но не «прыжок». */
        const val LEVEL_SCALE = 0.16f

        /** Атака/спад сглаживания уровня за кадр. */
        const val LEVEL_ATTACK = 0.45f
        const val LEVEL_RELEASE = 0.12f

        // --- волновые кольца -----------------------------------------------------------------

        const val WAVE_RINGS = 3

        /** Кольцо рождается на 1.10 радиуса планеты и уходит до края короны. */
        const val WAVE_START = 1.10f
        const val WAVE_SPAN = 0.85f

        const val WAVE_BASE_ALPHA = 0.16f
        const val WAVE_VOICE_ALPHA = 0.46f

        const val WAVE_STROKE_MIN = 0.6f
        const val WAVE_STROKE_SPAN = 1.6f

        // --- орбита -------------------------------------------------------------------------

        const val ORBIT_TILT_DEG = -24f
        const val ORBIT_A = 1.58f
        const val ORBIT_B = 0.54f
        const val ORBIT_ALPHA = 0.30f
        const val ORBIT_SPEED = 1.7f

        const val SPARK_RADIUS = 0.045f
        const val SPARK_ALPHA = 0.95f
        const val SPARK_TAIL_RADIUS = 0.030f
        const val SPARK_TAIL_ALPHA = 0.55f
        const val TAIL_STEPS = 3
        const val TAIL_STEP_RAD = 0.09f

        // --- планета ------------------------------------------------------------------------

        /** Смещение источника света в долях радиуса планеты. */
        const val LIGHT_DX = 0.38f
        const val LIGHT_DY = 0.42f

        const val PLANET_GLOW_ALPHA = 0.55f
        const val PLANET_GLOW_VOICE = 0.35f

        const val CRATER_COUNT = 12
        const val CRATER_ALPHA = 0.55f

        const val DUST_ALPHA = 0.85f

        const val CAP_W = 0.34f
        const val CAP_Y = 0.92f
        const val CAP_H = 0.16f
        const val CAP_ALPHA = 0.18f

        // --- лимб ---------------------------------------------------------------------------

        const val RIM_RATIO = 0.995f
        const val RIM_STROKE = 0.9f
        const val RIM_STROKE_VOICE = 1.4f
        const val RIM_ALPHA = 0.55f
        const val RIM_VOICE_ALPHA = 0.40f

        const val CRESCENT_START = 148f
        const val CRESCENT_SWEEP = 112f
        const val CRESCENT_STROKE = 2.4f
        const val CRESCENT_STROKE_VOICE = 2.0f
        const val CRESCENT_ALPHA = 0.75f
        const val CRESCENT_VOICE_ALPHA = 0.25f

        /**
         * Детерминированные координаты кратеров: широта (-0.9..0.9), долгота (0..2π) и размер.
         *
         * Предвычислены один раз на процесс: генерировать их в `onDraw` значило бы аллоцировать
         * на каждом кадре (см. KDoc класса). Тот же линейный конгруэнтный генератор, что у
         * заставки, — рисунок совпадает с планетой на фоне.
         */
        val CRATER_LAT = FloatArray(CRATER_COUNT)
        val CRATER_LON = FloatArray(CRATER_COUNT)
        val CRATER_SIZE = FloatArray(CRATER_COUNT)

        init {
            var seed = 0x1A2B3C4DL
            for (index in 0 until CRATER_COUNT) {
                seed = seed * 1664525L + 1013904223L
                CRATER_LAT[index] = (((seed ushr 8) and 0xFFFFL).toFloat() / 65535f - 0.5f) * 1.8f
                seed = seed * 1664525L + 1013904223L
                CRATER_LON[index] = ((seed ushr 8) and 0xFFFFL).toFloat() / 65535f * TWO_PI
                seed = seed * 1664525L + 1013904223L
                CRATER_SIZE[index] = 0.045f +
                    ((seed ushr 8) and 0xFFFFL).toFloat() / 65535f * 0.10f
            }
        }
    }
}
