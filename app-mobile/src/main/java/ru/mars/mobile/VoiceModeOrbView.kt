package ru.mars.mobile

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
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

/**
 * Орб полноэкранного голосового режима: настоящая планета Марс из космической заставки,
 * окружённая живой атмосферой, которая дышит в такт речи.
 *
 * ## Почему текстура, а не рисунок
 * До этой волны Марс здесь рисовался вручную: двенадцать плоских кружков-кратеров, белый овал
 * «полярной шапки», пунктирное колечко-орбита. На экране получалась игрушка — потому что
 * нарисовать фотореалистичную планету кодом нельзя, а притворяться, что можно, хуже, чем не
 * рисовать вовсе. Теперь в центре орба лежит **тот же шар Марса, что на заставке приложения**
 * (`mars_bg.webp` → `R.drawable.mars_orb_planet`): настоящие каньоны, настоящее альбедо,
 * настоящий терминатор. Текстура вырезана из кадра заставки по измеренному лимбу (центр
 * `(540.9, 1200.9)`, радиус 425.2 px, невязка окружности 0.42 px) и сохранена как RGBA WebP
 * 768×768 с мягкой растушёвкой альфы — поэтому у диска нет ни чёрных углов, ни жёсткой кромки.
 *
 * ## Что добавлено поверх текстуры
 * Статичная фотография не отвечает на вопрос «слышит ли он меня». Поэтому планета обёрнута в
 * атмосферу, повторяющую слои шейдера [MarsSpaceBackgroundView] и собранную из тех же красок
 * [MarsSpaceShaderPolicy]:
 * 1. **Внешняя корона** — широкое радиальное гало, смещённое к источнику света (свет на
 *    текстуре стоит слева-сверху), поэтому ореол не «наклеен по центру», а освещён неравномерно.
 * 2. **Внутренняя горячая корона** — узкое кольцо у лимба: два слоя дают глубину, которой не
 *    бывает у одного градиента.
 * 3. **Атмосферные акустические волны** — мягкие широкие полосы, расходящиеся от лимба наружу;
 *    живут только в фазах, где человек говорит или говорит Марс ([VoiceModeTone.LISTENING],
 *    [VoiceModeTone.SPEAKING]), и разгораются от [smoothedLevel].
 * 4. **Сам диск** — текстура через [BitmapShader] с медленным осевым дрейфом и «дыханием»
 *    масштаба, затемнение лимба (limb darkening — реальный эффект сферической атмосферы) и
 *    слабая подсветка ночной стороны, чтобы тёмное полушарие читалось как шар, а не как вырез.
 * 5. **Серп света** — три дуги разной толщины и яркости по освещённому лимбу (широкая тусклая =
 *    рассеяние, узкая яркая = кромка атмосферы) плюс тонкая холодная кромка на ночной стороне.
 *
 * Цвет и энергия слоёв — по фазе: `IDLE` благородный тёмно-рубиновый, `LISTENING` живой алый,
 * `THINKING` тёплый янтарно-солнечный, `SPEAKING` плазменно-голубой. Сама планета при этом
 * остаётся Марсом: фазу несёт атмосфера, а не перекраска поверхности.
 *
 * ## Почему в onDraw нет ни одной аллокации
 * Это экран разговора: он перерисовывается 60 раз в секунду, пока идёт реплика, и живёт на
 * телефоне вместе с распознаванием и синтезом. Кадр, создающий `Paint`, `RadialGradient`,
 * `RectF` или `Matrix`, даёт мусор в молодом поколении → GC-паузы → подёргивание орба ровно в
 * тот момент, когда человек ждёт реакции. Поэтому:
 * - все кисти, `Matrix`, `RectF` дуг и `BitmapShader` — поля класса, созданные один раз;
 * - градиенты пересобираются только в [onSizeChanged]/при смене тона ([buildShaders]);
 * - текстура декодируется один раз ([loadPlanet]) и больше не трогается;
 * - дрейф текстуры — это `Matrix.setScale` + `postTranslate` по уже существующим объектам,
 *   дальше `BitmapShader.setLocalMatrix` (пересчёт матрицы, не аллокация);
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

    /** Внешняя корона: широкое гало, смещённое к источнику света. */
    private val haloOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Внутренняя горячая корона: узкое кольцо у самого лимба. */
    private val haloInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Атмосферное кольцо, обнимающее диск (полоса градиента, а не штрих круга). */
    private val coronaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * Фон орба: глубокий обсидиановый космос и газовые карманы туманности — те же слои, что
     * считает шейдер [MarsSpaceBackgroundView], только радиальными градиентами. Нужны, чтобы
     * голосовой режим не висел «шариком в пустоте»: во время разговора этот фон пульсирует
     * неоном фазы вместе с короной.
     */
    private val voidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nebulaRubyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nebulaAmberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nebulaPlumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Неоновая вуаль фазы позади планеты: главный источник «пульсации в момент разговора». */
    private val neonVeilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Диск планеты: текстура из заставки через [BitmapShader]. */
    private val planetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // Билинейная фильтрация обязательна: текстура 768 px сжимается до ~2.2 радиуса диска,
        // и без неё лимб рассыпается на ступеньки.
        isFilterBitmap = true
        isDither = true
    }

    /** Limb darkening: затемнение к краю диска — то, что делает шар шаром. */
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Слабая подсветка ночной стороны: без неё тёмное полушарие выглядит вырезанным. */
    private val nightFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = NIGHT_FILL
    }

    /** Дымка атмосферы внутри освещённого лимба. */
    private val hazePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** Серп света по освещённому лимбу: три прохода разной толщины одним цветом фазы. */
    private val crescentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** Тонкая холодная кромка на ночной стороне: атмосфера, ловящая скользящий свет. */
    private val nightRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = NIGHT_RIM
    }

    /** Атмосферные акустические волны: широкие мягкие полосы, цвет — неон фазы. */
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** Резерв, если текстура почему-то не декодировалась: градиентный шар вместо пустоты. */
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // --- текстура планеты -------------------------------------------------------------------

    /** Шейдер текстуры (держит битмап заставки): матрица двигает её, не пересоздавая шейдер. */
    private var planetShader: BitmapShader? = null

    /** Матрица текстуры: тот же объект на каждом кадре (`setScale` + `postTranslate`). */
    private val planetMatrix = Matrix()

    // --- геометрия ---------------------------------------------------------------------------

    /** Габариты дуг лимба и дымки: те же `RectF` на каждом кадре. */
    private val crescentBounds = RectF()
    private val hazeBounds = RectF()
    private val rimBounds = RectF()

    private var centerX = 0f
    private var centerY = 0f

    /** Радиус короны в пикселях: 98% меньшей стороны — габарит вьюхи это «воздух» вокруг света. */
    private var radius = 0f

    /** Радиус самого диска планеты: 49% короны. */
    private var planetR = 0f

    /**
     * Радиус внешней короны в пикселях.
     *
     * Привязан к меньшей стороне вьюхи, а не к планете: гало обязано погаснуть ВНУТРИ вьюхи,
     * иначе край вьюхи обрежет его на ненулевой альфе и вокруг орба появится прямоугольник.
     */
    private var haloR = 0f

    /** Толщина штрихов в пикселях: `dp(1)`, посчитанная один раз. */
    private var baseStroke = 1f

    // --- состояние ---------------------------------------------------------------------------

    /** Текущая роль цвета. Меняется только через [render]. */
    private var tone: VoiceModeTone = VoiceModeTone.IDLE

    /** Неон текущей фазы (корона, волны, лимб). */
    private var neon = NEON_IDLE

    /** Энергия слоёв под текущую фазу: задаётся в [applyTone], читается в кадре. */
    private var haloEnergy = 0.40f
    private var coronaEnergy = 0.38f
    private var limbEnergy = 0.50f
    private var waveEnergy = 0f

    /** Просили ли движение. Само движение дополнительно зависит от системных флагов. */
    private var pulses = false

    /** Целевая амплитуда речи 0..1 из контроллера. */
    private var level = 0f

    /** Сглаженная амплитуда: атака быстрая, спад плавный — иначе речь читается как вспышки. */
    private var smoothedLevel = 0f

    /** Фаза дыхания 0..1 внутри периода; в статичном режиме — [REST_PHASE]. */
    private var phase = REST_PHASE

    /** Собственный угол дрейфа планеты в радианах: накапливается по времени кадров. */
    private var drift = 0f

    /** Время предыдущего кадра: нужно только для приращения дрейфа. */
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
     * пропущенных кадрах и после долгой паузы даёт рывок. Дрейф текстуры, наоборот, копится по
     * `dt` — ему нужна непрерывность, а не привязка к периоду дыхания.
     */
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!breathing) return
            if (originNanos == 0L) originNanos = frameTimeNanos
            if (lastFrameNanos == 0L) lastFrameNanos = frameTimeNanos
            val elapsedMs = (frameTimeNanos - originNanos) / 1_000_000L
            phase = (elapsedMs % PERIOD_MS).toFloat() / PERIOD_MS.toFloat()

            // Приращение дрейфа: зажато сверху, чтобы после паузы (или подлагивания) планета
            // не прыгала на пол-оборота за один кадр.
            val dtSec = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastFrameNanos = frameTimeNanos
            drift += dtSec * DRIFT_RAD_PER_SEC
            if (drift > TWO_PI) drift -= TWO_PI

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
        baseStroke = MarsTheme.dp(context, 1).toFloat().coerceAtLeast(1f)
        loadPlanet()
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
     * Амплитуда речи 0..1: корона, волны и серп дышат в такт.
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

    // --- текстура ----------------------------------------------------------------------------

    /**
     * Декодировать шар Марса из заставки — один раз за жизнь вьюхи.
     *
     * Ошибка декодирования не должна ронять экран разговора: при `null` кадр рисует
     * [fallbackPaint] — градиентный шар той же палитры. Это хуже текстуры, но лучше, чем
     * пустое место там, где человек ждёт ответа.
     */
    private fun loadPlanet() {
        if (planetShader != null) return
        val options = BitmapFactory.Options().apply { inScaled = false }
        val bitmap = try {
            BitmapFactory.decodeResource(resources, R.drawable.mars_orb_planet, options)
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        if (bitmap == null) return
        planetShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also {
            planetPaint.shader = it
        }
    }

    // --- геометрия и цвет ---------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = (if (w < h) w else h) / 2f * HALO_FILL
        haloR = radius
        planetR = radius * PLANET_RATIO
        // Габариты дуг привязаны к центру и радиусу — считаются здесь, не на кадр.
        crescentBounds.set(
            centerX - planetR * CRESCENT_RATIO,
            centerY - planetR * CRESCENT_RATIO,
            centerX + planetR * CRESCENT_RATIO,
            centerY + planetR * CRESCENT_RATIO,
        )
        hazeBounds.set(
            centerX - planetR * HAZE_RATIO,
            centerY - planetR * HAZE_RATIO,
            centerX + planetR * HAZE_RATIO,
            centerY + planetR * HAZE_RATIO,
        )
        rimBounds.set(
            centerX - planetR * NIGHT_RIM_RATIO,
            centerY - planetR * NIGHT_RIM_RATIO,
            centerX + planetR * NIGHT_RIM_RATIO,
            centerY + planetR * NIGHT_RIM_RATIO,
        )
        // Градиенты привязаны к центру и радиусу, поэтому пересобираются здесь, а не на кадр.
        buildShaders()
    }

    /**
     * Пересобрать градиенты под текущий тон и радиус.
     *
     * Корона — не два стопа «цвет → прозрачный»: чистый линейный спад даёт видимую границу там,
     * где гаснет насыщенность, и ореол читается как размытый круг. Пять стопов держат цвет до
     * середины и мягко отпускают его к краю, поэтому свечение выглядит светом.
     *
     * Источник света на текстуре стоит слева-сверху, и корона смещена туда же: симметричное гало
     * вокруг неравномерно освещённой планеты сразу выдаёт «наклейку».
     */
    private fun buildShaders() {
        if (radius <= 0f || planetR <= 0f) {
            haloOuterPaint.shader = null
            haloInnerPaint.shader = null
            coronaPaint.shader = null
            voidPaint.shader = null
            nebulaRubyPaint.shader = null
            nebulaAmberPaint.shader = null
            nebulaPlumPaint.shader = null
            neonVeilPaint.shader = null
            shadePaint.shader = null
            fallbackPaint.shader = null
            return
        }
        val lightX = centerX - planetR * LIGHT_DX
        val lightY = centerY - planetR * LIGHT_DY

        // Фон: тот же обсидиан, что у заставки, — от почти чёрного верха к ржавому низу.
        voidPaint.shader = LinearGradient(
            centerX,
            centerY - haloR,
            centerX,
            centerY + haloR,
            intArrayOf(
                MarsSpaceShaderPolicy.COLOR_VOID_TOP,
                MarsGradients.withAlpha(MarsSpaceShaderPolicy.COLOR_VOID_BOTTOM, 0xE6),
                MarsGradients.withAlpha(MarsSpaceShaderPolicy.COLOR_VOID_BOTTOM, 0x00),
            ),
            floatArrayOf(0f, 0.72f, 1f),
            Shader.TileMode.CLAMP,
        )
        // Газовые карманы туманности: рубин, янтарь и слива — ровно палитра шейдера. Радиусы
        // подобраны так, чтобы каждый гас ДО края вьюхи (никакого квадрата вокруг орба).
        nebulaRubyPaint.shader = RadialGradient(
            centerX - haloR * 0.30f,
            centerY - haloR * 0.42f,
            haloR * 0.92f,
            intArrayOf(
                MarsGradients.withAlpha(MarsSpaceShaderPolicy.COLOR_NEBULA_RUBY, 0x5C),
                MarsGradients.withAlpha(MarsSpaceShaderPolicy.COLOR_NEBULA_RUBY, 0x2A),
                MarsGradients.withAlpha(MarsSpaceShaderPolicy.COLOR_NEBULA_RUBY, 0x00),
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP,
        )
        nebulaAmberPaint.shader = RadialGradient(
            centerX + haloR * 0.38f,
            centerY + haloR * 0.34f,
            haloR * 0.86f,
            intArrayOf(
                MarsGradients.withAlpha(MarsSpaceShaderPolicy.COLOR_NEBULA_AMBER, 0x4A),
                MarsGradients.withAlpha(MarsSpaceShaderPolicy.COLOR_NEBULA_AMBER, 0x1F),
                MarsGradients.withAlpha(MarsSpaceShaderPolicy.COLOR_NEBULA_AMBER, 0x00),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        nebulaPlumPaint.shader = RadialGradient(
            centerX + haloR * 0.10f,
            centerY + haloR * 0.58f,
            haloR * 0.80f,
            intArrayOf(
                MarsGradients.withAlpha(MarsSpaceShaderPolicy.COLOR_NEBULA_PLUM, 0x3D),
                MarsGradients.withAlpha(MarsSpaceShaderPolicy.COLOR_NEBULA_PLUM, 0x18),
                MarsGradients.withAlpha(MarsSpaceShaderPolicy.COLOR_NEBULA_PLUM, 0x00),
            ),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        )
        // Неоновая вуаль фазы: тот же цвет, что у короны, но разлитый по всему фону.
        neonVeilPaint.shader = RadialGradient(
            centerX - haloR * 0.08f,
            centerY - haloR * 0.10f,
            haloR * 1.02f,
            intArrayOf(
                MarsGradients.withAlpha(neon, 0x30),
                MarsGradients.withAlpha(neon, 0x1E),
                MarsGradients.withAlpha(neon, 0x08),
                MarsGradients.withAlpha(neon, 0x00),
            ),
            floatArrayOf(0f, 0.38f, 0.70f, 1f),
            Shader.TileMode.CLAMP,
        )
        haloOuterPaint.shader = RadialGradient(
            centerX - planetR * HALO_SHIFT_X,
            centerY - planetR * HALO_SHIFT_Y,
            haloR,
            intArrayOf(
                MarsGradients.withAlpha(neon, 0x4D),
                MarsGradients.withAlpha(neon, 0x40),
                MarsGradients.withAlpha(neon, 0x21),
                MarsGradients.withAlpha(neon, 0x0A),
                MarsGradients.withAlpha(neon, 0x00),
            ),
            floatArrayOf(0f, 0.28f, 0.55f, 0.80f, 1f),
            Shader.TileMode.CLAMP,
        )
        // Внутренняя корона: прозрачная в центре, чтобы не мылить диск, и плотная у лимба.
        haloInnerPaint.shader = RadialGradient(
            lightX,
            lightY,
            planetR * INNER_SPAN,
            intArrayOf(
                MarsGradients.withAlpha(neon, 0x00),
                MarsGradients.withAlpha(neon, 0x00),
                MarsGradients.withAlpha(neon, 0x3D),
                MarsGradients.withAlpha(neon, 0x1C),
                MarsGradients.withAlpha(neon, 0x00),
            ),
            floatArrayOf(0f, 0.50f, 0.60f, 0.78f, 1f),
            Shader.TileMode.CLAMP,
        )
        // Атмосферное кольцо: полоса на 78–100% радиуса — ровно над лимбом планеты.
        coronaPaint.shader = RadialGradient(
            centerX,
            centerY,
            planetR * CORONA_SPAN,
            intArrayOf(
                MarsGradients.withAlpha(neon, 0x00),
                MarsGradients.withAlpha(neon, 0x00),
                MarsGradients.withAlpha(neon, 0x66),
                MarsGradients.withAlpha(neon, 0x33),
                MarsGradients.withAlpha(neon, 0x00),
            ),
            floatArrayOf(0f, 0.78f, 0.88f, 0.95f, 1f),
            Shader.TileMode.CLAMP,
        )
        // Limb darkening: к краю диск темнеет — это и есть объём сферы.
        shadePaint.shader = RadialGradient(
            centerX,
            centerY,
            planetR,
            intArrayOf(0x00000000, 0x00000000, 0x57000000),
            floatArrayOf(0f, 0.60f, 1f),
            Shader.TileMode.CLAMP,
        )
        // Резервный шар: та же палитра заставки, если текстура не поднялась.
        fallbackPaint.shader = RadialGradient(
            lightX,
            lightY,
            planetR * 1.55f,
            intArrayOf(
                MarsSpaceShaderPolicy.COLOR_SURFACE_LIT,
                MarsSpaceShaderPolicy.COLOR_SURFACE_DARK,
                MarsSpaceShaderPolicy.COLOR_NIGHT_SIDE,
            ),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    /** Кисти и энергия слоёв под новый тон. Вызывается на смену фазы, не на кадр. */
    private fun applyTone() {
        neon = neonFor(tone)
        wavePaint.color = neon
        hazePaint.color = neon
        crescentPaint.color = neon
        // Планета остаётся Марсом во всех фазах: цвет несёт атмосфера, а не поверхность.
        val energy = energyFor(tone)
        haloEnergy = energy[0]
        coronaEnergy = energy[1]
        limbEnergy = energy[2]
        waveEnergy = energy[3]
        buildShaders()
    }

    /**
     * Неон фазы.
     *
     * Это не палитра темы, а именно неоновые акценты атмосферы: у [MarsTheme] нет ролей
     * «рубиновый неон» и «аквамариновая плазма», а выводить их из `STATUS_*` значило бы получить
     * грязные полутона. Базовый Марс при этом остаётся из [MarsSpaceShaderPolicy].
     *
     * Пара `IDLE`/`LISTENING` разведена намеренно: покой — глубокий рубин, слушание — живой алый
     * с большей энергией. Одинаковый оттенок в двух состояниях читался бы как «ничего не
     * изменилось».
     */
    private fun neonFor(tone: VoiceModeTone): Int = when (tone) {
        VoiceModeTone.IDLE -> NEON_IDLE
        VoiceModeTone.LISTENING -> NEON_LISTENING
        VoiceModeTone.THINKING -> NEON_THINKING
        VoiceModeTone.SPEAKING -> NEON_SPEAKING
    }

    /**
     * Энергия слоёв: `[гало, кольцо, лимб, волны]`.
     *
     * Покой — тлеющая корона и тишина. Слушание — разговор человека: волны есть, но мягче, чем
     * на речи Марса. Размышление — самая тёплая и плотная корона без волн (Марс молчит).
     * Речь — максимальный лимб и самые живые волны.
     */
    private fun energyFor(tone: VoiceModeTone): FloatArray = when (tone) {
        VoiceModeTone.IDLE -> ENERGY_IDLE
        VoiceModeTone.LISTENING -> ENERGY_LISTENING
        VoiceModeTone.THINKING -> ENERGY_THINKING
        VoiceModeTone.SPEAKING -> ENERGY_SPEAKING
    }

    // --- кадр ---------------------------------------------------------------------------------

    /**
     * Кадр орба.
     *
     * Ни одной аллокации: только чтения полей, арифметика, `canvas.save/scale` и
     * `drawCircle`/`drawArc`. `save`/`restoreToCount` памяти не выделяют (это счётчик во
     * внутреннем стеке Canvas).
     */
    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f || planetR <= 0f) return

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

        // Планета — фотография, поэтому масштаб «дышит» сдержанно: сильное дыхание радиуса
        // превратило бы реалистичный шар в резиновый мяч.
        val scale = MIN_SCALE + BREATH_SCALE * breath + LEVEL_SCALE * smoothedLevel

        val saved = canvas.save()
        canvas.scale(scale, scale, centerX, centerY)

        // Порядок слоёв: корона -> волны -> диск -> атмосфера лимба. Волны идут до диска, чтобы
        // не перекрывать его и не «просвечивать» сквозь ночную сторону.
        drawHalo(canvas, breath, voice)
        drawWaves(canvas, voice)
        drawPlanet(canvas)
        drawAtmosphere(canvas, breath, voice)

        canvas.restoreToCount(saved)
    }

    /** Слои 1–2: пульсирующий неоновый фон туманности (в стиле Квартала), внешняя корона и горячее кольцо у лимба. */
    private fun drawHalo(canvas: Canvas, breath: Float, voice: Float) {
        nebulaRubyPaint.alpha = alphaOf(0.55f + 0.35f * breath + 0.25f * voice)
        canvas.drawCircle(centerX, centerY, haloR, nebulaRubyPaint)
        nebulaAmberPaint.alpha = alphaOf(0.45f + 0.30f * breath + 0.25f * voice)
        canvas.drawCircle(centerX, centerY, haloR, nebulaAmberPaint)
        nebulaPlumPaint.alpha = alphaOf(0.50f + 0.25f * breath + 0.20f * voice)
        canvas.drawCircle(centerX, centerY, haloR, nebulaPlumPaint)
        neonVeilPaint.alpha = alphaOf(haloEnergy * (0.65f + 0.45f * breath) + 0.45f * voice)
        canvas.drawCircle(centerX, centerY, haloR, neonVeilPaint)

        haloOuterPaint.alpha = alphaOf(haloEnergy * (0.70f + 0.55f * breath) + HALO_VOICE * voice)
        canvas.drawCircle(centerX, centerY, haloR, haloOuterPaint)

        haloInnerPaint.alpha = alphaOf(haloEnergy * 0.90f + INNER_VOICE * voice)
        canvas.drawCircle(centerX, centerY, planetR * INNER_SPAN, haloInnerPaint)
    }

    /**
     * Слой 3: атмосферные акустические волны.
     *
     * Полосы сдвинуты по фазе на треть периода, поэтому в любой момент одна рождается у лимба,
     * вторая в середине пути, третья гаснет на краю — получается непрерывная волна, а не три
     * синхронных вспышки. Штрих широкий, а альфа низкая: узкое яркое кольцо читалось бы как
     * векторный «радар», широкая тусклая полоса — как рассеяние в атмосфере.
     */
    private fun drawWaves(canvas: Canvas, voice: Float) {
        if (waveEnergy <= 0f) return
        val loud = (WAVE_BASE_ALPHA + WAVE_VOICE_ALPHA * voice) * waveEnergy
        for (index in 0 until WAVE_RINGS) {
            val progress = (phase + index.toFloat() / WAVE_RINGS) % 1f
            // Яркость: рождается у лимба, гаснет к краю короны.
            val fade = (1f - progress) * (1f - progress)
            val alpha = fade * loud
            if (alpha <= 0.004f) continue
            val ringRadius = planetR * (WAVE_START + WAVE_SPAN * progress)
            if (ringRadius >= radius) continue
            wavePaint.strokeWidth = planetR * (WAVE_WIDTH_MIN + WAVE_WIDTH_SPAN * progress)
            wavePaint.alpha = alphaOf(alpha)
            canvas.drawCircle(centerX, centerY, ringRadius, wavePaint)
        }
    }

    /**
     * Слой 4: сам Марс.
     *
     * Текстура рисуется кругом радиуса [planetR], но её собственный диск нарисован крупнее
     * ([TEXTURE_OVERSCAN]): запас по краю нужен, чтобы медленный дрейф не вытащил на лимб
     * прозрачную кайму. Дальше — limb darkening и слабая подсветка ночной стороны: без них
     * тёмное полушарие сливается с космосом и шар читается как полумесяц-наклейка.
     */
    private fun drawPlanet(canvas: Canvas) {
        val shader = planetShader
        if (shader == null) {
            canvas.drawCircle(centerX, centerY, planetR, fallbackPaint)
        } else {
            // Медленный осевой дрейф: амплитуда в доли радиуса, период — десятки секунд.
            val dx = sin(drift) * planetR * DRIFT_X
            val dy = cos(drift * DRIFT_Y_RATIO) * planetR * DRIFT_Y
            // Масштаб текстуры: её диск (TEXTURE_DISC_PX из TEXTURE_SIZE_PX) должен лечь на
            // радиус planetR * TEXTURE_OVERSCAN.
            val k = planetR * TEXTURE_OVERSCAN / TEXTURE_DISC_PX
            planetMatrix.setScale(k, k)
            planetMatrix.postTranslate(
                centerX + dx - TEXTURE_SIZE_PX * 0.5f * k,
                centerY + dy - TEXTURE_SIZE_PX * 0.5f * k,
            )
            shader.setLocalMatrix(planetMatrix)
            canvas.drawCircle(centerX, centerY, planetR, planetPaint)
        }

        // Объём: затемнение к краю диска (реальное limb darkening атмосферы Марса).
        canvas.drawCircle(centerX, centerY, planetR, shadePaint)
        // Ночная сторона не должна быть абсолютно чёрной: слабый тёплый подсвет.
        canvas.drawCircle(centerX, centerY, planetR, nightFillPaint)
    }

    /**
     * Слой 5: атмосфера лимба.
     *
     * Четыре прохода снизу вверх: кольцо-корона, дымка внутри освещённого лимба, серп света
     * (широкий тусклый -> узкий яркий) и холодная кромка на ночной стороне. Толщина и яркость
     * дышат вместе с речью: на громком слове кромка вспыхивает, в тишине остаётся тлеющей.
     */
    private fun drawAtmosphere(canvas: Canvas, breath: Float, voice: Float) {
        coronaPaint.alpha = alphaOf(coronaEnergy * (0.85f + 0.25f * breath) + CORONA_VOICE * voice)
        canvas.drawCircle(centerX, centerY, planetR * CORONA_SPAN, coronaPaint)

        hazePaint.strokeWidth = planetR * HAZE_WIDTH
        hazePaint.alpha = alphaOf(limbEnergy * HAZE_BASE + HAZE_VOICE * voice)
        canvas.drawArc(hazeBounds, LIGHT_CENTER - HAZE_SWEEP * 0.5f, HAZE_SWEEP, false, hazePaint)

        // Серп: три дуги одного цвета. Широкая задаёт рассеяние, узкая — саму кромку.
        val crescent = (CRESCENT_BASE + CRESCENT_VOICE * voice) * (0.55f + 0.45f * limbEnergy)
        for (pass in 0 until CRESCENT_PASSES) {
            val sweep = CRESCENT_SWEEP[pass]
            crescentPaint.strokeWidth = planetR * CRESCENT_WIDTH[pass] + baseStroke * 0.5f
            crescentPaint.alpha = alphaOf(crescent * CRESCENT_ALPHA[pass])
            canvas.drawArc(
                crescentBounds,
                LIGHT_CENTER - sweep * 0.5f,
                sweep,
                false,
                crescentPaint,
            )
        }

        // Ночная кромка: атмосфера ловит скользящий свет и на тёмной стороне.
        nightRimPaint.strokeWidth = baseStroke * NIGHT_RIM_STROKE
        nightRimPaint.alpha = alphaOf(NIGHT_RIM_ALPHA + NIGHT_RIM_VOICE * voice)
        canvas.drawArc(rimBounds, 0f, 360f, false, nightRimPaint)
    }

    /** Альфа 0..1 в байты канала без риска выйти за границу: `Paint.alpha` ждёт 0..255. */
    private fun alphaOf(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).toInt()

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

        /** Корона занимает 98% меньшей стороны: габарит вьюхи — это «воздух» вокруг света. */
        const val HALO_FILL = 0.98f

        /** Радиус диска планеты от радиуса короны: 49% — планета, а не «шарик в рамке». */
        const val PLANET_RATIO = 0.49f

        const val TWO_PI = 6.2831855f

        /**
         * Дрейф текстуры: полный круг синуса примерно за 40 секунд.
         *
         * Это не вращение планеты (у фото-текстуры нет второй половины, и «прокрутка» выдала бы
         * шов), а медленное покачивание оси: амплитуда — доли радиуса, поэтому поверхность
         * кажется живой, но остаётся той же поверхностью.
         */
        const val DRIFT_RAD_PER_SEC = 0.16f
        const val DRIFT_X = 0.014f
        const val DRIFT_Y = 0.010f
        const val DRIFT_Y_RATIO = 0.73f

        // --- текстура -----------------------------------------------------------------------

        /** Размер исходного битмапа `mars_orb_planet`. */
        const val TEXTURE_SIZE_PX = 768f

        /** Радиус диска планеты внутри этого битмапа (кроп с растушёвкой). */
        const val TEXTURE_DISC_PX = 360f

        /**
         * Насколько диск текстуры больше диска орба.
         *
         * 4.5% запаса: столько нужно, чтобы дрейф не вытащил на лимб прозрачную кайму, и при
         * этом лимб орба остался «своим» — его рисует атмосфера, а не край картинки.
         */
        const val TEXTURE_OVERSCAN = 1.045f

        // --- неон фаз -----------------------------------------------------------------------

        /** Покой: благородный тёмно-рубиновый Марс. */
        val NEON_IDLE = 0xFFC41E3A.toInt()

        /** Слушаю: живой алый — микрофон открыт, человек говорит. */
        val NEON_LISTENING = 0xFFFF3D4F.toInt()

        /** Думаю: золотисто-янтарная солнечная корона. */
        val NEON_THINKING = 0xFFFFC24D.toInt()

        /** Говорю: неоново-лазурная плазма вокруг рубинового Марса. */
        val NEON_SPEAKING = 0xFF4CC8FF.toInt()

        /** Слабый тёплый подсвет ночной стороны (альфа уже в цвете). */
        val NIGHT_FILL = 0x143C1814

        /** Холодная кромка ночного лимба. */
        val NIGHT_RIM = 0xFF8C5A5A.toInt()

        // --- энергия фаз: [гало, кольцо, лимб, волны] ---------------------------------------

        val ENERGY_IDLE = floatArrayOf(0.40f, 0.38f, 0.50f, 0.00f)
        val ENERGY_LISTENING = floatArrayOf(0.66f, 0.60f, 0.78f, 0.50f)
        val ENERGY_THINKING = floatArrayOf(0.70f, 0.62f, 0.68f, 0.00f)
        val ENERGY_SPEAKING = floatArrayOf(0.58f, 0.78f, 0.98f, 0.80f)

        // --- корона -------------------------------------------------------------------------

        /** Внешнее гало: 2.45 радиуса планеты — «воздух» вокруг света. */
        const val HALO_SPAN = 2.45f

        /** Внутренняя горячая корона: узкое кольцо у лимба. */
        const val INNER_SPAN = 1.55f

        /** Смещение гало к источнику света (слева-сверху). */
        const val HALO_SHIFT_X = 0.14f
        const val HALO_SHIFT_Y = 0.18f

        /** Смещение источника света для внутренней короны. */
        const val LIGHT_DX = 0.06f
        const val LIGHT_DY = 0.08f

        const val HALO_VOICE = 0.20f
        const val INNER_VOICE = 0.22f
        const val CORONA_VOICE = 0.26f

        /** Вес речи и дыхания в общей «живости» кадра. */
        const val VOICE_WEIGHT = 0.72f
        const val BREATH_WEIGHT = 0.28f

        /** Нижняя граница и размах масштаба дыхания. */
        const val MIN_SCALE = 0.985f
        const val BREATH_SCALE = 0.020f

        /** Доля радиуса, которую добавляет полная громкость: заметно, но не «прыжок». */
        const val LEVEL_SCALE = 0.045f

        /** Атака/спад сглаживания уровня за кадр. */
        const val LEVEL_ATTACK = 0.45f
        const val LEVEL_RELEASE = 0.12f

        // --- волны --------------------------------------------------------------------------

        const val WAVE_RINGS = 3

        /** Волна рождается на 1.04 радиуса планеты и уходит к краю короны. */
        const val WAVE_START = 1.04f
        const val WAVE_SPAN = 0.95f

        const val WAVE_BASE_ALPHA = 0.30f
        const val WAVE_VOICE_ALPHA = 0.42f

        /** Ширина полосы в долях радиуса: широкая и мягкая, а не векторное кольцо. */
        const val WAVE_WIDTH_MIN = 0.10f
        const val WAVE_WIDTH_SPAN = 0.16f

        // --- лимб ---------------------------------------------------------------------------

        /** Кольцо-корона: полоса градиента до 1.18 радиуса планеты. */
        const val CORONA_SPAN = 1.18f

        /** Дымка внутри освещённого лимба. */
        const val HAZE_RATIO = 0.985f
        const val HAZE_WIDTH = 0.10f
        const val HAZE_BASE = 0.14f
        const val HAZE_VOICE = 0.10f
        const val HAZE_SWEEP = 150f

        /** Серп: три прохода — широкая тусклая, средняя, узкая яркая. */
        const val CRESCENT_RATIO = 1.004f
        const val CRESCENT_PASSES = 3
        val CRESCENT_WIDTH = floatArrayOf(0.130f, 0.055f, 0.018f)
        val CRESCENT_ALPHA = floatArrayOf(0.09f, 0.22f, 0.72f)
        val CRESCENT_SWEEP = floatArrayOf(152f, 112f, 72f)
        const val CRESCENT_BASE = 0.42f
        const val CRESCENT_VOICE = 0.30f

        /**
         * Центр освещённого лимба в градусах Canvas.
         *
         * Свет на текстуре падает слева-сверху, поэтому серп живёт на левом лимбе: 198° — это
         * левый край с уклоном вниз (0° — три часа, 90° — шесть, 270° — двенадцать).
         */
        const val LIGHT_CENTER = 198f

        /** Холодная кромка ночной стороны. */
        const val NIGHT_RIM_RATIO = 1.002f
        const val NIGHT_RIM_STROKE = 0.9f
        const val NIGHT_RIM_ALPHA = 0.10f
        const val NIGHT_RIM_VOICE = 0.06f
    }
}
