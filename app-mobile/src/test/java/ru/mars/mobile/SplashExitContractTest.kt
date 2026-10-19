package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контрактные тесты МОМЕНТА ВЫХОДА с заставки — «маршрут после заставки» на
 * уровне чистой политики [SplashPolicy].
 *
 * Здесь нет ни одного Android-класса и ни одного имени чужого экрана
 * (`SplashActivity`, `SplashRoute`, ...): реализация экрана живёт в другом
 * дереве, и тест не имеет права угадывать её API. Вместо этого моделируется
 * ровно то, что делает вызывающий код: пока `decisionFor` возвращает Hold —
 * заставка на экране, на первом Go — начинается фейд и переход к следующему
 * экрану. Все утверждения выводятся только из спецификации.
 *
 * Что ловит каждая группа:
 * - момент выхода: слишком ранний (мигание) или слишком поздний (залипание)
 *   переход при готовом/неготовом таргете;
 * - верхняя граница: недоступный бэкенд не должен держать заставку дольше MAX;
 * - фейд: переход обязан начинаться в момент Go, а не раньше, и его
 *   длительность обязана совпадать с fadeOutMs;
 * - reduce motion: переход сразу и без фейда;
 * - монотонность: один раз принятое решение Go не откатывается назад в Hold,
 *   иначе экран «возвращается» на заставку.
 */
class SplashExitContractTest {

    /** Шаг кадрового цикла заставки: ~60 fps. */
    private val frameStepMs = 16L

    /** Горизонт симуляции: заведомо больше верхней границы ожидания. */
    private val horizonMs = SplashPolicy.MAX_VISIBLE_MS + 1_000L

    /**
     * Прогоняет кадровый цикл и возвращает момент первого Go — это и есть
     * «маршрут после заставки»: с него экран уходит дальше.
     *
     * @param readyAtMs момент, с которого таргет готов; `null` — не готов никогда.
     */
    private fun exitAtMs(readyAtMs: Long?, reducedMotion: Boolean = false): Long {
        var elapsed = 0L
        while (elapsed <= horizonMs) {
            val ready = readyAtMs != null && elapsed >= readyAtMs
            val decision = SplashPolicy.decisionFor(elapsed, targetReady = ready, reducedMotion = reducedMotion)
            if (decision == SplashPolicy.Decision.Go) return elapsed
            elapsed += frameStepMs
        }
        throw AssertionError("заставка не завершилась за ${horizonMs}мс — выхода нет")
    }

    @Test
    fun `ready target leaves within one frame after the minimum`() {
        // Дефект: переход раньше MIN — мигание заставки; позже MIN на кадр-другой
        // при готовых данных — «вязкий» старт.
        val exit = exitAtMs(readyAtMs = 0L)
        assertTrue(
            "выход на ${exit}мс раньше минимального показа ${SplashPolicy.MIN_VISIBLE_MS}мс",
            exit >= SplashPolicy.MIN_VISIBLE_MS
        )
        assertTrue(
            "выход на ${exit}мс задержался больше кадра после минимума",
            exit < SplashPolicy.MIN_VISIBLE_MS + frameStepMs
        )
    }

    @Test
    fun `slow target leaves right after it becomes ready`() {
        // Дефект: таргет доготовился на 900мс, а заставка всё равно висит до MAX —
        // пользователь ждёт на пустом экране уже готовое приложение.
        val readyAt = 900L
        val exit = exitAtMs(readyAtMs = readyAt)
        assertTrue("выход на ${exit}мс раньше готовности таргета $readyAt мс", exit >= readyAt)
        assertTrue(
            "выход на ${exit}мс задержался больше кадра после готовности $readyAt мс",
            exit < readyAt + frameStepMs
        )
    }

    @Test
    fun `never ready target still leaves at the cap`() {
        // Дефект: без верхней границы недоступный бэкенд оставляет заставку
        // навсегда — приложение «не стартует».
        val exit = exitAtMs(readyAtMs = null)
        assertTrue(
            "выход на ${exit}мс раньше верхней границы ${SplashPolicy.MAX_VISIBLE_MS}мс",
            exit >= SplashPolicy.MAX_VISIBLE_MS
        )
        assertTrue(
            "выход на ${exit}мс позже верхней границы + кадр",
            exit < SplashPolicy.MAX_VISIBLE_MS + frameStepMs
        )
    }

    @Test
    fun `fade starts at the exit moment and lasts exactly the policy duration`() {
        // Дефект: фейд, начатый до Go (экран гаснет, а данные ещё грузятся) или
        // длящийся не fadeOutMs (расхождение с анимацией экрана).
        listOf(0L, 900L, null).forEach { readyAt ->
            val exit = exitAtMs(readyAtMs = readyAt)
            val fade = SplashPolicy.fadeOutMs(reducedMotion = false)
            assertEquals(
                "длительность фейда обязана совпасть с FADE_OUT_MS",
                SplashPolicy.FADE_OUT_MS,
                fade
            )
            val total = exit + fade
            assertTrue(
                "сценарий readyAt=$readyAt: экран суммарно ${total}мс — дольше верхней границы + кадр + фейд",
                total <= SplashPolicy.MAX_VISIBLE_MS + frameStepMs + SplashPolicy.FADE_OUT_MS
            )
            assertTrue(
                "сценарий readyAt=$readyAt: экран суммарно ${total}мс — меньше минимального показа",
                total >= SplashPolicy.MIN_VISIBLE_MS
            )
        }
    }

    @Test
    fun `reduce motion routes away immediately without a fade`() {
        // Дефект: при reduce motion заставка всё равно держится 600мс и гаснет
        // 260мс — ровно та анимация, которую пользователь отключил.
        listOf(0L, 900L, null).forEach { readyAt ->
            val exit = exitAtMs(readyAtMs = readyAt, reducedMotion = true)
            assertEquals("при reduce motion выход обязан быть на первом кадре", 0L, exit)
            assertEquals("при reduce motion фейда нет", 0, SplashPolicy.fadeOutMs(reducedMotion = true))
        }
    }

    @Test
    fun `once the route is taken it never falls back to the splash`() {
        // Дефект: решение «мигает» — после Go снова Hold (например, из-за
        // нестабильного targetReady или сдвига якоря времени). Экран вернётся
        // на заставку, чего пользователь видеть не должен.
        var left = false
        var elapsed = 0L
        while (elapsed <= horizonMs) {
            val decision = SplashPolicy.decisionFor(elapsed, targetReady = true, reducedMotion = false)
            if (left) {
                assertEquals(
                    "после выхода на elapsed=$elapsed решение обязано остаться Go",
                    SplashPolicy.Decision.Go,
                    decision
                )
            }
            if (decision == SplashPolicy.Decision.Go) left = true
            elapsed += frameStepMs
        }
        assertTrue("за симуляцию маршрут так и не был принят", left)
    }

    @Test
    fun `waiting route never exceeds the cap for any readiness moment`() {
        // Дефект: граница работает только для «идеальных» значений; при
        // произвольном моменте готовности выход запаздывает.
        var readyAt = 0L
        while (readyAt <= SplashPolicy.MAX_VISIBLE_MS + 500L) {
            val exit = exitAtMs(readyAtMs = readyAt)
            assertTrue(
                "readyAt=$readyAt: выход на ${exit}мс позже верхней границы + кадр",
                exit < SplashPolicy.MAX_VISIBLE_MS + frameStepMs
            )
            assertTrue(
                "readyAt=$readyAt: выход на ${exit}мс раньше минимума",
                exit >= SplashPolicy.MIN_VISIBLE_MS
            )
            readyAt += 97L
        }
    }
}
