package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контракт слоя движения TV-клиента (чистый JVM, без Robolectric):
 *
 * - масштаб длительности аниматоров 0f, отрицательный или NaN — «уменьшить движение»;
 * - при уменьшенном движении каждый cue схлопывается в 0 мс;
 * - при нормальном масштабе длительности равны токенам [TvMotionTokens];
 * - токены TV не разъезжаются с mobile `MarsMotion` (сверка по исходнику mobile-модуля);
 * - в main-исходниках app-tv не осталось захардкоженных длительностей анимации.
 */
class TvMotionPolicyTest {

    /** Каталог модуля app-tv (тесты Gradle запускаются из него; ищем от user.dir). */
    private val moduleDir: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) {
        it.parentFile
    }.take(4).first { File(it, "src/main/java/ru/mars/tv").isDirectory }

    private val mainDir = File(moduleDir, "src/main")
    private val mobileTokensFile = File(
        moduleDir,
        "../app-mobile/src/main/java/ru/mars/mobile/MarsMotion.kt",
    )

    private fun mainSources(ext: String): List<File> =
        mainDir.walkTopDown().filter { it.isFile && it.extension == ext }.toList()

    private fun mobileToken(text: String, name: String): Int {
        val match = Regex("const val $name: Int = (\\d+)").find(text)
            ?: error("MarsMotion.$name не найден в mobile-токенах")
        return match.groupValues[1].toInt()
    }

    // --- контракт политики ---------------------------------------------------------------

    @Test
    fun `scale 1f is not reduced motion`() {
        assertFalse(TvMotionPolicy.isReducedMotion(1f))
    }

    @Test
    fun `scale 0f is reduced motion`() {
        assertTrue(TvMotionPolicy.isReducedMotion(0f))
    }

    @Test
    fun `negative and NaN scales are reduced motion`() {
        assertTrue(TvMotionPolicy.isReducedMotion(Float.NaN))
        assertTrue(TvMotionPolicy.isReducedMotion(-1f))
        assertTrue(TvMotionPolicy.isReducedMotion(-0.5f))
    }

    @Test
    fun `reduced motion collapses every cue to zero`() {
        TvMotionCue.values().forEach { cue ->
            assertEquals(
                "cue $cue обязан быть мгновенным при уменьшенном движении",
                0,
                TvMotionPolicy.durationFor(cue, reducedMotion = true),
            )
        }
    }

    @Test
    fun `full motion maps content cues to baseMs`() {
        assertEquals(TvMotionTokens.baseMs, TvMotionPolicy.durationFor(TvMotionCue.CHAT_MESSAGE_REVEAL, false))
        assertEquals(TvMotionTokens.baseMs, TvMotionPolicy.durationFor(TvMotionCue.CARD_EXPAND, false))
        assertEquals(TvMotionTokens.baseMs, TvMotionPolicy.durationFor(TvMotionCue.NETWORK_BANNER, false))
    }

    @Test
    fun `full motion maps micro cues to fastMs`() {
        assertEquals(TvMotionTokens.fastMs, TvMotionPolicy.durationFor(TvMotionCue.SCROLL_TO_BOTTOM_HINT, false))
        assertEquals(TvMotionTokens.fastMs, TvMotionPolicy.durationFor(TvMotionCue.FOCUS_LIFT, false))
    }

    @Test
    fun `every cue is positive with full motion and never exceeds slowMs`() {
        TvMotionCue.values().forEach { cue ->
            val full = TvMotionPolicy.durationFor(cue, reducedMotion = false)
            val reduced = TvMotionPolicy.durationFor(cue, reducedMotion = true)
            assertTrue("cue $cue должен быть положительным, было $full", full > 0)
            assertTrue("cue $cue длиннее slowMs: $full", full <= TvMotionTokens.slowMs)
            assertTrue("cue $cue при уменьшенном движении не ноль: $reduced", reduced == 0)
        }
    }

    // --- токены -------------------------------------------------------------------------

    @Test
    fun `tokens match the calm motion stance`() {
        assertEquals(120, TvMotionTokens.fastMs)
        assertEquals(200, TvMotionTokens.baseMs)
        assertEquals(300, TvMotionTokens.slowMs)
    }

    @Test
    fun `TV tokens match the mobile MarsMotion values`() {
        assertTrue(
            "mobile-токены не найдены: $mobileTokensFile",
            mobileTokensFile.isFile,
        )
        val mobile = mobileTokensFile.readText()
        assertEquals("fastMs разъехался с mobile", mobileToken(mobile, "fastMs"), TvMotionTokens.fastMs)
        assertEquals("baseMs разъехался с mobile", mobileToken(mobile, "baseMs"), TvMotionTokens.baseMs)
        assertEquals("slowMs разъехался с mobile", mobileToken(mobile, "slowMs"), TvMotionTokens.slowMs)
    }

    @Test
    fun `decelerate is the default curve and bounce or overshoot are forbidden`() {
        assertEquals("decelerate", TvMotionTokens.INTERPOLATOR_DECELERATE)
        assertTrue(TvMotionTokens.isAllowedInterpolator(TvMotionTokens.INTERPOLATOR_DECELERATE))
        assertTrue(TvMotionTokens.isAllowedInterpolator(TvMotionTokens.INTERPOLATOR_ACCELERATE))
        assertFalse(TvMotionTokens.isAllowedInterpolator(TvMotionTokens.INTERPOLATOR_BOUNCE))
        assertFalse(TvMotionTokens.isAllowedInterpolator(TvMotionTokens.INTERPOLATOR_OVERSHOOT))
    }

    // --- guard: длительностей в исходниках не осталось ------------------------------------

    @Test
    fun `no hardcoded animator duration remains in main sources`() {
        val literalDuration = Regex("""setDuration\(\s*\d""")
        val legacyConst = Regex("""ANIM_MS\s*=""")
        val offenders = mainSources("kt").filter { file ->
            val text = file.readText()
            literalDuration.containsMatchIn(text) || legacyConst.containsMatchIn(text)
        }.map { it.name }
        assertTrue(
            "захардкоженные длительности анимации остались: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `every focus lift site resolves its duration through the policy`() {
        // Фокус — единственная реальная анимация app-tv: все её вызовы обязаны идти через
        // TvFocusLift (он берёт длительность из политики), а не через собственный animate().
        // Голый animate().cancel() движением не является и здесь не считается.
        val directAnimator = Regex("""\.animate\(\)\s*\.(?!cancel)\w""")
        val offenders = mainSources("kt")
            .filter { it.name != "TvFocusLift.kt" }
            .filter { directAnimator.containsMatchIn(it.readText()) }
            .map { it.name }
        assertTrue("прямые ViewPropertyAnimator вне TvFocusLift: $offenders", offenders.isEmpty())
    }
}
