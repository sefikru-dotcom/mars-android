package ru.mars.mobile

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.PowerManager
import android.provider.Settings
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Живой фон «Глубокий космос с красной планетой Марс» — GPU-шейдер, нарисованный позади контента.
 *
 * Устройство слоя:
 *  - внутри [GLSurfaceView] с ES 3.0 (или ES 2.0, если телефон не умеет 3.0) и полноэкранным
 *    шейдером из [MarsSpaceShaderPolicy] — звёзды, туманность, кометы и Марс считаются целиком
 *    на GPU, без единого битмапа и без Canvas-оверлея;
 *  - если GL-контекст не поднялся, шейдер не собрался или устройство вообще без OpenGL —
 *    включается Canvas-фоллбэк ([CanvasBackdrop]) с той же палитрой: градиент космоса,
 *    туманность, звёзды, кометы и планета с атмосферным ореолом;
 *  - кадры выдаёт [Choreographer], а ритм (24 fps в покое, 60 fps при скролле) считает
 *    [MarsSpaceShaderPolicy]; режим `RENDERMODE_WHEN_DIRTY` означает, что без запроса
 *    кадра GPU не работает вообще;
 *  - режим голосового орба ([setVoiceOrbMode] + [setVoiceState]) превращает ту же заставку в
 *    акустическое гало экрана разговора: газовые облака FBM и центральная рэлеевская корона
 *    подмешивают неон фазы и пульсируют в такт речи, а маленькая планета на горизонте убирается —
 *    настоящий Марс в этот момент рисует `VoiceModeOrbView` по центру.
 *
 * Бережность к батарее — не пожелание, а контракт:
 *  - `onDetachedFromWindow` и `onWindowVisibilityChanged` полностью глушат тикер и
 *    останавливают GL-поток (`onPause`): в фоне слой потребляет ноль;
 *  - reduce motion (в системе «удалить анимации», `ANIMATOR_DURATION_SCALE == 0`) и режим
 *    энергосбережения рисуют ровно один статичный кадр [MarsSpaceShaderPolicy.STATIC_TIME_SEC]
 *    и больше не тикают;
 *  - буфер рендера меньше вью ([MarsSpaceShaderPolicy.renderScale]): газ мягкий, лишние
 *    пиксели только греют телефон.
 *
 * Подключение — одной строкой в экране:
 * ```
 * setContentView(MarsSpaceBackgroundView.wrap(this, root))
 * ```
 */
class MarsSpaceBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val renderer = SpaceRenderer()
    private val canvasBackdrop = CanvasBackdrop(context)

    private var glView: GLSurfaceView? = null
    private var glResumed = false
    private var fallingBack = false

    /** Контент, за скроллом которого следит фон (ставится из [wrap]). */
    private var trackedContent: View? = null
    private var scrollHost: View? = null
    private var scrollListenerInstalled = false

    private var attached = false
    private var windowVisible = false
    private var animating = false

    /** Хост попросил паузу ([pauseMotion]): окно может быть ещё видимым, но кадры не нужны. */
    private var hostPaused = false

    private var simTimeSec = MarsSpaceShaderPolicy.STATIC_TIME_SEC
    private var smoothScroll = 0f
    private var rawScrollPx = 0
    private var lastFrameNs = 0L
    private var lastDrawMs = 0L

    /** Один `Choreographer` на вьюху: `getInstance()` — обращение к ThreadLocal на каждый кадр. */
    private var choreographer: Choreographer? = null

    /**
     * Режим голосового орба: заставка становится акустическим гало экрана разговора.
     *
     * В этом режиме (1) маленькая планета на горизонте убирается — настоящий Марс рисует
     * `VoiceModeOrbView` в центре, (2) туманность и центральная корона подмешивают неон фазы
     * ([voiceNeonColor]) с силой [voiceLevel], (3) кадры идут на 60 fps, чтобы свечение дышало
     * в такт каждому слогу, а не дёргалось на 24 кадрах покоя.
     */
    private var voiceOrbMode = false

    /** Амплитуда голоса 0..1 (uniform `uVoiceLevel`): СГЛАЖЕННАЯ, её и видят шейдер и фоллбэк. */
    private var voiceLevel = 0f

    /**
     * Цель сглаживания амплитуды — то, что пришло из контроллера.
     *
     * Контроллер публикует громкость 10–16 раз в секунду ([LocalVoiceController] троттлит её
     * осознанно). Пока шейдер получал это число напрямую, неоновая туманность вспыхивала
     * ступенькой каждые 60–100 мс — при 60 кадрах в секунду это читается как рывки, и хуже
     * всего то, что корона планеты в этот момент уже шла плавно (сглаживание уровня живёт в
     * [OrbMotionPolicy]). Теперь фон догоняет цель по кадрам тем же сглаживанием
     * ([OrbMotionPolicy.smoothLevel]), и оба индикатора дышат синхронно.
     */
    private var voiceLevelTarget = 0f

    /** Неон текущей фазы (uniform `uVoiceNeon`), разложенный по каналам. Догоняет цель в кадре. */
    private var voiceNeonColor = 0
    private var voiceNeonR = 1f
    private var voiceNeonG = 0.24f
    private var voiceNeonB = 0.23f

    /**
     * Целевой неон фазы.
     *
     * Раньше `setVoiceState` присваивал каналы напрямую, и туманность перекрашивалась за один
     * кадр: вместе с орбом это давало ту самую вспышку «красный → голубой». Теперь здесь только
     * цель, а [advanceVoiceNeon] подтягивает к ней текущие каналы тем же экспоненциальным шагом
     * ([VoiceNeonPalette.stepFactor]), что и корона планеты.
     */
    private var targetNeonColor = 0
    private var targetNeonR = 1f
    private var targetNeonG = 0.24f
    private var targetNeonB = 0.23f

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos -> onFrame(frameTimeNanos) }

    private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
        val host = scrollHost ?: return@OnScrollChangedListener
        rawScrollPx = scrollOffsetOf(host)
    }

    init {
        setBackgroundColor(MarsSpaceShaderPolicy.COLOR_VOID_TOP)
        canvasBackdrop.visibility = GONE
        addView(canvasBackdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        createGlSurface()
    }

    // ── Публичный API ───────────────────────────────────────────────────────────────────

    /** Ручной источник скролла: сдвиг ленты в пикселях (например, из `RecyclerView`-обёртки). */
    fun setRawScrollPx(px: Int) {
        rawScrollPx = px.coerceAtLeast(0)
    }

    /** Живой ли сейчас тикер (для тестов и логов). */
    val isAnimating: Boolean get() = animating

    /**
     * Заморозить фон, не трогая GL-поток.
     *
     * Хост зовёт это на `onPause`: окно после паузы ещё какое-то время видимо, а кадры под чужой
     * активностью не нужны. Именно остановка тикера, а не `GLSurfaceView.onPause()`: контекст
     * остаётся живым, на экране — последний нарисованный кадр, и возврат не требует пересборки
     * шейдеров.
     */
    fun pauseMotion() {
        hostPaused = true
        animating = false
        frames().removeFrameCallback(frameCallback)
    }

    /** Продолжить с того же места: время симуляции не сбрасывается, скачка нет. */
    fun resumeMotion() {
        hostPaused = false
        if (!shouldRender()) return
        resumeRendering()
    }

    /** Работает ли Canvas-фоллбэк вместо GPU. */
    val isUsingCanvasFallback: Boolean get() = fallingBack

    /** Следить за скроллом переданного контента (вызывается из [wrap], можно и вручную). */
    fun followScrollOf(content: View) {
        trackedContent = content
        installScrollTracking()
    }

    /**
     * Включить режим голосового орба: заставка сама становится неоновым гало экрана разговора.
     *
     * Идемпотентно. При включении частота кадров поднимается до [MarsSpaceShaderPolicy.SCROLL_FPS]:
     * туманность FBM должна дышать в реальном времени в такт речи, а 24 кадра покоя для этого
     * слишком редки. При выключении ритм возвращается к обычному.
     */
    fun setVoiceOrbMode(enabled: Boolean) {
        if (voiceOrbMode == enabled) return
        voiceOrbMode = enabled
        renderer.voiceOrbMode = MarsSpaceShaderPolicy.voiceOrbUniform(enabled)
        canvasBackdrop.voiceOrbMode = enabled
        // Смена ритма: следующий кадр должен пройти сразу, не дожидаясь старого интервала.
        lastDrawMs = 0L
        requestVoiceFrame()
    }

    /**
     * Уровень голоса 0..1 и неон текущей фазы: неоновое свечение заставки в такт речи.
     *
     * Метод вызывается из колбэка громкости ([VoiceModeOverlay.setLevel]) до 16 раз в секунду,
     * поэтому здесь нет ни аллокаций, ни пересборки шейдеров — только три числа uniform и
     * запрос одного кадра.
     *
     * @param level амплитуда речи; зажимается [MarsSpaceShaderPolicy.voiceLevel] (NaN → 0);
     * @param neonColor ARGB неона фазы: рубиновый/коралловый/янтарный/персиковый.
     */
    fun setVoiceState(level: Float, neonColor: Int) {
        val nextLevel = MarsSpaceShaderPolicy.voiceLevel(level)
        val levelChanged = nextLevel != voiceLevelTarget
        voiceLevelTarget = nextLevel
        // Кадров нет (reduce motion, скрытый слой): сглаживать нечем — ставим ровно, иначе фон
        // остался бы на амплитуде прошлой реплики до первого кадра. Перерисовку в этом случае
        // заказывает requestVoiceFrame ниже — статичным кадром.
        val snapped = !animating && voiceLevel != nextLevel
        if (snapped) setVoiceLevel(nextLevel)

        if (neonColor != targetNeonColor) {
            targetNeonColor = neonColor
            targetNeonR = MarsSpaceShaderPolicy.neonChannel(neonColor, 16)
            targetNeonG = MarsSpaceShaderPolicy.neonChannel(neonColor, 8)
            targetNeonB = MarsSpaceShaderPolicy.neonChannel(neonColor, 0)
            if (!animating || voiceNeonColor == 0) {
                // Кадров нет (reduce motion, скрытый слой) или это самый первый цвет — красить
                // плавно не от чего: фон остался бы на цвете предыдущей фазы до первого кадра.
                snapVoiceNeon()
            }
            requestVoiceFrame()
            return
        }
        if (levelChanged || snapped) requestVoiceFrame()
    }

    /**
     * Плавный шаг амплитуды речи: подтянуть текущую громкость к цели.
     *
     * Шаг — то же сглаживание, что у короны планеты ([OrbMotionPolicy.smoothLevel]): одинаковая
     * атака и одинаковый спад на одном и том же кадре. Иначе два индикатора одного и того же
     * голоса жили бы с разной задержкой и на экране это читалось бы как рассинхрон.
     */
    private fun advanceVoiceLevel(dt: Float) {
        val next = OrbMotionPolicy.smoothLevel(voiceLevel, voiceLevelTarget, dt)
        if (next != voiceLevel) setVoiceLevel(next)
    }

    /** Раздать сглаженную амплитуду шейдеру и Canvas-фоллбэку — одна точка, одно число. */
    private fun setVoiceLevel(value: Float) {
        voiceLevel = value
        renderer.voiceLevel = value
        canvasBackdrop.voiceLevel = value
    }

    /**
     * Плавный шаг неона фазы: подтянуть текущие каналы к целевым.
     *
     * Вызывается из кадрового цикла до отрисовки, поэтому uniform `uVoiceNeon` и Canvas-фоллбэк
     * всегда видят одно и то же число. Допуск 1/255 — тот же, что у орба: экспонента не достигает
     * цели никогда, а держать цикл кадров за последним битом цвета незачем.
     */
    private fun advanceVoiceNeon(dt: Float) {
        if (!neonTransitioning()) return
        val k = VoiceNeonPalette.stepFactor(dt)
        voiceNeonR += (targetNeonR - voiceNeonR) * k
        voiceNeonG += (targetNeonG - voiceNeonG) * k
        voiceNeonB += (targetNeonB - voiceNeonB) * k
        if (abs(targetNeonR - voiceNeonR) <= NEON_SNAP &&
            abs(targetNeonG - voiceNeonG) <= NEON_SNAP &&
            abs(targetNeonB - voiceNeonB) <= NEON_SNAP
        ) {
            voiceNeonR = targetNeonR
            voiceNeonG = targetNeonG
            voiceNeonB = targetNeonB
        }
        publishVoiceNeon()
    }

    /** Поставить неон ровно в цель — статичный кадр, скрытый слой, конец перехода. */
    private fun snapVoiceNeon() {
        voiceNeonR = targetNeonR
        voiceNeonG = targetNeonG
        voiceNeonB = targetNeonB
        voiceNeonColor = targetNeonColor
        renderer.voiceNeonR = voiceNeonR
        renderer.voiceNeonG = voiceNeonG
        renderer.voiceNeonB = voiceNeonB
        canvasBackdrop.voiceNeonColor = voiceNeonColor
    }

    /** Разложить текущие каналы в uniform рендерера и в цвет Canvas-фоллбэка. */
    private fun publishVoiceNeon() {
        renderer.voiceNeonR = voiceNeonR
        renderer.voiceNeonG = voiceNeonG
        renderer.voiceNeonB = voiceNeonB
        // Цвет фоллбэка собирается из тех же каналов, а не интерполируется отдельно: две
        // независимые интерполяции одного цвета разъехались бы по фазе.
        voiceNeonColor = Color.rgb(
            byteChannel(voiceNeonR),
            byteChannel(voiceNeonG),
            byteChannel(voiceNeonB),
        )
        canvasBackdrop.voiceNeonColor = voiceNeonColor
    }

    /** Есть ли ещё расхождение неона с целью (поканально, с допуском [NEON_SNAP]). */
    private fun neonTransitioning(): Boolean =
        abs(targetNeonR - voiceNeonR) > NEON_SNAP ||
            abs(targetNeonG - voiceNeonG) > NEON_SNAP ||
            abs(targetNeonB - voiceNeonB) > NEON_SNAP

    /** Канал 0..1 в байт без риска выйти за границу: `Color.rgb` ждёт 0..255. */
    private fun byteChannel(value: Float): Int = (value * 255f).toInt().coerceIn(0, 255)

    /**
     * Запросить кадр.
     *
     * При живом тикере это только «следующий vsync нужен сразу»: рисовать прямо здесь нельзя —
     * сглаженные uniform'ы двигаются в кадровом цикле, и внеочередной кадр показал бы прошлое
     * значение громкости, добавив лишний кадр вне vsync (рваный ритм вместо ровных 60).
     *
     * Когда тикера нет (reduce motion, скрытый слой) — наоборот, нужен один статичный кадр: иначе
     * фон навсегда остался бы на цвете и громкости прошлой фазы, ведь кадров, которые донесли бы
     * новые uniform'ы, не будет.
     */
    private fun requestVoiceFrame() {
        if (!animating) {
            drawStaticFrame()
            return
        }
        lastDrawMs = 0L
    }

    // ── GL ─────────────────────────────────────────────────────────────────────────────

    private fun createGlSurface() {
        val glVersion = reportedGlEsVersion()
        if (glVersion < ES2_VERSION) {
            // Совсем без OpenGL (эмулятор без GL, урезанный рендер) — сразу Canvas.
            switchToCanvasFallback()
            return
        }
        val es3 = glVersion >= ES3_VERSION
        val view = GLSurfaceView(context)
        view.setEGLContextClientVersion(if (es3) 3 else 2)
        // Без depth/stencil: шейдеру нужен только цвет — экономия памяти и пропускной способности.
        view.setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        view.setPreserveEGLContextOnPause(false)
        renderer.es3 = es3
        view.setRenderer(renderer)
        view.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        view.holder.setFormat(PixelFormat.OPAQUE)
        addView(view, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        glView = view
    }

    private fun reportedGlEsVersion(): Int {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return try {
            manager?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        } catch (e: Exception) {
            0
        }
    }

    private fun switchToCanvasFallback() {
        if (fallingBack) return
        fallingBack = true
        animating = false
        frames().removeFrameCallback(frameCallback)
        glView?.let { view ->
            glPause()
            removeView(view)
        }
        glView = null
        canvasBackdrop.visibility = VISIBLE
        canvasBackdrop.setFrame(MarsSpaceShaderPolicy.STATIC_TIME_SEC, smoothScroll)
        canvasBackdrop.invalidate()
        // Спрятанный слой кадров не просит: `shouldRender()` учитывает и видимость предков.
        if (shouldRender()) resumeRendering()
    }

    private fun glResume() {
        if (glResumed) return
        glView?.onResume()
        glResumed = true
    }

    private fun glPause() {
        if (!glResumed) return
        glResumed = false
        glView?.onPause()
    }

    // ── Размер и плотность буфера ──────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val scale = MarsSpaceShaderPolicy.renderScale(w, h)
        val bufferWidth = max(1, (w * scale).toInt())
        val bufferHeight = max(1, (h * scale).toInt())
        // Уменьшённый буфер растягивается на весь вью билинейно — газ этого не замечает.
        glView?.holder?.setFixedSize(bufferWidth, bufferHeight)
        renderer.surfaceWidth = bufferWidth
        renderer.surfaceHeight = bufferHeight
    }

    // ── Кадровый цикл ──────────────────────────────────────────────────────────────────

    private fun onFrame(frameTimeNanos: Long) {
        if (!animating) return
        val nowMs = frameTimeNanos / 1_000_000L
        val dt = if (lastFrameNs == 0L) {
            1f / MarsSpaceShaderPolicy.SCROLL_FPS
        } else {
            MarsSpaceShaderPolicy.clampFrameDt((frameTimeNanos - lastFrameNs) / 1_000_000_000f)
        }
        lastFrameNs = frameTimeNanos
        simTimeSec += dt
        // Неон фазы перетекает из состояния в состояние тем же экспоненциальным шагом, что и
        // корона планеты: иначе 3D-туманность перекрашивалась бы за один кадр и вспыхивала.
        advanceVoiceNeon(dt)
        // Амплитуда речи догоняет цель тем же шагом: контроллер присылает её 10–16 раз в секунду,
        // и без сглаживания газ вспыхивал бы ступенькой на каждом пакете.
        advanceVoiceLevel(dt)
        smoothScroll = MarsSpaceShaderPolicy.smoothScroll(rawScrollPx, smoothScroll, dt)
        val scrolling = MarsSpaceShaderPolicy.isScrolling(rawScrollPx, smoothScroll)
        // Голосовой орб — такая же «живая» сцена, как скролл: неоновая туманность обязана
        // обновляться каждый vsync, иначе свечение в такт речи шло бы рывками 24 кадра.
        val fast = scrolling || voiceOrbMode
        if (MarsSpaceShaderPolicy.shouldDrawFrame(lastDrawMs, nowMs, fast)) {
            lastDrawMs = nowMs
            drawFrame(simTimeSec, smoothScroll)
        }
        frames().postFrameCallback(frameCallback)
    }

    private fun drawFrame(timeSec: Float, scroll: Float) {
        val view = glView
        if (view != null) {
            renderer.timeSec = timeSec
            renderer.scroll = scroll
            view.requestRender()
        } else {
            canvasBackdrop.setFrame(timeSec, scroll)
            canvasBackdrop.invalidate()
        }
    }

    private fun drawStaticFrame() {
        smoothScroll = 0f
        // Статичный кадр кадров не имеет — догонять амплитуду нечем, ставим её ровно в цель.
        if (voiceLevel != voiceLevelTarget) setVoiceLevel(voiceLevelTarget)
        if (neonTransitioning()) snapVoiceNeon()
        drawFrame(MarsSpaceShaderPolicy.STATIC_TIME_SEC, 0f)
    }

    /**
     * Можно ли вообще рисовать: вьюха в окне, окно видимо, её цепочка предков не спрятана и хост не
     * просил паузу.
     *
     * Одно место вместо трёх разных наборов условий: раньше [onAttachedToWindow] проверял
     * `isShown`, а [switchToCanvasFallback] — только `attached && windowVisible`, поэтому
     * спрятанный слой (корень голосового режима в `GONE`) продолжал крутить кадры после падения
     * GL на Canvas-фоллбэк.
     */
    private fun shouldRender(): Boolean =
        attached && windowVisible && isShown && !hostPaused

    private fun resumeRendering() {
        if (!shouldRender()) return
        glResume()
        val scale = animatorScale()
        val animate = MarsSpaceShaderPolicy.shouldAnimate(
            animatorsEnabled = scale > 0f,
            powerSave = isPowerSave(),
            animatorScale = scale,
        )
        if (animate) {
            if (!animating) {
                animating = true
                lastFrameNs = 0L
                lastDrawMs = 0L
                frames().postFrameCallback(frameCallback)
            }
        } else {
            // Reduce motion / энергосбережение: один выразительный статичный кадр и тишина.
            animating = false
            frames().removeFrameCallback(frameCallback)
            drawStaticFrame()
        }
    }

    private fun pauseRendering() {
        animating = false
        frames().removeFrameCallback(frameCallback)
        glPause()
    }

    /** Один `Choreographer` на вьюху: `getInstance()` — обращение к ThreadLocal, а кадров 60 в секунду. */
    private fun frames(): Choreographer = choreographer ?: Choreographer.getInstance().also {
        choreographer = it
    }

    private fun animatorScale(): Float = try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    } catch (e: Exception) {
        1f
    }

    private fun isPowerSave(): Boolean {
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return manager?.isPowerSaveMode == true
    }

    // ── Жизненный цикл ────────────────────────────────────────────────────────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        windowVisible = windowVisibility == VISIBLE
        // Новое окно — новая жизнь: пауза прошлого окна к этому отношения не имеет.
        hostPaused = false
        // Перевешиваем слушатель скролла на «настоящий» ViewTreeObserver: до attach он
        // плавающий, и подписка, сделанная в wrap(), молча теряется при вложении в окно.
        removeScrollTracking()
        installScrollTracking()
        // Голосовой слой приезжает в окно уже скрытым (`GONE`): кадры не нужны, пока его не
        // покажут, иначе GL-поток грел бы телефон под лентой чата.
        if (shouldRender()) resumeRendering() else pauseRendering()
    }

    override fun onDetachedFromWindow() {
        attached = false
        hostPaused = false
        pauseRendering()
        removeScrollTracking()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        val visible = visibility == VISIBLE
        if (visible == windowVisible) return
        windowVisible = visible
        if (!attached) return
        if (visible) resumeRendering() else pauseRendering()
    }

    /**
     * Видимость самой вьюхи (а не окна).
     *
     * Голосовой слой живёт в дереве постоянно и просто прячется через `GONE`: окно при этом
     * остаётся видимым, и `onWindowVisibilityChanged` не приходит. Без этой проверки фон крутил бы
     * 60 кадров шейдера, пока человек читает чат. `onVisibilityChanged` приходит и на предков,
     * поэтому скрытие корня слоя честно гасит GL-поток.
     */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!attached) return
        // `isShown` учитывает всю цепочку предков: скрытый корень слоя гасит фон, даже если у
        // самой вьюхи visibility осталась VISIBLE.
        if (shouldRender()) resumeRendering() else pauseRendering()
    }

    // ── Скролл контента ───────────────────────────────────────────────────────────────

    private fun installScrollTracking() {
        val content = trackedContent ?: return
        val host = findScrollHost(content) ?: return
        if (scrollListenerInstalled && scrollHost === host) {
            rawScrollPx = scrollOffsetOf(host)
            return
        }
        removeScrollTracking()
        scrollHost = host
        host.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        scrollListenerInstalled = true
        rawScrollPx = scrollOffsetOf(host)
    }

    private fun removeScrollTracking() {
        if (scrollListenerInstalled) {
            val host = scrollHost
            if (host != null) {
                val observer = host.viewTreeObserver
                if (observer.isAlive) observer.removeOnScrollChangedListener(scrollListener)
            }
        }
        scrollListenerInstalled = false
        scrollHost = null
    }

    private fun findScrollHost(root: View): View? {
        if (root is ScrollView || root is NestedScrollView) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            val found = findScrollHost(root.getChildAt(index))
            if (found != null) return found
        }
        return null
    }

    private fun scrollOffsetOf(view: View): Int = view.scrollY.coerceAtLeast(0)

    // ── GPU-рендерер ──────────────────────────────────────────────────────────────────

    private inner class SpaceRenderer : GLSurfaceView.Renderer {

        var es3 = true
        var timeSec = MarsSpaceShaderPolicy.STATIC_TIME_SEC
        var scroll = 0f
        var surfaceWidth = 1
        var surfaceHeight = 1

        /** Голосовой режим и неон: пишутся с UI-потока, читаются на GL-потоке (float/boolean). */
        var voiceOrbMode = 0f
        var voiceLevel = 0f
        var voiceNeonR = 1f
        var voiceNeonG = 0.24f
        var voiceNeonB = 0.23f

        private var programId = 0
        private var aPosLocation = -1
        private var uResLocation = -1
        private var uTimeLocation = -1
        private var uScrollLocation = -1
        private var uVoiceLevelLocation = -1
        private var uVoiceNeonLocation = -1
        private var uVoiceOrbModeLocation = -1
        private var quad: FloatBuffer? = null
        private var ready = false

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glClearColor(0.004f, 0.006f, 0.014f, 1f)
            val vertex = if (es3) {
                MarsSpaceShaderPolicy.VERTEX_SHADER_300
            } else {
                MarsSpaceShaderPolicy.VERTEX_SHADER_100
            }
            val fragment = if (es3) {
                MarsSpaceShaderPolicy.FRAGMENT_SHADER_300
            } else {
                MarsSpaceShaderPolicy.FRAGMENT_SHADER_100
            }
            programId = linkProgram(vertex, fragment)
            if (programId == 0) {
                ready = false
                post { switchToCanvasFallback() }
                return
            }
            uResLocation = GLES20.glGetUniformLocation(programId, "uRes")
            uTimeLocation = GLES20.glGetUniformLocation(programId, "uTime")
            uScrollLocation = GLES20.glGetUniformLocation(programId, "uScroll")
            uVoiceLevelLocation = GLES20.glGetUniformLocation(programId, "uVoiceLevel")
            uVoiceNeonLocation = GLES20.glGetUniformLocation(programId, "uVoiceNeon")
            uVoiceOrbModeLocation = GLES20.glGetUniformLocation(programId, "uVoiceOrbMode")
            if (!es3) {
                aPosLocation = GLES20.glGetAttribLocation(programId, "aPos")
                quad = fullScreenQuad()
            }
            ready = true
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidth = max(1, width)
            surfaceHeight = max(1, height)
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (!ready || programId == 0) return
            GLES20.glUseProgram(programId)
            GLES20.glUniform2f(uResLocation, surfaceWidth.toFloat(), surfaceHeight.toFloat())
            GLES20.glUniform1f(uTimeLocation, timeSec)
            GLES20.glUniform1f(uScrollLocation, scroll)
            // Голосовое гало: те же числа, что пришли из VoiceModeOverlay.setLevel/setVoiceState.
            GLES20.glUniform1f(uVoiceLevelLocation, voiceLevel)
            GLES20.glUniform3f(uVoiceNeonLocation, voiceNeonR, voiceNeonG, voiceNeonB)
            GLES20.glUniform1f(uVoiceOrbModeLocation, voiceOrbMode)
            if (es3) {
                // Три вершины выводятся из gl_VertexID — ни одного буфера на кадр.
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
            } else {
                val buffer = quad ?: return
                buffer.position(0)
                GLES20.glEnableVertexAttribArray(aPosLocation)
                GLES20.glVertexAttribPointer(aPosLocation, 2, GLES20.GL_FLOAT, false, 0, buffer)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(aPosLocation)
            }
        }

        private fun fullScreenQuad(): FloatBuffer = ByteBuffer
            .allocateDirect(QUAD_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD_COORDS)
                position(0)
            }

        private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
            val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource, "vertex")
            if (vertex == 0) return 0
            val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource, "fragment")
            if (fragment == 0) {
                GLES20.glDeleteShader(vertex)
                return 0
            }
            val program = GLES20.glCreateProgram()
            if (program == 0) {
                GLES20.glDeleteShader(vertex)
                GLES20.glDeleteShader(fragment)
                return 0
            }
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                Log.w(TAG, "GL program link failed: " + GLES20.glGetProgramInfoLog(program))
                GLES20.glDeleteProgram(program)
                return 0
            }
            return program
        }

        private fun compileShader(type: Int, source: String, label: String): Int {
            val shader = GLES20.glCreateShader(type)
            if (shader == 0) return 0
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.w(TAG, "GL $label shader compile failed: " + GLES20.glGetShaderInfoLog(shader))
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }
    }

    // ── Canvas 2D фоллбэк ─────────────────────────────────────────────────────────────

    /**
     * Запасной художник: та же палитра и та же композиция (космос, туманность, звёзды, кометы,
     * Марс на горизонте), но без GLSL. Дороже на кадр, зато работает везде, включая устройства
     * без OpenGL и случаи, когда драйвер отказался собирать шейдер.
     */
    private inner class CanvasBackdrop(context: Context) : View(context) {

        private var timeSec = MarsSpaceShaderPolicy.STATIC_TIME_SEC
        private var scroll = 0f

        /** Голосовое гало в фоллбэке: те же три величины, что уходят в uniform шейдера. */
        var voiceOrbMode = false
        var voiceLevel = 0f
        var voiceNeonColor = 0

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        /** Отдельная кисть под неоновую корону: пульс идёт её `alpha`, градиент переиспользуется. */
        private val neonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var neonShader: RadialGradient? = null
        private var neonShaderColor = 0
        private var neonShaderRadius = -1f
        private var neonShaderCx = -1f
        private var neonShaderCy = -1f

        private val arcBounds = RectF()
        private val planetClip = Path()

        fun setFrame(time: Float, scrollValue: Float) {
            timeSec = time
            scroll = scrollValue
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w < 2f || h < 2f) return
            val minDim = min(w, h)

            drawVoid(canvas, w, h)
            drawNebula(canvas, w, h, minDim)
            drawStars(canvas, w, h, minDim)
            drawComets(canvas, w, h)
            // В голосовом режиме маленькая планета на горизонте уступает место орбу в центре.
            if (!voiceOrbMode) drawMars(canvas, w, h, minDim)
            drawVoiceNeon(canvas, w, h, minDim)
        }

        /**
         * Неоновая корона голосового экрана: центральное рэлеевское свечение той же палитры,
         * что `radialGlow` в шейдере, с дыханием от [voiceLevel].
         *
         * Без GPU это единственный способ показать, что фон слышит речь, — поэтому пульс здесь
         * читается даже сильнее, чем в шейдере: у фоллбэка нет объёмной туманности.
         *
         * Градиент кэшируется по цвету и геометрии, а пульс идёт через `Paint.alpha`. Раньше
         * `RadialGradient` собирался НА КАЖДОМ КАДРЕ: четыре цвета плюс нативный шейдер 60 раз в
         * секунду — это и мусор в молодом поколении, и заметный шаг по времени кадра ровно в тот
         * момент, когда человек говорит.
         */
        private fun drawVoiceNeon(canvas: Canvas, w: Float, h: Float, minDim: Float) {
            val gate = if (voiceOrbMode) 1f else voiceLevel
            if (gate <= 0.01f) return
            val pulse = 0.28f + 0.95f * voiceLevel
            val alpha = (0.55f * gate * pulse).coerceIn(0f, 1f)
            val neon = if (voiceNeonColor == 0) VoiceNeonPalette.IDLE else voiceNeonColor
            val cx = w * 0.5f
            val cy = h * 0.5f
            val radius = minDim * 0.78f
            if (neonShader == null ||
                neonShaderColor != neon ||
                neonShaderRadius != radius ||
                neonShaderCx != cx ||
                neonShaderCy != cy
            ) {
                val red = Color.red(neon)
                val green = Color.green(neon)
                val blue = Color.blue(neon)
                neonShader = RadialGradient(
                    cx, cy, radius,
                    intArrayOf(
                        Color.argb(235, red, green, blue),
                        Color.argb(120, red, green, blue),
                        Color.argb(45, red, green, blue),
                        Color.TRANSPARENT,
                    ),
                    floatArrayOf(0f, 0.34f, 0.66f, 1f),
                    Shader.TileMode.CLAMP,
                ).also { neonPaint.shader = it }
                neonShaderColor = neon
                neonShaderRadius = radius
                neonShaderCx = cx
                neonShaderCy = cy
            }
            neonPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, radius, neonPaint)
        }

        private fun drawVoid(canvas: Canvas, w: Float, h: Float) {
            fillPaint.style = Paint.Style.FILL
            fillPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                MarsSpaceShaderPolicy.COLOR_VOID_TOP,
                MarsSpaceShaderPolicy.COLOR_VOID_BOTTOM,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, fillPaint)
            fillPaint.shader = null
        }

        private fun drawNebula(canvas: Canvas, w: Float, h: Float, minDim: Float) {
            val drift = scroll * 0.03f * minDim
            blob(
                canvas,
                w * 0.26f, h * 0.30f + drift, minDim * 0.78f,
                0x55751A1F.toInt(), 0x22751A1F.toInt(),
            )
            blob(
                canvas,
                w * 0.78f, h * 0.62f - drift, minDim * 0.66f,
                0x4A854214.toInt(), 0x18854214.toInt(),
            )
            blob(
                canvas,
                w * 0.62f, h * 0.14f + drift * 0.6f, minDim * 0.52f,
                0x3D3D1438, 0x103D1438,
            )
        }

        private fun blob(canvas: Canvas, cx: Float, cy: Float, radius: Float, core: Int, edge: Int) {
            glowPaint.style = Paint.Style.FILL
            glowPaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(core, edge, Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, radius, glowPaint)
            glowPaint.shader = null
        }

        private fun drawStars(canvas: Canvas, w: Float, h: Float, minDim: Float) {
            var seed = 0x5EED2A17L
            fillPaint.style = Paint.Style.FILL
            for (index in 0 until STAR_COUNT) {
                seed = seed * 1664525L + 1013904223L
                val rx = ((seed ushr 8) and 0xFFFFL).toFloat() / 65535f
                seed = seed * 1664525L + 1013904223L
                val ry = ((seed ushr 8) and 0xFFFFL).toFloat() / 65535f
                seed = seed * 1664525L + 1013904223L
                val rz = ((seed ushr 8) and 0xFFFFL).toFloat() / 65535f
                val x = rx * w
                val y = ((ry * h + scroll * 0.02f * minDim) % h + h) % h
                val twinkle = 0.55f + 0.45f * sin(timeSec * 1.7f + rz * 6.283f)
                val alpha = (twinkle * 205f).toInt().coerceIn(0, 255)
                fillPaint.color = Color.argb(alpha, 255, 219, 189)
                canvas.drawCircle(x, y, 0.45f + rz * 1.55f, fillPaint)
            }
        }

        private fun drawComets(canvas: Canvas, w: Float, h: Float) {
            for (index in 0 until 2) {
                val period = 11f + index * 4.5f
                val phase = ((timeSec / period) + index * 0.37f) % 1f
                val life = smoothstep(0f, 0.10f, phase) * (1f - smoothstep(0.74f, 0.96f, phase))
                if (life <= 0.02f) continue
                val headX = w * (0.10f + phase * 0.72f)
                val headY = h * (0.04f + phase * 0.52f)
                val tailX = headX - w * 0.22f
                val tailY = headY - h * 0.16f
                glowPaint.style = Paint.Style.STROKE
                glowPaint.strokeWidth = 2.2f + index
                glowPaint.strokeCap = Paint.Cap.ROUND
                glowPaint.shader = LinearGradient(
                    tailX, tailY, headX, headY,
                    Color.argb(0, 245, 92, 46),
                    Color.argb((210 * life).toInt(), 255, 226, 192),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawLine(tailX, tailY, headX, headY, glowPaint)
                glowPaint.shader = null
                fillPaint.style = Paint.Style.FILL
                fillPaint.color = Color.argb((235 * life).toInt(), 255, 244, 228)
                canvas.drawCircle(headX, headY, 2.6f, fillPaint)
            }
        }

        private fun drawMars(canvas: Canvas, w: Float, h: Float, minDim: Float) {
            val cx = w * 0.5f + MarsSpaceShaderPolicy.PLANET_CENTER_X * minDim
            val cy = h * 0.5f -
                MarsSpaceShaderPolicy.PLANET_CENTER_Y * minDim -
                scroll * MarsSpaceShaderPolicy.PLANET_SCROLL_FACTOR * minDim
            val radius = MarsSpaceShaderPolicy.PLANET_RADIUS * minDim
            if (radius < 2f) return

            // Пылевая атмосфера: тёплое рэлеевское гало вокруг диска.
            glowPaint.style = Paint.Style.FILL
            glowPaint.shader = RadialGradient(
                cx, cy, radius * 1.45f,
                intArrayOf(
                    Color.TRANSPARENT,
                    0x66EB4724.toInt(),
                    0x33F55C2E.toInt(),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.66f, 0.80f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, radius * 1.45f, glowPaint)
            glowPaint.shader = null

            // Ночная сторона — база диска.
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = MarsSpaceShaderPolicy.COLOR_NIGHT_SIDE
            canvas.drawCircle(cx, cy, radius, fillPaint)

            // Освещённая поверхность: градиент от лимба к терминатору + кратеры и бури.
            canvas.save()
            planetClip.reset()
            planetClip.addCircle(cx, cy, radius, Path.Direction.CW)
            canvas.clipPath(planetClip)
            fillPaint.shader = RadialGradient(
                cx - radius * 0.44f, cy - radius * 0.48f, radius * 1.62f,
                intArrayOf(
                    MarsSpaceShaderPolicy.COLOR_SURFACE_LIT,
                    MarsSpaceShaderPolicy.COLOR_SURFACE_DARK,
                    0xFF2A0A06.toInt(),
                ),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, radius, fillPaint)
            fillPaint.shader = null

            var seed = 0x1A2B3C4DL
            for (index in 0 until CRATER_COUNT) {
                seed = seed * 1664525L + 1013904223L
                val rx = ((seed ushr 9) and 0xFFFFL).toFloat() / 65535f
                seed = seed * 1664525L + 1013904223L
                val ry = ((seed ushr 9) and 0xFFFFL).toFloat() / 65535f
                val craterX = cx + (rx - 0.5f) * radius * 1.7f
                val craterY = cy + (ry - 0.5f) * radius * 1.7f
                val craterR = radius * (0.05f + rx * 0.12f)
                fillPaint.color = 0x55200A06
                canvas.drawCircle(craterX, craterY, craterR, fillPaint)
                fillPaint.color = 0x44FF9A62
                canvas.drawCircle(craterX - craterR * 0.22f, craterY - craterR * 0.22f, craterR * 0.55f, fillPaint)
            }

            // Пылевая буря: полупрозрачная взвесь над освещённой частью.
            fillPaint.shader = RadialGradient(
                cx - radius * 0.30f, cy + radius * 0.18f, radius * 0.95f,
                intArrayOf(0x55864214, Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx - radius * 0.30f, cy + radius * 0.18f, radius * 0.95f, fillPaint)
            fillPaint.shader = null
            canvas.restore()

            // Рубиново-огненный серп на лимбе, ярче со стороны света.
            glowPaint.style = Paint.Style.STROKE
            glowPaint.strokeWidth = max(1.4f, radius * 0.040f)
            glowPaint.color = 0x55F55C2E
            canvas.drawCircle(cx, cy, radius * 0.988f, glowPaint)
            glowPaint.color = 0xCCFF8A4A.toInt()
            arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(arcBounds, 148f, 112f, false, glowPaint)
            glowPaint.style = Paint.Style.FILL
        }

        private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            if (edge1 <= edge0) return if (x < edge0) 0f else 1f
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }

    companion object {
        private const val TAG = "MarsSpaceBg"

        /** `reqGlEsVersion` для ES 2.0 и ES 3.0 — те же коды, что в `ActivityManager`. */
        private const val ES2_VERSION = 0x00020000
        private const val ES3_VERSION = 0x00030000

        private val QUAD_COORDS = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private const val STAR_COUNT = 150
        private const val CRATER_COUNT = 9

        /**
         * Допуск схождения неона: 1/255 — меньше одного шага байта канала.
         *
         * Экспонента не достигает цели никогда, поэтому на этом расстоянии каналы ставятся ровно
         * в цель — иначе переход «тикал» бы ещё секунды после того, как разница стала невидимой.
         */
        private const val NEON_SNAP = 1f / 255f

        /**
         * Оборачивает корневую View экрана в FrameLayout с живым космическим фоном Марса позади
         * контента. Идемпотентно: повторный вызов для уже обёрнутого контента возвращает тот же
         * контейнер и не плодит лишние GL-поверхности.
         *
         * Фон занимает весь контейнер и не участвует в кликах (`isClickable = false`), контент
         * лежит поверх вторым ребёнком с `MATCH_PARENT`, поэтому вёрстка экрана не меняется.
         */
        @JvmStatic
        fun wrap(context: Context, contentView: View): FrameLayout {
            val existingParent = contentView.parent as? ViewGroup
            if (existingParent is FrameLayout &&
                existingParent.childCount == 2 &&
                existingParent.getChildAt(0) is MarsSpaceBackgroundView &&
                existingParent.getChildAt(1) === contentView
            ) {
                return existingParent
            }

            val container = FrameLayout(context)
            container.clipToPadding = false

            val background = MarsSpaceBackgroundView(context)
            background.isClickable = false
            background.isFocusable = false
            background.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val fullScreen = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            container.addView(background, fullScreen)
            container.addView(
                contentView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            background.followScrollOf(contentView)

            if (existingParent != null) {
                val index = existingParent.indexOfChild(contentView)
                val params = contentView.layoutParams
                existingParent.removeViewAt(index)
                if (params != null) {
                    existingParent.addView(container, index, params)
                } else {
                    existingParent.addView(container, index)
                }
            }
            return container
        }
    }
}
