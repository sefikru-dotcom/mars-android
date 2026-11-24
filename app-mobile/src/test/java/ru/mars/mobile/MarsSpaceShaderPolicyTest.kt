package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * JVM-тесты политики космического фона «Глубокий космос с Марсом».
 *
 * Две группы проверок:
 *  1. GLSL-исходники — что шейдеры действительно собираемы в принципе (версии, точность,
 *     выходная переменная, отсутствие не-ASCII и ES3-only конструкций в ES2-ветке) и что
 *     марсианская картинка на месте: 4 слоя звёзд, туманность с domain warping, пылевые
 *     прожилки, кометы, рельеф с кратерами и бурями, рубиновый серп, ACES и виньетка;
 *  2. математика кадров — 24 fps в покое / 60 fps при скролле, инерционный параллакс
 *     `tanh(raw / 850) * 1.4`, границы шага симуляции и решение «анимировать ли».
 *
 * Тесты не требуют Android, GPU и эмулятора: политика — чистый JVM-объект.
 */
class MarsSpaceShaderPolicyTest {

    // ── GLSL: преамбулы и версии ───────────────────────────────────────────────────────

    @Test
    fun vertexShader300IsEs3FullscreenTriangle() {
        val src = MarsSpaceShaderPolicy.VERTEX_SHADER_300
        assertTrue(src.startsWith("#version 300 es"))
        // Полноэкранный треугольник без VBO: вершины выводятся из gl_VertexID.
        assertTrue(src.contains("gl_VertexID"))
        assertTrue(src.contains("gl_Position"))
        assertFalse(src.contains("attribute "))
    }

    @Test
    fun vertexShader100IsEs2AttributeQuad() {
        val src = MarsSpaceShaderPolicy.VERTEX_SHADER_100
        assertTrue(src.startsWith("#version 100"))
        assertTrue(src.contains("attribute vec2 aPos"))
        assertTrue(src.contains("gl_Position"))
        // gl_VertexID в ES 2.0 не существует — иначе шейдер не соберётся на старых GPU.
        assertFalse(src.contains("gl_VertexID"))
    }

    @Test
    fun fragmentShader300UsesOutVariable() {
        val src = MarsSpaceShaderPolicy.FRAGMENT_SHADER_300
        assertTrue(src.startsWith("#version 300 es"))
        assertTrue(src.contains("precision highp float"))
        assertTrue(src.contains("out vec4 fragColor"))
        assertTrue(src.contains("fragColor = vec4(col, 1.0)"))
        assertFalse(src.contains("gl_FragColor"))
    }

    @Test
    fun fragmentShader100UsesGlFragColorWithPrecisionFallback() {
        val src = MarsSpaceShaderPolicy.FRAGMENT_SHADER_100
        assertTrue(src.startsWith("#version 100"))
        assertTrue(src.contains("gl_FragColor = vec4(col, 1.0)"))
        // highp в фрагментном шейдере ES 2.0 необязателен — нужен безопасный фоллбэк.
        assertTrue(src.contains("GL_FRAGMENT_PRECISION_HIGH"))
        assertTrue(src.contains("precision mediump float"))
        assertFalse(src.contains("out vec4"))
    }

    @Test
    fun bothFragmentShadersDeclareTheSameUniformContract() {
        for (src in listOf(
            MarsSpaceShaderPolicy.FRAGMENT_SHADER_300,
            MarsSpaceShaderPolicy.FRAGMENT_SHADER_100,
        )) {
            assertTrue(src.contains("uniform vec2 uRes;"))
            assertTrue(src.contains("uniform float uTime;"))
            assertTrue(src.contains("uniform float uScroll;"))
            // Контракт переменных тот же, что у эталона Квартала (_CS_FRAG_300).
            assertTrue(src.contains("gl_FragCoord.xy"))
        }
    }

    @Test
    fun fragmentShadersAreSyntacticallyBalanced() {
        for (src in listOf(
            MarsSpaceShaderPolicy.FRAGMENT_SHADER_300,
            MarsSpaceShaderPolicy.FRAGMENT_SHADER_100,
            MarsSpaceShaderPolicy.VERTEX_SHADER_300,
            MarsSpaceShaderPolicy.VERTEX_SHADER_100,
        )) {
            assertEquals("braces", count(src, "{"), count(src, "}"))
            assertEquals("parens", count(src, "("), count(src, ")"))
            assertTrue(src.trimEnd().endsWith("}"))
        }
    }

    @Test
    fun shaderSourcesArePureAscii() {
        // Строгие драйверы GLSL ES не принимают не-ASCII в исходнике: комментарии тоже ASCII.
        for (src in listOf(
            MarsSpaceShaderPolicy.FRAGMENT_SHADER_300,
            MarsSpaceShaderPolicy.FRAGMENT_SHADER_100,
            MarsSpaceShaderPolicy.VERTEX_SHADER_300,
            MarsSpaceShaderPolicy.VERTEX_SHADER_100,
        )) {
            val bad = src.firstOrNull { it.code > 126 || (it.code < 32 && it != '\n' && it != '\t') }
            assertNull("не-ASCII символ в шейдере: $bad", bad)
        }
    }

    @Test
    fun shadersAvoidIntegerLiteralsInsideVectorConstructors() {
        // vec3(1, 0, 0) собирается не всеми компиляторами ES 2.0 — только 1.0 / 0.0.
        val intInVector = Regex("vec[234]\\(\\s*-?\\d+\\s*,")
        for (src in listOf(
            MarsSpaceShaderPolicy.FRAGMENT_SHADER_300,
            MarsSpaceShaderPolicy.FRAGMENT_SHADER_100,
        )) {
            val match = intInVector.find(src)
            assertNull("целочисленный литерал в конструкторе вектора: ${match?.value}", match)
        }
    }

    // ── GLSL: содержание картинки ─────────────────────────────────────────────────────

    @Test
    fun noiseAndFbmHelpersArePresent() {
        val src = MarsSpaceShaderPolicy.FRAGMENT_SHADER_300
        assertTrue(src.contains("float h1(vec3 p)"))
        assertTrue(src.contains("vec3 h3(vec3 p)"))
        assertTrue(src.contains("float vnoise(vec3 x)"))
        assertTrue(src.contains("float fbm5(vec3 p)"))
        assertTrue(src.contains("float fbm3(vec3 p)"))
        // Пять октав в fbm5 — как в эталоне Квартала.
        assertTrue(src.contains("for(int i = 0; i < 5; i++)"))
        assertTrue(src.contains("mat2 rot2(float a)"))
    }

    @Test
    fun starFieldHasFourRotatingDepthLayersWithFlares() {
        val src = MarsSpaceShaderPolicy.FRAGMENT_SHADER_300
        assertTrue(src.contains("float starLayer("))
        // Определение + четыре вызова слоёв глубины.
        assertTrue(count(src, "starLayer(") >= 5)
        // Выравнивание пробелов в шейдере не должно ломать проверку: сравниваем без пробелов.
        val compact = src.replace(" ", "")
        for (scale in listOf("38.0", "21.0", "13.5", "6.2")) {
            assertTrue("нет слоя звёзд с масштабом $scale", compact.contains(",$scale,"))
        }
        // Мерцание и крестовые вспышки ярких звёзд.
        assertTrue(src.contains("float tw = 0.68 + 0.32 * sin("))
        assertTrue(src.contains("float flare"))
        assertTrue(src.contains("r.z > 0.986"))
        // Вращение небосвода вокруг полярной оси.
        assertTrue(src.contains("vec2 pole"))
        assertTrue(src.contains("rot2(uTime * 0.0042)"))
    }

    @Test
    fun nebulaUsesDomainWarpingAndDustLanes() {
        val src = MarsSpaceShaderPolicy.FRAGMENT_SHADER_300
        // Двухуровневый domain warping: q -> r -> f.
        assertTrue(src.contains("vec3 q = vec3("))
        assertTrue(src.contains("vec3 r = vec3("))
        assertTrue(src.contains("fbm5(np + 2.45 * q"))
        assertTrue(src.contains("fbm5(np + 2.2 * r)"))
        // Тёмные пылевые прожилки (Bok globules) и их поглощение.
        assertTrue(src.contains("Bok globules"))
        assertTrue(src.contains("float dustLane"))
        assertTrue(src.contains("float transmittance = exp(-dustLane * env * 2.65)"))
        // Подсвеченная кромка — forward scattering.
        assertTrue(src.contains("float rimLight"))
        assertTrue(src.contains("neb = neb * (0.32 + 0.68 * transmittance)"))
    }

    @Test
    fun nebulaPaletteIsRubyAmberAndPlumNotCyanBlue() {
        val src = MarsSpaceShaderPolicy.FRAGMENT_SHADER_300
        assertTrue(src.contains("vec3 cRuby  = vec3(0.46, 0.10, 0.12)"))
        assertTrue(src.contains("vec3 cAmber = vec3(0.52, 0.26, 0.08)"))
        assertTrue(src.contains("vec3 cPlum  = vec3(0.24, 0.08, 0.22)"))
        assertTrue(src.contains("vec3 cEmber = vec3(0.60, 0.20, 0.10)"))
        // Холодный синий остался только как якорь глубины, а не как основа палитры.
        assertFalse(src.contains("cCyan"))
        assertFalse(src.contains("cBlue"))
    }

    @Test
    fun toneMappingIsAcesFilmicAndVignetteIsCinematic() {
        val src = MarsSpaceShaderPolicy.FRAGMENT_SHADER_300
        assertTrue(src.contains("vec3 acesTone(vec3 x)"))
        assertTrue(src.contains("2.51 * x + 0.03"))
        assertTrue(src.contains("2.43 * x + 0.59"))
        assertTrue(src.contains("col = acesTone(col * vig * 1.22)"))
        assertTrue(src.contains("float vig = 1.0 - 0.28 * dot(uv, uv)"))
    }

    @Test
    fun cometsWithPlasmaTailAreProcedural() {
        val src = MarsSpaceShaderPolicy.FRAGMENT_SHADER_300
        assertTrue(src.contains("vec3 cometStreak(vec2 uv, float seed, float t)"))
        // Определение + три пролёта комет в main.
        assertTrue(count(src, "cometStreak(") >= 4)
        assertTrue(src.contains("plasma tail"))
        assertTrue(src.contains("float tail = exp(-perp * perp"))
        assertTrue(src.contains("float coma = exp("))
        // Хвост светится позади головы: step(along, 0.0) отсекает область перед ней.
        assertTrue(src.contains("step(along, 0.0)"))
    }

    // ── GLSL: планета Марс ────────────────────────────────────────────────────────────

    @Test
    fun marsPaletteMatchesTheContract() {
        val src = MarsSpaceShaderPolicy.FRAGMENT_SHADER_300
        // Атмосферное рассеяние: тёплый красно-янтарный ореол.
        assertTrue(src.contains("vec3(0.92, 0.28, 0.14)"))
        assertTrue(src.contains("vec3(1.00, 0.45, 0.22)"))
        // Поверхность: терракота и тёмная багровая база.
        assertTrue(src.contains("vec3(0.32, 0.08, 0.04)"))
        assertTrue(src.contains("vec3(0.78, 0.24, 0.10)"))
        // Ночная сторона и рубиново-огненный серп на лимбе.
        assertTrue(src.contains("vec3 pDark = vec3(0.015, 0.005, 0.006)"))
        assertTrue(src.contains("vec3(0.96, 0.36, 0.18)"))
    }

    @Test
    fun planetRadiusInShaderMatchesThePolicyConstant() {
        val expected = "float pRad = " + MarsSpaceShaderPolicy.PLANET_RADIUS + ";"
        assertTrue(
            "шейдер и PLANET_RADIUS разъехались: ожидалось '$expected'",
            MarsSpaceShaderPolicy.FRAGMENT_SHADER_300.contains(expected),
        )
        assertTrue(
            MarsSpaceShaderPolicy.FRAGMENT_SHADER_300.contains(
                "vec2(0.41, 0.24 + uScroll * 0.036)",
            ),
        )
        assertEquals(0.24f, MarsSpaceShaderPolicy.PLANET_RADIUS, 0.0001f)
        assertEquals(0.41f, MarsSpaceShaderPolicy.PLANET_CENTER_X, 0.0001f)
        assertEquals(0.24f, MarsSpaceShaderPolicy.PLANET_CENTER_Y, 0.0001f)
        assertEquals(0.036f, MarsSpaceShaderPolicy.PLANET_SCROLL_FACTOR, 0.0001f)
    }

    @Test
    fun planetHasReliefCratersAndDustStorms() {
        val src = MarsSpaceShaderPolicy.FRAGMENT_SHADER_300
        assertTrue(src.contains("float macro = fbm5(nrm * 2.60"))
        assertTrue(src.contains("float detail = fbm3(nrm * 7.40"))
        assertTrue(src.contains("float crater = smoothstep(0.62, 0.98, craterRidge)"))
        assertTrue(src.contains("float storm = smoothstep(0.55, 0.92, fbm3(nrm * 3.10"))
        assertTrue(src.contains("pLit += vec3(0.86, 0.42, 0.18) * storm * 0.30"))
    }

    @Test
    fun planetIsBackLitWithCrescentAndAtmosphericHalo() {
        val src = MarsSpaceShaderPolicy.FRAGMENT_SHADER_300
        assertTrue(src.contains("vec2 sunDir = normalize(vec2(-0.76, -0.65))"))
        assertTrue(src.contains("float atmoGlow = exp(-max(pDist - pRad, 0.0) * 26.0)"))
        assertTrue(src.contains("float crescent = pow(sunAlign, 2.5)"))
        assertTrue(src.contains("float rim = pow(1.0 - z, 3.8) * pow(sunAlign, 1.9)"))
        // Диск планеты затмевает звёзды за собой.
        assertTrue(src.contains("if (pDist < pRad)"))
        assertTrue(src.contains("col = mix(col, pCol, edgeAa)"))
    }

    // ── Палитра для Canvas-фоллбэка ───────────────────────────────────────────────────

    @Test
    fun warmColorsAreWarmAndNightSideIsAlmostBlack() {
        for (color in listOf(
            MarsSpaceShaderPolicy.COLOR_ATMOSPHERE,
            MarsSpaceShaderPolicy.COLOR_ATMOSPHERE_CORE,
            MarsSpaceShaderPolicy.COLOR_SURFACE_LIT,
            MarsSpaceShaderPolicy.COLOR_LIMB_CRESCENT,
            MarsSpaceShaderPolicy.COLOR_STAR_WARM,
        )) {
            val r = (color ushr 16) and 0xFF
            val g = (color ushr 8) and 0xFF
            val b = color and 0xFF
            assertEquals("цвет должен быть непрозрачным: ${Integer.toHexString(color)}", 0xFF, (color ushr 24) and 0xFF)
            assertTrue("ожидался тёплый оттенок (R > G > B): ${Integer.toHexString(color)}", r > g && g > b)
        }
        val night = MarsSpaceShaderPolicy.COLOR_NIGHT_SIDE
        assertTrue((night and 0xFF) < 8)
        assertTrue(((night ushr 16) and 0xFF) < 8)
    }

    @Test
    fun voidColorsStayDeepAndDark() {
        for (color in listOf(
            MarsSpaceShaderPolicy.COLOR_VOID_TOP,
            MarsSpaceShaderPolicy.COLOR_VOID_BOTTOM,
            MarsSpaceShaderPolicy.COLOR_NEBULA_RUBY,
            MarsSpaceShaderPolicy.COLOR_NEBULA_PLUM,
        )) {
            val r = (color ushr 16) and 0xFF
            val g = (color ushr 8) and 0xFF
            val b = color and 0xFF
            assertTrue("фон должен оставаться тёмным: ${Integer.toHexString(color)}", r + g + b < 0x140)
        }
    }

    // ── Кадровый ритм ─────────────────────────────────────────────────────────────────

    @Test
    fun targetFpsIs24IdleAnd60WhileScrolling() {
        assertEquals(24, MarsSpaceShaderPolicy.targetFps(scrolling = false))
        assertEquals(60, MarsSpaceShaderPolicy.targetFps(scrolling = true))
        assertEquals(24, MarsSpaceShaderPolicy.IDLE_FPS)
        assertEquals(60, MarsSpaceShaderPolicy.SCROLL_FPS)
    }

    @Test
    fun frameIntervalsMatchTheFpsTargets() {
        assertEquals(41L, MarsSpaceShaderPolicy.frameIntervalMs(scrolling = false))
        assertEquals(16L, MarsSpaceShaderPolicy.frameIntervalMs(scrolling = true))
        assertEquals(MarsSpaceShaderPolicy.IDLE_FRAME_INTERVAL_MS, MarsSpaceShaderPolicy.frameIntervalMs(false))
        assertEquals(MarsSpaceShaderPolicy.SCROLL_FRAME_INTERVAL_MS, MarsSpaceShaderPolicy.frameIntervalMs(true))
        assertTrue(
            "скролл обязан рисовать чаще покоя",
            MarsSpaceShaderPolicy.frameIntervalMs(true) < MarsSpaceShaderPolicy.frameIntervalMs(false),
        )
        val idleFps = MarsSpaceShaderPolicy.framesPerSecond(MarsSpaceShaderPolicy.IDLE_FRAME_INTERVAL_MS)
        assertTrue("24 fps в покое, получено $idleFps", idleFps in 23.5..25.0)
        assertEquals(0.0, MarsSpaceShaderPolicy.framesPerSecond(0L), 0.0)
    }

    @Test
    fun firstFrameIsDrawnImmediately() {
        assertTrue(MarsSpaceShaderPolicy.shouldDrawFrame(lastDrawMs = 0L, nowMs = 12_345L, scrolling = false))
        assertTrue(MarsSpaceShaderPolicy.shouldDrawFrame(lastDrawMs = 0L, nowMs = 0L, scrolling = true))
    }

    @Test
    fun idleThrottlesToTwentyFourFps() {
        val last = 1_000L
        assertFalse(MarsSpaceShaderPolicy.shouldDrawFrame(last, last + 16, scrolling = false))
        assertFalse(MarsSpaceShaderPolicy.shouldDrawFrame(last, last + 40, scrolling = false))
        assertTrue(MarsSpaceShaderPolicy.shouldDrawFrame(last, last + 41, scrolling = false))
        assertTrue(MarsSpaceShaderPolicy.shouldDrawFrame(last, last + 200, scrolling = false))
    }

    @Test
    fun scrollingAllowsEveryVsync() {
        val last = 1_000L
        assertFalse(MarsSpaceShaderPolicy.shouldDrawFrame(last, last + 15, scrolling = true))
        assertTrue(MarsSpaceShaderPolicy.shouldDrawFrame(last, last + 16, scrolling = true))
        assertTrue(MarsSpaceShaderPolicy.shouldDrawFrame(last, last + 17, scrolling = true))
    }

    @Test
    fun clockResetNeverStallsTheBackground() {
        // Choreographer может отдать меньший таймстамп после сна/перезапуска — рисуем сразу.
        assertTrue(MarsSpaceShaderPolicy.shouldDrawFrame(lastDrawMs = 5_000L, nowMs = 10L, scrolling = false))
    }

    @Test
    fun simulationStepIsBounded() {
        assertEquals(0.05f, MarsSpaceShaderPolicy.clampFrameDt(9f), 0.0f)
        assertEquals(0.001f, MarsSpaceShaderPolicy.clampFrameDt(-1f), 0.0f)
        assertEquals(0.001f, MarsSpaceShaderPolicy.clampFrameDt(0f), 0.0f)
        assertEquals(0.016f, MarsSpaceShaderPolicy.clampFrameDt(0.016f), 0.0001f)
        assertEquals(
            MarsSpaceShaderPolicy.MIN_FRAME_DT_SEC,
            MarsSpaceShaderPolicy.clampFrameDt(Float.NaN),
            0.0f,
        )
    }

    // ── Параллакс ────────────────────────────────────────────────────────────────────

    @Test
    fun scrollTargetIsZeroAtTopAndSaturatesAtTheLimit() {
        assertEquals(0f, MarsSpaceShaderPolicy.scrollTarget(0), 0.0f)
        assertEquals(0f, MarsSpaceShaderPolicy.scrollTarget(-400), 0.0f)
        val huge = MarsSpaceShaderPolicy.scrollTarget(Int.MAX_VALUE)
        assertTrue("параллакс обязан насыщаться, получено $huge", abs(huge - 1.4f) < 1e-4f)
        assertTrue(MarsSpaceShaderPolicy.scrollTarget(10_000) <= 1.4f)
    }

    @Test
    fun scrollTargetMatchesTheTangentCurve() {
        // tanh(850 / 850) * 1.4 = 0.761594 * 1.4
        assertEquals(1.0662f, MarsSpaceShaderPolicy.scrollTarget(850), 0.001f)
        // tanh(425 / 850) * 1.4 = 0.462117 * 1.4
        assertEquals(0.6470f, MarsSpaceShaderPolicy.scrollTarget(425), 0.001f)
    }

    @Test
    fun scrollTargetIsMonotonic() {
        var previous = -1f
        for (raw in intArrayOf(0, 50, 200, 500, 850, 1_500, 4_000, 20_000)) {
            val value = MarsSpaceShaderPolicy.scrollTarget(raw)
            assertTrue("параллакс не должен убывать: raw=$raw value=$value", value > previous)
            previous = value
        }
    }

    @Test
    fun smoothScrollDoesNotMoveWithoutTime() {
        assertEquals(0.3f, MarsSpaceShaderPolicy.smoothScroll(900, 0.3f, 0f), 0.0f)
        // Отрицательный шаг не откатывает сцену назад.
        assertEquals(0.3f, MarsSpaceShaderPolicy.smoothScroll(900, 0.3f, -0.5f), 0.0f)
    }

    @Test
    fun smoothScrollEasesTowardTheTargetWithoutOvershoot() {
        val target = MarsSpaceShaderPolicy.scrollTarget(850)
        var current = 0f
        var previous = 0f
        repeat(30) {
            current = MarsSpaceShaderPolicy.smoothScroll(850, current, 1f / 60f)
            assertTrue("перелёт цели: $current > $target", current <= target + 1e-6f)
            assertTrue("параллакс поехал назад", current >= previous)
            previous = current
        }
        assertTrue("за полсекунды должно пройти больше половины пути", current > target * 0.5f)
    }

    @Test
    fun smoothScrollConvergesAndSettles() {
        var current = 0f
        repeat(180) { current = MarsSpaceShaderPolicy.smoothScroll(1_200, current, 1f / 60f) }
        val target = MarsSpaceShaderPolicy.scrollTarget(1_200)
        assertEquals(target, current, 1e-3f)
        assertFalse(MarsSpaceShaderPolicy.isScrolling(1_200, current))
    }

    @Test
    fun hugeFrameStepIsClampedToFiftyMilliseconds() {
        val afterHugeStep = MarsSpaceShaderPolicy.smoothScroll(850, 0f, 5f)
        val afterClampedStep = MarsSpaceShaderPolicy.smoothScroll(850, 0f, 0.05f)
        assertEquals(afterClampedStep, afterHugeStep, 0.0f)
        assertTrue("за один шаг нельзя перескочить цель", afterHugeStep < MarsSpaceShaderPolicy.scrollTarget(850))
    }

    @Test
    fun scrollingFlagFollowsTheEasing() {
        assertTrue(MarsSpaceShaderPolicy.isScrolling(2_000, 0f))
        assertFalse(MarsSpaceShaderPolicy.isScrolling(0, 0f))
        assertFalse(MarsSpaceShaderPolicy.isScrolling(2_000, MarsSpaceShaderPolicy.scrollTarget(2_000)))
    }

    // ── Живость фона ─────────────────────────────────────────────────────────────────

    @Test
    fun animationRunsOnlyWhenNothingForbidsIt() {
        assertTrue(MarsSpaceShaderPolicy.shouldAnimate(true, powerSave = false, animatorScale = 1f))
        assertTrue(MarsSpaceShaderPolicy.shouldAnimate(true, powerSave = false, animatorScale = 0.5f))
    }

    @Test
    fun reduceMotionAndPowerSaveFreezeTheBackground() {
        assertFalse("reduce motion", MarsSpaceShaderPolicy.shouldAnimate(true, false, 0f))
        assertFalse("энергосбережение", MarsSpaceShaderPolicy.shouldAnimate(true, true, 1f))
        assertFalse("анимация запрещена вызывающим", MarsSpaceShaderPolicy.shouldAnimate(false, false, 1f))
        assertFalse("отрицательный масштаб", MarsSpaceShaderPolicy.shouldAnimate(true, false, -1f))
        assertFalse("NaN-масштаб", MarsSpaceShaderPolicy.shouldAnimate(true, false, Float.NaN))
        assertFalse(
            "энергосбережение важнее масштаба",
            MarsSpaceShaderPolicy.shouldAnimate(true, true, 2f),
        )
    }

    @Test
    fun staticFrameHasAExpressiveTimestamp() {
        assertTrue(MarsSpaceShaderPolicy.STATIC_TIME_SEC > 0f)
        assertTrue(MarsSpaceShaderPolicy.STATIC_TIME_SEC in 5f..30f)
    }

    // ── Разрешение рендера ───────────────────────────────────────────────────────────

    @Test
    fun renderScaleDefaultsToTheReferenceDownscale() {
        assertEquals(0.85f, MarsSpaceShaderPolicy.renderScale(1080, 2400), 0.0001f)
        assertEquals(0.85f, MarsSpaceShaderPolicy.renderScale(720, 1600), 0.0001f)
    }

    @Test
    fun renderScaleRespectsThePixelBudgetAndTheFloor() {
        // 4000 x 4000 выходит за бюджет 2.4 Мп — масштаб ужимается, но не ниже 0.5.
        assertEquals(MarsSpaceShaderPolicy.MIN_RENDER_SCALE, MarsSpaceShaderPolicy.renderScale(4_000, 4_000), 0.0001f)
        val scale = MarsSpaceShaderPolicy.renderScale(3_000, 3_000)
        assertTrue(scale in MarsSpaceShaderPolicy.MIN_RENDER_SCALE..MarsSpaceShaderPolicy.RENDER_SCALE)
        val pixels = (3_000 * scale).toLong() * (3_000 * scale).toLong()
        assertTrue(
            "бюджет пикселей превышен: $pixels",
            pixels <= MarsSpaceShaderPolicy.MAX_RENDER_PIXELS * 1.02,
        )
    }

    @Test
    fun renderScaleSurvivesDegenerateSizes() {
        assertEquals(MarsSpaceShaderPolicy.MIN_RENDER_SCALE, MarsSpaceShaderPolicy.renderScale(0, 0), 0.0f)
        assertEquals(MarsSpaceShaderPolicy.MIN_RENDER_SCALE, MarsSpaceShaderPolicy.renderScale(-10, 100), 0.0f)
    }

    // ── Вспомогательное ──────────────────────────────────────────────────────────────

    private fun count(haystack: String, needle: String): Int {
        var index = haystack.indexOf(needle)
        var total = 0
        while (index >= 0) {
            total++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return total
    }
}
