package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт фотореалистичного Орба: планета крупная и строго по центру, а неоновое свечение
 * вокруг неё — не самодельные пятна, а свет настоящей 3D-заставки.
 *
 * Instrumented-слоя в проекте нет, поэтому геометрия и проводка закреплены как source-контракт
 * (тот же стиль, что [ChatVoiceUiWiringContractTest]): тест читает РЕАЛЬНЫЙ production-исходник и
 * проверяет решения, которые ломаются молча — размер диска, центровку гало, отсутствие
 * нарисованных вручную «туманностей» и связь с uniform-переменными шейдера.
 *
 * Что защищается (по замечаниям владельца после Волны 19):
 * 1. планета была мелкой (`ORB_DP 232 × PLANET_RATIO 0.49` = шар 113 dp) — теперь ~194 dp;
 * 2. планета казалась сдвинутой влево-вверх — из-за смещённого гало и почти чёрного правого
 *    нижнего лимба; гало центрировано, лимб подсвечен по всему периметру (Fresnel 360°);
 * 3. неоновый фон выглядел «наляпанным» — четыре плоских `RadialGradient`-круга убраны, космос
 *    рисует шейдер заставки на весь экран и пульсирует неоном фазы в такт голосу.
 */
class VoiceModeOrbViewContractTest {

    // ── Палитра неона: одна на орб и на 3D-фон ─────────────────────────────────────────

    @Test
    fun `неон каждой фазы свой и непрозрачный`() {
        val colors = VoiceModeTone.entries.map { VoiceNeonPalette.of(it) }
        assertEquals("у каждой фазы свой цвет", VoiceModeTone.entries.size, colors.distinct().size)
        colors.forEach { color ->
            assertEquals(
                "цвет обязан быть непрозрачным: ${Integer.toHexString(color)}",
                0xFF,
                (color ushr 24) and 0xFF,
            )
        }
    }

    @Test
    fun `покой рубиновый, слушание алее, речь холодная`() {
        val idle = VoiceNeonPalette.of(VoiceModeTone.IDLE)
        val listening = VoiceNeonPalette.of(VoiceModeTone.LISTENING)
        val speaking = VoiceNeonPalette.of(VoiceModeTone.SPEAKING)
        // Пара IDLE/LISTENING разведена намеренно: покой глубже, слушание — живой алый.
        assertTrue("слушание должно быть ярче покоя", red(listening) > red(idle))
        // Речь — лазурная плазма: синий канал выше красного, иначе «говорю» не отличить.
        assertTrue("речь должна быть холодной", blue(speaking) > red(speaking))
    }

    @Test
    fun `неон уходит в шейдер как вектор 0_1`() {
        val speaking = VoiceNeonPalette.of(VoiceModeTone.SPEAKING)
        val r = MarsSpaceShaderPolicy.neonChannel(speaking, 16)
        val g = MarsSpaceShaderPolicy.neonChannel(speaking, 8)
        val b = MarsSpaceShaderPolicy.neonChannel(speaking, 0)
        for (channel in listOf(r, g, b)) {
            assertTrue("канал вне 0..1: $channel", channel in 0f..1f)
        }
        assertEquals(0x4C / 255f, r, 0.0001f)
        assertEquals(0xC8 / 255f, g, 0.0001f)
        assertEquals(0xFF / 255f, b, 0.0001f)
    }

    // ── Uniform-контракт голосового свечения ────────────────────────────────────────────

    @Test
    fun `шейдеры объявляют голосовые uniform-переменные`() {
        for (src in listOf(
            MarsSpaceShaderPolicy.FRAGMENT_SHADER_300,
            MarsSpaceShaderPolicy.FRAGMENT_SHADER_100,
        )) {
            assertTrue(src.contains("uniform float uVoiceLevel;"))
            assertTrue(src.contains("uniform vec3 uVoiceNeon;"))
            assertTrue(src.contains("uniform float uVoiceOrbMode;"))
            // Газ и корона подмешивают неон фазы ровно той формулой, что в контракте.
            assertTrue(src.contains("float n1 = smoothstep("))
            assertTrue(src.contains("float n2 = smoothstep("))
            assertTrue(src.contains("float n3 = smoothstep("))
            assertTrue(src.contains("uVoiceNeon * (n1 * 0.65 + n2 * 0.45"))
            assertTrue(src.contains("(0.28 + 0.95 * voiceLevel)"))
            assertTrue(src.contains("float radialGlow"))
        }
    }

    @Test
    fun `в голосовом режиме маленькая планета уступает место орбу`() {
        val src = MarsSpaceShaderPolicy.FRAGMENT_SHADER_300
        assertTrue(src.contains("float voiceMode = step(0.5, uVoiceOrbMode);"))
        assertTrue(src.contains("float planetVis = 1.0 - voiceMode;"))
        // Планета не рисуется в голосовом режиме: её расстояние уводится за предел диска,
        // поэтому и диск, и ореол, и серп гаснут без второй ветки в шейдере.
        assertTrue(src.contains("float pDist = length(dp) + (1.0 - planetVis) * 8.0;"))
        assertTrue(src.contains("atmoGlow *= planetVis;"))
    }

    @Test
    fun `уровень голоса зажат и не пропускает NaN`() {
        assertEquals(0f, MarsSpaceShaderPolicy.voiceLevel(Float.NaN), 0f)
        assertEquals(0f, MarsSpaceShaderPolicy.voiceLevel(-3f), 0f)
        assertEquals(1f, MarsSpaceShaderPolicy.voiceLevel(9f), 0f)
        assertEquals(0.42f, MarsSpaceShaderPolicy.voiceLevel(0.42f), 0.0001f)
        assertEquals(1f, MarsSpaceShaderPolicy.voiceOrbUniform(true), 0f)
        assertEquals(0f, MarsSpaceShaderPolicy.voiceOrbUniform(false), 0f)
    }

    // ── Геометрия: планета крупнее и строго по центру ───────────────────────────────────

    @Test
    fun `планета крупная, а гало концентрично диску`() {
        val src = orbSource()
        assertTrue(
            "PLANET_RATIO обязан вырасти с 0.49 до 0.66",
            src.contains("const val PLANET_RATIO = 0.66f"),
        )
        // Смещённое влево-вверх гало давало оптическую иллюзию съехавшего шара.
        assertTrue(src.contains("const val HALO_SHIFT_X = 0f"))
        assertTrue(src.contains("const val HALO_SHIFT_Y = 0f"))
        // Внешнее гало рисуется ровно по центру вьюхи.
        assertTrue(src.contains("canvas.drawCircle(centerX, centerY, haloR, haloOuterPaint)"))
    }

    @Test
    fun `лимб подсвечен по всему периметру`() {
        val src = orbSource()
        // Fresnel-свечение и благородный контур — круги, а не дуги: 360°, включая ночную сторону.
        assertTrue(src.contains("limbGlowPaint"))
        assertTrue(
            src.contains("canvas.drawCircle(centerX, centerY, planetR * LIMB_GLOW_RATIO, limbGlowPaint)"),
        )
        assertTrue(
            src.contains("canvas.drawCircle(centerX, centerY, planetR * NIGHT_RIM_RATIO, nightRimPaint)"),
        )
        // Кольцо-корона тоже полный круг — полоса градиента у лимба.
        assertTrue(src.contains("canvas.drawCircle(centerX, centerY, planetR * CORONA_SPAN, coronaPaint)"))
        // Ночная кромка больше не «угадывается»: толщина и альфа подняты.
        assertTrue(src.contains("const val NIGHT_RIM_ALPHA = 0.34f"))
        assertTrue(src.contains("const val NIGHT_RIM_STROKE = 1.6f"))
    }

    @Test
    fun `самодельные пятна туманности убраны из вьюхи`() {
        val src = orbSource()
        for (paint in listOf(
            "nebulaRubyPaint",
            "nebulaAmberPaint",
            "nebulaPlumPaint",
            "neonVeilPaint",
            "voidPaint",
        )) {
            assertFalse("наляпистый слой $paint должен быть удалён", src.contains(paint))
        }
        // Космос рисует шейдер заставки — вьюха на него ссылается, а не подменяет градиентами.
        assertTrue(src.contains("MarsSpaceBackgroundView"))
    }

    @Test
    fun `акустические волны и серп остались живыми`() {
        val src = orbSource()
        // Волны дышат в такт речи и живут только в фазах разговора.
        assertTrue(src.contains("private fun drawWaves"))
        assertTrue(src.contains("wavePaint"))
        assertTrue(src.contains("const val WAVE_RINGS = 3"))
        // Серп света и дымка — по освещённому лимбу.
        assertTrue(src.contains("private fun drawAtmosphere"))
        assertTrue(src.contains("crescentPaint"))
        assertTrue(src.contains("hazePaint"))
        // Уровень речи по-прежнему сглаживается в кадре.
        assertTrue(src.contains("fun setLevel(level: Float)"))
        assertTrue(src.contains("smoothedLevel"))
    }

    // ── Вспомогательное ────────────────────────────────────────────────────────────────

    private fun red(color: Int): Int = (color ushr 16) and 0xFF

    private fun blue(color: Int): Int = color and 0xFF

    private fun orbSource(): String = source("src/main/java/ru/mars/mobile/VoiceModeOrbView.kt")

    /** Найти production-исходник от текущего каталога вверх: тест может бежать из модуля и из корня. */
    private fun source(relative: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            val nested = File(dir, "app-mobile/$relative")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile
        }
        throw AssertionError("Не найден исходник: $relative")
    }
}
