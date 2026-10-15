package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-контракт анимированного статус-индикатора (Instrumented-слоя нет,
 * поэтому проводка закреплена по реальному production-исходнику — тот же приём,
 * что в InsightsShellContractTest/KanbanShellContractTest).
 *
 * Проверяет то, что нельзя выразить чистой JVM-логикой:
 * - публичная сигнатура [MarsTheme.glowingStatusDot] не изменилась (её зовут
 *   ChatActivity/Todos/Cron — файл ChatActivity НЕ трогаем);
 * - анимация идёт по альфе ОРЕОЛ-слоя (`setLayerAlpha` старшего слоя), а не по
 *   альфе/масштабу вьюхи — иначе пульсировало бы и ядро, и текст;
 * - аниматор отменяется при detach от окна (в чате карточки пересоздаются на
 *   каждом рендере — иначе утечка аниматоров);
 * - пульсация гасится при отключённых аниматорах/экономии батареи/reduce motion.
 */
class StatusDotPulseContractTest {

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

    /**
     * Исходник без комментариев. Проверки «чего в коде быть не должно» обязаны
     * смотреть на КОД: доки специально называют скрытый `setLayerAlpha` и
     * `MotionCue` как то, чего мы сознательно не используем, и по сырому тексту
     * запрет срабатывал бы на собственное объяснение.
     */
    private fun codeOnly(raw: String): String =
        raw.replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("(?m)//.*$"), " ")

    private val theme by lazy { source("src/main/java/ru/mars/mobile/MarsTheme.kt") }
    private val pulse by lazy { source("src/main/java/ru/mars/mobile/StatusDotPulse.kt") }
    private val policy by lazy { source("src/main/java/ru/mars/mobile/MarsPulsePolicy.kt") }
    private val motion by lazy { source("src/main/java/ru/mars/mobile/MarsMotion.kt") }

    @Test
    fun `public signature of glowingStatusDot is unchanged`() {
        assertTrue(
            "Публичная сигнатура обязана остаться ровно такой — её зовут экраны, включая ChatActivity",
            theme.contains("fun glowingStatusDot(context: Context, color: Int): View")
        )
        assertFalse(
            "Перегрузок с доп. параметрами быть не должно: вызывающий код (ChatActivity) не трогаем",
            theme.contains("fun glowingStatusDot(context: Context, color: Int, ")
        )
    }

    @Test
    fun `pulse is wired through the policy and the attach helper`() {
        assertTrue(
            "Маршрутизация по тону обязана идти через политику",
            theme.contains("MarsPulsePolicy.roleForColor(color)")
        )
        assertTrue(
            "Анимация подключается отдельным хелпером, а не кодом внутри view-фабрики",
            theme.contains("StatusDotPulse.attach(context, dot, layers, role)")
        )
        assertTrue(theme.contains("import android.graphics.drawable.LayerDrawable"))
    }

    @Test
    fun `only the halo layer alpha animates, never the view alpha or scale`() {
        assertTrue(
            "Пульсирует альфа drawable-ореола (слой HALO_LAYER_INDEX)",
            pulse.contains("layers.getDrawable(HALO_LAYER_INDEX)")
        )
        assertTrue(
            "Альфа кадра обязана считаться политикой",
            pulse.contains("MarsPulsePolicy.alphaByteAt(")
        )
        assertTrue(
            "Альфа ставится на сам drawable ореола, а не на LayerDrawable целиком: " +
                "унаследованный setAlpha бьёт по всем слоям и заставил бы мигать ядро",
            pulse.contains("halo.alpha =")
        )
        assertFalse(
            "LayerDrawable.setLayerAlpha — скрытый API, его в android-34 нет вовсе",
            codeOnly(pulse).contains("setLayerAlpha")
        )
        listOf("dot.alpha =", "dot.scaleX", "dot.scaleY", "dot.translationY", "dot.translationX")
            .forEach { forbidden ->
                assertFalse(
                    "Пульсация не должна трогать вьюху напрямую ($forbidden): иначе пульсируют ядро/геометрия",
                    pulse.contains(forbidden)
                )
            }
    }

    @Test
    fun `halo drawable is isolated from the shared constant state`() {
        assertTrue(
            "mutate() обязателен: без него альфа уехала бы на все индикаторы списка сразу",
            pulse.contains("halo.mutate()")
        )
        assertTrue(
            "mutate() обязан идти ДО первой записи альфы",
            pulse.indexOf("halo.mutate()") < pulse.indexOf("halo.alpha =")
        )
        assertTrue(
            "У пульсирующей точки ореол берётся как непрозрачный тон + mutate (вид непульсирующих не меняется)",
            theme.contains("ovalDrawable(context, color).mutate()")
        )
        assertTrue(
            "Непульсирующие тона обязаны сохранить прежний полупрозрачный glowRing",
            theme.contains("ovalDrawable(context, MarsGradients.glowRing(color))")
        )
        assertFalse(
            "Ядро (слой 1) не должно получать mutate внутри пульсации",
            pulse.contains("getDrawable(1)")
        )
    }

    @Test
    fun `animator is cancelled on detach from the window`() {
        assertTrue(
            "Нужен слушатель attach/detach",
            pulse.contains("addOnAttachStateChangeListener")
        )
        assertTrue(
            "На detach аниматор обязан отменяться",
            pulse.contains("onViewDetachedFromWindow") && pulse.contains("animator.cancel()")
        )
        assertTrue(
            "Повторный attach не должен плодить второй цикл",
            pulse.contains("if (!animator.isStarted) animator.start()")
        )
        assertTrue(
            "Вьюха, созданная уже прикреплённой, обязана стартовать цикл сразу",
            pulse.contains("isAttachedToWindow")
        )
    }

    @Test
    fun `animator is infinite only when the mode allows it`() {
        assertTrue(pulse.contains("ValueAnimator.INFINITE"))
        assertTrue(
            "Старт цикла возможен только после проверки режима",
            pulse.indexOf("if (mode != MarsPulsePolicy.PulseMode.PULSE) return") <
                pulse.indexOf("ValueAnimator.ofFloat(0f, 1f)")
        )
        assertTrue(
            "Easing считает политика, поэтому аниматор линеен",
            pulse.contains("LinearInterpolator()")
        )
    }

    @Test
    fun `escape hatches are read from the system`() {
        assertTrue(
            "Системное «убрать анимации»",
            pulse.contains("ValueAnimator.areAnimatorsEnabled()")
        )
        assertTrue(
            "Экономия батареи",
            pulse.contains("isPowerSaveMode")
        )
        assertTrue(
            "Тот же источник reduce motion, что у остального motion клиента",
            pulse.contains("Settings.Global.ANIMATOR_DURATION_SCALE")
        )
        assertTrue(
            "Решение о режиме принимается политикой, а не UI-кодом",
            pulse.contains("MarsPulsePolicy.modeFor(")
        )
    }

    @Test
    fun `pulse cycle is not routed through the transition policy`() {
        assertFalse(
            "Период пульсации не должен жить в перечне переходов MotionCue",
            codeOnly(policy).contains("MotionCue")
        )
        assertFalse(
            "Пульсацию нельзя прогонять через MotionPolicy.durationFor: его 300 ms ceiling " +
                "схлопнул бы живой цикл в мгновенную смену состояния",
            codeOnly(policy).contains("durationFor")
        )
        assertTrue(
            "Но reduce motion обязан гасить пульсацию через MotionPolicy",
            policy.contains("MotionPolicy.isReducedMotion(")
        )
    }

    @Test
    fun `pulse policy stays free of android classes`() {
        listOf("android.", "import android").forEach { needle ->
            assertFalse(
                "MarsPulsePolicy обязан оставаться чистой JVM-логикой ($needle)",
                codeOnly(policy).contains(needle)
            )
        }
    }

    @Test
    fun `motion stance documents the live-state pulse exception`() {
        assertTrue(
            "Исключение для индикаторов живого состояния должно быть зафиксировано в стойке",
            motion.contains("live-state indicators may pulse")
        )
        assertTrue(
            "Стойка обязана ссылаться на новую политику, чтобы её не «починили» как баг",
            motion.contains("MarsPulsePolicy")
        )
        assertTrue(
            "Запрет декоративных циклов обязан остаться",
            motion.contains("no infinite/decorative looping animations")
        )
    }

    @Test
    fun `policy data matches the view layer expectations`() {
        assertEquals(MarsPulsePolicy.MIN_ALPHA, 0.25f, 0f)
        assertEquals(MarsPulsePolicy.MAX_ALPHA, 0.65f, 0f)
        assertEquals(MarsPulsePolicy.PulseRole.RUNNING, MarsPulsePolicy.roleForColor(MarsGradients.STATUS_RUNNING))
        assertEquals(MarsPulsePolicy.PulseRole.ONLINE, MarsPulsePolicy.roleForColor(MarsGradients.STATUS_ONLINE))
    }
}
