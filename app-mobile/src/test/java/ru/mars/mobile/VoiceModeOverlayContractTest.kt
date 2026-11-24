package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Слой голосового режима: то, что можно проверить без телефона.
 *
 * Сам [VoiceModeOverlay] — Android-класс (View, Handler, Canvas), его нельзя собрать на
 * JVM. Поэтому проверяется то, на чём слой держится и что ломается молча: переход
 * «показ → скрытие» не должен оставлять орб крутящим кадры, а подписи обязаны совпадать
 * с тем, что увидит человек. Логика подписей живёт в [VoiceModePresenter] и покрыта
 * отдельным тестом; здесь — только инвариант «слой ничего своего не придумывает».
 */
class VoiceModeOverlayContractTest {

    @Test
    fun `скрытый слой не должен пульсировать`() {
        // Инвариант анимации: орб дышит только в показанном слое. У VoiceModeOverlay
        // hide() вызывает render(IDLE, pulses = false) — проверяем само правило на
        // состоянии, из которого слой берёт решение.
        assertFalse(VoiceModeState().orbPulses)
        assertTrue(VoiceModeState(phase = LocalVoicePhase.LISTENING).orbPulses)
        assertTrue(VoiceModeState(phase = LocalVoicePhase.THINKING).orbPulses)
        assertTrue(VoiceModeState(phase = LocalVoicePhase.SPEAKING).orbPulses)
    }

    @Test
    fun `фаза покоя — единственная без пульсации`() {
        val pulsing = LocalVoicePhase.entries.filter { VoiceModeState(phase = it).orbPulses }
        assertEquals(LocalVoicePhase.entries.size - 1, pulsing.size)
        assertFalse(LocalVoicePhase.IDLE in pulsing)
    }

    @Test
    fun `снимок для слоя переносит фазу и реплику без потерь`() {
        // Слой берёт снимок из LocalVoiceState (композер) и не хранит своего состояния:
        // эта проверка фиксирует, что фаз и текст доходят без подмены.
        LocalVoicePhase.entries.forEach { phase ->
            val voice = LocalVoiceState(phase = phase, partial = "текст")
            val state = VoiceModeState.from(voice)
            assertEquals(phase, state.phase)
            assertEquals("текст", state.userLine)
        }
    }

    @Test
    fun `тон слоя различает все четыре фазы`() {
        val tones = LocalVoicePhase.entries.map { VoiceModeState(phase = it).tone }
        assertEquals(LocalVoicePhase.entries.size, tones.distinct().size)
    }

    @Test
    fun `все действия слоя покрыты и не пустые`() {
        // Кнопка вызывает onMicTap/onStopSpeech по screen.action. Если для фазы нет
        // действия, нажатие не должно ничего обещать — это проверяет презентер,
        // здесь фиксируем полноту перечисления.
        VoiceModeAction.entries.forEach { action ->
            assertTrue(action.name.isNotBlank())
        }
        assertEquals(4, VoiceModeAction.entries.size)
    }

    // ── Компоновка и настоящий космос (Волна 20) ────────────────────────────────────────

    @Test
    fun `орб стоит по центру и не прыгает от появления реплик`() {
        val src = source(OVERLAY)
        // Крупная планета: было 232 dp при PLANET_RATIO 0.49 (шар 113 dp).
        assertTrue("ORB_DP обязан вырасти до 300", src.contains("const val ORB_DP = 300"))
        // Орб с подписью — отдельный слой со Gravity.CENTER, а не колонка, прижатая к низу.
        assertTrue(
            "орб обязан центрироваться, а не прижиматься к низу",
            src.contains("gravity = Gravity.CENTER"),
        )
        assertFalse(
            "колонка больше не растягивается на весь экран и не прижимается к низу",
            src.contains("root.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)"),
        )
        // Реплики и кнопки — отдельный нижний блок.
        assertTrue(src.contains("val bottomBlock = LinearLayout(context)"))
        assertTrue(src.contains("root.addView(bottomBlock"))
    }

    @Test
    fun `фоном слоя служит настоящая 3D-заставка в режиме голосового орба`() {
        val src = source(OVERLAY)
        // Глухая серая панель ушла: на ней самодельные пятна читались как «наляпано поверх чата».
        assertFalse(
            "корень не должен красить себя стеклянной панелью",
            src.contains("setBackgroundColor(MarsTheme.GLASS_PANEL)"),
        )
        assertTrue(src.contains("private val spaceBg = MarsSpaceBackgroundView(context)"))
        assertTrue("заставка обязана быть в режиме голосового орба", src.contains("setVoiceOrbMode(true)"))
        // Фон — ПЕРВЫЙ ребёнок корня: иначе он перекрыл бы планету и капсулы.
        val backgroundIndex = src.indexOf("root.addView(spaceBg")
        val orbIndex = src.indexOf("root.addView(column")
        assertTrue("фон должен добавляться раньше контента", backgroundIndex in 0 until orbIndex)
    }

    @Test
    fun `громкость речи уходит и в орб, и в неон заставки`() {
        val src = source(OVERLAY)
        // Один вход — два потребителя: корона планеты и туманность вспыхивают синхронно.
        assertTrue(src.contains("orb.setLevel(next)"))
        assertTrue(src.contains("spaceBg.setVoiceState(next, neon)"))
        // Смена фазы перекрашивает и фон: неон берётся из общей палитры.
        assertTrue(src.contains("spaceBg.setVoiceState(level, neon)"))
        assertTrue(src.contains("neon = VoiceNeonPalette.of(screen.tone)"))
        // Уровень зажимается политикой, а не «как пришло».
        assertTrue(src.contains("MarsSpaceShaderPolicy.voiceLevel(level)"))
    }

    @Test
    fun `заставка получает 60 кадров и гаснет вместе со слоем`() {
        val bg = source("src/main/java/ru/mars/mobile/MarsSpaceBackgroundView.kt")
        // Голосовой режим — такая же живая сцена, как скролл: неон обязан идти каждый vsync.
        assertTrue(bg.contains("val fast = scrolling || voiceOrbMode"))
        // Спрятанный слой не должен крутить GL-поток под лентой чата.
        assertTrue(bg.contains("override fun onVisibilityChanged"))
        // Одно условие «можно рисовать» на все входы: attached + окно + видимость предков.
        // Раньше `switchToCanvasFallback` проверял только `attached && windowVisible` и запускал
        // Canvas-цикл на спрятанном слое.
        assertTrue(bg.contains("attached && windowVisible && isShown && !hostPaused"))
        assertTrue(bg.contains("if (shouldRender()) resumeRendering() else pauseRendering()"))
        // Uniform-переменные выставляются на каждый кадр.
        assertTrue(bg.contains("\"uVoiceLevel\""))
        assertTrue(bg.contains("\"uVoiceNeon\""))
        assertTrue(bg.contains("\"uVoiceOrbMode\""))
        // Canvas-фоллбэк знает про голосовое гало, а маленькую планету в нём убирает.
        assertTrue(bg.contains("private fun drawVoiceNeon"))
        assertTrue(bg.contains("if (!voiceOrbMode) drawMars(canvas, w, h, minDim)"))
    }

    @Test
    fun `громкость фона сглаживается по кадрам, а не подаётся ступенькой`() {
        val bg = source("src/main/java/ru/mars/mobile/MarsSpaceBackgroundView.kt")
        // Контроллер присылает уровень 10–16 раз в секунду. Пока эти числа шли в uniform напрямую,
        // неоновая туманность вспыхивала ступенькой каждые 60–100 мс — при 60 кадрах это рывки,
        // и тем заметнее, что корона планеты в это же время шла плавно.
        assertTrue("цель сглаживания — отдельное поле", bg.contains("private var voiceLevelTarget"))
        assertTrue("кадр обязан двигать амплитуду", bg.contains("advanceVoiceLevel(dt)"))
        assertTrue(
            "сглаживание — то же, что у орба",
            bg.contains("OrbMotionPolicy.smoothLevel(voiceLevel, voiceLevelTarget, dt)"),
        )
        // Ступенька не должна вернуться: `setVoiceState` не пишет громкость в рендерер напрямую.
        assertFalse(
            "громкость не должна подаваться в uniform напрямую из setVoiceState",
            bg.contains("renderer.voiceLevel = nextLevel"),
        )
        // Единственная точка, где сглаженное значение расходится по потребителям.
        assertTrue(bg.contains("private fun setVoiceLevel(value: Float)"))
    }

    @Test
    fun `статичный кадр фона доносит новые uniform и не собирает градиент на каждый кадр`() {
        val bg = source("src/main/java/ru/mars/mobile/MarsSpaceBackgroundView.kt")
        // Без кадров (reduce motion, скрытый слой) новые uniform'ы донести некому: нужен один
        // статичный кадр, иначе фон навсегда остался бы на цвете прошлой фазы.
        assertTrue(bg.contains("private fun requestVoiceFrame()"))
        assertTrue(bg.contains("drawStaticFrame()"))
        // Неоновая корона фоллбэка кэшируется, пульс идёт через Paint.alpha: RadialGradient на
        // каждом кадре — это шейдер, собранный 60 раз в секунду.
        assertTrue(bg.contains("private var neonShader: RadialGradient?"))
        assertTrue(bg.contains("neonPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)"))
    }

    @Test
    fun `пауза слоя останавливает движение, но не перекрашивает орб`() {
        val src = source(OVERLAY)
        // Было `orb.render(IDLE, pulses = false)` в onPause: пауза перекрашивала орб в покой, и на
        // возврате он вспыхивал из рубинового IDLE в цвет текущей фазы.
        assertFalse(
            "пауза не должна подменять фазу",
            src.contains("fun onPause() = orb.render(VoiceModeTone.IDLE, pulses = false)"),
        )
        assertTrue(src.contains("orb.pauseMotion()"))
        assertTrue(src.contains("orb.resumeMotion()"))
        assertTrue(src.contains("spaceBg.pauseMotion()"))
        assertTrue(src.contains("spaceBg.resumeMotion()"))
    }

    @Test
    fun `подпись фазы больше не красится статусными цветами`() {
        val src = source(OVERLAY)
        // Зелёный «онлайн», жёлтое «предупреждение» и голубое «инфо» на тёплом Марсе читались
        // как три разных приложения в одной строке.
        for (status in listOf("STATUS_ONLINE", "STATUS_WARNING", "STATUS_INFO")) {
            for (tone in listOf("LISTENING", "THINKING", "SPEAKING")) {
                assertFalse(
                    "тон $tone не должен брать резкий статусный $status",
                    src.contains("VoiceModeTone.$tone -> MarsTheme.$status"),
                )
            }
        }
        assertTrue(src.contains("VoiceModeTone.IDLE -> MarsTheme.TEXT_MUTED"))
        assertTrue(
            "подпись активной фазы — неон фазы, приглушённый к текстовому",
            src.contains("VoiceNeonPalette.lerpColor(VoiceNeonPalette.of(tone), MarsTheme.TEXT, TONE_TEXT_MIX)"),
        )
        assertTrue(src.contains("const val TONE_TEXT_MIX = 0.55f"))
    }

    @Test
    fun `неон фона перетекает между фазами плавно`() {
        val bg = source("src/main/java/ru/mars/mobile/MarsSpaceBackgroundView.kt")
        // Цель и текущее значение — разные поля: иначе `uVoiceNeon` переключался бы за один кадр.
        assertTrue(bg.contains("private var targetNeonR"))
        assertTrue(bg.contains("private var targetNeonG"))
        assertTrue(bg.contains("private var targetNeonB"))
        assertTrue(bg.contains("private var targetNeonColor"))
        assertTrue("шаг перехода — общая экспонента палитры", bg.contains("VoiceNeonPalette.stepFactor(dt)"))
        assertTrue("кадр обязан двигать переход", bg.contains("advanceVoiceNeon(dt)"))
        // Без кадров (reduce motion / скрытый слой) цвет ставится сразу.
        assertTrue(bg.contains("snapVoiceNeon()"))
    }

    /** Исходник от текущего каталога вверх: тест может бежать из модуля и из корня репозитория. */
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

    private companion object {
        const val OVERLAY = "src/main/java/ru/mars/mobile/VoiceModeOverlay.kt"
    }
}
