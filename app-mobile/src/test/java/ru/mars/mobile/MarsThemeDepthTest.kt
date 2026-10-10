package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Obsidian Depth contract tests: background layering, gradient card specs,
 * status badges and pure color math from MarsGradients (JVM-safe data layer
 * that MarsTheme renders into GradientDrawables on device).
 */
class MarsThemeDepthTest {

    // ---- Background depth layers ----

    @Test
    fun `depth layers match obsidian palette`() {
        assertEquals(0xff07080b.toInt(), MarsGradients.BG)
        assertEquals(0xff0a0d13.toInt(), MarsGradients.SURFACE_0)
        assertEquals(0xff0f131c.toInt(), MarsGradients.SURFACE_1)
        assertEquals(0xff151b27.toInt(), MarsGradients.SURFACE_2)
        assertEquals(0xff1c2333.toInt(), MarsGradients.SURFACE_3)
    }

    @Test
    fun `depth ramp is strictly increasing in luminance`() {
        val layers = MarsGradients.DEPTH_LAYERS
        assertEquals(5, layers.size)
        for (i in 1 until layers.size) {
            assertTrue(
                "layer $i must be lighter than layer ${i - 1} to read as 'nearer'",
                MarsGradients.luminance(layers[i]) > MarsGradients.luminance(layers[i - 1])
            )
        }
    }

    @Test
    fun `all depth layers are fully opaque`() {
        MarsGradients.DEPTH_LAYERS.forEach { color ->
            assertTrue("depth layer must be opaque", MarsGradients.isOpaque(color))
        }
    }

    @Test
    fun `MarsTheme surface aliases mirror the gradient ramp`() {
        assertEquals(MarsGradients.BG, MarsTheme.DEPTH_BG)
        assertEquals(MarsGradients.BG, MarsTheme.BG)
        assertEquals(MarsGradients.SURFACE_0, MarsTheme.SURFACE_0)
        assertEquals(MarsGradients.SURFACE_1, MarsTheme.SURFACE_1)
        assertEquals(MarsGradients.SURFACE_2, MarsTheme.SURFACE_2)
        assertEquals(MarsGradients.SURFACE_3, MarsTheme.SURFACE_3)
        assertEquals(MarsGradients.STATUS_RUNNING, MarsTheme.STATUS_RUNNING)
        assertEquals(MarsGradients.STATUS_DONE, MarsTheme.STATUS_DONE)
    }

    // ---- Gradient card specs ----

    @Test
    fun `user bubble is deep sapphire gradient with thin rim`() {
        val spec = MarsGradients.USER_BUBBLE
        assertEquals(0xff1e2638.toInt(), spec.startColor)
        assertEquals(0xff141a28.toInt(), spec.endColor)
        assertEquals(0xff2d3b55.toInt(), spec.strokeColor)
        assertEquals(MarsGradients.Angle.TL_BR, spec.angle)
        assertTrue("bubble must have real gradient depth", spec.hasDepth)
        assertTrue("bubble must have a stroke", spec.hasStroke)
        assertTrue("bubble text padding expected", spec.paddingDp > 0)
    }

    @Test
    fun `bot card is graphite gradient with micro border and inner padding`() {
        val spec = MarsGradients.BOT_CARD
        assertEquals(0xff0f131c.toInt(), spec.startColor)
        assertEquals(0xff090c13.toInt(), spec.endColor)
        assertEquals(0xff1c2333.toInt(), spec.strokeColor)
        assertEquals(MarsGradients.Angle.TOP_BOTTOM, spec.angle)
        assertTrue(spec.hasDepth)
        assertTrue("bot card needs a light inner padding", spec.paddingDp > 0)
    }

    @Test
    fun `thinking card is translucent sapphire haze`() {
        val spec = MarsGradients.THINKING_CARD
        assertEquals(0x142563eb, spec.startColor)
        assertEquals(0x081e3a8a, spec.endColor)
        assertEquals(0x333b82f6, spec.strokeColor)
        assertTrue(spec.hasDepth)
        assertTrue("thinking fill must stay translucent", !MarsGradients.isOpaque(spec.startColor))
        assertTrue(!MarsGradients.isOpaque(spec.endColor))
        assertTrue(!MarsGradients.isOpaque(spec.strokeColor))
    }

    @Test
    fun `tool card is matte technical graphite`() {
        val spec = MarsGradients.TOOL_CARD
        assertEquals(0xff0a0d14.toInt(), spec.startColor)
        assertEquals(0xff080b10.toInt(), spec.endColor)
        assertEquals(0xff1e2637.toInt(), spec.strokeColor)
        assertTrue(spec.hasDepth)
        assertTrue(MarsGradients.isOpaque(spec.startColor))
    }

    @Test
    fun `composer capsule is a floating pill with glow and thin frame`() {
        val spec = MarsGradients.COMPOSER_CAPSULE
        assertEquals(0xff131722.toInt(), spec.startColor)
        assertEquals(0xff0d1017.toInt(), spec.endColor)
        assertEquals(0xff252d3f.toInt(), spec.strokeColor)
        assertTrue("capsule must render fully round", spec.isCapsule)
        assertEquals(MarsGradients.CAPSULE_RADIUS_DP, spec.radiusDp)
        assertTrue("capsule needs a soft ambient glow", spec.hasGlow)
        assertEquals(0x142563eb, spec.glowColor)
        assertTrue(!MarsGradients.isOpaque(spec.glowColor))
    }

    @Test
    fun `every design-system surface has gradient depth`() {
        assertEquals(5, MarsGradients.ALL_SPECS.size)
        MarsGradients.ALL_SPECS.forEach { spec ->
            assertTrue("${spec.name} must not be flat", spec.hasDepth)
            assertTrue("${spec.name} must have a hairline border", spec.hasStroke)
            assertTrue("${spec.name} stroke width must be hairline", spec.strokeWidthDp == 1)
            val names = MarsGradients.ALL_SPECS.count { it.name == spec.name }
            assertEquals("${spec.name} must be unique", 1, names)
        }
    }

    // ---- Status dots & badges ----

    @Test
    fun `all six status badges exist with expected labels`() {
        val labels = MarsGradients.Status.values().map { it.badge.label }
        assertEquals(
            listOf("ONLINE", "ERROR", "WARNING", "INFO", "RUNNING", "DONE"),
            labels
        )
    }

    @Test
    fun `badge labels are plain ASCII words - no emojis or glyphs`() {
        MarsGradients.Status.values().forEach { status ->
            val label = status.badge.label
            assertTrue(
                "label '$label' must be plain ASCII letters",
                label.matches(Regex("^[A-Z]+$"))
            )
            label.forEach { ch ->
                assertTrue("label char '$ch' outside ASCII", ch.code in 0x20..0x7e)
            }
        }
    }

    @Test
    fun `badge background is translucent tint of its dot color`() {
        MarsGradients.Status.values().forEach { status ->
            val badge = status.badge
            assertEquals(
                "badge bg must be the dot hue at BADGE_BG_ALPHA",
                MarsGradients.BADGE_BG_ALPHA,
                MarsGradients.alphaOf(badge.bg)
            )
            assertEquals(
                "badge bg RGB must match dot RGB",
                badge.dot and 0x00ffffff,
                badge.bg and 0x00ffffff
            )
            assertEquals(
                "badge stroke must be the dot hue at BADGE_STROKE_ALPHA",
                MarsGradients.BADGE_STROKE_ALPHA,
                MarsGradients.alphaOf(badge.stroke)
            )
        }
    }

    @Test
    fun `badge text is opaque and brighter than the deep background`() {
        MarsGradients.Status.values().forEach { status ->
            val badge = status.badge
            assertTrue("badge text must be opaque", MarsGradients.isOpaque(badge.text))
            assertTrue(
                "badge text must contrast against obsidian BG",
                MarsGradients.luminance(badge.text) > MarsGradients.luminance(MarsGradients.BG)
            )
            assertNotEquals("text must differ from bg tint", badge.text, badge.bg)
        }
    }

    @Test
    fun `glow ring keeps hue and stays translucent`() {
        val glow = MarsGradients.glowRing(MarsGradients.STATUS_ONLINE)
        assertEquals(MarsGradients.GLOW_RING_ALPHA, MarsGradients.alphaOf(glow))
        assertEquals(
            MarsGradients.STATUS_ONLINE and 0x00ffffff,
            glow and 0x00ffffff
        )
    }

    @Test
    fun `status dot colors are opaque and distinct per severity`() {
        val dots = listOf(
            MarsGradients.STATUS_ONLINE,
            MarsGradients.STATUS_ERROR,
            MarsGradients.STATUS_WARNING,
            MarsGradients.STATUS_INFO,
            MarsGradients.STATUS_RUNNING
        )
        dots.forEach { assertTrue("dot must be opaque", MarsGradients.isOpaque(it)) }
        assertEquals("dot colors must be unique", dots.size, dots.distinct().size)
        assertEquals(MarsGradients.STATUS_ONLINE, MarsGradients.STATUS_DONE)
    }

    // ---- Pure color math ----

    @Test
    fun `withAlpha replaces only the alpha byte`() {
        val base = 0xff1e2638.toInt()
        val faded = MarsGradients.withAlpha(base, 0x40)
        assertEquals(0x401e2638, faded)
        assertEquals(0x40, MarsGradients.alphaOf(faded))
        assertEquals(0xff, MarsGradients.alphaOf(MarsGradients.withAlpha(base, 0xff)))
        assertEquals(0, MarsGradients.alphaOf(MarsGradients.withAlpha(base, 0)))
    }

    @Test
    fun `withAlpha rejects out-of-range alpha`() {
        listOf(-1, 256, 1000).forEach { bad ->
            try {
                MarsGradients.withAlpha(0xff000000.toInt(), bad)
                fail("withAlpha($bad) must throw IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
                // ok
            }
        }
    }

    @Test
    fun `luminance anchors - black zero, white one, mid gray between`() {
        assertEquals(0.0, MarsGradients.luminance(0xff000000.toInt()), 1e-9)
        assertEquals(1.0, MarsGradients.luminance(0xffffffff.toInt()), 1e-9)
        val mid = MarsGradients.luminance(0xff808080.toInt())
        assertTrue(mid > 0.0 && mid < 1.0)
        // Alpha must not affect luminance of the RGB part.
        assertEquals(
            MarsGradients.luminance(0xff1e2638.toInt()),
            MarsGradients.luminance(0x001e2638),
            1e-9
        )
    }

    @Test
    fun `micro highlight is faint white`() {
        val highlight = MarsGradients.microHighlight()
        assertEquals(0x0d, MarsGradients.alphaOf(highlight))
        assertEquals(0x00ffffff, highlight and 0x00ffffff)
        val custom = MarsGradients.microHighlight(0x20)
        assertEquals(0x20, MarsGradients.alphaOf(custom))
    }

    @Test
    fun `gradient spec capsule detection boundary`() {
        val justBelow = MarsGradients.GradientSpec("x", 0, 1, radiusDp = MarsGradients.CAPSULE_RADIUS_DP - 1)
        val exact = MarsGradients.GradientSpec("y", 0, 1, radiusDp = MarsGradients.CAPSULE_RADIUS_DP)
        assertTrue(!justBelow.isCapsule)
        assertTrue(exact.isCapsule)
    }
}
