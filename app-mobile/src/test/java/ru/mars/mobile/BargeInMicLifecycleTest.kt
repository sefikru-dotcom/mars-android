package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Жизненный цикл микрофона во время озвучки — и почему перебивание голосом нельзя обещать на
 * динамике.
 *
 * ## Что здесь подтверждено аудитом и закрыто
 * 1. **Микрофон открывался под заглушку и под предпросмотр.** `armBargeInMic` звался из
 *    `onTtsSpeakingChanged(true)` без разбора того, ЧТО звучит: «Секунду, думаю.» и пункт
 *    «Озвучить» поднимали захват и открывали реплику в ASR. Дальше конец подсказки переводил фазу
 *    в LISTENING, ответ читался при открытом микрофоне, а записанное уходило в чат новым
 *    вопросом. Теперь речь помечена ([SpeechPurpose]), и микрофон открывается только под ОТВЕТ.
 * 2. **Маршрут брался по ДОСТУПНЫМ выходам.** `getDevices(GET_DEVICES_OUTPUTS)` перечисляет
 *    воткнутую гарнитуру рядом со встроенным динамиком, поэтому «в системе есть наушники»
 *    открывало микрофон и на динамике — то есть ровно тот дуплекс, которого быть не должно.
 *    Теперь нужен доказанный АКТИВНЫЙ выход ([ActiveOutput]); нет доказательства — нет
 *    автоперебивания.
 * 3. **Сторож микрофона ломал SPEAKING.** Тишина в захвате перебивания — норма (человек
 *    слушает), а сторож через три секунды объявлял микрофон мёртвым, закрывал захват посреди
 *    ответа и в итоге переводил фазу в IDLE с «Микрофон не отвечает», убирая кнопку «Стоп».
 *    Теперь сторож к захвату перебивания не применяется вовсе.
 * 4. **Предбуфер путался с рабочим захватом.** `capture?.isCapturing` не отличает предбуфер от
 *    реплики, поэтому ответ Марса мог быть записан в НАЧАЛО будущей фразы человека. Различие
 *    вынесено в [MicMode] и проверяется политикой.
 *
 * ## Границы проверки
 * Контроллер целиком на JVM не поднимается (`Handler` главного `Looper`, `Activity`), поэтому:
 * - чистые решения проверяются ПОВЕДЕНИЕМ ([BargeInMicPolicy], [AudioRoutePolicy],
 *   [ActiveOutput]) — матрица условий, а не одна счастливая комбинация;
 * - проводка — контрактом по исходнику (тот же приём, что в `VoiceStopAndBargeInTest`).
 *
 * ## Чего эти тесты не доказывают
 * Что перебивание на наушниках срабатывает на живом устройстве: юнит-тест не слышит микрофон и не
 * видит, как платформа называет активный выход. Он доказывает обратное и главное — что без
 * доказательств микрофон не открывается.
 */
class BargeInMicLifecycleTest {

    // --- что звучит: перебивают ответ, а не заглушку и не предпросмотр ------------------------

    /** Идеальные условия: доказанные наушники, экран на переднем плане, свободный микрофон. */
    private fun bargeIn(
        enabled: Boolean = true,
        route: AudioRoute = AudioRoute.HEADPHONES,
        routeConfirmed: Boolean = true,
        purpose: SpeechPurpose = SpeechPurpose.ANSWER,
        paused: Boolean = false,
        released: Boolean = false,
        backgroundServiceListening: Boolean = false,
        micMode: MicMode = MicMode.CLOSED,
    ): Boolean = BargeInMicPolicy.keepMicDuringSpeech(
        enabled = enabled,
        route = route,
        routeConfirmed = routeConfirmed,
        purpose = purpose,
        paused = paused,
        released = released,
        backgroundServiceListening = backgroundServiceListening,
        micMode = micMode,
    )

    @Test
    fun `под ответ на доказанных наушниках микрофон открывается`() {
        assertTrue(bargeIn())
    }

    @Test
    fun `под заглушку Секунду думаю микрофон не открывается`() {
        // Иначе «Секунду, думаю.» записывалась бы как реплика человека и уходила в чат вопросом.
        assertFalse(bargeIn(purpose = SpeechPurpose.HINT))
    }

    @Test
    fun `под предпросмотр сообщения микрофон не открывается`() {
        // Пункт «Озвучить» у сообщения: перебивать нечего, это не ответ на вопрос.
        assertFalse(bargeIn(purpose = SpeechPurpose.PREVIEW))
    }

    @Test
    fun `когда ничего не звучит микрофон не открывается`() {
        assertFalse(bargeIn(purpose = SpeechPurpose.NONE))
    }

    // --- маршрут: доказанный активный выход, а не список доступных -----------------------------

    @Test
    fun `недоказанный маршрут выключает автоперебивание даже при наушниках в списке`() {
        // Главный дефект: гарнитура воткнута, звук идёт из динамика, «маршрут = наушники».
        assertFalse(
            "без доказательства активного выхода перебивание не включается",
            bargeIn(routeConfirmed = false),
        )
    }

    @Test
    fun `на динамике и на неизвестном маршруте перебивания нет`() {
        assertFalse(bargeIn(route = AudioRoute.SPEAKER))
        assertFalse(bargeIn(route = AudioRoute.UNKNOWN))
    }

    @Test
    fun `выключенное перебивание не открывает микрофон даже на наушниках`() {
        assertFalse(bargeIn(enabled = false))
    }

    // --- экран и владелец микрофона -----------------------------------------------------------

    @Test
    fun `в фоне и после освобождения микрофон не открывается`() {
        assertFalse("микрофон в кармане — не разговор", bargeIn(paused = true))
        assertFalse(bargeIn(released = true))
    }

    @Test
    fun `микрофон, занятый фоновым разговором, не отбирается`() {
        assertFalse(bargeIn(backgroundServiceListening = true))
    }

    // --- предбуфер — не рабочий захват --------------------------------------------------------

    @Test
    fun `захват предбуфера не считается микрофоном перебивания`() {
        // Предбуфер копит звук для реплики человека: ответ Марса, записанный туда, стал бы её
        // началом и ушёл бы в чат.
        assertFalse(bargeIn(micMode = MicMode.PRE_ROLL))
    }

    @Test
    fun `живой рабочий захват переиспользуется, а не переоткрывается`() {
        assertTrue("второй AudioRecord отобрал бы звук у первого", bargeIn(micMode = MicMode.STREAMING))
        assertTrue(bargeIn(micMode = MicMode.CLOSED))
    }

    // --- конец озвучки: захват, поднятый только ради перебивания, закрывается ------------------

    @Test
    fun `свой захват перебивания закрывается, если человек так и не заговорил`() {
        assertTrue(
            BargeInMicPolicy.closeOwnedCaptureOnSpeechEnd(
                captureOpen = true,
                owned = true,
                accepted = false,
                userStopped = false,
            ),
        )
    }

    @Test
    fun `захват остаётся, если перебивание подтвердилось`() {
        assertFalse(
            "речь человека ещё не кончилась — закрытие потеряло бы её",
            BargeInMicPolicy.closeOwnedCaptureOnSpeechEnd(
                captureOpen = true,
                owned = true,
                accepted = true,
                userStopped = false,
            ),
        )
    }

    @Test
    fun `после Стоп микрофон остаётся слушать`() {
        // «Стоп» — это «замолчи и слушай меня», а не «выключи разговор».
        assertFalse(
            BargeInMicPolicy.closeOwnedCaptureOnSpeechEnd(
                captureOpen = true,
                owned = true,
                accepted = false,
                userStopped = true,
            ),
        )
    }

    @Test
    fun `реплику человека, открытую им самим, конец озвучки не закрывает`() {
        assertFalse(
            "захват не наш: в нём слова человека",
            BargeInMicPolicy.closeOwnedCaptureOnSpeechEnd(
                captureOpen = true,
                owned = false,
                accepted = false,
                userStopped = false,
            ),
        )
    }

    @Test
    fun `без открытого захвата закрывать нечего`() {
        assertFalse(
            BargeInMicPolicy.closeOwnedCaptureOnSpeechEnd(
                captureOpen = false,
                owned = true,
                accepted = false,
                userStopped = false,
            ),
        )
    }

    // --- активный выход: чистое решение по типу устройства ------------------------------------

    @Test
    fun `активный выход в наушниках доказывается любым их типом`() {
        listOf(
            AudioRoutePolicy.TYPE_WIRED_HEADSET,
            AudioRoutePolicy.TYPE_WIRED_HEADPHONES,
            AudioRoutePolicy.TYPE_BLUETOOTH_SCO,
            AudioRoutePolicy.TYPE_BLUETOOTH_A2DP,
            AudioRoutePolicy.TYPE_USB_HEADSET,
            AudioRoutePolicy.TYPE_HEARING_AID,
            AudioRoutePolicy.TYPE_BLE_HEADSET,
        ).forEach { type ->
            assertEquals(
                "тип $type",
                AudioRoute.HEADPHONES,
                AudioRoutePolicy.confirmedRouteFor(type),
            )
        }
    }

    @Test
    fun `активный динамик доказывается как динамик`() {
        assertEquals(
            AudioRoute.SPEAKER,
            AudioRoutePolicy.confirmedRouteFor(AudioRoutePolicy.TYPE_BUILTIN_SPEAKER),
        )
    }

    @Test
    fun `неизвестный тип и отсутствие ответа платформы не доказывают ничего`() {
        assertEquals(AudioRoute.UNKNOWN, AudioRoutePolicy.confirmedRouteFor(null))
        assertEquals(AudioRoute.UNKNOWN, AudioRoutePolicy.confirmedRouteFor(AudioRoutePolicy.TYPE_HDMI))
        assertEquals(
            AudioRoute.UNKNOWN,
            AudioRoutePolicy.confirmedRouteFor(AudioRoutePolicy.TYPE_LINE_ANALOG),
        )
    }

    @Test
    fun `недоказанный маршрут всегда эхо-опасен`() {
        assertTrue(ActiveOutput.UNKNOWN.isEchoRisk)
        assertFalse(ActiveOutput.UNKNOWN.isConfirmedHeadphones)
        // Даже «наушники», но без доказательства: верить нечему.
        assertTrue(ActiveOutput(AudioRoute.HEADPHONES, confirmed = false).isEchoRisk)
        assertFalse(ActiveOutput(AudioRoute.HEADPHONES, confirmed = false).isConfirmedHeadphones)
        // Доказанные наушники — единственный безопасный случай.
        assertFalse(ActiveOutput(AudioRoute.HEADPHONES, confirmed = true).isEchoRisk)
        assertTrue(ActiveOutput(AudioRoute.HEADPHONES, confirmed = true).isConfirmedHeadphones)
        assertTrue(ActiveOutput(AudioRoute.SPEAKER, confirmed = true).isEchoRisk)
    }

    // --- проводка: контракт по исходнику ------------------------------------------------------

    @Test
    fun `захват перебивания не взводит сторож микрофона`() {
        // Сторож лечит «микрофон молчит», а в захвате перебивания молчание — норма. Взведённый
        // сторож закрывал захват посреди ответа и переводил SPEAKING в IDLE.
        val barge = body(source(CONTROLLER), "private fun armBargeInMic(")
        assertFalse("сторож не имеет отношения к перебиванию", barge.contains("armMicWatchdog"))
    }

    @Test
    fun `сторож не трогает захват перебивания`() {
        val tick = body(source(CONTROLLER), "private val watchdogTick")
        assertTrue(
            "взведённый ранее сторож обязан отступить от захвата перебивания",
            tick.contains("if (bargeInMicOpen) return"),
        )
        val restart = body(source(CONTROLLER), "private fun restartCapture(")
        assertTrue(
            "перезапуск посреди ответа закрыл бы захват перебивания",
            restart.contains("if (bargeInMicOpen) return"),
        )
    }

    @Test
    fun `сдача сторожа не гасит фазу озвучки`() {
        val fail = body(source(CONTROLLER), "private fun failMicWatchdog(")
        assertTrue(
            "фаза SPEAKING при живом захвате перебивания — не повод объявлять микрофон мёртвым",
            fail.contains("state.phase == LocalVoicePhase.SPEAKING"),
        )
        val guard = fail.indexOf("if (speaking) return")
        val notice = fail.indexOf("MIC_NOT_RESPONDING")
        assertTrue("сообщение о мёртвом микрофоне должно быть после проверки фазы", guard in 0 until notice)
    }

    @Test
    fun `решение о микрофоне принимается по активному выходу`() {
        val barge = body(source(CONTROLLER), "private fun armBargeInMic(")
        assertTrue("маршрут — активный выход", barge.contains("routes?.activeOutput()"))
        assertTrue("доказанность маршрута уходит в политику", barge.contains("routeConfirmed = active.confirmed"))
        assertTrue("что звучит — тоже условие", barge.contains("purpose = speechPurpose"))
        assertFalse(
            "по доступным выходам решение принимать нельзя",
            barge.contains("routes?.route"),
        )
    }

    @Test
    fun `предбуфер не путается с рабочим захватом`() {
        val barge = body(source(CONTROLLER), "private fun armBargeInMic(")
        assertTrue("режим микрофона считается одной функцией", barge.contains("micMode()"))
        assertTrue("решение по режиму — в политике", barge.contains("micMode = mode"))
        assertTrue(
            "переиспользуется только РАБОЧИЙ захват",
            barge.contains("if (mode == MicMode.STREAMING)"),
        )
        assertFalse(
            "`capture?.isCapturing` не отличает предбуфер от реплики",
            barge.contains("capture?.isCapturing == true"),
        )
        val micMode = body(source(CONTROLLER), "private fun micMode(")
        assertTrue(micMode.contains("MicMode.PRE_ROLL"))
        assertTrue(micMode.contains("streamingToEngine"))
    }

    @Test
    fun `пути к озвучке помечают, что именно звучит`() {
        val src = source(CONTROLLER)
        listOf(
            "fun onAnswerStarted(",
            "fun onAnswerDelta(",
            "fun onAnswerCompleted(",
            "fun onAnswerEnded(",
        ).forEach { declaration ->
            assertTrue(
                "$declaration обязан пометить ответ: иначе перебивание сработает на заглушке",
                body(src, declaration).contains("speechPurpose = SpeechPurpose.ANSWER"),
            )
        }
        assertTrue(
            "заглушка не ответ",
            body(src, "private val thinkingHint").contains("speechPurpose = SpeechPurpose.HINT"),
        )
        assertTrue(
            "предпросмотр не ответ",
            body(src, "fun speakNow(").contains("speechPurpose = SpeechPurpose.PREVIEW"),
        )
        assertTrue(
            "конец речи снимает метку",
            body(src, "private fun onTtsSpeakingChanged(").contains("speechPurpose = SpeechPurpose.NONE"),
        )
    }

    @Test
    fun `конец озвучки закрывает захват, поднятый только ради перебивания`() {
        val changed = body(source(CONTROLLER), "private fun onTtsSpeakingChanged(")
        assertTrue(changed.contains("BargeInMicPolicy.closeOwnedCaptureOnSpeechEnd("))
        assertTrue(
            "признак подтверждённого перебивания берётся из гейта: к этому моменту он уже верен",
            changed.contains("bargeInGate.acceptedCount"),
        )
        val close = body(source(CONTROLLER), "private fun closeBargeInCapture(")
        assertTrue(
            "недописанная реплика не должна дожить до Final и уйти в чат",
            close.contains("cancelUtterance"),
        )
    }

    @Test
    fun `контроллер владеет гейтом перебивания`() {
        val src = source(CONTROLLER)
        assertTrue("счётчик перебиваний читается у своего гейта", src.contains("private val bargeInGate = BargeInGate()"))
        assertTrue(
            "гейт передаётся в озвучку",
            body(src, "private fun ensureTts(").contains("gate = bargeInGate"),
        )
    }

    /** Тело функции/свойства по объявлению: от первой `{` до парной закрывающей. */
    private fun body(source: String, declaration: String): String {
        val start = source.indexOf(declaration)
        if (start < 0) throw AssertionError("Не найдено объявление: $declaration")
        val open = source.indexOf('{', start)
        if (open < 0) throw AssertionError("У объявления нет тела: $declaration")
        var depth = 0
        var index = open
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
            index++
        }
        throw AssertionError("Тело не закрыто: $declaration")
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
        const val CONTROLLER = "src/main/java/ru/mars/mobile/LocalVoiceController.kt"
    }
}
