package ru.mars.mobile

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
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
import kotlin.math.abs
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
 * 1. **Внешняя корона** — широкое радиальное гало, строго концентричное диску. Раньше оно было
 *    сдвинуто влево-вверх «к источнику света», и вместе с тёмной правой-нижней стороной текстуры
 *    это давало сильную оптическую иллюзию, что шар съехал из центра — теперь свет и сфера делят
 *    один центр, а направление света несут серп и дымка, а не смещённое гало.
 * 2. **Внутренняя горячая корона** — узкое кольцо у лимба: два слоя дают глубину, которой не
 *    бывает у одного градиента.
 * 3. **Атмосферные акустические волны** — мягкие широкие полосы, расходящиеся от лимба наружу;
 *    живут только в фазах, где человек говорит или говорит Марс ([VoiceModeTone.LISTENING],
 *    [VoiceModeTone.SPEAKING]), и разгораются от [smoothedLevel].
 * 4. **Сам диск** — текстура через [BitmapShader] с медленным осевым дрейфом и «дыханием»
 *    масштаба, затемнение лимба (limb darkening — реальный эффект сферической атмосферы) и
 *    слабая подсветка ночной стороны, чтобы тёмное полушарие читалось как шар, а не как вырез.
 * 5. **Серп света** — три дуги разной толщины и яркости по освещённому лимбу (широкая тусклая =
 *    рассеяние, узкая яркая = кромка атмосферы).
 * 6. **Fresnel-контур 360°** — неоновое свечение лимба и тонкая линия по всему периметру: ночная
 *    сторона текстуры почти чёрная (`RGB ≈ 4, 2, 2`), и без собственного света правый-нижний край
 *    шара сливался с космосом, из-за чего планета читалась подрезанной, а не круглой.
 *
 * ## Чего здесь больше нет
 * Своих «туманностей»: четыре плоских `RadialGradient`-круга (рубин, янтарь, слива, неоновая
 * вуаль) рисовались внутри квадрата вьюхи поверх глухого серого фона — на экране это выглядело
 * наляпанными поверх чата цветными пятнами. Настоящий глубокий космос, FBM-туманность, звёзды,
 * кометы и рэлеевская корона считает шейдер [MarsSpaceBackgroundView], который лежит первым
 * слоем всего экрана разговора и пульсирует неоном фазы в такт голосу.
 *
 * Цвет и энергия слоёв — по фазе, но в одной тёплой гамме Марса: `IDLE` глубокий тёмно-рубиновый,
 * `LISTENING` мягкий кораллово-рубиновый, `THINKING` янтарно-терракотовый, `SPEAKING` тёплый
 * персиково-золотистый. Никакого холодного голубого в речи: раньше `SPEAKING` был кислотно-лазурным
 * и переход с рубинового на него бил по глазам. Сама планета при этом остаётся Марсом: фазу несёт
 * атмосфера, а не перекраска поверхности.
 *
 * ## Почему переход фазы занимает время
 * Раньше смена тона была одним присваиванием: `neon = neonFor(tone)` и `buildShaders()` за 0 мс, то
 * есть вспышкой в один кадр. Теперь у цвета и энергий есть ЦЕЛЬ ([targetNeon], [targetHaloEnergy],
 * …), а текущие значения догоняют её экспоненциально с постоянной [VoiceNeonPalette.TRANSITION_TAU_SEC]
 * — свет перетекает за ~1.2 с, как остывающая лампа, и цикл кадров не останавливается, пока переход
 * не сошётся. Мгновенно применяется только то, что не может быть плавным: выключенные системой
 * анимации и самый первый кадр, когда предыдущего цвета ещё нет.
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
 * явный [Choreographer.FrameCallback]: он ставится только пока вьюха прикреплена и фаза требует
 * движения ЛИБО пока не сошёлся переход цвета/энергий, и снимается в [stopBreathing], которую
 * вызывает [onDetachedFromWindow]. Переход в `IDLE` доводится до конца тем же циклом, а не
 * обрывается на полпути.
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

    /** Внешняя корона: широкое гало, строго концентричное диску планеты. */
    private val haloOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Внутренняя горячая корона: узкое кольцо у самого лимба. */
    private val haloInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Атмосферное кольцо, обнимающее диск (полоса градиента, а не штрих круга). */
    private val coronaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * Неоновое свечение лимба по всему периметру — аналог формулы `pow(1.0 - mu, 3.2)` из
     * [MarsSpaceShaderPolicy]: широкий тусклый штрих поверх тонкого контура.
     *
     * Слой существует потому, что ночная сторона текстуры почти чёрная (`RGB ≈ 4, 2, 2`) и без
     * собственного света правый-нижний край шара сливался с космосом — планета читалась
     * подрезанной справа, а не круглой.
     */
    private val limbGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

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

    /** Неон текущей фазы (корона, волны, лимб). Догоняет [targetNeon] в кадре. */
    private var neon = NEON_IDLE

    /** Энергия слоёв: текущие значения, которые плавно догоняют цели из [applyTone]. */
    private var haloEnergy = 0.34f
    private var coronaEnergy = 0.32f
    private var limbEnergy = 0.42f
    private var waveEnergy = 0f

    /**
     * Цели перехода: цвет и энергия слоёв той фазы, которую просят показать.
     *
     * Отдельные поля, а не прямое присваивание в [applyTone], — в этом и весь смысл волны:
     * смена фазы больше не происходит за один кадр, текущие значения догоняют эти цели
     * экспоненциально (см. [advanceTransition]).
     */
    private var targetNeon = NEON_IDLE
    private var targetHaloEnergy = 0.34f
    private var targetCoronaEnergy = 0.32f
    private var targetLimbEnergy = 0.42f
    private var targetWaveEnergy = 0f

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
     * Разрешена ли анимация системой — посчитанное заранее.
     *
     * Чтение `Settings.Global` и опрос `PowerManager` идут через `ContentResolver`: это единицы
     * миллисекунд, и на каждый вызов [render] (хост может звать его десятки раз в секунду) такая
     * проверка стала бы заметной. Поэтому решение принимается при смене фазы, при смене
     * «дышать/стоять» и при переподключении вьюхи, а не на кадр.
     *
     * Отдельное поле, а не «разрешено ли дыхание»: [pulses] — это ПРОСЬБА хоста, а здесь — право
     * системы. Пока эти два условия были склеены в одном флаге, выключение [pulses] (переход в
     * `IDLE`) отбирало право и у перехода цвета, и он ставился мгновенно — та самая вспышка,
     * которую этот класс обещает не делать.
     */
    private var animationsOn = false

    /**
     * Хост попросил паузу (экран ушёл в фон, см. [pauseMotion]).
     *
     * Нужен потому, что окно после `onPause` ещё какое-то время остаётся видимым: без этого флага
     * кадры шли бы под чужой активностью до первого `onWindowVisibilityChanged`.
     */
    private var hostPaused = false

    /** Берётся один раз на цикл, а не на кадр: `getInstance()` — обращение к ThreadLocal. */
    private var choreographer: Choreographer? = null

    /**
     * Кадр дыхания.
     *
     * Объект-поле, а не лямбда на кадр: аллокация в цикле перерисовки недопустима (см. KDoc
     * класса). Фаза копится ПО ПРИРАЩЕНИЮ `dt`, а не считается от начала цикла: пока она
     * отсчитывалась от `originNanos`, каждый останов и перезапуск цикла (каждая смена фазы,
     * каждый конец перехода) возвращал дыхание в точку 0 — самая тёмная альфа вместо той, на
     * которой орб остановился, и кольца акустических волн телепортировались назад. Приращение
     * зажато сверху, поэтому после пропущенных кадров или паузы фаза сдвигается на один шаг, а не
     * прыгает. Дрейф текстуры копится так же — ему нужна непрерывность, а не привязка к периоду.
     */
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!breathing) return
            // Первый кадр цикла даёт приращение 0: предыдущего времени ещё нет, и «шаг с потолка»
            // (0.05 с) сдвинул бы фазу на целых 0.05/3 периода ровно в момент старта.
            val previousNanos = lastFrameNanos
            lastFrameNanos = frameTimeNanos
            val dtSec = if (previousNanos == 0L) {
                0f
            } else {
                ((frameTimeNanos - previousNanos) / 1_000_000_000f)
                    .coerceIn(0f, OrbMotionPolicy.MAX_FRAME_DT_SEC)
            }

            phase = OrbMotionPolicy.advancePhase(phase, dtSec, PERIOD_MS)
            drift += dtSec * DRIFT_RAD_PER_SEC
            if (drift > TWO_PI) drift -= TWO_PI

            // Атака быстрее спада: слово начинается рывком, а затухает плавно. Шаг считается по
            // реальному dt, а не «раз в кадр»: на 120 Гц прежняя формула гасила слово вдвое
            // быстрее, чем на 60, и орб вёл себя по-разному на разных телефонах.
            smoothedLevel = OrbMotionPolicy.smoothLevel(smoothedLevel, level, dtSec)

            // Плавный переход фазы: цвет и энергии догоняют цели экспоненциально, поэтому
            // LISTENING → SPEAKING читается как перетекание света, а не как щелчок выключателя.
            // Шаг считается по реальному dt кадра, а не «раз в кадр»: иначе на 30 fps переход
            // был бы вдвое медленнее, чем на 60.
            if (needsTransition()) advanceTransition(VoiceNeonPalette.stepFactor(dtSec))

            if (!needsTransition() && !pulses) {
                // Переход доехал, а дышать больше не о чем: цикл кадров обязан остановиться сам,
                // иначе статичный IDLE крутил бы перерисовку вечно. Фаза при этом НЕ сбрасывается:
                // следующий запуск продолжит дыхание с этой точки.
                stopBreathing()
                invalidate()
                return
            }

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
     * @param tone роль цвета: покой — тёмный рубин, слушаю — коралловый рубин, думаю — янтарь,
     *   говорю — персиковое золото (те же роли, что у [LocalVoiceButton], чтобы кнопка композера
     *   и полный экран не спорили о цвете);
     * @param pulses нужен ли цикл дыхания. `false` — статичный кадр, цикл кадров не заводится.
     */
    fun render(tone: VoiceModeTone, pulses: Boolean) {
        val toneChanged = this.tone != tone
        val pulsesChanged = this.pulses != pulses
        this.tone = tone
        this.pulses = pulses
        // Право на анимацию перечитывается и при смене фазы: человек мог выключить анимации, не
        // покидая экран разговора. Чтение настроек дорого только на кадр — здесь его нет.
        if (toneChanged || pulsesChanged) animationsOn = animationAllowed()
        if (toneChanged) {
            applyTone()
            invalidate()
        }
        // Кадры нужны не только для дыхания: пока цвет и энергии не сошлись с целью, переход
        // обязан доехать — в том числе переход в IDLE.
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
     * Гало строго концентрично планете ([HALO_SHIFT_X]/[HALO_SHIFT_Y] — нули): сдвинутое
     * влево-вверх свечение вместе с тёмной правой-нижней стороной текстуры давало сильную
     * оптическую иллюзию, что шар съехал из центра. Теперь свет и сфера делят один центр.
     */
    private fun buildShaders() {
        if (radius <= 0f || planetR <= 0f) {
            haloOuterPaint.shader = null
            haloInnerPaint.shader = null
            coronaPaint.shader = null
            shadePaint.shader = null
            fallbackPaint.shader = null
            return
        }
        val lightX = centerX - planetR * LIGHT_DX
        val lightY = centerY - planetR * LIGHT_DY

        // Внешнее гало: пик ровно на кромке диска (0.66 радиуса гало при PLANET_RATIO 0.66) и
        // мягкий спад наружу. Раньше пик стоял в центре и прятался под планетой — вокруг крупного
        // шара оставался бы лишь тусклый ободок. Альфа-стопы снижены на ~18% (было
        // 0x26/0x40/0x4D/0x16): на пике речи гало сливалось в слепящее белое кольцо.
        haloOuterPaint.shader = RadialGradient(
            centerX - planetR * HALO_SHIFT_X,
            centerY - planetR * HALO_SHIFT_Y,
            haloR,
            intArrayOf(
                MarsGradients.withAlpha(neon, 0x1F),
                MarsGradients.withAlpha(neon, 0x34),
                MarsGradients.withAlpha(neon, 0x3F),
                MarsGradients.withAlpha(neon, 0x12),
                MarsGradients.withAlpha(neon, 0x00),
            ),
            floatArrayOf(0f, 0.52f, PLANET_RATIO, 0.86f, 1f),
            Shader.TileMode.CLAMP,
        )
        // Внутренняя корона: прозрачная в центре, чтобы не мылить диск, и плотная у лимба.
        // Стопы тоже приглушены (было 0x3D/0x1C): горячее кольцо у лимба давало самый резкий
        // перепад яркости на всём кадре.
        haloInnerPaint.shader = RadialGradient(
            lightX,
            lightY,
            planetR * INNER_SPAN,
            intArrayOf(
                MarsGradients.withAlpha(neon, 0x00),
                MarsGradients.withAlpha(neon, 0x00),
                MarsGradients.withAlpha(neon, 0x33),
                MarsGradients.withAlpha(neon, 0x18),
                MarsGradients.withAlpha(neon, 0x00),
            ),
            floatArrayOf(0f, 0.50f, 0.60f, 0.78f, 1f),
            Shader.TileMode.CLAMP,
        )
        // Атмосферное кольцо: полоса на 70–100% радиуса — ровно над лимбом планеты. Широкая и
        // плотная, чтобы контур читался по ВСЕМУ периметру, включая почти чёрный ночной лимб.
        // Пик приглушён с 0x7A до 0x64 (и с 0x40 до 0x34): контур остаётся читаемым, но больше
        // не «обводит» планету неоновой линией.
        coronaPaint.shader = RadialGradient(
            centerX,
            centerY,
            planetR * CORONA_SPAN,
            intArrayOf(
                MarsGradients.withAlpha(neon, 0x00),
                MarsGradients.withAlpha(neon, 0x00),
                MarsGradients.withAlpha(neon, 0x64),
                MarsGradients.withAlpha(neon, 0x34),
                MarsGradients.withAlpha(neon, 0x00),
            ),
            floatArrayOf(0f, 0.70f, 0.84f, 0.94f, 1f),
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

    /**
     * Записать цель перехода под новый тон.
     *
     * Цвет и энергия здесь НЕ присваиваются напрямую: раньше именно это присваивание и делало
     * смену фазы вспышкой за один кадр (резкий контраст «красный → голубой», на который жаловался
     * владелец). Теперь пишутся цели, а текущие значения догоняют их в кадре ([advanceTransition]).
     *
     * Мгновенное применение остаётся ровно для двух случаев, где плавность невозможна или вредна:
     * выключенные системой анимации (человек просил покой) и невидимая вьюха — спрятанный слой,
     * фоновое окно. Догонять переход, которого никто не видит, значит крутить кадры впустую.
     *
     * Раньше здесь стояло `!motionAllowed || !breathing`, и второе условие было ошибкой: в момент
     * смены фазы цикл кадров всегда ещё не запущен (его запускает следующий за [applyTone] вызов
     * [syncBreathing]), поэтому ЛЮБАЯ смена тона ставилась мгновенно — плавным оставался только
     * тот переход, что успел начаться раньше. Флаг `motionAllowed` к тому же смешивал просьбу
     * хоста ([pulses]) с правом системы, так что переход `LISTENING → IDLE` (где `pulses = false`
     * по определению) всегда был щелчком.
     */
    private fun applyTone() {
        targetNeon = neonFor(tone)
        val energy = energyFor(tone)
        targetHaloEnergy = energy[0]
        targetCoronaEnergy = energy[1]
        targetLimbEnergy = energy[2]
        targetWaveEnergy = energy[3]
        if (OrbMotionPolicy.shouldSnap(animationsOn, canAnimate())) snapToTargets()
    }

    /** Поставить цвет и энергии ровно в цель: статичный кадр, выключенные анимации, схождение. */
    private fun snapToTargets() {
        neon = targetNeon
        haloEnergy = targetHaloEnergy
        coronaEnergy = targetCoronaEnergy
        limbEnergy = targetLimbEnergy
        waveEnergy = targetWaveEnergy
        paintWithNeon()
    }

    /**
     * Один шаг перехода: приблизить текущие значения к целям на долю [k].
     *
     * `k` приходит из [VoiceNeonPalette.stepFactor] — это экспонента от `dt`, поэтому переход
     * занимает одно и то же время на 60 и на 30 кадрах в секунду.
     */
    private fun advanceTransition(k: Float) {
        neon = VoiceNeonPalette.lerpColor(neon, targetNeon, k)
        haloEnergy += (targetHaloEnergy - haloEnergy) * k
        coronaEnergy += (targetCoronaEnergy - coronaEnergy) * k
        limbEnergy += (targetLimbEnergy - limbEnergy) * k
        waveEnergy += (targetWaveEnergy - waveEnergy) * k
        // Каналы сошлись до 1/255 — дожимаем ровно в цель: экспонента не достигает её никогда, и
        // без этого цикл кадров гонялся бы за последним битом цвета ещё пару секунд после того,
        // как глаз переход уже не видит.
        if (neon != targetNeon && colorsConverged(neon, targetNeon)) neon = targetNeon
        paintWithNeon()
    }

    /** Есть ли ещё расхождение с целью: по цвету — точное, по энергиям — с допуском. */
    private fun needsTransition(): Boolean =
        neon != targetNeon ||
            abs(haloEnergy - targetHaloEnergy) > TRANSITION_EPSILON ||
            abs(coronaEnergy - targetCoronaEnergy) > TRANSITION_EPSILON ||
            abs(limbEnergy - targetLimbEnergy) > TRANSITION_EPSILON ||
            abs(waveEnergy - targetWaveEnergy) > TRANSITION_EPSILON

    /** Совпадают ли цвета поканально в пределах 1/255 — критерий дожима в цель. */
    private fun colorsConverged(a: Int, b: Int): Boolean =
        abs(((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF)) <= 1 &&
            abs(((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF)) <= 1 &&
            abs((a and 0xFF) - (b and 0xFF)) <= 1

    /**
     * Перекрасить кисти и градиенты под текущий [neon].
     *
     * Единственное место, где цвет фазы доходит до рисования: вызывается и при мгновенном
     * применении ([snapToTargets]), и на каждом кадре перехода. Градиенты пересобираются, потому
     * что их стопы — это тот же неон с разной альфой.
     */
    private fun paintWithNeon() {
        wavePaint.color = neon
        hazePaint.color = neon
        crescentPaint.color = neon
        // Неоновая кромка лимба — сам неон фазы; благородный контур — тёплый свет атмосферы
        // заставки, чтобы ночная сторона читалась как сфера, а не как вырез в космосе.
        limbGlowPaint.color = neon
        nightRimPaint.color = blendColors(neon, MarsSpaceShaderPolicy.COLOR_ATMOSPHERE_CORE, NIGHT_RIM_WARMTH)
        // Планета остаётся Марсом во всех фазах: цвет несёт атмосфера, а не поверхность.
        buildShaders()
    }

    /**
     * Неон фазы.
     *
     * Это не палитра темы, а именно неоновые акценты атмосферы: у [MarsTheme] нет ролей
     * «рубиновый неон» и «персиковое золото», а выводить их из `STATUS_*` значило бы получить
     * грязные полутона. Базовый Марс при этом остаётся из [MarsSpaceShaderPolicy].
     *
     * Вся четвёрка живёт в одной тёплой гамме ([VoiceNeonPalette]): покой — глубокий рубин,
     * слушание — коралловый рубин с большей энергией, размышление — янтарь, речь — персиковое
     * золото. Резкого перехода «красный → кислотно-голубой» больше нет: фазы различаются
     * светлотой и энергией внутри одной гаммы.
     */
    private fun neonFor(tone: VoiceModeTone): Int = VoiceNeonPalette.of(tone)

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
        //
        // Одна и та же формула и в цикле кадров, и в статичном кадре: статичная ветка берёт ту же
        // [phase] ([REST_PHASE] — точка, где дыхание равно прежней константе покоя). Пока статика
        // считалась отдельной формулой, в момент остановки цикла (переход в IDLE доехал) альфа
        // прыгала с текущей фазы на середину подъёма — заметный щелчок яркости в самом конце
        // перехода, хотя цвет к тому моменту уже перетёк плавно.
        val breath = OrbMotionPolicy.breathAt(phase)

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

    /**
     * Слои 1–2: внешняя корона и горячее кольцо у лимба.
     *
     * Никаких собственных «туманностей» здесь больше нет: газ, звёзды и кометы считает настоящий
     * шейдер [MarsSpaceBackgroundView] на весь экран (в режиме `setVoiceOrbMode(true)` он ещё и
     * пульсирует неоном фазы). Внутри вьюхи остаётся только то, что принадлежит планете —
     * атмосферное гало, концентричное диску.
     */
    private fun drawHalo(canvas: Canvas, breath: Float, voice: Float) {
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
     * Пять проходов снизу вверх: кольцо-корона (360°), неоновое свечение лимба (360°), тонкий
     * контур по всему периметру, дымка и серп на освещённой стороне. Полный круг у первых трёх —
     * принципиален: ночная сторона текстуры почти чёрная, и без собственного света правый-нижний
     * край шара сливался с космосом, из-за чего сфера читалась подрезанной и «съехавшей».
     * Толщина и яркость дышат вместе с речью: на громком слове кромка вспыхивает.
     */
    private fun drawAtmosphere(canvas: Canvas, breath: Float, voice: Float) {
        coronaPaint.alpha = alphaOf(coronaEnergy * (0.92f + 0.28f * breath) + CORONA_VOICE * voice)
        canvas.drawCircle(centerX, centerY, planetR * CORONA_SPAN, coronaPaint)

        // Fresnel-свечение лимба: широкий тусклый штрих — тот же pow(1 - mu, 3.2) шейдера.
        limbGlowPaint.strokeWidth = planetR * LIMB_GLOW_WIDTH
        limbGlowPaint.alpha = alphaOf(LIMB_GLOW_BASE + LIMB_GLOW_VOICE * voice)
        canvas.drawCircle(centerX, centerY, planetR * LIMB_GLOW_RATIO, limbGlowPaint)

        // Благородный контур: тонкая линия, которая держит круглым даже почти чёрный ночной лимб.
        nightRimPaint.strokeWidth = baseStroke * NIGHT_RIM_STROKE
        nightRimPaint.alpha = alphaOf(NIGHT_RIM_ALPHA + NIGHT_RIM_VOICE * voice)
        canvas.drawCircle(centerX, centerY, planetR * NIGHT_RIM_RATIO, nightRimPaint)

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
    }

    /** Альфа 0..1 в байты канала без риска выйти за границу: `Paint.alpha` ждёт 0..255. */
    private fun alphaOf(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).toInt()

    /**
     * Смешать два непрозрачных цвета: `t = 0` — [from], `t = 1` — [to].
     *
     * Своя функция, а не общая утилита: вызывается ровно один раз на смену фазы (не на кадр),
     * поэтому аллокаций в цикле перерисовки не добавляет, а зависимость от чужого API не нужна.
     */
    private fun blendColors(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val a = ((to ushr 24) and 0xFF).toFloat() * k + ((from ushr 24) and 0xFF).toFloat() * (1f - k)
        val r = ((to ushr 16) and 0xFF).toFloat() * k + ((from ushr 16) and 0xFF).toFloat() * (1f - k)
        val g = ((to ushr 8) and 0xFF).toFloat() * k + ((from ushr 8) and 0xFF).toFloat() * (1f - k)
        val b = (to and 0xFF).toFloat() * k + (from and 0xFF).toFloat() * (1f - k)
        return (a.toInt() shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    // --- жизненный цикл дыхания ---------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Вьюха могла быть прикреплена уже после render(): здесь догоняем состояние. Здесь же
        // перечитываем системные флаги: человек мог вернуться на экран после того, как выключил
        // анимации в настройках.
        animationsOn = animationAllowed()
        hostPaused = false
        syncBreathing()
    }

    override fun onDetachedFromWindow() {
        // Сначала гасим цикл, потом отдаём вьюху системе: иначе следующий кадр пришёл бы на
        // отсоединённую вьюху и держал её (и Activity) до своего конца.
        stopBreathing()
        // Возврата на экран из этого состояния уже не будет, а на следующем появлении нужен кадр
        // ровно в цели, а не остаток недоехавшего перехода.
        if (needsTransition()) snapToTargets()
        hostPaused = false
        super.onDetachedFromWindow()
    }

    /**
     * Видимость вьюхи или её предков.
     *
     * Слой голосового режима живёт в дереве постоянно и просто прячется через `GONE` у корня:
     * `onDetachedFromWindow` при этом не приходит, а `render()` продолжает вызываться хостом на
     * каждое обновление снимка речи. Без этой проверки орб крутил цикл кадров на невидимой вьюхе,
     * пока человек читает чат, — ровно та утечка батареи, ради которой цикл и сделан явным.
     */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isAttachedToWindow) return
        syncBreathing()
    }

    /** Окно ушло в фон (или вернулось): кадры под чужой активностью не нужны. */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!isAttachedToWindow) return
        syncBreathing()
    }

    /**
     * Остановить кадры снаружи: хост ушёл в фон, но окно ещё формально видимо.
     *
     * Тон и цели не сбрасываются — на возвращении ([resumeMotion]) переход продолжит с того
     * места, где остановился, а не начнётся заново.
     */
    fun pauseMotion() {
        hostPaused = true
        stopBreathing()
    }

    /** Хост вернулся: вернуть цикл кадров, если фаза его всё ещё требует. */
    fun resumeMotion() {
        hostPaused = false
        syncBreathing()
    }

    /**
     * Настоящая ли это видимость: прикреплена, вся цепочка предков `VISIBLE`, окно `VISIBLE`.
     *
     * `isShown` покрывает прикрепление и видимость предков, но НЕ видимость окна — поэтому окно
     * проверяется отдельно. Догонять переход на невидимой вьюхе незачем: кадры не увидят.
     */
    private fun canAnimate(): Boolean =
        isShown && windowVisibility == View.VISIBLE && !hostPaused

    /**
     * Согласовать цикл кадров с желанием вызывающего, видимостью и системными флагами.
     *
     * Единственное место, где решается «дышать или стоять», поэтому здесь же снимаются кадры при
     * выключенных анимациях и у спрятанной вьюхи — иначе решение пришлось бы дублировать в
     * [render], в [onAttachedToWindow] и в обработчиках видимости.
     */
    private fun syncBreathing() {
        if (OrbMotionPolicy.shouldSnap(animationsOn, canAnimate())) {
            stopBreathing()
            // Плавность — это тоже движение, которого не просили: при выключенных анимациях
            // переход ставится мгновенно. У невидимой вьюхи догонять нечего по той же причине —
            // кадры ушли бы в никуда.
            if (needsTransition()) snapToTargets()
            if (!animationsOn) phase = REST_PHASE
            smoothedLevel = level
            invalidate()
            return
        }
        // Кадры нужны, пока идёт дыхание фазы ИЛИ пока цвет и энергии не сошлись с целью:
        // переход в IDLE тоже обязан доехать плавно, а не оборваться на полпути.
        if (OrbMotionPolicy.needsFrames(pulses, needsTransition(), animationsOn, canAnimate())) {
            startBreathing()
        } else {
            stopBreathing()
        }
    }

    private fun startBreathing() {
        if (breathing) return
        breathing = true
        // Фаза НЕ сбрасывается: она живёт дольше одного запуска цикла, иначе каждый перезапуск
        // ронял бы дыхание в самую тёмную точку периода. Обнуляется только отметка времени —
        // первый кадр нового цикла даёт нулевое приращение.
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

        /**
         * Опорная фаза статичного кадра: орб выглядит «живым», но стоит.
         *
         * 0.19 — точка подъёма, где `alphaAt` даёт ровно прежнюю константу покоя (0.32 амплитуды,
         * альфа 0.378). Значение подобрано обратной задачей `(1 - cos 2πp) / 2 = 0.32`: фаза и
         * «доля амплитуды» — теперь одно и то же число, и статичный кадр не отличается по яркости
         * от прежнего, хотя считается той же формулой, что и дыхание.
         */
        const val REST_PHASE = 0.1914f

        /** Корона занимает 98% меньшей стороны: габарит вьюхи — это «воздух» вокруг света. */
        const val HALO_FILL = 0.98f

        /**
         * Радиус диска планеты от радиуса короны: 66% — почти в два раза крупнее прежних 49%.
         *
         * При `ORB_DP = 300` (см. [VoiceModeOverlay]) это даёт диск ~194 dp вместо ~113 dp:
         * Марс наконец занимает экран как планета, а не как значок в рамке.
         */
        const val PLANET_RATIO = 0.66f

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

        /** Покой: благородный тёмно-рубиновый Марс (палитра общая с фоном, см. [VoiceNeonPalette]). */
        val NEON_IDLE = VoiceNeonPalette.IDLE

        /** Слабый тёплый подсвет ночной стороны (альфа уже в цвете). */
        val NIGHT_FILL = 0x143C1814

        /** Холодная кромка ночного лимба (стартовый цвет; рабочее значение считает [applyTone]). */
        val NIGHT_RIM = 0xFF8C5A5A.toInt()

        // --- энергия фаз: [гало, кольцо, лимб, волны] ---------------------------------------

        /**
         * Энергии слоёв — на 15–20% ниже прежних пиков.
         *
         * Прежние значения (`0.40/0.66/0.70/0.58` по гало, `0.98` по лимбу в речи) вместе с
         * плотными альфа-стопами градиентов давали на пике речи слепящий белый ободок вокруг
         * диска: свет «резал» глаз вместо того, чтобы мягко светиться. Свечение должно быть
         * бархатным — яркость фазы теперь читается по цвету и площади гало, а не по вспышке.
         */
        val ENERGY_IDLE = floatArrayOf(0.34f, 0.32f, 0.42f, 0.00f)
        val ENERGY_LISTENING = floatArrayOf(0.56f, 0.51f, 0.66f, 0.42f)
        val ENERGY_THINKING = floatArrayOf(0.59f, 0.53f, 0.58f, 0.00f)
        val ENERGY_SPEAKING = floatArrayOf(0.49f, 0.66f, 0.82f, 0.67f)

        /**
         * Допуск схождения перехода по энергии: 0.3% амплитуды — это меньше одного шага альфы
         * (1/255 = 0.39%), то есть дальше догонять уже нечего, разница не видна на экране.
         */
        const val TRANSITION_EPSILON = 0.003f

        // --- корона -------------------------------------------------------------------------

        /** Внешнее гало: 2.45 радиуса планеты — «воздух» вокруг света. */
        const val HALO_SPAN = 2.45f

        /** Внутренняя горячая корона: узкое кольцо у лимба. */
        const val INNER_SPAN = 1.55f

        /** Смещение гало к источнику света: нули — гало обязано быть концентрично диску. */
        const val HALO_SHIFT_X = 0f
        const val HALO_SHIFT_Y = 0f

        /**
         * Смещение источника света для внутренней короны.
         *
         * Сведено к долям процента радиуса: свет на текстуре и так падает слева-сверху, а любой
         * заметный сдвиг внутреннего кольца снова сбивал бы оптический центр сферы.
         */
        const val LIGHT_DX = 0.02f
        const val LIGHT_DY = 0.03f

        const val HALO_VOICE = 0.20f
        const val INNER_VOICE = 0.22f
        const val CORONA_VOICE = 0.26f

        /** Вес речи и дыхания в общей «живости» кадра. */
        const val VOICE_WEIGHT = 0.72f
        const val BREATH_WEIGHT = 0.28f

        /** Нижняя граница и размах масштаба дыхания. */
        const val MIN_SCALE = 0.985f
        const val BREATH_SCALE = 0.020f

        /**
         * Доля радиуса, которую добавляет полная громкость: заметно, но не «прыжок».
         */
        const val LEVEL_SCALE = 0.045f

        // Атака/спад сглаживания амплитуды живут в [OrbMotionPolicy] как постоянные времени:
        // шаг «доля за кадр» зависел от частоты кадров, и на 120 Гц орб гас вдвое быстрее, чем на 60.

        // --- волны --------------------------------------------------------------------------

        const val WAVE_RINGS = 3

        /**
         * Волна рождается на 1.04 радиуса планеты и уходит к краю короны.
         *
         * Размах подобран под крупную планету (`PLANET_RATIO = 0.66`): 1.04 + 0.46 = 1.50 радиуса
         * диска — это ровно край гало, поэтому волна гаснет сама, а не обрывается о границу вьюхи.
         */
        const val WAVE_START = 1.04f
        const val WAVE_SPAN = 0.46f

        const val WAVE_BASE_ALPHA = 0.25f
        const val WAVE_VOICE_ALPHA = 0.35f

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
        const val NIGHT_RIM_RATIO = 1.004f

        /**
         * Толщина контура ночного лимба в `dp`.
         *
         * Было 0.9 — линия тоньше пикселя на mdpi, и на тёмной стороне она пропадала совсем.
         */
        const val NIGHT_RIM_STROKE = 1.6f

        /** Яркость контура: теперь он виден по всему периметру, а не только угадывается. */
        const val NIGHT_RIM_ALPHA = 0.34f
        const val NIGHT_RIM_VOICE = 0.20f

        /** Сколько тёплого света атмосферы подмешано в контур (0 — чистый неон фазы). */
        const val NIGHT_RIM_WARMTH = 0.55f

        // --- Fresnel-свечение лимба ---------------------------------------------------------

        /** Ширина свечения в долях радиуса планеты: широкая и мягкая, как рассеяние в воздухе. */
        const val LIMB_GLOW_WIDTH = 0.085f

        /** Кольцо свечения идёт ровно по кромке диска. */
        const val LIMB_GLOW_RATIO = 1.010f

        /** Базовая яркость свечения и добавка от голоса. */
        const val LIMB_GLOW_BASE = 0.16f
        const val LIMB_GLOW_VOICE = 0.26f
    }
}

/**
 * Неоновая палитра фаз голосового режима — один цвет на всю сцену.
 *
 * Зачем отдельный объект: тот же оттенок нужен и короне планеты ([VoiceModeOrbView]), и
 * неоновой 3D-туманности фона ([MarsSpaceBackgroundView.setVoiceState] → uniform `uVoiceNeon`).
 * Пока палитра жила приватной константой вьюхи, фон и шар приходилось красить «примерно тем же
 * цветом» — и на экране это читалось как две несвязанные картинки. Теперь источник правды один.
 *
 * Объект не зависит от `android.*`, поэтому палитра проверяется обычным JVM-тестом
 * ([VoiceModeOrbViewContractTest]).
 */
internal object VoiceNeonPalette {

    /**
     * Постоянная времени перехода между фазами, секунды.
     *
     * 0.45 с — это ~95% пути за 1.35 с и ~99% за 2 с: глаз читает такой переход как «свет
     * перетёк», а не как «включили другую лампу». Быстрее 0.3 с снова выглядит вспышкой,
     * медленнее 0.7 с — как будто интерфейс подвис.
     */
    const val TRANSITION_TAU_SEC = 0.45f

    /** Покой: глубокий благородный тёмно-рубиновый — тлеющая корона. */
    val IDLE = 0xFFB8293D.toInt()

    /** Слушаю: мягкий тёплый кораллово-рубиновый — микрофон открыт, человек говорит. */
    val LISTENING = 0xFFE84A58.toInt()

    /** Думаю: мягкий янтарно-терракотовый закат Марса — модель считает. */
    val THINKING = 0xFFE58B42.toInt()

    /**
     * Говорю: тёплый персиково-золотистый — солнечный свет в атмосфере Марса.
     *
     * Раньше здесь стоял кислотно-голубой `0xFF4CC8FF`: переход с рубинового на него был
     * контрастом в 180° по тону, и смена фазы била по глазам. Теперь речь — самая светлая и
     * тёплая точка той же гаммы: синий канал вырос (0x79 против 0x3D у покоя), но красный
     * по-прежнему доминирует, поэтому оттенок читается как золото, а не как холодный синий.
     */
    val SPEAKING = 0xFFF2A679.toInt()

    /** Цвет фазы: ровно один на каждую роль, без «похожих» пар. */
    fun of(tone: VoiceModeTone): Int = when (tone) {
        VoiceModeTone.IDLE -> IDLE
        VoiceModeTone.LISTENING -> LISTENING
        VoiceModeTone.THINKING -> THINKING
        VoiceModeTone.SPEAKING -> SPEAKING
    }

    /**
     * Поканальная интерполяция ARGB: `t = 0` — [from], `t = 1` — [to].
     *
     * Считается в int-каналах, а не через `Color`/`ColorUtils`: объект обязан оставаться
     * независимым от `android.*`, иначе палитру нельзя проверить обычным JVM-тестом. Альфа
     * интерполируется наравне с цветом — иначе переход между полупрозрачными красками давал бы
     * рывок по плотности.
     *
     * Канал считается с округлением и гарантированным шагом в единицу ([mixChannel]): иначе
     * пошаговая интерполяция с малым `k` застывала бы, не дойдя до цели, и цикл кадров крутился
     * бы вечно вокруг цвета, который уже не меняется.
     */
    fun lerpColor(from: Int, to: Int, t: Float): Int {
        val k = if (t.isFinite()) t.coerceIn(0f, 1f) else 0f
        if (k <= 0f) return from
        if (k >= 1f) return to
        val a = mixChannel(channel(from, 24), channel(to, 24), k)
        val r = mixChannel(channel(from, 16), channel(to, 16), k)
        val g = mixChannel(channel(from, 8), channel(to, 8), k)
        val b = mixChannel(channel(from, 0), channel(to, 0), k)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Доля пути к цели за кадр длиной [dtSec] при постоянной времени [tauSec].
     *
     * Экспонента, а не фиксированный шаг: так переход занимает одно и то же время на любой
     * частоте кадров и никогда не «перелетает» цель. `dt` зажат сверху четвертью секунды — после
     * сворачивания приложения и возврата один гигантский кадр иначе перескочил бы сразу в конец,
     * и переход выглядел бы щелчком.
     */
    fun stepFactor(dtSec: Float, tauSec: Float = TRANSITION_TAU_SEC): Float {
        if (dtSec <= 0f || !dtSec.isFinite()) return 0f
        if (tauSec <= 0f || !tauSec.isFinite()) return 1f
        val dt = dtSec.coerceIn(0f, 0.25f)
        return 1f - kotlin.math.exp((-dt / tauSec).toDouble()).toFloat()
    }

    /** Канал 0..255: сдвиг 24 — альфа, 16 — красный, 8 — зелёный, 0 — синий. */
    private fun channel(color: Int, shift: Int): Int = (color ushr shift) and 0xFF

    /**
     * Один канал: линейная интерполяция с округлением и гарантированным шагом в единицу.
     *
     * Две ловушки целочисленной интерполяции:
     * 1. `toInt()` на убывающей разности отсекает дробь и залипает на целом значении — нужно
     *    округление;
     * 2. при кадре 60 fps и тау 0.45 `k ≈ 0.036`, то есть шаг `(to - from) * k` становится меньше
     *    половины канала, округляется в ноль — и канал замирает в 13 единицах от цели, а цикл
     *    кадров крутится вечно. Поэтому пока канал не равен цели, он двигается минимум на 1.
     *
     * Перелёта при этом не бывает: `rounded` не выходит за [to], а `coerceIn` держит его между
     * «шагнул на единицу» и целью.
     */
    private fun mixChannel(from: Int, to: Int, k: Float): Int {
        if (from == to) return from
        val rounded = kotlin.math.round(from + (to - from) * k).toInt()
        return if (to > from) rounded.coerceIn(from + 1, to) else rounded.coerceIn(to, from - 1)
    }
}

/**
 * Движение орба: когда вообще нужны кадры, как копится фаза дыхания и как сглаживается речь.
 *
 * Зачем отдельный объект, а не пять строк внутри вьюхи: `View` и `Choreographer` на обычной JVM
 * не собрать, и всё, что живёт прямо в [VoiceModeOrbView], проверяется только чтением исходника.
 * Здесь же нет ни одного `android.*` — правила «когда дыхание честное, а когда статичный кадр»,
 * непрерывность фазы и частотно-независимое сглаживание проверяются обычным JUnit
 * ([VoiceModeOrbViewContractTest]), включая те дефекты, которые до этой волны ловились только
 * глазами на телефоне.
 *
 * Три правила, вокруг которых всё построено:
 * 1. **Право системы и просьба хоста — разные вещи.** [pulses] — «дыши, пока я показываю фазу»,
 *    анимация разрешена/запрещена настройками. Переход цвета обязан ехать, даже когда дыхание уже
 *    не нужно (переход в `IDLE`), поэтому [needsFrames] складывает оба условия, а не склеивает их
 *    в один флаг.
 * 2. **Невидимая вьюха кадров не просит.** Спрятанный слой (`GONE`) и фоновое окно — это не
 *    «медленно», это «зря»: цикл кадров на невидимой вьюхе жжёт батарею и держит Activity.
 * 3. **Фаза живёт дольше одного запуска.** Она копится по `dt` и не сбрасывается ни на старте
 *    цикла, ни на его остановке: иначе каждый перезапуск ронял бы дыхание в самую тёмную точку,
 *    а кольца акустических волн телепортировались бы назад.
 */
internal object OrbMotionPolicy {

    /**
     * Потолок приращения времени за кадр, секунды.
     *
     * После сворачивания приложения, подлагивания или долгой паузы `dt` бывает в сотни
     * миллисекунд; без потолка фаза и дрейф перескочили бы на полпериода за один кадр — это и
     * читается как «орб дёрнулся».
     */
    const val MAX_FRAME_DT_SEC = 0.05f

    /**
     * Постоянная времени атаки амплитуды речи, секунды.
     *
     * 0.028 с — это прежние 45% пути за кадр 60 fps, переписанные как время: слово по-прежнему
     * «вспыхивает», но на 120 Гц орб больше не гаснет вдвое быстрее, чем на 60.
     */
    const val LEVEL_ATTACK_TAU_SEC = 0.028f

    /** Постоянная времени спада: прежние 12% за кадр 60 fps — плавное затухание между словами. */
    const val LEVEL_RELEASE_TAU_SEC = 0.131f

    /**
     * Допуск схождения амплитуды: 0.2% — меньше одного шага альфы (1/255 = 0.39%).
     * Дальше догонять нечего, а экспонента сама по себе не достигает цели никогда.
     */
    const val LEVEL_SNAP = 0.002f

    /**
     * Нужны ли кадры прямо сейчас.
     *
     * @param pulses просит ли хост дыхание фазы;
     * @param transitioning не сошёлся ли переход цвета/энергий (его обязано доехать даже в `IDLE`);
     * @param animationsOn разрешены ли анимации системой;
     * @param visible видна ли вьюха на самом деле.
     */
    fun needsFrames(
        pulses: Boolean,
        transitioning: Boolean,
        animationsOn: Boolean,
        visible: Boolean,
    ): Boolean = animationsOn && visible && (pulses || transitioning)

    /**
     * Ставить ли цели мгновенно, без кадров.
     *
     * Мгновенно — только когда движение запрещено системой или его некому увидеть.
     */
    fun shouldSnap(animationsOn: Boolean, visible: Boolean): Boolean = !animationsOn || !visible

    /** Фаза в 0..1: битое значение (NaN, бесконечность) — начало цикла, а не случайный кадр. */
    fun normalizePhase(phase: Float): Float {
        if (!phase.isFinite()) return 0f
        val wrapped = phase - kotlin.math.floor(phase)
        return if (wrapped >= 1f) 0f else wrapped
    }

    /**
     * Один шаг фазы дыхания: `dt / период`, зажатый сверху [MAX_FRAME_DT_SEC].
     *
     * Накопление, а не `(время - начало) % период`: у накопления нет начала, которое можно
     * потерять при перезапуске цикла, — поэтому остановка и возврат не дают скачка. Уплывание
     * накопления (float за минуты) для декоративной синусоиды не значит ничего: важна
     * непрерывность, а не абсолютная фаза.
     */
    fun advancePhase(phase: Float, dtSec: Float, periodMs: Long): Float {
        val current = normalizePhase(phase)
        if (periodMs <= 0L) return current
        if (!dtSec.isFinite() || dtSec <= 0f) return current
        val stepSec = dtSec.coerceAtMost(MAX_FRAME_DT_SEC)
        return normalizePhase(current + stepSec / (periodMs / 1000f))
    }

    /**
     * «Дыхание» 0..1 в фазе [phase]: та же кривая, что у статус-точек ([MarsPulsePolicy.alphaAt]),
     * нормированная в долю амплитуды.
     *
     * Одна формула на цикл кадров и на статичный кадр: раньше статика считалась отдельной
     * константой, и в момент остановки цикла яркость прыгала к ней с текущей фазы.
     */
    fun breathAt(phase: Float): Float {
        val span = MarsPulsePolicy.MAX_ALPHA - MarsPulsePolicy.MIN_ALPHA
        if (span <= 0f) return 0f
        val alpha = MarsPulsePolicy.alphaAt(normalizePhase(phase))
        return ((alpha - MarsPulsePolicy.MIN_ALPHA) / span).coerceIn(0f, 1f)
    }

    /**
     * Сглаженная амплитуда речи: атака быстрее спада, шаг — экспонента от `dt`.
     *
     * Цель приходит пачками 10–16 Гц; без сглаживания речь читалась бы как мигание, а шаг «доля
     * за кадр» делал бы скорость реакции зависимой от частоты экрана.
     */
    fun smoothLevel(current: Float, target: Float, dtSec: Float): Float {
        val from = if (current.isFinite()) current.coerceIn(0f, 1f) else 0f
        val to = if (target.isFinite()) target.coerceIn(0f, 1f) else 0f
        if (from == to) return to
        val tau = if (to > from) LEVEL_ATTACK_TAU_SEC else LEVEL_RELEASE_TAU_SEC
        val next = from + (to - from) * VoiceNeonPalette.stepFactor(dtSec, tau)
        return if (kotlin.math.abs(to - next) <= LEVEL_SNAP) to else next.coerceIn(0f, 1f)
    }
}
