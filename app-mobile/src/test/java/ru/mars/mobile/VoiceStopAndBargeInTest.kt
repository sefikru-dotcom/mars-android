package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Кнопка «Стоп» на орбе и голосовое перебивание — ДВА РАЗНЫХ пути, и оба проверяются здесь.
 *
 * ## Что было сломано (подтверждено кодом, не догадкой)
 * 1. **Стоп не был «липким».** `stopSpeaking()` глушил звук и переводил фазу в IDLE, но нигде не
 *    запоминалось, что человек ПОПРОСИЛ тишины. Потоковая озвучка продолжает кормить контроллер
 *    дельтами (`onAnswerDelta` → `appendStream` → `pump` → `speakPieceNow`), а `voiceTurn` до
 *    конца хода остаётся `true` — поэтому через полсекунды после нажатия Марс начинал говорить
 *    снова со следующего куска. Снаружи: «кнопка стоп не работает».
 * 2. **Слой не перерисовывался на смену фазы.** `LocalChatActivity` перерисовывает слой только
 *    из `render(state)`, то есть на изменение ЛЕНТЫ. Нажатие «Стоп» ленту не меняет — орб
 *    оставался в «Говорю…» с кнопкой «Стоп» поверх уже наступившей тишины.
 * 3. **Голосового перебивания в локальном чате не было вовсе.** `onTtsSpeakingChanged(true)`
 *    безусловно закрывал микрофон, поэтому `onSpeechProgress` не тикал, `offerSpeech` не
 *    вызывался, и [BargeInGate] нечего было оценивать: перебить можно было только жестом.
 * 4. **Терялось начало фразы.** `startCapture` открывал микрофон ПОСЛЕ `startUtterance()`, а
 *    `AudioRecord` с AEC/NS стартует 100–300 мс — сказанное в это окно не записывалось.
 *
 * ## Границы проверки
 * Контроллер целиком на JVM не поднимается (`Handler` главного `Looper`, `Activity`), поэтому:
 * - чистые решения проверяются поведением ([AnswerSpeechGate], [BargeInMicPolicy]);
 * - проводка контроллера и слоя — контрактом по исходнику (тот же приём, что в
 *   `VoiceModeOverlayContractTest`): важно не «как написано», а что стоп взводит намерение,
 *   что дельты его спрашивают и что микрофон открывается до реплики;
 * - [StreamingStt] проверяется по-настоящему: у него нет `android.*` в горячем пути.
 *
 * ## Чего эти тесты НЕ доказывают
 * Живого перебивания на телефоне. Дуплекс на динамике не включён сознательно
 * ([BargeInMicPolicy]), а путь на наушниках требует приёмки на устройстве: юнит-тест не
 * слышит микрофон и не видит, как `BargeInGate` срабатывает от настоящей речи.
 */
class VoiceStopAndBargeInTest {

    // --- намерение «молчи»: стоп живёт один ответ -------------------------------------------

    @Test
    fun `по умолчанию ответ озвучивается`() {
        val gate = AnswerSpeechGate()
        assertTrue(gate.canSpeak(speakAnswers = true, voiceTurn = true))
        assertFalse(gate.mutedByUser)
    }

    @Test
    fun `стоп глушит остаток текущего ответа`() {
        val gate = AnswerSpeechGate()
        gate.onUserStop()
        // Ровно дефект: следующая дельта потоковой озвучки включала звук обратно.
        assertFalse("после «Стоп» следующая дельта не должна говорить", gate.canSpeak(true, true))
        assertTrue(gate.mutedByUser)
    }

    @Test
    fun `стоп не глушит следующий ход`() {
        val gate = AnswerSpeechGate()
        gate.onUserStop()
        gate.onNewTurn()
        assertTrue("иначе один «Стоп» выключал бы озвучку навсегда", gate.canSpeak(true, true))
        assertFalse(gate.mutedByUser)
    }

    @Test
    fun `ответ на печатный ввод не озвучивается ни до, ни после стопа`() {
        val gate = AnswerSpeechGate()
        assertFalse(gate.canSpeak(speakAnswers = true, voiceTurn = false))
        gate.onUserStop()
        gate.onNewTurn()
        assertFalse(gate.canSpeak(speakAnswers = true, voiceTurn = false))
    }

    @Test
    fun `выключенная озвучка не включается ни стопом, ни новым ходом`() {
        val gate = AnswerSpeechGate()
        gate.onUserStop()
        gate.onNewTurn()
        assertFalse(gate.canSpeak(speakAnswers = false, voiceTurn = true))
    }

    // --- микрофон во время речи Марса --------------------------------------------------------

    @Test
    fun `на наушниках микрофон остаётся открытым для перебивания`() {
        assertTrue(BargeInMicPolicy.keepMicDuringSpeech(enabled = true, route = AudioRoute.HEADPHONES))
    }

    @Test
    fun `на динамике и на неизвестном маршруте перебивания нет`() {
        // Динамик попадает в микрофон: длительность речи там ничего не доказывает, а TTS
        // говорит ровно так же долго. Лучше «не перебил», чем «Марс перебил сам себя».
        assertFalse(BargeInMicPolicy.keepMicDuringSpeech(enabled = true, route = AudioRoute.SPEAKER))
        assertFalse(BargeInMicPolicy.keepMicDuringSpeech(enabled = true, route = AudioRoute.UNKNOWN))
    }

    @Test
    fun `выключенное перебивание не открывает микрофон даже на наушниках`() {
        assertFalse(BargeInMicPolicy.keepMicDuringSpeech(enabled = false, route = AudioRoute.HEADPHONES))
    }

    // --- захват ASR: неготовый движок не должен убивать реплику -------------------------------

    private class FakePort(override var isReady: Boolean = true) : AsrEnginePort {
        override var events: AsrEnginePort.Events? = null
        var starts = 0
        var accepted = 0

        override fun load(): Boolean = true

        override fun startUtterance() {
            starts++
        }

        override fun acceptPcm(pcm: ByteArray, length: Int) {
            accepted += length
        }

        override fun finishUtterance(): String = ""

        override fun release() = Unit
    }

    @Test
    fun `готовая реплика открывается в движке сразу и звук уходит`() {
        val port = FakePort()
        val stt = StreamingStt(port)
        stt.startUtterance()
        assertEquals(1, port.starts)
        stt.acceptPcm(ByteArray(16), 16)
        assertEquals(16, port.accepted)
    }

    @Test
    fun `неготовый движок не убивает реплику насовсем`() {
        val port = FakePort(isReady = false)
        val stt = StreamingStt(port)
        stt.startUtterance()
        // Пока движок не готов, звук уходит в никуда — это честно, распознавать нечем.
        stt.acceptPcm(ByteArray(16), 16)
        assertEquals(0, port.accepted)
        // А вот реплика обязана остаться ОТКРЫТОЙ: раньше здесь снимался `utteranceOpen`, и
        // весь остаток фразы молча терялся (в локальном чате whisper-пути нет).
        port.isReady = true
        stt.acceptPcm(ByteArray(16), 16)
        assertEquals("реплика жива: звук снова уходит в движок", 16, port.accepted)
    }

    @Test
    fun `звук вне реплики отбрасывается`() {
        val port = FakePort()
        val stt = StreamingStt(port)
        stt.acceptPcm(ByteArray(8), 8)
        assertEquals("хвост прошлой реплики не должен попасть в движок", 0, port.accepted)
    }

    // --- проводка: контракт по исходнику ------------------------------------------------------

    @Test
    fun `стоп на орбе взводит намерение, а не только глушит звук`() {
        val stop = body(source(CONTROLLER), "fun stopSpeaking()")
        assertTrue("стоп обязан запомнить намерение", stop.contains("speechGate.onUserStop()"))
        assertTrue("стоп обязан вернуть микрофон в строй", stop.contains("returnToRest()"))
        assertTrue("стоп обязан глушить звук", stop.contains("silenceSpeech()"))
    }

    @Test
    fun `внутренние пути глушат звук, не взводя стоп`() {
        // Новый ход и уход экрана не значат «человек попросил молчания»: если бы они звали
        // stopSpeaking(), озвучка пропадала бы после первого же send() или onPause.
        val turn = body(source(CONTROLLER), "fun onTurnStarted()")
        assertTrue(turn.contains("silenceSpeech()"))
        assertFalse(turn.contains("stopSpeaking()"))
    }

    @Test
    fun `потоковые дельты спрашивают намерение перед синтезом`() {
        val src = source(CONTROLLER)
        assertTrue(body(src, "fun onAnswerStarted").contains("speechGate.canSpeak"))
        assertTrue(body(src, "fun onAnswerDelta").contains("speechGate.canSpeak"))
        assertTrue("конец потока тоже обязан уважать стоп", body(src, "fun onAnswerEnded").contains("mutedByUser"))
    }

    @Test
    fun `смена фазы доходит до слоя без кадра ленты`() {
        assertTrue(
            "наблюдатель обязан вызываться из emit",
            source(CONTROLLER).contains("onStateObserver?.invoke(next)"),
        )
        val overlay = source(OVERLAY)
        assertTrue(
            "слой обязан подписаться на смену фазы",
            overlay.contains("channel?.onStateObserver = { next -> if (isShowing) renderPhase(next) }"),
        )
        assertTrue(
            "скрытый слой обязан отписаться",
            overlay.contains("channel?.onStateObserver = null"),
        )
    }

    @Test
    fun `микрофон открывается до реплики, а не после`() {
        // Иначе старт `AudioRecord` (100–300 мс с AEC/NS) съедает начало фразы: человек уже
        // говорит, а запись ещё не идёт.
        val capture = body(source(CONTROLLER), "private fun startCapture(")
        val mic = capture.indexOf("val fresh = HandsFreeCapture()")
        val utterance = capture.indexOf("wrapper.startUtterance()")
        assertTrue("оба шага обязаны быть в startCapture", mic >= 0 && utterance > mic)
        // Порядок «микрофон → реплика → перелив предбуфера под замком» не должен разъехаться.
        val drain = capture.indexOf("preRoll?.drain()")
        assertTrue("накопленное уходит в стрим после старта записи", drain > utterance)
    }

    @Test
    fun `захват перебивания не переключает фазу на слушание`() {
        val barge = body(source(CONTROLLER), "private fun armBargeInMic(")
        assertFalse(
            "пока ответ звучит, фаза обязана остаться SPEAKING (иначе кнопка «Стоп» исчезнет)",
            barge.contains("LocalVoicePhase.LISTENING"),
        )
        assertTrue(barge.contains("bargeInMicOpen = true"))
        assertTrue("решение по маршруту — в политике", barge.contains("BargeInMicPolicy.keepMicDuringSpeech"))
    }

    @Test
    fun `захват перебивания не закрывается защитой от эха`() {
        val close = body(source(CONTROLLER), "private fun closeMicForAssistantSpeech(")
        assertTrue(
            "иначе каждая дельта ответа закрывала бы реплику перебивания",
            close.contains("if (bargeInMicOpen) return false"),
        )
    }

    @Test
    fun `смена маршрута посреди ответа закрывает захват перебивания`() {
        // Вынули наушники — микрофон начал бы слышать динамик. Перебивание в этом ответе
        // отключается, а не превращается в самоперебивание. Проверяется ДОКАЗАННЫЙ активный
        // выход: «в списке доступных есть гарнитура» не значит, что звук идёт в неё.
        val listener = body(source(CONTROLLER), "private val captureListener")
        assertTrue(
            "тик речи обязан сверять активный маршрут с тем, на котором захват открывался",
            listener.contains("routes?.activeOutput()"),
        )
        assertTrue(
            "недоказанный маршрут тоже закрывает захват",
            listener.contains("isConfirmedHeadphones"),
        )
        assertFalse(
            "решение по доступным выходам — тот самый дефект: гарнитура воткнута, звук из динамика",
            listener.contains("routes?.route"),
        )
    }

    @Test
    fun `захват ASR не снимает флаг реплики на одном буфере`() {
        val accept = body(source(STREAMING_STT), "override fun acceptPcm(")
        assertFalse(
            "`utteranceOpen = false` здесь убивал весь остаток фразы",
            accept.contains("utteranceOpen = false"),
        )
    }

    /**
     * Тело функции по её объявлению: от первой `{` до парной закрывающей.
     *
     * Счёт скобок, а не «до следующей пустой строки»: в этих функциях есть вложенные блоки, и
     * обрезка по отступу разрезала бы `synchronized { … }` посередине.
     */
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
        const val OVERLAY = "src/main/java/ru/mars/mobile/VoiceModeOverlay.kt"
        const val STREAMING_STT = "src/main/java/ru/mars/mobile/StreamingStt.kt"
    }
}
