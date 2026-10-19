package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контрактные JVM-тесты политики таймингов заставки [SplashPolicy].
 *
 * Тесты написаны ПО СПЕЦИФИКАЦИИ, без доступа к реализации: любая корректная
 * реализация обязана их пройти, любая сдвинутая граница — упасть. Ни одного
 * Android-класса здесь нет, поэтому Robolectric не нужен.
 *
 * Что именно ловит каждая группа:
 * - константы: «поехавшие» 600/1800/260/3000 мс и рассогласование с периодом
 *   пульсации онлайн-точки ([MarsPulsePolicy.PERIOD_ONLINE_MS]);
 * - reduce motion: заставка, которая всё равно держит экран или гасит фейд
 *   не в ноль, — прямой путь к «зависшему» старту у пользователя с
 *   отключёнными анимациями;
 * - минимальный показ: мигание заставки на быстром старте (Hold(0) или
 *   пропуск минимального показа);
 * - ограниченное ожидание: бесконечный сплэш при недоступном бэкенде и
 *   Hold(0) — «крутимся, но условие выхода уже выполнено»;
 * - готовность таргета и верхняя граница: преждевременный уход и, наоборот,
 *   игнорирование готовности;
 * - границы и переполнение: отрицательный elapsed, ровно MIN/MAX,
 *   Long.MAX_VALUE — исключения и отрицательный остаток в Int;
 * - shouldPulse: пульсация в статике (нарушение reduce motion).
 */
class SplashPolicyTest {

    // ── Вспомогательные проверки ─────────────────────────────────────────────

    /** Остаток из решения; падает, если решение не Hold. */
    private fun remainingOf(decision: SplashPolicy.Decision): Int {
        assertTrue("ожидался Hold, получено $decision", decision is SplashPolicy.Decision.Hold)
        return (decision as SplashPolicy.Decision.Hold).remainingMs
    }

    /** Проверяет и тип, и точный остаток — сдвиг на 1 мс тоже дефект. */
    private fun assertHold(expectedMs: Int, decision: SplashPolicy.Decision, message: String = "") {
        assertEquals(
            "ожидался Hold($expectedMs), получено $decision. $message",
            SplashPolicy.Decision.Hold(expectedMs),
            decision
        )
        assertEquals("остаток Hold обязан совпасть с ожидаемым", expectedMs, remainingOf(decision))
    }

    private fun assertGo(decision: SplashPolicy.Decision, message: String = "") {
        assertEquals("ожидался Go, получено $decision. $message", SplashPolicy.Decision.Go, decision)
    }

    /**
     * Эталонная модель из спецификации. Нужна, чтобы плотным перебором поймать
     * off-by-one на любой границе, а не только на трёх «красивых» значениях.
     */
    private fun expectedDecision(
        elapsedMs: Long,
        targetReady: Boolean,
        reducedMotion: Boolean,
    ): SplashPolicy.Decision {
        if (reducedMotion) return SplashPolicy.Decision.Go
        val elapsed = elapsedMs.coerceAtLeast(0L)
        if (elapsed < SplashPolicy.MIN_VISIBLE_MS) {
            return SplashPolicy.Decision.Hold((SplashPolicy.MIN_VISIBLE_MS - elapsed).toInt())
        }
        if (elapsed >= SplashPolicy.MAX_VISIBLE_MS) return SplashPolicy.Decision.Go
        return if (targetReady) {
            SplashPolicy.Decision.Go
        } else {
            SplashPolicy.Decision.Hold((SplashPolicy.MAX_VISIBLE_MS - elapsed).toInt())
        }
    }

    // ── Константы ────────────────────────────────────────────────────────────

    @Test
    fun `minimum visible is shorter than the maximum wait`() {
        // Если MIN > MAX, окно ожидания пустое и decisionFor не сможет вернуть
        // Hold для «жду таргет»: заставка либо мигнёт, либо зависнет.
        assertTrue(
            "MIN_VISIBLE_MS (${SplashPolicy.MIN_VISIBLE_MS}) обязан быть <= MAX_VISIBLE_MS (${SplashPolicy.MAX_VISIBLE_MS})",
            SplashPolicy.MIN_VISIBLE_MS <= SplashPolicy.MAX_VISIBLE_MS
        )
        assertTrue(
            "минимальный показ обязан быть положительным, иначе заставка не видна",
            SplashPolicy.MIN_VISIBLE_MS > 0
        )
    }

    @Test
    fun `timing constants match the spec`() {
        assertEquals("минимальный показ заставки", 600, SplashPolicy.MIN_VISIBLE_MS)
        assertEquals("верхняя граница ожидания", 1800, SplashPolicy.MAX_VISIBLE_MS)
        assertEquals("длительность фейда", 260, SplashPolicy.FADE_OUT_MS)
        assertTrue("фейд обязан быть положительным", SplashPolicy.FADE_OUT_MS > 0)
        assertTrue(
            "фейд не должен съедать весь минимальный показ",
            SplashPolicy.FADE_OUT_MS < SplashPolicy.MIN_VISIBLE_MS
        )
    }

    @Test
    fun `pulse period matches the online status dot`() {
        // Заставка не заводит собственный ритм: она переиспользует мягкий период
        // онлайн-точки, иначе два индикатора на экране мигали бы вразнобой.
        assertEquals(
            "период пульсации заставки обязан совпасть с периодом онлайн-точки",
            MarsPulsePolicy.PERIOD_ONLINE_MS,
            SplashPolicy.PULSE_PERIOD_MS
        )
        assertEquals(
            "PULSE_PERIOD_MS обязан быть ровно периодом онлайн-точки (3000)",
            3000,
            SplashPolicy.PULSE_PERIOD_MS
        )
        assertEquals(
            "pulsePeriodMs(false) обязан вернуть PULSE_PERIOD_MS",
            SplashPolicy.PULSE_PERIOD_MS,
            SplashPolicy.pulsePeriodMs(reducedMotion = false)
        )
    }

    // ── Reduce motion ────────────────────────────────────────────────────────

    @Test
    fun `reduce motion always goes whatever the clock says`() {
        // Дефект: реализация сначала проверяет elapsed/targetReady и только потом
        // reducedMotion — тогда пользователь с отключёнными анимациями всё равно
        // ждёт 600..1800 мс на пустом экране.
        val clocks = listOf(
            Long.MIN_VALUE, -1L, 0L, 1L, 16L,
            SplashPolicy.MIN_VISIBLE_MS - 1L, SplashPolicy.MIN_VISIBLE_MS.toLong(),
            SplashPolicy.MIN_VISIBLE_MS + 1L,
            SplashPolicy.MAX_VISIBLE_MS - 1L, SplashPolicy.MAX_VISIBLE_MS.toLong(),
            SplashPolicy.MAX_VISIBLE_MS + 1L, Int.MAX_VALUE.toLong(), Long.MAX_VALUE,
        )
        for (elapsed in clocks) {
            for (ready in listOf(true, false)) {
                assertGo(
                    SplashPolicy.decisionFor(elapsed, targetReady = ready, reducedMotion = true),
                    "reduce motion обязан уходить сразу: elapsed=$elapsed ready=$ready"
                )
            }
        }
    }

    @Test
    fun `reduce motion collapses fade and pulse to zero`() {
        // Дефект: фейд 260 мс или пульсация 3000 мс при reduce motion — это ровно
        // та анимация, которую пользователь просил выключить.
        assertEquals(
            "при reduce motion фейда быть не должно",
            0,
            SplashPolicy.fadeOutMs(reducedMotion = true)
        )
        assertEquals(
            "при reduce motion пульсации быть не должно",
            0,
            SplashPolicy.pulsePeriodMs(reducedMotion = true)
        )
    }

    // ── Минимальный показ ────────────────────────────────────────────────────

    @Test
    fun `zero elapsed holds for the whole minimum`() {
        // Дефект: Hold(0) на старте — заставка мигает одним кадром.
        assertHold(
            SplashPolicy.MIN_VISIBLE_MS,
            SplashPolicy.decisionFor(0L, targetReady = false, reducedMotion = false),
            "на нуле должен быть полный остаток минимального показа"
        )
        assertHold(
            SplashPolicy.MIN_VISIBLE_MS,
            SplashPolicy.decisionFor(0L, targetReady = true, reducedMotion = false),
            "готовый таргет не отменяет минимальный показ"
        )
    }

    @Test
    fun `hold counts down to the minimum`() {
        // Дефект: остаток считается от MAX вместо MIN (или в секундах) — на глаз
        // это «заставка висит дольше, чем нужно».
        val samples = listOf(1L, 16L, 100L, 300L, 599L)
        for (elapsed in samples) {
            val expected = (SplashPolicy.MIN_VISIBLE_MS - elapsed).toInt()
            assertHold(
                expected,
                SplashPolicy.decisionFor(elapsed, targetReady = false, reducedMotion = false),
                "остаток до минимума на elapsed=$elapsed"
            )
            assertHold(
                expected,
                SplashPolicy.decisionFor(elapsed, targetReady = true, reducedMotion = false),
                "готовность таргета не должна сокращать минимум (elapsed=$elapsed)"
            )
        }
    }

    @Test
    fun `negative elapsed behaves like zero`() {
        // Дефект: отрицательный elapsed (часы/монотонник до старта) даёт
        // Hold(600 + |elapsed|) или отрицательный остаток — заставка «висит».
        val atZero = SplashPolicy.decisionFor(0L, targetReady = false, reducedMotion = false)
        for (elapsed in listOf(-1L, -16L, -600L, Long.MIN_VALUE)) {
            val decision = SplashPolicy.decisionFor(elapsed, targetReady = false, reducedMotion = false)
            assertEquals(
                "отрицательный elapsed=$elapsed обязан трактоваться как 0",
                atZero,
                decision
            )
            assertHold(
                SplashPolicy.MIN_VISIBLE_MS,
                decision,
                "отрицательный elapsed=$elapsed не должен увеличивать остаток"
            )
        }
    }

    @Test
    fun `hold is never zero or negative at the start`() {
        // Дефект: Hold(0) — условие выхода «остаток <= 0» сработает в тот же кадр,
        // и минимальный показ фактически исчезнет.
        for (elapsed in -50L until SplashPolicy.MIN_VISIBLE_MS.toLong()) {
            val decision = SplashPolicy.decisionFor(elapsed, targetReady = false, reducedMotion = false)
            val remaining = remainingOf(decision)
            assertTrue(
                "на elapsed=$elapsed остаток $remaining обязан быть >= 1",
                remaining >= 1
            )
            assertTrue(
                "на elapsed=$elapsed остаток $remaining не может превышать минимум",
                remaining <= SplashPolicy.MIN_VISIBLE_MS
            )
        }
    }

    // ── Ограниченное ожидание таргета ────────────────────────────────────────

    @Test
    fun `waiting for the target is capped by the maximum`() {
        // Дефект: ожидание без верхней границы — при недоступном бэкенде заставка
        // остаётся навсегда; или остаток считается до MIN, и она уходит слишком рано.
        val samples = listOf(
            SplashPolicy.MIN_VISIBLE_MS.toLong(),
            SplashPolicy.MIN_VISIBLE_MS + 1L,
            900L,
            1200L,
            SplashPolicy.MAX_VISIBLE_MS - 1L,
        )
        for (elapsed in samples) {
            val expected = (SplashPolicy.MAX_VISIBLE_MS - elapsed).toInt()
            assertHold(
                expected,
                SplashPolicy.decisionFor(elapsed, targetReady = false, reducedMotion = false),
                "остаток до верхней границы на elapsed=$elapsed"
            )
        }
    }

    @Test
    fun `waiting hold is never zero and never exceeds the cap`() {
        // Дефект: Hold(0) в окне ожидания — «крутимся», хотя выходить уже некуда;
        // остаток больше MAX-MIN — ожидание растянуто.
        var checked = 0
        for (elapsed in SplashPolicy.MIN_VISIBLE_MS.toLong() until SplashPolicy.MAX_VISIBLE_MS.toLong()) {
            val decision = SplashPolicy.decisionFor(elapsed, targetReady = false, reducedMotion = false)
            val remaining = remainingOf(decision)
            assertTrue(
                "на elapsed=$elapsed остаток ожидания $remaining обязан быть >= 1",
                remaining >= 1
            )
            assertTrue(
                "на elapsed=$elapsed остаток ожидания $remaining больше окна ${SplashPolicy.MAX_VISIBLE_MS - SplashPolicy.MIN_VISIBLE_MS}",
                remaining <= SplashPolicy.MAX_VISIBLE_MS - SplashPolicy.MIN_VISIBLE_MS
            )
            checked++
        }
        assertTrue("перебор окна ожидания не выполнился", checked > 0)
    }

    @Test
    fun `remaining shrinks monotonically while waiting`() {
        // Дефект: немонотонный остаток (например, из-за приведения Long->Int или
        // пересчёта от другого якоря) — индикатор прогресса дёргается назад.
        var previous = SplashPolicy.MAX_VISIBLE_MS
        for (elapsed in SplashPolicy.MIN_VISIBLE_MS.toLong() until SplashPolicy.MAX_VISIBLE_MS.toLong()) {
            val remaining = remainingOf(
                SplashPolicy.decisionFor(elapsed, targetReady = false, reducedMotion = false)
            )
            assertTrue(
                "остаток вырос на elapsed=$elapsed: $previous -> $remaining",
                remaining < previous
            )
            previous = remaining
        }
    }

    // ── Готовность таргета ───────────────────────────────────────────────────

    @Test
    fun `ready target goes as soon as the minimum is reached`() {
        // Дефект: лишний кадр ожидания на ровно MIN — старт ощущается «вязким».
        assertGo(
            SplashPolicy.decisionFor(
                SplashPolicy.MIN_VISIBLE_MS.toLong(),
                targetReady = true,
                reducedMotion = false
            ),
            "ровно на MIN с готовым таргетом обязан быть Go"
        )
    }

    @Test
    fun `ready target goes later in the window too`() {
        // Дефект: Go только на точном MIN, а на MIN+1 снова Hold — заставка
        // залипает до MAX, хотя данные уже пришли.
        for (elapsed in listOf(
            SplashPolicy.MIN_VISIBLE_MS + 1L,
            800L,
            1200L,
            SplashPolicy.MAX_VISIBLE_MS - 1L,
        )) {
            assertGo(
                SplashPolicy.decisionFor(elapsed, targetReady = true, reducedMotion = false),
                "готовый таргет обязан уводить заставку на elapsed=$elapsed"
            )
        }
    }

    @Test
    fun `readiness does not cut the minimum show short`() {
        // Дефект: проверка targetReady раньше минимального показа — мгновенное
        // исчезновение заставки (мигание) при быстром ответе бэкенда.
        assertHold(
            SplashPolicy.MIN_VISIBLE_MS,
            SplashPolicy.decisionFor(0L, targetReady = true, reducedMotion = false)
        )
        assertHold(
            1,
            SplashPolicy.decisionFor(
                SplashPolicy.MIN_VISIBLE_MS - 1L,
                targetReady = true,
                reducedMotion = false
            )
        )
    }

    @Test
    fun `not ready at the minimum keeps holding`() {
        // Дефект: неготовый таргет уходит на MIN — экран следующего шага пустой.
        assertHold(
            SplashPolicy.MAX_VISIBLE_MS - SplashPolicy.MIN_VISIBLE_MS,
            SplashPolicy.decisionFor(
                SplashPolicy.MIN_VISIBLE_MS.toLong(),
                targetReady = false,
                reducedMotion = false
            )
        )
    }

    // ── Верхняя граница ──────────────────────────────────────────────────────

    @Test
    fun `maximum elapsed forces go even when the target is not ready`() {
        // Дефект: ожидание дольше MAX — пользователь смотрит на заставку вечно.
        for (elapsed in listOf(
            SplashPolicy.MAX_VISIBLE_MS.toLong(),
            SplashPolicy.MAX_VISIBLE_MS + 1L,
            SplashPolicy.MAX_VISIBLE_MS * 2L,
            10_000L,
        )) {
            assertGo(
                SplashPolicy.decisionFor(elapsed, targetReady = false, reducedMotion = false),
                "верхняя граница обязана выбивать заставку на elapsed=$elapsed"
            )
        }
    }

    @Test
    fun `huge elapsed values do not overflow`() {
        // Дефект: (MAX - elapsed).toInt() при огромном elapsed даёт мусор в Int
        // (в т.ч. положительный) — заставка «ждёт» отрицательное время или виснет.
        for (elapsed in listOf(
            Int.MAX_VALUE.toLong(),
            Int.MAX_VALUE.toLong() + 1L,
            Long.MAX_VALUE / 2,
            Long.MAX_VALUE - 1L,
            Long.MAX_VALUE,
        )) {
            for (ready in listOf(true, false)) {
                assertGo(
                    SplashPolicy.decisionFor(elapsed, targetReady = ready, reducedMotion = false),
                    "огромный elapsed=$elapsed (ready=$ready) обязан давать Go"
                )
            }
        }
    }

    // ── Границы и устойчивость ───────────────────────────────────────────────

    @Test
    fun `exact boundary values follow the spec`() {
        // Дефект: строгие/нестрогие сравнения перепутаны на самих границах —
        // классический источник «на 1 мс раньше/позже».
        assertHold(
            SplashPolicy.MIN_VISIBLE_MS,
            SplashPolicy.decisionFor(0L, targetReady = false, reducedMotion = false),
            "elapsed = 0"
        )
        assertGo(
            SplashPolicy.decisionFor(
                SplashPolicy.MIN_VISIBLE_MS.toLong(),
                targetReady = true,
                reducedMotion = false
            ),
            "elapsed = MIN, готов"
        )
        assertHold(
            SplashPolicy.MAX_VISIBLE_MS - SplashPolicy.MIN_VISIBLE_MS,
            SplashPolicy.decisionFor(
                SplashPolicy.MIN_VISIBLE_MS.toLong(),
                targetReady = false,
                reducedMotion = false
            ),
            "elapsed = MIN, не готов"
        )
        assertGo(
            SplashPolicy.decisionFor(
                SplashPolicy.MAX_VISIBLE_MS.toLong(),
                targetReady = false,
                reducedMotion = false
            ),
            "elapsed = MAX, не готов"
        )
        assertGo(
            SplashPolicy.decisionFor(
                SplashPolicy.MAX_VISIBLE_MS.toLong(),
                targetReady = true,
                reducedMotion = false
            ),
            "elapsed = MAX, готов"
        )
    }

    @Test
    fun `decision never throws for extreme inputs`() {
        // Дефект: require()/checkArgument на elapsed, деление на ноль, toInt() без
        // приведения — исключение в UI-потоке роняет старт приложения.
        val clocks = listOf(
            Long.MIN_VALUE, Long.MIN_VALUE + 1L, -1L, 0L, 1L,
            SplashPolicy.MIN_VISIBLE_MS - 1L, SplashPolicy.MIN_VISIBLE_MS.toLong(),
            SplashPolicy.MAX_VISIBLE_MS - 1L, SplashPolicy.MAX_VISIBLE_MS.toLong(),
            Int.MAX_VALUE.toLong(), Long.MAX_VALUE,
        )
        for (elapsed in clocks) {
            for (ready in listOf(true, false)) {
                for (reduced in listOf(true, false)) {
                    // Любое исключение здесь — провал теста.
                    SplashPolicy.decisionFor(elapsed, targetReady = ready, reducedMotion = reduced)
                }
            }
        }
    }

    @Test
    fun `hold carries an int remaining and go is a singleton`() {
        // Дефект: Hold без value-семантики ломает сравнение состояний и
        // Compose-стабильность; Go, созданный заново, ломает identity-проверки
        // «уже ушли?». Спецификация фиксирует data class и data object.
        assertEquals(SplashPolicy.Decision.Hold(1), SplashPolicy.Decision.Hold(1))
        assertNotEquals(SplashPolicy.Decision.Hold(1), SplashPolicy.Decision.Hold(2))
        assertNotEquals(
            "Hold не может быть равен Go",
            SplashPolicy.Decision.Hold(0) as SplashPolicy.Decision,
            SplashPolicy.Decision.Go as SplashPolicy.Decision
        )
        assertSame(
            "Go обязан быть синглтоном (data object)",
            SplashPolicy.Decision.Go,
            SplashPolicy.Decision.Go
        )
    }

    @Test
    fun `every hold remaining fits an int and stays positive`() {
        // Дефект: остаток, посчитанный в Long и приведённый к Int без зажима,
        // может стать отрицательным — цикл выхода не сработает.
        var holds = 0
        for (elapsed in -100L..(SplashPolicy.MAX_VISIBLE_MS + 100L)) {
            for (ready in listOf(true, false)) {
                val decision = SplashPolicy.decisionFor(elapsed, targetReady = ready, reducedMotion = false)
                if (decision is SplashPolicy.Decision.Hold) {
                    holds++
                    assertTrue(
                        "Hold на elapsed=$elapsed (ready=$ready) с остатком ${decision.remainingMs}",
                        decision.remainingMs >= 1
                    )
                    assertTrue(
                        "остаток ${decision.remainingMs} на elapsed=$elapsed превышает верхнюю границу",
                        decision.remainingMs <= SplashPolicy.MAX_VISIBLE_MS
                    )
                }
            }
        }
        assertTrue("на диапазоне не нашлось ни одного Hold — модель сломана", holds > 0)
    }

    // ── Достижимость выхода ──────────────────────────────────────────────────

    @Test
    fun `go is reachable on all three exit routes`() {
        // Маршрут после заставки не должен зависеть от одного сценария:
        // 1) «ждать не пришлось» — таргет готов к моменту MIN;
        // 2) «доготовился в окне» — таргет готов позже MIN;
        // 3) «просрочено» — таргет так и не готов, сработала верхняя граница.
        // Дефект: реализация, из которой выхода нет хотя бы на одном маршруте,
        // оставляет пользователя на заставке.
        val noWait = SplashPolicy.decisionFor(
            SplashPolicy.MIN_VISIBLE_MS.toLong(), targetReady = true, reducedMotion = false
        )
        val caughtUp = SplashPolicy.decisionFor(
            SplashPolicy.MIN_VISIBLE_MS + 400L, targetReady = true, reducedMotion = false
        )
        val overdue = SplashPolicy.decisionFor(
            SplashPolicy.MAX_VISIBLE_MS.toLong(), targetReady = false, reducedMotion = false
        )
        assertGo(noWait, "маршрут «ждать не пришлось»")
        assertGo(caughtUp, "маршрут «таргет доготовился»")
        assertGo(overdue, "маршрут «ожидание просрочено»")
    }

    // ── Пульсация ────────────────────────────────────────────────────────────

    @Test
    fun `only the pulse mode pulses`() {
        // Дефект: пульсация в STATIC — анимация при выключенных анимациях;
        // отсутствие пульсации в PULSE — «мёртвый» индикатор загрузки.
        assertTrue(
            "PULSE обязан пульсировать",
            SplashPolicy.shouldPulse(MarsPulsePolicy.PulseMode.PULSE)
        )
        assertFalse(
            "STATIC пульсировать не должен",
            SplashPolicy.shouldPulse(MarsPulsePolicy.PulseMode.STATIC)
        )
    }

    @Test
    fun `shouldPulse agrees with the mode enum for every case`() {
        // Дефект: захардкоженный when без else / сравнение с чужим кейсом —
        // новый режим молча начнёт пульсировать.
        val modes = MarsPulsePolicy.PulseMode.values()
        assertTrue("в PulseMode обязаны быть оба режима", modes.size >= 2)
        for (mode in modes) {
            assertEquals(
                "shouldPulse($mode) обязан равняться (mode == PULSE)",
                mode == MarsPulsePolicy.PulseMode.PULSE,
                SplashPolicy.shouldPulse(mode)
            )
        }
    }

    @Test
    fun `static motion keeps the fade but drops the pulse`() {
        // Дефект: fadeOutMs(true)=FADE_OUT_MS (фейд при reduce motion) или
        // pulsePeriodMs(false)=0 (индикатор замирает при полном движении).
        assertEquals(SplashPolicy.FADE_OUT_MS, SplashPolicy.fadeOutMs(reducedMotion = false))
        assertEquals(SplashPolicy.PULSE_PERIOD_MS, SplashPolicy.pulsePeriodMs(reducedMotion = false))
        assertTrue(
            "период пульсации обязан быть заметно длиннее фейда",
            SplashPolicy.pulsePeriodMs(false) > SplashPolicy.fadeOutMs(false)
        )
    }

    // ── Плотный перебор по эталонной модели ──────────────────────────────────

    @Test
    fun `decision matches the spec model over a dense sweep`() {
        // Дефект: любое расхождение с таблицей спецификации на любом миллисекундном
        // шаге — включая окрестности MIN и MAX, где обычно и ошибаются.
        for (elapsed in -700L..(SplashPolicy.MAX_VISIBLE_MS + 700L)) {
            for (ready in listOf(true, false)) {
                for (reduced in listOf(true, false)) {
                    assertEquals(
                        "расхождение со спецификацией: elapsed=$elapsed ready=$ready reduced=$reduced",
                        expectedDecision(elapsed, ready, reduced),
                        SplashPolicy.decisionFor(elapsed, targetReady = ready, reducedMotion = reduced)
                    )
                }
            }
        }
    }

    @Test
    fun `decision matches the spec model for huge elapsed values`() {
        // Дефект: корректная работа только на «бытовых» значениях; переполнение
        // Int/Long проявляется лишь на больших elapsed.
        val clocks = listOf(
            SplashPolicy.MAX_VISIBLE_MS + 1L,
            SplashPolicy.MAX_VISIBLE_MS * 1000L,
            Int.MAX_VALUE.toLong(),
            Int.MAX_VALUE.toLong() + 1L,
            Long.MAX_VALUE / 3,
            Long.MAX_VALUE - 1L,
            Long.MAX_VALUE,
        )
        for (elapsed in clocks) {
            for (ready in listOf(true, false)) {
                assertEquals(
                    "расхождение со спецификацией на elapsed=$elapsed ready=$ready",
                    expectedDecision(elapsed, ready, reducedMotion = false),
                    SplashPolicy.decisionFor(elapsed, targetReady = ready, reducedMotion = false)
                )
            }
        }
    }
}
