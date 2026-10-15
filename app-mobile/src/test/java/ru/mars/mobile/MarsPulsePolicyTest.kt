package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Чистые JVM-тесты политики мягкой неоновой пульсации [MarsPulsePolicy]:
 * периоды по назначению, форма кривой (границы/середина, монотонность,
 * симметрия, отсутствие выхода за допустимый коридор), независимость формы от
 * частоты кадров и escape-hatch'и (анимации выключены / экономия батареи /
 * reduce motion).
 */
class MarsPulsePolicyTest {

    // ---- Периоды по назначению ----

    @Test
    fun `role periods match the webui pacing`() {
        assertEquals(1300, MarsPulsePolicy.PERIOD_RUNNING_MS)
        assertEquals(3000, MarsPulsePolicy.PERIOD_ONLINE_MS)
        assertEquals(2600, MarsPulsePolicy.PERIOD_PROGRESS_MS)

        assertEquals(MarsPulsePolicy.PERIOD_RUNNING_MS, MarsPulsePolicy.PulseRole.RUNNING.periodMs)
        assertEquals(MarsPulsePolicy.PERIOD_ONLINE_MS, MarsPulsePolicy.PulseRole.ONLINE.periodMs)
        assertEquals(MarsPulsePolicy.PERIOD_PROGRESS_MS, MarsPulsePolicy.PulseRole.PROGRESS.periodMs)
    }

    @Test
    fun `running dot is faster than the calm online dot`() {
        assertTrue(
            "точка «выполняется» должна дышать чаще точки «онлайн»",
            MarsPulsePolicy.PulseRole.RUNNING.periodMs < MarsPulsePolicy.PulseRole.ONLINE.periodMs
        )
        assertTrue(
            "точка «онлайн» должна быть самой мягкой по амплитуде",
            MarsPulsePolicy.PulseRole.ONLINE.maxAlpha < MarsPulsePolicy.PulseRole.RUNNING.maxAlpha
        )
    }

    @Test
    fun `no role pulses faster than the strobe floor`() {
        MarsPulsePolicy.PulseRole.values().forEach { role ->
            assertTrue(
                "роль $role должна укладываться в мягкий коридор периодов",
                role.isCalmPeriod
            )
            assertTrue(
                "период роли $role короче стробоскопического предела",
                role.periodMs >= MarsPulsePolicy.MIN_PERIOD_MS
            )
            assertTrue(
                "период роли $role длиннее верхнего предела мягкости",
                role.periodMs <= MarsPulsePolicy.MAX_PERIOD_MS
            )
        }
    }

    // ---- Альфа: коридор и мягкость ----

    @Test
    fun `alpha corridor is soft and matches the webui peak`() {
        assertEquals(0.25f, MarsPulsePolicy.MIN_ALPHA, 0f)
        assertEquals(0.65f, MarsPulsePolicy.MAX_ALPHA, 0f)
        assertTrue(
            "коридор должен быть уже половины: пульсация мягкая, не мигание",
            MarsPulsePolicy.MAX_ALPHA - MarsPulsePolicy.MIN_ALPHA <= 0.5f
        )
    }

    @Test
    fun `every role amplitude stays inside the soft corridor`() {
        MarsPulsePolicy.PulseRole.values().forEach { role ->
            assertTrue("min альфы роли $role вне коридора", role.maxAlpha >= MarsPulsePolicy.MIN_ALPHA)
            assertTrue("max альфы роли $role вне коридора", role.maxAlpha <= MarsPulsePolicy.MAX_ALPHA)
        }
        assertEquals(MarsPulsePolicy.ONLINE_MAX_ALPHA, MarsPulsePolicy.PulseRole.ONLINE.maxAlpha, 0f)
        assertEquals(0.5f, MarsPulsePolicy.ONLINE_MAX_ALPHA, 0f)
    }

    // ---- Форма кривой: границы, середина, симметрия ----

    @Test
    fun `phase zero starts at the darkest point`() {
        assertEquals(MarsPulsePolicy.MIN_ALPHA, MarsPulsePolicy.alphaAt(0f), 1e-6f)
    }

    @Test
    fun `phase half is the peak`() {
        assertEquals(MarsPulsePolicy.MAX_ALPHA, MarsPulsePolicy.alphaAt(0.5f), 1e-6f)
        assertEquals(
            MarsPulsePolicy.ONLINE_MAX_ALPHA,
            MarsPulsePolicy.alphaAt(0.5f, MarsPulsePolicy.ONLINE_MAX_ALPHA),
            1e-6f
        )
    }

    @Test
    fun `phase one wraps back to the darkest point`() {
        assertEquals(MarsPulsePolicy.MIN_ALPHA, MarsPulsePolicy.alphaAt(1f), 1e-6f)
        assertEquals(
            "цикл должен быть бесшовным: стык 1.0 и 0.0 совпадает",
            MarsPulsePolicy.alphaAt(0f),
            MarsPulsePolicy.alphaAt(1f),
            1e-6f
        )
    }

    @Test
    fun `curve is symmetric around the peak`() {
        for (step in 1 until 50) {
            val phase = step / 100f
            assertEquals(
                "кривая обязана быть симметричной на фазе $phase",
                MarsPulsePolicy.alphaAt(phase),
                MarsPulsePolicy.alphaAt(1f - phase),
                1e-6f
            )
        }
    }

    @Test
    fun `first half only rises and second half only falls`() {
        var previous = MarsPulsePolicy.alphaAt(0f)
        for (step in 1..100) {
            val phase = step / 200f // 0.005 .. 0.5
            val current = MarsPulsePolicy.alphaAt(phase)
            assertTrue(
                "подъём нарушен на фазе $phase: $previous -> $current",
                current >= previous
            )
            previous = current
        }
        previous = MarsPulsePolicy.alphaAt(0.5f)
        for (step in 1..100) {
            val phase = 0.5f + step / 200f // 0.505 .. 1.0
            val current = MarsPulsePolicy.alphaAt(phase)
            assertTrue(
                "спад нарушен на фазе $phase: $previous -> $current",
                current <= previous
            )
            previous = current
        }
    }

    @Test
    fun `alpha never leaves the corridor for any phase`() {
        val phases = listOf(-2.75f, -0.5f, 0f, 0.001f, 0.25f, 0.5f, 0.75f, 0.999f, 1f, 3.5f, 17.25f)
        phases.forEach { phase ->
            val alpha = MarsPulsePolicy.alphaAt(phase)
            assertTrue("альфа $alpha на фазе $phase ниже минимума", alpha >= MarsPulsePolicy.MIN_ALPHA)
            assertTrue("альфа $alpha на фазе $phase выше максимума", alpha <= MarsPulsePolicy.MAX_ALPHA)
        }
    }

    @Test
    fun `non-finite phase degrades to the start of the cycle instead of NaN`() {
        assertEquals(MarsPulsePolicy.MIN_ALPHA, MarsPulsePolicy.alphaAt(Float.NaN), 1e-6f)
        assertEquals(MarsPulsePolicy.MIN_ALPHA, MarsPulsePolicy.alphaAt(Float.POSITIVE_INFINITY), 1e-6f)
        assertEquals(MarsPulsePolicy.MIN_ALPHA, MarsPulsePolicy.alphaAt(Float.NEGATIVE_INFINITY), 1e-6f)
    }

    @Test
    fun `alpha is period-wrapped for phases beyond one cycle`() {
        assertEquals(MarsPulsePolicy.alphaAt(0.3f), MarsPulsePolicy.alphaAt(1.3f), 1e-6f)
        assertEquals(MarsPulsePolicy.alphaAt(0.3f), MarsPulsePolicy.alphaAt(4.3f), 1e-6f)
        assertEquals(MarsPulsePolicy.alphaAt(0.7f), MarsPulsePolicy.alphaAt(-0.3f), 1e-6f)
    }

    @Test
    fun `alpha byte stays inside a byte and tracks the float curve`() {
        assertTrue(MarsPulsePolicy.alphaByteAt(0f) in 0..255)
        assertTrue(MarsPulsePolicy.alphaByteAt(0.5f) in 0..255)
        assertEquals("минимум 0.25 -> 64/255", 64, MarsPulsePolicy.alphaByteAt(0f))
        assertEquals("пик 0.65 -> 166/255", 166, MarsPulsePolicy.alphaByteAt(0.5f))
        assertEquals(
            "онлайн мягче: пик 0.5 -> 128/255",
            128,
            MarsPulsePolicy.alphaByteAt(0.5f, MarsPulsePolicy.ONLINE_MAX_ALPHA)
        )
        for (step in 0..50) {
            val phase = step / 50f
            val byte = MarsPulsePolicy.alphaByteAt(phase)
            assertTrue("байт альфы на фазе $phase вне диапазона: $byte", byte in 0..255)
        }
    }

    @Test
    fun `clamp keeps the peak inside the corridor`() {
        assertEquals(MarsPulsePolicy.MIN_ALPHA, MarsPulsePolicy.clampAlpha(0f), 1e-6f)
        assertEquals(MarsPulsePolicy.MIN_ALPHA, MarsPulsePolicy.clampAlpha(-1f), 1e-6f)
        assertEquals(MarsPulsePolicy.MAX_ALPHA, MarsPulsePolicy.clampAlpha(1f), 1e-6f)
        assertEquals(MarsPulsePolicy.MAX_ALPHA, MarsPulsePolicy.clampAlpha(9f), 1e-6f)
        assertEquals(MarsPulsePolicy.MIN_ALPHA, MarsPulsePolicy.clampAlpha(Float.NaN), 1e-6f)
        assertEquals(0.4f, MarsPulsePolicy.clampAlpha(0.4f), 1e-6f)
        assertEquals(
            "зажатую амплитуду обязан увидеть и alphaAt",
            MarsPulsePolicy.MAX_ALPHA,
            MarsPulsePolicy.alphaAt(0.5f, 9f),
            1e-6f
        )
    }

    // ---- Независимость от частоты кадров ----

    /** Кадры одного цикла при заданном fps: список (время в мс, альфа). */
    private fun sampleCycle(periodMs: Int, fps: Int, maxAlpha: Float): List<Pair<Float, Float>> {
        val frames = periodMs * fps / 1000
        return (0..frames).map { frame ->
            val timeMs = frame.toFloat() / fps * 1000f
            val phase = (timeMs % periodMs) / periodMs
            timeMs to MarsPulsePolicy.alphaAt(phase, maxAlpha)
        }
    }

    @Test
    fun `cycle shape does not depend on the frame rate`() {
        val period = MarsPulsePolicy.PulseRole.ONLINE.periodMs
        listOf(30, 60, 90, 120).forEach { fps ->
            val frames = sampleCycle(period, fps, MarsPulsePolicy.ONLINE_MAX_ALPHA)
            val first = frames.first().second
            val last = frames.last().second
            assertEquals("fps=$fps: цикл обязан начинаться в минимуме", MarsPulsePolicy.MIN_ALPHA, first, 1e-4f)
            assertEquals("fps=$fps: цикл обязан закрываться в минимуме", MarsPulsePolicy.MIN_ALPHA, last, 1e-4f)

            val peak = frames.maxOf { it.second }
            assertEquals(
                "fps=$fps: пик не должен зависеть от частоты кадров",
                MarsPulsePolicy.ONLINE_MAX_ALPHA,
                peak,
                1e-2f
            )
            val peakTime = frames.first { abs(it.second - peak) < 1e-4f }.first
            assertEquals(
                "fps=$fps: пик обязан прийтись на середину периода",
                period / 2f,
                peakTime,
                period / fps.toFloat() + 1f
            )

            // Сэмплирование чаще не должно давать «дребезг»: шаг между кадрами
            // обязан быть мал относительно амплитуды.
            val maxStep = frames.zipWithNext { a, b -> abs(b.second - a.second) }.max()
            assertTrue(
                "fps=$fps: скачок альфы за кадр $maxStep слишком велик для мягкой пульсации",
                maxStep < 0.05f
            )
        }
    }

    @Test
    fun `slower role breathes more gently per frame than the running role`() {
        val running = sampleCycle(
            MarsPulsePolicy.PulseRole.RUNNING.periodMs, 60, MarsPulsePolicy.PulseRole.RUNNING.maxAlpha
        )
        val online = sampleCycle(
            MarsPulsePolicy.PulseRole.ONLINE.periodMs, 60, MarsPulsePolicy.PulseRole.ONLINE.maxAlpha
        )
        val runningStep = running.zipWithNext { a, b -> abs(b.second - a.second) }.max()
        val onlineStep = online.zipWithNext { a, b -> abs(b.second - a.second) }.max()
        assertTrue(
            "точка «онлайн» ($onlineStep) должна дышать мягче точки «выполняется» ($runningStep)",
            onlineStep < runningStep
        )
    }

    // ---- Режим: пульсация или статика ----

    @Test
    fun `full motion enables the pulse`() {
        assertEquals(
            MarsPulsePolicy.PulseMode.PULSE,
            MarsPulsePolicy.modeFor(
                animationsRequested = true,
                animatorsEnabled = true,
                powerSaveMode = false,
                animatorDurationScale = 1f,
            )
        )
    }

    @Test
    fun `disabled system animators force the static dot`() {
        assertEquals(
            "ValueAnimator.areAnimatorsEnabled() == false обязан гасить пульсацию",
            MarsPulsePolicy.PulseMode.STATIC,
            MarsPulsePolicy.modeFor(true, animatorsEnabled = false, powerSaveMode = false, animatorDurationScale = 1f)
        )
    }

    @Test
    fun `power save mode forces the static dot`() {
        assertEquals(
            "экономия батареи обязана гасить пульсацию",
            MarsPulsePolicy.PulseMode.STATIC,
            MarsPulsePolicy.modeFor(true, animatorsEnabled = true, powerSaveMode = true, animatorDurationScale = 1f)
        )
    }

    @Test
    fun `reduce motion forces the static dot`() {
        listOf(0f, -1f, Float.NaN).forEach { scale ->
            assertEquals(
                "duration scale $scale — это reduce motion, пульсации быть не должно",
                MarsPulsePolicy.PulseMode.STATIC,
                MarsPulsePolicy.modeFor(true, animatorsEnabled = true, powerSaveMode = false, animatorDurationScale = scale)
            )
        }
        assertEquals(
            "дробный масштаб анимаций остаётся полным движением",
            MarsPulsePolicy.PulseMode.PULSE,
            MarsPulsePolicy.modeFor(true, animatorsEnabled = true, powerSaveMode = false, animatorDurationScale = 0.5f)
        )
    }

    @Test
    fun `caller can switch the pulse off entirely`() {
        assertEquals(
            MarsPulsePolicy.PulseMode.STATIC,
            MarsPulsePolicy.modeFor(false, animatorsEnabled = true, powerSaveMode = false, animatorDurationScale = 1f)
        )
    }

    @Test
    fun `static mode has no period and keeps the resting halo alpha`() {
        MarsPulsePolicy.PulseRole.values().forEach { role ->
            assertEquals("в статике цикла нет", 0, MarsPulsePolicy.periodFor(role, MarsPulsePolicy.PulseMode.STATIC))
            assertEquals(
                "статичный ореол обязан совпасть с сегодняшним оттенком — вид не меняется",
                MarsGradients.GLOW_RING_ALPHA,
                MarsPulsePolicy.startAlphaByte(role, MarsPulsePolicy.PulseMode.STATIC)
            )
            assertEquals(role.periodMs, MarsPulsePolicy.periodFor(role, MarsPulsePolicy.PulseMode.PULSE))
        }
    }

    @Test
    fun `pulse starts in the darkest phase so the halo never jumps on attach`() {
        MarsPulsePolicy.PulseRole.values().forEach { role ->
            assertEquals(
                "роль $role должна стартовать с минимума",
                MarsPulsePolicy.alphaByteAt(0f, role.maxAlpha),
                MarsPulsePolicy.startAlphaByte(role, MarsPulsePolicy.PulseMode.PULSE)
            )
        }
    }

    // ---- Маппинг тона в назначение ----

    @Test
    fun `only live status tones pulse`() {
        assertEquals(
            MarsPulsePolicy.PulseRole.RUNNING,
            MarsPulsePolicy.roleForColor(MarsGradients.STATUS_RUNNING)
        )
        assertEquals(
            MarsPulsePolicy.PulseRole.ONLINE,
            MarsPulsePolicy.roleForColor(MarsGradients.STATUS_ONLINE)
        )
    }

    @Test
    fun `non-live tones stay static`() {
        val staticTones = listOf(
            MarsGradients.STATUS_ERROR,
            MarsGradients.STATUS_WARNING,
            MarsGradients.STATUS_INFO,
            MarsTheme.TEXT_MUTED,
            MarsTheme.ACCENT,
        )
        staticTones.forEach { tone ->
            assertNull("тон $tone не описывает живое состояние и пульсировать не должен",
                MarsPulsePolicy.roleForColor(tone))
        }
    }

    @Test
    fun `running and online tones are distinct so the roles never collide`() {
        assertNotEquals(MarsGradients.STATUS_RUNNING, MarsGradients.STATUS_ONLINE)
    }

    // ---- Статичная фаза ----

    @Test
    fun `no pulse frame is dimmer than today's static halo`() {
        // Сегодняшний ореол — GLOW_RING_ALPHA (0x33). Самый тёмный кадр цикла не
        // должен быть темнее: иначе пульсация «проваливала» бы точку ниже нынешнего
        // вида, и она пропадала бы в тёмной теме.
        val dimmest = MarsPulsePolicy.alphaByteAt(0f)
        assertTrue(
            "тёмный кадр $dimmest не должен быть темнее сегодняшнего ореола ${MarsGradients.GLOW_RING_ALPHA}",
            dimmest >= MarsGradients.GLOW_RING_ALPHA
        )
        val peak = MarsPulsePolicy.alphaByteAt(0.5f)
        assertTrue("пик $peak обязан быть выразительнее тёмного кадра $dimmest", peak > dimmest)
        assertTrue(
            "размах цикла обязан быть заметен глазом (>= 16/255)",
            peak - dimmest >= 16
        )
        val onlineDimmest = MarsPulsePolicy.alphaByteAt(0f, MarsPulsePolicy.ONLINE_MAX_ALPHA)
        val onlinePeak = MarsPulsePolicy.alphaByteAt(0.5f, MarsPulsePolicy.ONLINE_MAX_ALPHA)
        assertTrue(
            "онлайн-точка обязана оставаться мягче: её размах меньше",
            onlinePeak - onlineDimmest < peak - dimmest
        )
        assertTrue(
            "онлайн-точка обязана оставаться видимой в самой тёмной фазе",
            onlineDimmest >= MarsGradients.GLOW_RING_ALPHA
        )
    }

    @Test
    fun `pulse policy never produces motion tokens of the transition policy`() {
        // Циклы и переходы — разные сущности: период пульсации заведомо больше
        // самого длинного перехода, иначе «мягкое дыхание» превратилось бы в мигание.
        MarsPulsePolicy.PulseRole.values().forEach { role ->
            assertTrue(
                "период роли $role не должен подменяться токенами переходов MarsMotion",
                role.periodMs > MarsMotion.slowMs
            )
        }
        assertFalse(MarsPulsePolicy.PulseRole.values().isEmpty())
    }
}
