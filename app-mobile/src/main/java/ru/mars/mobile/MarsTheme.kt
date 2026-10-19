package ru.mars.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Mars WebUI Design System Parity.
 * Pure Obsidian Dark Theme (#07080B) with Ruby Mars (#EF4444) accents,
 * subtle micro-borders (#1E2433), floating glass capsules and zero emojis.
 *
 * Пульсирует ТОЛЬКО альфа ореол-слоя. У пульсирующих точек ореол строится из
 * НЕПРОЗРАЧНОГО тона, а его альфу несёт сам drawable (`GradientDrawable.alpha`):
 * `LayerDrawable.setLayerAlpha` — скрытый API платформы (в API 34 его нет), и
 * альфа самого слоя — единственный публичный путь. Непульсирующие тона идут
 * прежним путём ([MarsGradients.glowRing]) — их вид не меняется ни на байт.
 */
object MarsTheme {
    // Primary palette
    const val BG = 0xff07080b.toInt()
    const val SIDEBAR = 0xff0c0e14.toInt()
    const val SURFACE = 0xff10131b.toInt()
    const val SURFACE_SUBTLE = 0xff141822.toInt()
    const val SURFACE_CARD = 0xff161b28.toInt()
    
    // Borders
    const val BORDER = 0xff212634.toInt()
    const val BORDER_SUBTLE = 0xff171c26.toInt()
    const val BORDER_MUTED = 0xff283042.toInt()
    /** Thin hairline border for floating modal surfaces (dialogs, sheets). */
    const val BORDER_DIALOG = 0xff1e2433.toInt()
    
    // Accent Ruby
    const val ACCENT = 0xffef4444.toInt()
    const val ACCENT_HOVER = 0xfff87171.toInt()
    const val ACCENT_BG = 0x26ef4444.toInt() // 15% alpha
    /** 20%-alpha accent border for accent-tinted pills/buttons (WebUI `rgba(239,68,68,.2)`). */
    const val ACCENT_BORDER = 0x33ef4444.toInt()
    const val ACCENT_TEXT = 0xfffca5a5.toInt()

    /** Standard 20% white ripple for every interactive surface (buttons, icon buttons). */
    const val RIPPLE = 0x33ffffff.toInt()
    
    // Text colors
    const val TEXT = 0xfff1f5f9.toInt()
    const val TEXT_STRONG = 0xffffffff.toInt()
    const val TEXT_MUTED = 0xff8b95a5.toInt()
    const val TEXT_EM = 0xffcbd5e1.toInt()

    // Thoughts (blue subtle tone for reasoning accordion)
    const val THOUGHT_BG = 0x143b82f6.toInt()
    const val THOUGHT_BORDER = 0x333b82f6.toInt()
    
    // Chat & Code
    const val USER_BUBBLE = 0xff1c2436.toInt()
    const val USER_BORDER = 0xff2b374e.toInt()
    const val BOT_CARD = 0xff0f121a.toInt()
    const val BOT_BORDER = 0xff1b2230.toInt()
    const val CODE_BG = 0xff090b10.toInt()
    const val CODE_BORDER = 0xff1c2331.toInt()
    
    // Status colors
    const val STATUS_ONLINE = 0xff22c55e.toInt()
    const val STATUS_ERROR = 0xffef4444.toInt()
    const val STATUS_WARNING = 0xfff59e0b.toInt()
    const val STATUS_INFO = 0xff38bdf8.toInt()

    fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    // =====================================================================
    // Кэш шаблонов Drawable (горячий путь рендера чата)
    // =====================================================================
    /**
     * Кэшировать Drawable по параметрам безопасно ТОЛЬКО в форме «шаблон +
     * независимая копия»: один и тот же экземпляр Drawable нельзя разделять
     * между View — setBackground привязывает к нему callback и bounds
     * конкретной View, и при переиспользовании экземпляра bounds последних
     * layout-проходов перезаписывают предыдущие (артефакты отрисовки), а
     * callback удерживает View (утечка). setCallback(null) от bounds не
     * спасает, поэтому разделяемый экземпляр не кэшируется вообще.
     *
     * Вместо этого кэш хранит полностью настроенные «шаблоны» (GradientDrawable
     * не держит Context — dp() сводится к float-параметрам), а наружу
     * выдаётся копия `constantState.newDrawable().mutate()`: mutate() даёт
     * копии собственный GradientState, шаблон остаётся нетронутым, у каждой
     * View свой экземпляр — визуальных отличий от свежепостроенного drawable
     * нет. В ключ включён densityDpi: параметры зависят от плотности, кэш не
     * должен протекать между конфигурациями. Ограничение размера — LRU.
     *
     * RippleDrawable (button/circleIconButton) НЕ кэшируется даже шаблонами:
     * ripple хранит per-view состояние нажатия/hotspot, а эти фабрики
     * вызываются один раз на настройку контрола, а не на кадр стрима.
     */
    private const val TEMPLATE_CACHE_MAX = 32
    private const val KIND_PILL = 1
    private const val KIND_CARD = 2
    private const val KIND_GRADIENT_CARD = 3
    private const val KIND_OVAL = 4
    private const val KIND_GLOW_BACKDROP = 5
    private const val KIND_STATUS_BADGE = 6

    private data class DrawableKey(
        val kind: Int,
        val densityDpi: Int,
        val a: Int = 0,
        val b: Int = 0,
        val c: Int = 0,
        val d: Int = 0,
        val e: Int = 0,
        val f: Int = 0,
    )

    private val drawableTemplates = ThreadSafeLru<DrawableKey, GradientDrawable>(TEMPLATE_CACHE_MAX)

    private fun cachedGradient(
        key: DrawableKey,
        build: () -> GradientDrawable,
    ): GradientDrawable {
        val template = drawableTemplates.get(key) ?: build().also { drawableTemplates.put(key, it) }
        // Если платформа не отдаёт constantState (JVM unit-тесты с
        // returnDefaultValues), fallback — свежая сборка без кэша.
        val copy = template.constantState?.newDrawable()?.mutate() as? GradientDrawable
        return copy ?: build()
    }

    private fun densityKey(context: Context, kind: Int) =
        DrawableKey(kind, context.resources.displayMetrics.densityDpi)

    /** Floating rounded pill / capsule (for composer, model selector, quick chips) */
    fun pill(context: Context, bg: Int, strokeColor: Int = BORDER, strokeWidthDp: Int = 1): GradientDrawable {
        val key = densityKey(context, KIND_PILL).copy(a = bg, b = strokeColor, c = strokeWidthDp)
        return cachedGradient(key) {
            GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(context, 999).toFloat()
                if (strokeWidthDp > 0) setStroke(dp(context, strokeWidthDp), strokeColor)
            }
        }
    }

    /** Rounded card with subtle border */
    fun card(context: Context, bg: Int, radiusDp: Int = 14, strokeColor: Int = BORDER, strokeWidthDp: Int = 1): GradientDrawable {
        val key = densityKey(context, KIND_CARD).copy(a = bg, b = radiusDp, c = strokeColor, d = strokeWidthDp)
        return cachedGradient(key) {
            GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(context, radiusDp).toFloat()
                if (strokeWidthDp > 0) setStroke(dp(context, strokeWidthDp), strokeColor)
            }
        }
    }

    /** Interactive button with ripple effect and rounded corners */
    fun button(context: Context, bg: Int, radiusDp: Int = 10, strokeColor: Int = 0): RippleDrawable {
        val content = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(context, radiusDp).toFloat()
            if (strokeColor != 0) setStroke(dp(context, 1), strokeColor)
        }
        val mask = GradientDrawable().apply {
            setColor(TEXT_STRONG)
            cornerRadius = dp(context, radiusDp).toFloat()
        }
        val rippleColor = ColorStateList.valueOf(RIPPLE)
        return RippleDrawable(rippleColor, content, mask)
    }

    /** Circular action icon button with ripple */
    fun circleIconButton(
        context: Context,
        drawableRes: Int,
        contentDesc: String,
        bg: Int = SURFACE_SUBTLE,
        iconTint: Int = TEXT,
        sizeDp: Int = 40,
        strokeColor: Int = BORDER
    ): ImageButton {
        return ImageButton(context).apply {
            contentDescription = contentDesc
            val normal = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bg)
                if (strokeColor != 0) setStroke(dp(context, 1), strokeColor)
            }
            val mask = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(TEXT_STRONG)
            }
            background = RippleDrawable(ColorStateList.valueOf(RIPPLE), normal, mask)
            setImageDrawable(ContextCompat.getDrawable(context, drawableRes))
            imageTintList = ColorStateList.valueOf(iconTint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dp(context, 10)
            setPadding(pad, pad, pad, pad)
        }
    }

    /**
     * Small circular status dot, tinted with a STATUS_* color — replaces the `●` text glyph
     * (font-dependent metrics/color across OEM skins, same anti-pattern as emojis). The caller
     * sets the layout size (8 dp matches the WebUI status bullets).
     */
    fun statusDot(context: Context, color: Int): View {
        return View(context).apply {
            background = ovalDrawable(context, color)
        }
    }

    /** Кэшируемый шаблон круга: выдаёт независимую копию (см. [cachedGradient]). */
    private fun ovalDrawable(context: Context, color: Int): GradientDrawable =
        cachedGradient(densityKey(context, KIND_OVAL).copy(a = color)) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }

    // =====================================================================
    // Obsidian Depth — layered surfaces & gradient cards (see MarsGradients)
    // =====================================================================

    // Background depth layers: farthest -> nearest (Obsidian Deep Glass ramp).
    /** Deepest app background (obsidian). */
    const val DEPTH_BG = MarsGradients.BG
    /** Base surface layer (screens, panels). */
    const val SURFACE_0 = MarsGradients.SURFACE_0
    /** Raised layer (cards, lists). */
    const val SURFACE_1 = MarsGradients.SURFACE_1
    /** Higher layer (popovers, dialogs). */
    const val SURFACE_2 = MarsGradients.SURFACE_2
    /** Nearest layer (menus, floating capsules). */
    const val SURFACE_3 = MarsGradients.SURFACE_3

    /** Running/done status accents (online/error/warning/info live above). */
    const val STATUS_RUNNING = MarsGradients.STATUS_RUNNING
    const val STATUS_DONE = MarsGradients.STATUS_DONE

    /**
     * Подложка заблокированной карточки канбана: warn-тон 10% поверх поверхности.
     * Токен заведён здесь, а не литералом в экране, — иначе у канбана появляется
     * своя палитра и он расходится с остальными экранами при следующей правке темы.
     */
    val BLOCKED_CARD_BG = MarsGradients.withAlpha(STATUS_WARNING, MarsGradients.BADGE_BG_ALPHA)

    /** Затемнение под полноэкранным просмотром изображения (lightbox). 90% чёрного. */
    const val LIGHTBOX_SCRIM = 0xE6000000.toInt()

    /** Maps a pure [MarsGradients.Angle] onto the Android gradient orientation. */
    private fun orientationOf(angle: MarsGradients.Angle): GradientDrawable.Orientation = when (angle) {
        MarsGradients.Angle.TOP_BOTTOM -> GradientDrawable.Orientation.TOP_BOTTOM
        MarsGradients.Angle.TL_BR -> GradientDrawable.Orientation.TL_BR
        MarsGradients.Angle.LEFT_RIGHT -> GradientDrawable.Orientation.LEFT_RIGHT
    }

    /**
     * Builds the gradient card drawable for a [MarsGradients.GradientSpec]:
     * two-stop LINEAR_GRADIENT fill, hairline stroke, rounded corners (999 dp = capsule).
     */
    fun gradientCard(context: Context, spec: MarsGradients.GradientSpec): GradientDrawable {
        val key = densityKey(context, KIND_GRADIENT_CARD).copy(
            a = spec.startColor,
            b = spec.endColor,
            c = spec.angle.ordinal,
            d = spec.radiusDp,
            e = spec.strokeColor,
            f = spec.strokeWidthDp,
        )
        return cachedGradient(key) {
            val radius = if (spec.isCapsule) dp(context, 999).toFloat() else dp(context, spec.radiusDp).toFloat()
            GradientDrawable(
                orientationOf(spec.angle),
                intArrayOf(spec.startColor, spec.endColor)
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                if (spec.hasStroke) {
                    setStroke(dp(context, spec.strokeWidthDp), spec.strokeColor)
                }
            }
        }
    }

    /** User bubble: deep noble sapphire gradient with a thin rim light. */
    fun userBubble(context: Context): GradientDrawable = gradientCard(context, MarsGradients.USER_BUBBLE)

    /** Bot message card: deep graphite gradient with a micro border. */
    fun botMessageCard(context: Context): GradientDrawable = gradientCard(context, MarsGradients.BOT_CARD)

    /** Thinking ("Мысли") card: translucent sapphire haze gradient. */
    fun thinkingCard(context: Context): GradientDrawable = gradientCard(context, MarsGradients.THINKING_CARD)

    /** Tool call card: matte technical graphite gradient. */
    fun toolCallCard(context: Context): GradientDrawable = gradientCard(context, MarsGradients.TOOL_CARD)

    /** Composer capsule: floating layered pill with a thin frame and a soft glow color. */
    fun composerCapsule(context: Context): GradientDrawable = gradientCard(context, MarsGradients.COMPOSER_CAPSULE)

    /**
     * Soft ambient glow behind a floating surface (e.g. composer): a translucent
     * tinted capsule drawn with an inset, meant as layer 0 of a LayerDrawable.
     */
    fun glowBackdrop(context: Context, glowColor: Int, radiusDp: Int = 999): GradientDrawable {
        val key = densityKey(context, KIND_GLOW_BACKDROP).copy(a = glowColor, b = radiusDp)
        return cachedGradient(key) {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, radiusDp).toFloat()
                setColor(glowColor)
            }
        }
    }

    /**
     * Status dot with an ambient glow ring: solid core + translucent halo of the
     * same hue (vector shapes only — no glyphs). Size the host view 12–14 dp;
     * the core occupies the inner ~55%.
     *
     * Индикаторы живого состояния («выполняется», «онлайн») мягко пульсируют
     * альфой ореола — паритет с WebUI `mars-neon-pulse`. Период и амплитуда
     * берутся из [MarsPulsePolicy], анимация — из [StatusDotPulse]. Сигнатура и
     * габариты вьюхи не меняются; ядро всегда непрозрачно, геометрия и текст не
     * затрагиваются. При выключенных анимациях/экономии батареи/`reduce motion`
     * точка возвращается к прежнему статичному виду.
     */
    fun glowingStatusDot(context: Context, color: Int): View {
        // Овалы — из кэша шаблонов (копии независимы). LayerDrawable сознательно
        // НЕ кэшируется: он ставится background конкретной View, а bounds и
        // callback у Drawable принадлежат одной View — общий экземпляр дал бы
        // пересекающиеся bounds между точками. Сборка LayerDrawable из двух
        // готовых копий дешёвая (массив + insets).
        val role = MarsPulsePolicy.roleForColor(color)
        // У пульсирующей точки ореол — непрозрачный тон, а альфу несёт сам
        // Drawable (публичный `setAlpha`); у остальных — прежний полупрозрачный
        // glowRing, то есть вид непульсирующих точек не меняется.
        // mutate() поверх копии из cachedGradient: копия уже независима, но явный
        // mutate — страховка от общего ConstantState, иначе альфа одного индикатора
        // утекла бы на все точки списка (включая статичные).
        val glow = if (role != null) {
            ovalDrawable(context, color).mutate()
        } else {
            ovalDrawable(context, MarsGradients.glowRing(color))
        }
        val core = ovalDrawable(context, color)
        val inset = dp(context, 3)
        val layers = LayerDrawable(arrayOf(glow, core))
        layers.setLayerInset(1, inset, inset, inset, inset)
        val dot = View(context).apply { background = layers }
        // Пульсируют только живые состояния; остальные тона остаются статичными.
        if (role != null) StatusDotPulse.attach(context, dot, layers, role)
        return dot
    }

    /**
     * Status badge pill drawable for [MarsGradients.Status]: translucent tinted
     * background, hairline stroke of the same hue. Pair with [applyStatusBadge].
     */
    fun statusBadge(context: Context, status: MarsGradients.Status): GradientDrawable {
        val key = densityKey(context, KIND_STATUS_BADGE).copy(a = status.ordinal)
        return cachedGradient(key) {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 999).toFloat()
                setColor(status.badge.bg)
                setStroke(dp(context, 1), status.badge.stroke)
            }
        }
    }

    /**
     * Styles [view] as a status badge: ASCII label, contrasting opaque text,
     * translucent pill background and a comfortable tap-safe padding.
     */
    fun applyStatusBadge(view: TextView, status: MarsGradients.Status) {
        view.text = status.badge.label
        view.setTextColor(status.badge.text)
        view.background = statusBadge(view.context, status)
        val hPad = dp(view.context, 8)
        val vPad = dp(view.context, 3)
        view.setPadding(hPad, vPad, hPad, vPad)
        view.gravity = Gravity.CENTER
    }
}
