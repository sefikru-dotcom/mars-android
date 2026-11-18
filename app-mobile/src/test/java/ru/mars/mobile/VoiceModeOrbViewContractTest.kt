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
 *
 * Волна 21 добавила к этому две вещи, которые тоже ломаются молча:
 * 4. палитра перестала быть «рубин → кислотно-голубой»: вся четвёрка фаз живёт в тёплой гамме
 *    Марса, иначе смена фазы бьёт по глазам контрастом в 180°;
 * 5. переход фазы больше не присваивание, а экспоненциальная интерполяция цвета и энергий
 *    ([VoiceNeonPalette.stepFactor] / [VoiceNeonPalette.lerpColor]) — и в орбе, и в 3D-фоне.
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
    fun `вся палитра тёплая, без холодного синего в речи`() {
        val idle = VoiceNeonPalette.of(VoiceModeTone.IDLE)
        val listening = VoiceNeonPalette.of(VoiceModeTone.LISTENING)
        val thinking = VoiceNeonPalette.of(VoiceModeTone.THINKING)
        val speaking = VoiceNeonPalette.of(VoiceModeTone.SPEAKING)

        // Пара IDLE/LISTENING разведена намеренно: покой глубже, слушание — коралловый рубин.
        assertTrue("слушание должно быть ярче покоя", red(listening) > red(idle))

        // Волна 21: речь больше не кислотно-голубая. Было 0xFF4CC8FF — синий канал выше
        // красного в три с лишним раза, и переход с рубинового бил по глазам контрастом в 180°.
        // Тёплая гамма — красный канал доминирует во ВСЕХ четырёх фазах.
        for (color in listOf(idle, listening, thinking, speaking)) {
            assertTrue(
                "фаза ${Integer.toHexString(color)} обязана быть тёплой: red > blue",
                red(color) > blue(color),
            )
        }
        // Персиково-золотистая речь: зелёный канал выше синего, то есть оттенок жёлтый, не голубой.
        assertTrue("речь обязана быть персиково-золотистой", green(speaking) > blue(speaking))

        // Палитра не «примерно тёплая», а именно та, что в контракте.
        assertEquals(0xFFB8293D.toInt(), idle)
        assertEquals(0xFFE84A58.toInt(), listening)
        assertEquals(0xFFE58B42.toInt(), thinking)
        assertEquals(0xFFF2A679.toInt(), speaking)

        // Фазы всё ещё различимы: речь светлее покоя, и её синий выше синего покоя — но красный
        // по-прежнему ведущий, поэтому оттенок читается как золото, а не как холодный синий.
        assertTrue("речь должна быть светлее покоя", red(speaking) > red(idle))
        assertTrue("синий речи обязан остаться ниже красного", blue(speaking) < red(speaking))
        assertTrue("синий речи выше синего покоя", blue(speaking) > blue(idle))
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
        assertEquals(0xF2 / 255f, r, 0.0001f)
        assertEquals(0xA6 / 255f, g, 0.0001f)
        assertEquals(0x79 / 255f, b, 0.0001f)
    }

    // ── Плавный переход фазы (Волна 21) ────────────────────────────────────────────────

    @Test
    fun `lerpColor идёт по каналам и зажимается в границы`() {
        val from = 0xFFB8293D.toInt()
        val to = 0xFFF2A679.toInt()

        assertEquals("t = 0 — ровно исходный цвет", from, VoiceNeonPalette.lerpColor(from, to, 0f))
        assertEquals("t = 1 — ровно целевой цвет", to, VoiceNeonPalette.lerpColor(from, to, 1f))
        assertEquals("t < 0 не уводит за исходный", from, VoiceNeonPalette.lerpColor(from, to, -0.4f))
        assertEquals("t > 1 не уводит за целевой", to, VoiceNeonPalette.lerpColor(from, to, 1.4f))

        val half = VoiceNeonPalette.lerpColor(from, to, 0.5f)
        assertTrue("середина — не край", half != from && half != to)
        assertEquals("альфа на обоих концах непрозрачная", 0xFF, (half ushr 24) and 0xFF)
        // Поканальная интерполяция: каждый канал — среднее своих концов (±1 на округление).
        assertTrue("красный на середине", kotlin.math.abs(red(half) - (red(from) + red(to)) / 2) <= 1)
        assertTrue("зелёный на середине", kotlin.math.abs(green(half) - (green(from) + green(to)) / 2) <= 1)
        assertTrue("синий на середине", kotlin.math.abs(blue(half) - (blue(from) + blue(to)) / 2) <= 1)

        // Альфа интерполируется наравне с цветом: иначе переход между полупрозрачными красками
        // давал бы рывок по плотности.
        val faded = VoiceNeonPalette.lerpColor(0x00FF0000, 0xFFFF0000.toInt(), 0.5f)
        val fadedAlpha = (faded ushr 24) and 0xFF
        assertTrue("альфа на середине: $fadedAlpha", fadedAlpha in 127..128)
    }

    @Test
    fun `stepFactor — экспонента с зажимом dt и защитой от нулевой тау`() {
        assertEquals("нулевой dt не двигает переход", 0f, VoiceNeonPalette.stepFactor(0f), 0f)
        assertEquals("сбой таймера (dt < 0) не двигает", 0f, VoiceNeonPalette.stepFactor(-0.5f), 0f)
        assertEquals("нулевая тау — сразу в цель", 1f, VoiceNeonPalette.stepFactor(0.016f, 0f), 0f)

        val k = VoiceNeonPalette.stepFactor(1f / 60f)
        assertEquals(
            "шаг кадра 60 fps — ровно формула контракта",
            1f - kotlin.math.exp((-1f / 60f / VoiceNeonPalette.TRANSITION_TAU_SEC).toDouble()).toFloat(),
            k,
            1e-6f,
        )
        assertTrue("шаг обязан лежать в 0..1: $k", k in 0f..1f)

        // dt зажат четвертью секунды: после сворачивания приложения один гигантский кадр не
        // должен перескакивать переход в конец.
        assertEquals(
            "dt больше 0.25 с зажимается",
            VoiceNeonPalette.stepFactor(0.25f),
            VoiceNeonPalette.stepFactor(5f),
            1e-6f,
        )
        assertTrue("0.25 с — ещё не «сразу в цель»", VoiceNeonPalette.stepFactor(0.25f) < 1f)

        // Монотонность: длиннее кадр — больше шаг.
        assertTrue(VoiceNeonPalette.stepFactor(0.008f) < VoiceNeonPalette.stepFactor(0.016f))
        assertTrue(VoiceNeonPalette.stepFactor(0.016f) < VoiceNeonPalette.stepFactor(0.033f))
    }

    @Test
    fun `переход фазы занимает около секунды, а не кадр и не вечность`() {
        var remaining = 1f
        var frames = 0
        val k = VoiceNeonPalette.stepFactor(1f / 60f)
        while (remaining > 0.05f && frames < 600) {
            remaining -= remaining * k
            frames++
        }
        // 3 тау = 1.35 с ≈ 81 кадр при 60 fps.
        assertTrue("переход не должен быть мгновенным: $frames кадров", frames >= 45)
        assertTrue("переход не должен тянуться: $frames кадров", frames <= 120)
    }

    @Test
    fun `переход фазы интерполируется в кадре, а не присваивается`() {
        val src = orbSource()
        // Цели и текущие значения — отдельные поля: без этого смена тона снова была бы вспышкой.
        for (field in listOf(
            "targetNeon",
            "targetHaloEnergy",
            "targetCoronaEnergy",
            "targetLimbEnergy",
            "targetWaveEnergy",
        )) {
            assertTrue("нет целевого поля $field", src.contains(field))
        }
        assertTrue(
            "кадр обязан двигать переход через stepFactor(dtSec)",
            src.contains("advanceTransition(VoiceNeonPalette.stepFactor(dtSec))"),
        )
        assertTrue(
            "цвет обязан интерполироваться, а не присваиваться",
            src.contains("VoiceNeonPalette.lerpColor(neon, targetNeon, k)"),
        )
        assertTrue("расхождение с целью обязано проверяться", src.contains("private fun needsTransition()"))
        // Пока переход не сошёлся, цикл кадров не останавливается — даже при переходе в IDLE.
        assertTrue(src.contains("if (!needsTransition() && !pulses)"))
        assertTrue(src.contains("OrbMotionPolicy.needsFrames("))
        // Кисти перекрашиваются из одного места, вместе с градиентами.
        assertTrue(src.contains("private fun paintWithNeon()"))
        for (paint in listOf("wavePaint", "hazePaint", "crescentPaint", "limbGlowPaint")) {
            assertTrue("кисть $paint обязана перекрашиваться", src.contains("$paint.color = neon"))
        }
        assertTrue(src.contains("buildShaders()"))
    }

    // ── Жизненный цикл и плавность (исправления после аудита) ────────────────────────────

    @Test
    fun `переход в IDLE не ставится мгновенно, а скрытая вьюха кадров не просит`() {
        // Право системы и просьба хоста — разные вещи. Переход цвета обязан доехать, когда
        // дыхание уже не нужно (pulses = false — это и есть состояние IDLE).
        assertTrue(
            "переход в IDLE обязан доезжать кадрами",
            OrbMotionPolicy.needsFrames(
                pulses = false,
                transitioning = true,
                animationsOn = true,
                visible = true,
            ),
        )
        // Дышать — только когда об этом просит хост.
        assertTrue(
            OrbMotionPolicy.needsFrames(
                pulses = true,
                transitioning = false,
                animationsOn = true,
                visible = true,
            ),
        )
        // Ничего не сошлось и просить нечего — кадров нет.
        assertFalse(
            OrbMotionPolicy.needsFrames(
                pulses = false,
                transitioning = false,
                animationsOn = true,
                visible = true,
            ),
        )
        // Скрытая вьюха (слой в GONE) и фоновое окно кадров не просят вовсе — даже дыша.
        assertFalse(
            "невидимая вьюха не должна крутить кадры",
            OrbMotionPolicy.needsFrames(
                pulses = true,
                transitioning = true,
                animationsOn = true,
                visible = false,
            ),
        )
        // Выключенные анимации — тем более.
        assertFalse(
            OrbMotionPolicy.needsFrames(
                pulses = true,
                transitioning = true,
                animationsOn = false,
                visible = true,
            ),
        )

        // Мгновенное применение — только когда движения не увидит никто.
        assertTrue(OrbMotionPolicy.shouldSnap(animationsOn = false, visible = true))
        assertTrue(OrbMotionPolicy.shouldSnap(animationsOn = true, visible = false))
        assertFalse(
            "видимая вьюха с разрешёнными анимациями обязана перетекать плавно",
            OrbMotionPolicy.shouldSnap(animationsOn = true, visible = true),
        )
    }

    @Test
    fun `точка мгновенного применения больше не зависит от pulses и breathing`() {
        val src = orbSource()
        // Дефект аудита: `!motionAllowed || !breathing` ставило ЛЮБУЮ смену тона мгновенно, потому
        // что цикл кадров в этот момент ещё не запущен, а `motionAllowed` включал в себя pulses.
        assertFalse(
            "условие snapToTargets не должно зависеть от дыхания",
            src.contains("if (!motionAllowed || !breathing) snapToTargets()"),
        )
        assertFalse("склеенный флаг motionAllowed обязан уйти", src.contains("private var motionAllowed"))
        assertTrue(src.contains("OrbMotionPolicy.shouldSnap(animationsOn, canAnimate())"))
        // Право системы перечитывается там, где оно может измениться.
        assertTrue(src.contains("animationsOn = animationAllowed()"))
        // Видимость — часть решения: слой прячется через GONE у корня, детача при этом нет.
        assertTrue("нужен обработчик видимости", src.contains("override fun onVisibilityChanged"))
        assertTrue("нужен обработчик видимости окна", src.contains("override fun onWindowVisibilityChanged"))
        assertTrue(src.contains("private fun canAnimate(): Boolean"))
        // Пауза хоста не перекрашивает орб: она только останавливает кадры.
        assertTrue(src.contains("fun pauseMotion()"))
        assertTrue(src.contains("fun resumeMotion()"))
    }

    @Test
    fun `фаза дыхания не сбрасывается на перезапуске цикла`() {
        val period = MarsPulsePolicy.PERIOD_ONLINE_MS.toLong()
        // Орб остановился на подъёме, через секунду снова просят дыхание: фаза обязана продолжиться
        // с того места, а не упасть в 0 (раньше отсчёт шёл от originNanos каждого запуска, и
        // альфа прыгала с текущей на самую тёмную точку периода).
        val stopped = 0.62f
        val next = OrbMotionPolicy.advancePhase(stopped, 1f / 60f, period)
        assertTrue("фаза обязана продолжиться вперёд: $next", next > stopped)
        assertEquals(
            "шаг за кадр — ровно dt / период",
            stopped + (1f / 60f) / (period / 1000f),
            next,
            1e-4f,
        )
        // И в статичном кадре используется та же фаза: отдельной константы покоя больше нет.
        assertFalse(orbSource().contains("REST_BREATH"))
    }

    @Test
    fun `фаза дыхания идёт с одинаковой скоростью на 30 и 60 кадрах`() {
        val period = MarsPulsePolicy.PERIOD_ONLINE_MS.toLong()
        var at60 = 0f
        var at30 = 0f
        repeat(60) { at60 = OrbMotionPolicy.advancePhase(at60, 1f / 60f, period) }
        repeat(30) { at30 = OrbMotionPolicy.advancePhase(at30, 1f / 30f, period) }
        assertEquals("секунда дыхания одна и та же на любой частоте", at60, at30, 1e-3f)
    }

    @Test
    fun `фаза всегда в пределах 0_1 и переживает битые значения`() {
        val period = MarsPulsePolicy.PERIOD_ONLINE_MS.toLong()
        // Битая фаза — это начало цикла, а не случайный кадр: значение совпадает с тем, что было бы
        // после честного старта с нуля, и на экране не появится произвольная яркость.
        assertEquals(
            "NaN трактуется как начало цикла",
            OrbMotionPolicy.advancePhase(0f, 0.016f, period),
            OrbMotionPolicy.advancePhase(Float.NaN, 0.016f, period),
            0f,
        )
        assertEquals(
            "бесконечность трактуется как начало цикла",
            OrbMotionPolicy.advancePhase(0f, 0.016f, period),
            OrbMotionPolicy.advancePhase(Float.POSITIVE_INFINITY, 0.016f, period),
            0f,
        )
        // Сбой таймера (dt < 0) не откатывает дыхание назад.
        assertEquals(0.4f, OrbMotionPolicy.advancePhase(0.4f, -0.5f, period), 1e-6f)
        // Нулевой период — фаза как есть, а не деление на ноль.
        assertEquals(0.4f, OrbMotionPolicy.advancePhase(0.4f, 0.016f, 0L), 1e-6f)

        var phase = 0.97f
        repeat(400) { phase = OrbMotionPolicy.advancePhase(phase, 0.016f, period) }
        assertTrue("фаза обязана остаться в 0..1: $phase", phase in 0f..1f)
        // Один гигантский кадр (после сворачивания) не перескакивает полпериода.
        val jumped = OrbMotionPolicy.advancePhase(0.5f, 5f, period)
        assertTrue("потолок приращения обязан работать: $jumped", jumped < 0.52f)
    }

    @Test
    fun `статичный кадр той же яркости, что прежний покой`() {
        // 0.32 амплитуды — прежняя константа покоя; фаза 0.1914 подобрана так, чтобы давать её же.
        assertEquals(0.32f, OrbMotionPolicy.breathAt(0.1914f), 0.005f)
        assertTrue(
            "REST_PHASE обязан остаться той же точкой",
            orbSource().contains("const val REST_PHASE = 0.1914f"),
        )
        // Дыхание в цикле и в статике — одна формула, значит и одна кривая.
        assertEquals(0f, OrbMotionPolicy.breathAt(0f), 0.001f)
        assertEquals(1f, OrbMotionPolicy.breathAt(0.5f), 0.001f)
    }

    @Test
    fun `сглаживание амплитуды не зависит от частоты кадров`() {
        // Цель скачет 0 → 1 (слово началось): за 0.2 с на 30 и на 60 кадрах путь обязан совпасть.
        var at60 = 0f
        var at30 = 0f
        repeat((0.2f * 60).toInt()) { at60 = OrbMotionPolicy.smoothLevel(at60, 1f, 1f / 60f) }
        repeat((0.2f * 30).toInt()) { at30 = OrbMotionPolicy.smoothLevel(at30, 1f, 1f / 30f) }
        assertEquals("атака не должна зависеть от fps", at60, at30, 0.01f)
        assertTrue("атака обязана успеть за 0.2 с: $at60", at60 > 0.95f)

        // Перелёта нет никогда.
        var value = 0f
        repeat(200) { value = OrbMotionPolicy.smoothLevel(value, 1f, 1f / 60f) }
        assertTrue("амплитуда не выходит за цель: $value", value <= 1f)
        assertEquals("и доходит ровно до цели", 1f, value, 0f)

        // Спад медленнее атаки: то же слово гаснет заметно дольше, чем вспыхивает.
        var release = 1f
        repeat(6) { release = OrbMotionPolicy.smoothLevel(release, 0f, 1f / 60f) }
        var attack = 0f
        repeat(6) { attack = OrbMotionPolicy.smoothLevel(attack, 1f, 1f / 60f) }
        assertTrue("спад обязан быть мягче атаки: $release", release > 1f - attack)

        // Битые значения не превращают радиус в NaN (иначе drawCircle не рисует ничего).
        assertEquals(0f, OrbMotionPolicy.smoothLevel(Float.NaN, Float.NaN, 0.016f), 0f)
        assertTrue(OrbMotionPolicy.smoothLevel(0.5f, Float.POSITIVE_INFINITY, 0.016f).isFinite())
    }

    @Test
    fun `свечение смягчено, а не только перекрашено`() {
        val src = orbSource()
        // Пиковые альфы гало снижены на ~18%: было 0x4D у внешнего и 0x7A у кольца-короны.
        assertTrue(src.contains("MarsGradients.withAlpha(neon, 0x3F)"))
        assertTrue(src.contains("MarsGradients.withAlpha(neon, 0x64)"))
        assertFalse(
            "слепящий пик 0x7A обязан уйти",
            src.contains("MarsGradients.withAlpha(neon, 0x7A)"),
        )
        // Волны стали мягче (было 0.30 / 0.42).
        assertTrue(src.contains("const val WAVE_BASE_ALPHA = 0.25f"))
        assertTrue(src.contains("const val WAVE_VOICE_ALPHA = 0.35f"))
        // Энергии речи больше не упираются в единицу (было 0.98 по лимбу).
        assertTrue(src.contains("val ENERGY_SPEAKING = floatArrayOf(0.49f, 0.66f, 0.82f, 0.67f)"))
    }

    @Test
    fun `фон перетекает между фазами тем же шагом`() {
        val bg = source("src/main/java/ru/mars/mobile/MarsSpaceBackgroundView.kt")
        assertTrue(bg.contains("private var targetNeonR"))
        assertTrue(bg.contains("private var targetNeonG"))
        assertTrue(bg.contains("private var targetNeonB"))
        assertTrue(bg.contains("private var targetNeonColor"))
        assertTrue(bg.contains("VoiceNeonPalette.stepFactor(dt)"))
        assertTrue(bg.contains("advanceVoiceNeon(dt)"))
        // Без кадров (reduce motion, скрытый слой) цвет ставится сразу, а не «догоняется» вечно.
        assertTrue(bg.contains("if (!animating || voiceNeonColor == 0) {"))
        assertTrue(bg.contains("snapVoiceNeon()"))
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

    private fun green(color: Int): Int = (color ushr 8) and 0xFF

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
