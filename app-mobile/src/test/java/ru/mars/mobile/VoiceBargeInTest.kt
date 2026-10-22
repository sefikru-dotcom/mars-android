package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Перебивание в цикле диалога: duplex-захват во время озвучки, подтверждение речи и защита от
 * самоперебивания.
 *
 * Почему это отдельный файл, а не строчки в [VoiceDialogLoopStreamingTest]: здесь проверяется не
 * механика состояний, а ГРАНИЦА между «человек говорит» и «микрофон слышит наш динамик». Ошибка в
 * любую сторону дорога: слишком доверчивая политика заставляет Марса замолкать на собственном
 * первом слове, слишком строгая — не даёт перебить вообще.
 *
 * Все сценарии проверяются на фейковом движке и фейковых часах: реальный ASR и реальный микрофон
 * тут не нужны, живая акустика проверяется на телефоне отдельно.
 */
class VoiceBargeInTest {

    private companion object {
        /** Текст ответа, который «звучит»: по нему отличаем эхо от речи человека. */
        const val ANSWER = "Сегодня в Ставрополе плюс двадцать, ветер слабый"

        /** Реплика человека, которой нет в ответе. */
        const val USER = "стоп подожди"
    }

    /** Фейковый движок: счётчики вызовов + умение выдать partial/final/speechStarted. */
    private class FakeStt : SpeechToText {
        override val id: String = "fake-asr"
        override var isReady: Boolean = true
        override var events: SpeechToText.Events? = null

        var starts = 0
        var cancels = 0
        var finishes = 0

        fun speechStarted() = events?.onSpeechStarted()
        fun partial(text: String) = events?.onPartial(text)
        fun final(text: String) = events?.onFinal(text)

        override fun startUtterance() {
            starts++
        }

        override fun acceptPcm(pcm: ByteArray, length: Int) = Unit

        override fun finishUtterance(): String? {
            finishes++
            return ""
        }

        override fun cancelUtterance() {
            cancels++
        }

        override fun release() = Unit
    }

    /** Хост-шпион: важно не только «сколько раз», но и «в каком порядке». */
    private class Host : VoiceDialogLoop.Listener {
        val events = mutableListOf<String>()
        val sent = mutableListOf<String>()
        val bargeIns = mutableListOf<BargeInEvent>()
        var stopSpeakingCalls = 0

        override fun onStateChanged(state: VoiceDialogLoop.State) {
            events += "state:$state"
        }

        override fun onSendRequested(requestId: Long, text: String, mode: VoiceDialogLoop.SendMode) {
            sent += text
            events += "send:$text"
        }

        override fun onStopSpeaking() {
            stopSpeakingCalls++
            events += "stop"
        }

        override fun onBargeIn(event: BargeInEvent) {
            bargeIns += event
            events += "barge:${event.evidence}"
        }
    }

    private var clock = 1_000_000L

    private fun loop(
        stt: SpeechToText,
        route: AudioRoute,
        aec: Boolean = false,
        bargeIn: Boolean = true,
        gate: BargeInGate = BargeInGate(),
    ): Pair<Host, VoiceDialogLoop> {
        val host = Host()
        return host to VoiceDialogLoop(
            listener = host,
            bargeInEnabled = bargeIn,
            stt = stt,
            bargeInGate = gate,
            audioRoute = { route },
            aecActive = { aec },
            nowMs = { clock },
        )
    }

    /**
     * Довести цикл до состояния «Марс говорит»: реплика человека → ход → озвучка.
     * После возврата: state == SPEAKING, монитор-реплика открыта, окно эхо-успокоения пройдено.
     */
    private fun speaking(
        host: Host,
        loop: VoiceDialogLoop,
        stt: FakeStt,
        answer: String? = ANSWER,
    ) {
        loop.start()
        stt.final("какая погода")
        loop.onAnswerStarted(answer)
        clock += 1_000L
    }

    // --- 1. защита от самоперебивания -----------------------------------------------------------

    @Test
    fun `тишина во время ответа не перебивает`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.SPEAKER, aec = true)
        speaking(host, loop, stt)

        // Сегментер не слышит речи: ни SpeechStarted, ни тиков.
        assertEquals(VoiceDialogLoop.State.SPEAKING, loop.state)
        assertEquals(0, host.stopSpeakingCalls)
        assertEquals(0, loop.bargeInCandidates)
    }

    @Test
    fun `эхо динамика с AEC не перебивает даже длинной речью и повтором слов ответа`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.SPEAKER, aec = true)
        speaking(host, loop, stt)

        // TTS звучит непрерывно: сегментер видит «речь» всю длину ответа.
        stt.speechStarted()
        var voiced = 0L
        repeat(30) {
            voiced += 100L
            clock += 100L
            loop.onSpeechProgress(voiced)
            // Движок распознаёт нашу же озвучку кусками.
            stt.partial("сегодня в ставрополе")
            stt.partial("плюс двадцать ветер слабый")
        }

        assertEquals("озвучка не должна останавливаться", 0, host.stopSpeakingCalls)
        assertEquals(0, loop.bargeIns)
        assertEquals(VoiceDialogLoop.State.SPEAKING, loop.state)
        assertTrue("кандидаты были, но не подтвердились", loop.bargeInCandidates > 0)
    }

    @Test
    fun `без текста ответа на динамике длительность не доказательство`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.SPEAKER, aec = true)
        speaking(host, loop, stt, answer = null)

        stt.speechStarted()
        clock += 3_000L
        loop.onSpeechProgress(3_000L)
        stt.partial(USER)

        assertEquals(0, host.stopSpeakingCalls)
        assertEquals(VoiceDialogLoop.State.SPEAKING, loop.state)
    }

    @Test
    fun `окно эхо-успокоения после старта озвучки`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.HEADPHONES)
        loop.start()
        stt.final("вопрос")
        loop.onAnswerStarted(ANSWER)

        // Начало ответа: хвост предыдущей фразы и атака TTS ещё в микрофоне.
        clock += 100L
        stt.speechStarted()
        loop.onSpeechProgress(400L)
        assertEquals("в окне успокоения не перебиваем", 0, host.stopSpeakingCalls)

        // Человек продолжает говорить уже после окна — вот это перебивание.
        clock += 400L
        assertTrue(loop.onSpeechProgress(800L))
        assertEquals(1, host.stopSpeakingCalls)
    }

    // --- 2. подтверждённое перебивание ---------------------------------------------------------

    @Test
    fun `на наушниках перебивает подтверждённая речь без partial`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.HEADPHONES)
        speaking(host, loop, stt)

        stt.speechStarted()
        assertFalse("одного старта речи мало", loop.onSpeechProgress(150L))
        assertTrue(loop.onSpeechProgress(260L))

        assertEquals(1, host.stopSpeakingCalls)
        assertEquals(VoiceDialogLoop.State.LISTENING, loop.state)
        assertEquals(BargeInEvidence.DURATION, loop.lastBargeInEvidence)
        val event = host.bargeIns.single()
        assertEquals(AudioRoute.HEADPHONES, event.route)
        assertEquals(BargeInEvidence.DURATION, event.evidence)
        assertTrue(event.voicedMs >= 260L)
        assertNull("partial не нужен", event.partial)
    }

    @Test
    fun `на динамике с AEC перебивает partial со своими словами`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.SPEAKER, aec = true)
        speaking(host, loop, stt)

        stt.speechStarted()
        stt.partial("сегодня в ставрополе")
        assertEquals("эхо ответа не считается", 0, host.stopSpeakingCalls)

        clock += 300L
        stt.partial(USER)

        assertEquals(1, host.stopSpeakingCalls)
        assertEquals(BargeInEvidence.TRANSCRIPT, loop.lastBargeInEvidence)
        assertEquals(USER, host.bargeIns.single().partial)
        assertTrue(host.bargeIns.single().aecActive)
    }

    @Test
    fun `на динамике без AEC нужен и текст, и чуть больше речи`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.SPEAKER, aec = false)
        speaking(host, loop, stt)

        stt.speechStarted()
        clock += 300L
        stt.partial(USER)
        assertEquals("без AEC порог длительности выше", 0, host.stopSpeakingCalls)

        clock += 200L
        stt.partial("$USER пожалуйста")
        assertEquals(1, host.stopSpeakingCalls)
        assertEquals(BargeInEvidence.TRANSCRIPT, loop.lastBargeInEvidence)
        assertFalse(host.bargeIns.single().aecActive)
    }

    @Test
    fun `Final во время озвучки — перебивание по факту и ровно один новый ход`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.SPEAKER, aec = false)
        speaking(host, loop, stt)
        val startsBefore = stt.starts

        stt.final(USER)

        assertEquals(1, host.stopSpeakingCalls)
        assertEquals(listOf("какая погода", USER), host.sent)
        assertEquals(VoiceDialogLoop.State.THINKING, loop.state)
        assertEquals("монитор-реплику не переоткрываем", startsBefore, stt.starts)
        assertEquals(BargeInEvidence.TRANSCRIPT, loop.lastBargeInEvidence)

        // Поздний повторный финал: ход уже ушёл, второго быть не может.
        stt.final(USER)
        assertEquals(2, host.sent.size)
    }

    @Test
    fun `всплески эха через паузу не подтверждают перебивание`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.HEADPHONES)
        speaking(host, loop, stt)

        // Три всплеска по 150 мс с паузами в 2 секунды: по отдельности — ниже порога.
        repeat(3) {
            stt.speechStarted()
            loop.onSpeechProgress(150L)
            loop.onSpeechProgress(0L)
            clock += 2_000L
        }

        assertEquals("пауза не должна стать доказательством речи", 0, host.stopSpeakingCalls)
        assertEquals(0, loop.bargeIns)
        assertEquals(VoiceDialogLoop.State.SPEAKING, loop.state)
    }

    // --- 3. эхо-финалы и сохранение новой реплики ----------------------------------------------

    @Test
    fun `эхо-финал во время озвучки выбрасывается и реплика открывается заново`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.SPEAKER, aec = true)
        speaking(host, loop, stt)
        val startsBefore = stt.starts

        stt.final("сегодня в ставрополе плюс двадцать")

        assertEquals("ответ Марса не стал репликой человека", 1, host.sent.size)
        assertEquals(1, loop.echoFinalsDropped)
        assertEquals(1, stt.cancels)
        assertEquals("монитор открыт снова", startsBefore + 1, stt.starts)
        assertEquals(0, host.stopSpeakingCalls)
        assertEquals(VoiceDialogLoop.State.SPEAKING, loop.state)
    }

    @Test
    fun `речь, начатая во время ответа, не теряется на его конце`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.SPEAKER, aec = false)
        speaking(host, loop, stt)
        val startsBefore = stt.starts

        // Человек говорит, но перебить не успел (коротко/тихо): политика видит кандидата.
        stt.speechStarted()
        loop.onSpeechProgress(120L)
        assertEquals(0, host.stopSpeakingCalls)

        loop.onAnswerFinished()
        assertEquals(VoiceDialogLoop.State.LISTENING, loop.state)
        assertEquals("реплика сохранена, вторую не открываем", startsBefore, stt.starts)
        assertEquals(0, stt.cancels)

        // Продолжение фразы приходит уже в слушании и уходит ходом.
        stt.final("что там по погоде")
        assertEquals(listOf("какая погода", "что там по погоде"), host.sent)
    }

    @Test
    fun `без речи монитор-реплика выбрасывается на конце ответа`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.SPEAKER, aec = true)
        speaking(host, loop, stt)
        val startsBefore = stt.starts

        loop.onAnswerFinished()

        assertEquals("эхо озвучки не должно остаться в реплике", 1, stt.cancels)
        assertEquals("открыта чистая реплика для ввода", startsBefore + 1, stt.starts)
        assertEquals(VoiceDialogLoop.State.LISTENING, loop.state)
        assertEquals(0, host.stopSpeakingCalls)
    }

    // --- 4. повторные перебивания и предел ------------------------------------------------------

    @Test
    fun `повторное перебивание в следующем ответе тоже работает`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.HEADPHONES)
        speaking(host, loop, stt)

        stt.speechStarted()
        assertTrue(loop.onSpeechProgress(260L))
        assertEquals(1, host.stopSpeakingCalls)

        // Человек договорил, ответил на новый вопрос — и перебил снова.
        stt.final("ещё вопрос")
        assertEquals(VoiceDialogLoop.State.THINKING, loop.state)
        loop.onAnswerStarted(ANSWER)
        clock += 1_000L
        stt.speechStarted()
        assertTrue(loop.onSpeechProgress(260L))

        assertEquals(2, host.stopSpeakingCalls)
        assertEquals(2, loop.bargeIns)
        assertEquals(2, host.bargeIns.size)
    }

    // --- 5. stop/dispose ------------------------------------------------------------------------

    @Test
    fun `stop во время ответа сбрасывает политику и не даёт поздних колбэков`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.HEADPHONES)
        speaking(host, loop, stt)
        stt.speechStarted()

        loop.stop()

        assertEquals(VoiceDialogLoop.State.IDLE, loop.state)
        assertEquals("монитор-реплика выброшена", 1, stt.cancels)
        assertFalse(loop.onSpeechProgress(5_000L))
        stt.partial(USER)
        stt.final(USER)
        assertEquals("после stop ничего не перебивает и не отправляет", 0, host.stopSpeakingCalls)
        assertEquals(1, host.sent.size)

        // Цикл можно запустить заново: политика не осталась в «ответе».
        assertTrue(loop.start())
        assertEquals(VoiceDialogLoop.State.LISTENING, loop.state)
        assertEquals(0, loop.bargeInCandidates)
    }

    @Test
    fun `перебивание не отменяет ход и не закрывает стрим`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.HEADPHONES)
        speaking(host, loop, stt)

        stt.speechStarted()
        assertTrue(loop.onSpeechProgress(300L))

        // Ход не отменён: хост получил только «замолчи» + событие перебивания.
        assertTrue(host.events.contains("stop"))
        assertEquals("state:LISTENING", host.events.last())
        assertEquals(listOf("какая погода"), host.sent)
        assertEquals(1, host.bargeIns.size)
        assertTrue("цикл продолжает слушать", loop.isActive)
    }
}
