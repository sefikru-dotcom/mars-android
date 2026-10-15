package ru.mars.mobile

/**
 * Obsidian Depth — pure-data gradient & depth system.
 *
 * Holds every color constant, gradient spec and status-badge spec as immutable
 * JVM-only data (no Android classes), so the whole visual-depth contract is
 * unit-testable without Robolectric. MarsTheme turns these specs into real
 * GradientDrawable/LayerDrawable instances on device.
 *
 * Rules: no emojis, no font glyphs as icons — status is expressed with vector
 * shapes (dots, rings, pills) built programmatically from these specs.
 */
object MarsGradients {

    // ---- Background depth layers (farthest -> nearest to the viewer) ----
    /** Deepest app background (obsidian). */
    const val BG = 0xff07080b.toInt()
    /** Base surface layer (screens, panels). */
    const val SURFACE_0 = 0xff0a0d13.toInt()
    /** Raised layer (cards, lists). */
    const val SURFACE_1 = 0xff0f131c.toInt()
    /** Higher layer (popovers, dialogs). */
    const val SURFACE_2 = 0xff151b27.toInt()
    /** Nearest layer (menus, floating capsules). */
    const val SURFACE_3 = 0xff1c2333.toInt()

    /** Ordered depth ramp: index N visually sits above index N-1. */
    val DEPTH_LAYERS: IntArray = intArrayOf(BG, SURFACE_0, SURFACE_1, SURFACE_2, SURFACE_3)

    // ---- Gradient card colors (Obsidian Deep Glass) ----
    /** User bubble: deep noble sapphire. */
    const val USER_BUBBLE_TOP = 0xff1e2638.toInt()
    const val USER_BUBBLE_BOTTOM = 0xff141a28.toInt()
    const val USER_BUBBLE_STROKE = 0xff2d3b55.toInt()

    /** Bot message card: deep graphite. */
    const val BOT_CARD_TOP = 0xff0f131c.toInt()
    const val BOT_CARD_BOTTOM = 0xff090c13.toInt()
    const val BOT_CARD_STROKE = 0xff1c2333.toInt()

    /** Thinking ("Мысли") card: translucent sapphire haze. */
    const val THINKING_TOP = 0x142563eb
    const val THINKING_BOTTOM = 0x081e3a8a
    const val THINKING_STROKE = 0x333b82f6

    /** Tool call card: matte technical graphite. */
    const val TOOL_CARD_TOP = 0xff0a0d14.toInt()
    const val TOOL_CARD_BOTTOM = 0xff080b10.toInt()
    const val TOOL_CARD_STROKE = 0xff1e2637.toInt()

    /** Composer capsule: floating layered capsule with soft glow. */
    const val COMPOSER_TOP = 0xff131722.toInt()
    const val COMPOSER_BOTTOM = 0xff0d1017.toInt()
    const val COMPOSER_STROKE = 0xff252d3f.toInt()
    const val COMPOSER_GLOW = 0x142563eb

    // ---- Status palette (dots & badges) ----
    const val STATUS_ONLINE = 0xff22c55e.toInt()
    const val STATUS_ERROR = 0xffef4444.toInt()
    const val STATUS_WARNING = 0xfff59e0b.toInt()
    const val STATUS_INFO = 0xff38bdf8.toInt()
    const val STATUS_RUNNING = 0xff3b82f6.toInt()
    const val STATUS_DONE = 0xff22c55e.toInt()

    /**
     * Alpha of the ambient glow ring around a status dot — the RESTING shade.
     * Live-state dots (running / online) additionally breathe between
     * [MarsPulsePolicy.MIN_ALPHA] and [MarsPulsePolicy.MAX_ALPHA]; when animations are
     * off they keep exactly this value, so the static look is unchanged.
     */
    const val GLOW_RING_ALPHA = 0x33
    /** Alpha of translucent badge backgrounds. */
    const val BADGE_BG_ALPHA = 0x1a
    /** Alpha of translucent badge strokes. */
    const val BADGE_STROKE_ALPHA = 0x33
    /** Alpha of the translucent text-match highlight in chat search (amber, over text). */
    const val SEARCH_HIGHLIGHT_ALPHA = 0x66

    /** Direction of a two-stop linear gradient. */
    enum class Angle { TOP_BOTTOM, TL_BR, LEFT_RIGHT }

    /**
     * Immutable spec of a gradient surface. Pure data — safe to assert in JVM tests.
     *
     * @param radiusDp corner radius in dp; 999 renders a full capsule.
     * @param strokeColor hairline border color; 0 means no stroke.
     * @param glowColor optional soft ambient glow behind the surface; 0 means none.
     * @param paddingDp recommended inner content padding in dp.
     */
    data class GradientSpec(
        val name: String,
        val startColor: Int,
        val endColor: Int,
        val angle: Angle = Angle.TOP_BOTTOM,
        val radiusDp: Int = 14,
        val strokeColor: Int = 0,
        val strokeWidthDp: Int = 1,
        val glowColor: Int = 0,
        val paddingDp: Int = 0
    ) {
        /** Both stops must be valid colors and differ, otherwise the "gradient" is flat. */
        val hasDepth: Boolean get() = startColor != endColor
        val hasStroke: Boolean get() = strokeColor != 0
        val hasGlow: Boolean get() = glowColor != 0
        val isCapsule: Boolean get() = radiusDp >= CAPSULE_RADIUS_DP
    }

    const val CAPSULE_RADIUS_DP = 999

    val USER_BUBBLE = GradientSpec(
        name = "userBubble",
        startColor = USER_BUBBLE_TOP,
        endColor = USER_BUBBLE_BOTTOM,
        angle = Angle.TL_BR,
        radiusDp = 18,
        strokeColor = USER_BUBBLE_STROKE,
        paddingDp = 12
    )

    val BOT_CARD = GradientSpec(
        name = "botCard",
        startColor = BOT_CARD_TOP,
        endColor = BOT_CARD_BOTTOM,
        angle = Angle.TOP_BOTTOM,
        radiusDp = 16,
        strokeColor = BOT_CARD_STROKE,
        paddingDp = 12
    )

    val THINKING_CARD = GradientSpec(
        name = "thinkingCard",
        startColor = THINKING_TOP,
        endColor = THINKING_BOTTOM,
        angle = Angle.TOP_BOTTOM,
        radiusDp = 14,
        strokeColor = THINKING_STROKE,
        paddingDp = 10
    )

    val TOOL_CARD = GradientSpec(
        name = "toolCard",
        startColor = TOOL_CARD_TOP,
        endColor = TOOL_CARD_BOTTOM,
        angle = Angle.TOP_BOTTOM,
        radiusDp = 12,
        strokeColor = TOOL_CARD_STROKE,
        paddingDp = 10
    )

    val COMPOSER_CAPSULE = GradientSpec(
        name = "composerCapsule",
        startColor = COMPOSER_TOP,
        endColor = COMPOSER_BOTTOM,
        angle = Angle.TOP_BOTTOM,
        radiusDp = CAPSULE_RADIUS_DP,
        strokeColor = COMPOSER_STROKE,
        glowColor = COMPOSER_GLOW,
        paddingDp = 8
    )

    /** All named gradient surfaces of the design system. */
    val ALL_SPECS: List<GradientSpec> = listOf(USER_BUBBLE, BOT_CARD, THINKING_CARD, TOOL_CARD, COMPOSER_CAPSULE)

    /**
     * Status badge: translucent tinted background + contrasting opaque text +
     * matching dot color. Text labels are plain ASCII words (no glyphs/emojis).
     */
    data class BadgeSpec(
        val label: String,
        val bg: Int,
        val text: Int,
        val dot: Int,
        val stroke: Int
    )

    enum class Status(val badge: BadgeSpec) {
        ONLINE(
            BadgeSpec(
                label = "ONLINE",
                bg = withAlpha(STATUS_ONLINE, BADGE_BG_ALPHA),
                text = 0xff4ade80.toInt(),
                dot = STATUS_ONLINE,
                stroke = withAlpha(STATUS_ONLINE, BADGE_STROKE_ALPHA)
            )
        ),
        ERROR(
            BadgeSpec(
                label = "ERROR",
                bg = withAlpha(STATUS_ERROR, BADGE_BG_ALPHA),
                text = 0xfffca5a5.toInt(),
                dot = STATUS_ERROR,
                stroke = withAlpha(STATUS_ERROR, BADGE_STROKE_ALPHA)
            )
        ),
        WARNING(
            BadgeSpec(
                label = "WARNING",
                bg = withAlpha(STATUS_WARNING, BADGE_BG_ALPHA),
                text = 0xfffbbf24.toInt(),
                dot = STATUS_WARNING,
                stroke = withAlpha(STATUS_WARNING, BADGE_STROKE_ALPHA)
            )
        ),
        INFO(
            BadgeSpec(
                label = "INFO",
                bg = withAlpha(STATUS_INFO, BADGE_BG_ALPHA),
                text = 0xff7dd3fc.toInt(),
                dot = STATUS_INFO,
                stroke = withAlpha(STATUS_INFO, BADGE_STROKE_ALPHA)
            )
        ),
        RUNNING(
            BadgeSpec(
                label = "RUNNING",
                bg = withAlpha(STATUS_RUNNING, BADGE_BG_ALPHA),
                text = 0xff93c5fd.toInt(),
                dot = STATUS_RUNNING,
                stroke = withAlpha(STATUS_RUNNING, BADGE_STROKE_ALPHA)
            )
        ),
        DONE(
            BadgeSpec(
                label = "DONE",
                bg = withAlpha(STATUS_DONE, BADGE_BG_ALPHA),
                text = 0xff86efac.toInt(),
                dot = STATUS_DONE,
                stroke = withAlpha(STATUS_DONE, BADGE_STROKE_ALPHA)
            )
        )
    }

    // ---- Pure color math (JVM-safe, no android.graphics.Color) ----

    /** Replace the alpha byte of [color] with [alpha] (0..255), keeping RGB. */
    fun withAlpha(color: Int, alpha: Int): Int {
        require(alpha in 0..255) { "alpha out of range: $alpha" }
        return (color and 0x00ffffff) or (alpha shl 24)
    }

    fun alphaOf(color: Int): Int = (color ushr 24) and 0xff

    fun isOpaque(color: Int): Boolean = alphaOf(color) == 0xff

    /** Relative luminance of the RGB part (0.0 = black .. 1.0 = white), WCAG-style. */
    fun luminance(color: Int): Double {
        val r = ((color shr 16) and 0xff) / 255.0
        val g = ((color shr 8) and 0xff) / 255.0
        val b = (color and 0xff) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** Ambient glow ring color for a status dot: same hue, translucent. */
    fun glowRing(color: Int): Int = withAlpha(color, GLOW_RING_ALPHA)

    /**
     * Micro-highlight: a faint white hairline tint used on the top edge of cards
     * to imitate light falling on obsidian glass.
     */
    fun microHighlight(alpha: Int = 0x0d): Int = withAlpha(0xffffffff.toInt(), alpha)
}
