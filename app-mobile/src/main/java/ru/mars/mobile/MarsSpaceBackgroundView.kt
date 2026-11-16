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
 *    кадра GPU не работает вообще.
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

    private var simTimeSec = MarsSpaceShaderPolicy.STATIC_TIME_SEC
    private var smoothScroll = 0f
    private var rawScrollPx = 0
    private var lastFrameNs = 0L
    private var lastDrawMs = 0L

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

    /** Работает ли Canvas-фоллбэк вместо GPU. */
    val isUsingCanvasFallback: Boolean get() = fallingBack

    /** Следить за скроллом переданного контента (вызывается из [wrap], можно и вручную). */
    fun followScrollOf(content: View) {
        trackedContent = content
        installScrollTracking()
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
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        glView?.let { view ->
            glPause()
            removeView(view)
        }
        glView = null
        canvasBackdrop.visibility = VISIBLE
        canvasBackdrop.setFrame(MarsSpaceShaderPolicy.STATIC_TIME_SEC, smoothScroll)
        canvasBackdrop.invalidate()
        if (attached && windowVisible) resumeRendering()
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
        smoothScroll = MarsSpaceShaderPolicy.smoothScroll(rawScrollPx, smoothScroll, dt)
        val scrolling = MarsSpaceShaderPolicy.isScrolling(rawScrollPx, smoothScroll)
        if (MarsSpaceShaderPolicy.shouldDrawFrame(lastDrawMs, nowMs, scrolling)) {
            lastDrawMs = nowMs
            drawFrame(simTimeSec, smoothScroll)
        }
        Choreographer.getInstance().postFrameCallback(frameCallback)
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
        drawFrame(MarsSpaceShaderPolicy.STATIC_TIME_SEC, 0f)
    }

    private fun resumeRendering() {
        if (!attached || !windowVisible) return
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
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        } else {
            // Reduce motion / энергосбережение: один выразительный статичный кадр и тишина.
            animating = false
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            drawStaticFrame()
        }
    }

    private fun pauseRendering() {
        animating = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        glPause()
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
        // Перевешиваем слушатель скролла на «настоящий» ViewTreeObserver: до attach он
        // плавающий, и подписка, сделанная в wrap(), молча теряется при вложении в окно.
        removeScrollTracking()
        installScrollTracking()
        resumeRendering()
    }

    override fun onDetachedFromWindow() {
        attached = false
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

        private var programId = 0
        private var aPosLocation = -1
        private var uResLocation = -1
        private var uTimeLocation = -1
        private var uScrollLocation = -1
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

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
            drawMars(canvas, w, h, minDim)
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
