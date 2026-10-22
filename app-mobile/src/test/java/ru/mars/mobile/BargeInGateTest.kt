package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Политика подтверждения перебивания — самая ответственная часть barge-in.
 *
 * Тесты намеренно написаны как «плохие» сценарии: динамик орёт ответом, движок распознаёт нашу же
 * озвучку, человек молчит или говорит коротко. Ни один из них не должен останавливать ответ.
 * И только сценарии, где речь человека действительно отличима, перебивают.
 */
class BargeInGateTest {

    private companion object {
        const val ANSWER = "Сегодня в Ставрополе плюс двадцать, ветер слабый"
        const val T0 = 10_000L
    }

    private fun gate(config: BargeInConfig = BargeInConfig()) = BargeInGate(config)

    private fun BargeInGate.speaking(
        route: AudioRoute,
        aec: Boolean = false,
        answer: String? = ANSWER,
    ) {
        onAnswerStarted(T0, route, aec, answer)
    }

    // --- 1. длительность ------------------------------------------------------------------------

    @Test
    fun `на наушниках хватает короткой подтверждённой речи`() {
        val gate = gate()
        gate.speaking(AudioRoute.HEADPHONES)
        assertTrue(gate.onSpeechStarted(T0 + 1_000))

        assertTrue(gate.onVoiced(150, T0 + 1_150) is BargeInDecision.Pending)
        val decision = gate.onVoiced(210, T0 + 1_210)
        assertTrue(decision is BargeInDecision.Trigger)
        assertEquals(BargeInEvidence.DURATION, (decision as BargeInDecision.Trigger).evidence)
        assertEquals(AudioRoute.HEADPHONES, decision.route)
    }

    @Test
    fun `на динамике длительность не доказательство, даже с AEC`() {
        val gate = gate()
        gate.speaking(AudioRoute.SPEAKER, aec = true)
        gate.onSpeechStarted(T0 + 500)

        // TTS звучит ровно так же громко и долго: это не аргумент.
        val decision = gate.onVoiced(4_000, T0 + 4_500)
        assertTrue(decision is BargeInDecision.Pending)
        assertEquals(
            BargeInReason.NO_TRANSCRIPT_PROOF,
            (decision as BargeInDecision.Pending).reason,
        )
    }

    @Test
    fun `неизвестный маршрут считается эхо-опасным`() {
        val gate = gate()
        gate.speaking(AudioRoute.UNKNOWN, aec = true)
        gate.onSpeechStarted(T0 + 500)

        assertTrue(gate.onVoiced(5_000, T0 + 5_500) is BargeInDecision.Pending)
    }

    // --- 2. текст -----------------------------------------------------------------------------

    @Test
    fun `partial со словами ответа — эхо и не подтверждает перебивание`() {
        val gate = gate()
        gate.speaking(AudioRoute.SPEAKER, aec = true)
        gate.onSpeechStarted(T0 + 500)

        gate.onPartial("сегодня в ставрополе", T0 + 700)
        gate.onPartial("плюс двадцать ветер слабый", T0 + 1_200)
        assertTrue("эхо узнаётся по словам ответа", gate.isEchoOfAssistant("сегодня в ставрополе"))

        assertTrue(gate.onVoiced(3_000, T0 + 3_500) is BargeInDecision.Pending)
    }

    @Test
    fun `partial со своими словами подтверждает перебивание на динамике с AEC`() {
        val gate = gate()
        gate.speaking(AudioRoute.SPEAKER, aec = true)
        gate.onSpeechStarted(T0 + 500)

        assertTrue(gate.onPartial("стоп", T0 + 600) is BargeInDecision.Pending)
        val decision = gate.onPartial("стоп подожди", T0 + 800)
        assertTrue(decision is BargeInDecision.Trigger)
        assertEquals(BargeInEvidence.TRANSCRIPT, (decision as BargeInDecision.Trigger).evidence)
        assertEquals("стоп подожди", gate.lastPartial)
    }

    @Test
    fun `без AEC на динамике нужен и текст, и больше речи`() {
        val gate = gate()
        gate.speaking(AudioRoute.SPEAKER, aec = false)
        gate.onSpeechStarted(T0 + 500)

        assertTrue(gate.onPartial("стоп подожди", T0 + 700) is BargeInDecision.Pending)
        assertTrue(gate.onPartial("стоп подожди", T0 + 850) is BargeInDecision.Pending)
        val decision = gate.onPartial("стоп подожди", T0 + 950)
        assertTrue(decision is BargeInDecision.Trigger)
        assertEquals(BargeInEvidence.TRANSCRIPT, (decision as BargeInDecision.Trigger).evidence)
    }

    @Test
    fun `без текста ответа на динамике текст не принимается`() {
        val gate = gate()
        gate.speaking(AudioRoute.SPEAKER, aec = true, answer = null)
        gate.onSpeechStarted(T0 + 500)

        // Проверить «эхо или человек» нечем — значит, доказательства нет.
        assertTrue(gate.onPartial("стоп подожди", T0 + 2_000) is BargeInDecision.Pending)
    }

    @Test
    fun `на наушниках текст не обязателен, но и не мешает`() {
        val gate = gate()
        gate.speaking(AudioRoute.HEADPHONES, answer = null)
        gate.onSpeechStarted(T0 + 500)

        val decision = gate.onPartial("стоп", T0 + 800)
        assertTrue(decision is BargeInDecision.Trigger)
    }

    // --- 3. защита от самоперебивания -----------------------------------------------------------

    @Test
    fun `окно эхо-успокоения отбрасывает раннюю речь и ранний partial`() {
        val gate = gate()
        gate.speaking(AudioRoute.HEADPHONES)

        assertFalse("речь в окне не принимается", gate.onSpeechStarted(T0 + 100))
        assertTrue(
            gate.onPartial("стоп", T0 + 120) is BargeInDecision.Pending,
        )
        assertEquals(
            BargeInReason.ECHO_SETTLE,
            (gate.onPartial("стоп", T0 + 120) as BargeInDecision.Pending).reason,
        )

        // После окна та же речь подтверждается.
        assertTrue(gate.onSpeechStarted(T0 + 400))
        assertTrue(gate.onVoiced(250, T0 + 650) is BargeInDecision.Trigger)
    }

    @Test
    fun `пауза между всплесками не копится в длительность речи`() {
        val gate = BargeInGate()
        gate.onAnswerStarted(T0, AudioRoute.HEADPHONES, aecActive = false)
        gate.onSpeechStarted(T0 + 400)

        // Всплеск эха короче порога, затем тишина, затем второй такой же всплеск.
        assertTrue(gate.onVoiced(120, T0 + 520) is BargeInDecision.Pending)
        gate.onSpeechEnded()
        gate.onSpeechStarted(T0 + 3_000)
        val decision = gate.onVoiced(120, T0 + 3_120)

        assertTrue(
            "3 секунды паузы не должны превращаться в доказательство речи",
            decision is BargeInDecision.Pending,
        )
        assertEquals(BargeInReason.TOO_SHORT, (decision as BargeInDecision.Pending).reason)
    }

    @Test
    fun `повторное перебивание требует больше речи`() {
        val gate = gate()
        gate.speaking(AudioRoute.HEADPHONES)
        gate.onSpeechStarted(T0 + 500)
        assertTrue(gate.onVoiced(250, T0 + 750) is BargeInDecision.Trigger)

        gate.onAccepted()
        gate.onSpeechStarted(T0 + 1_000)
        assertTrue("штраф за повтор поднимает порог", gate.onVoiced(250, T0 + 1_250) is BargeInDecision.Pending)
        assertTrue(gate.onVoiced(560, T0 + 1_560) is BargeInDecision.Trigger)
    }

    @Test
    fun `предел перебиваний на ответ закрывает политику`() {
        val gate = gate(BargeInConfig(maxBargeInsPerAnswer = 2))
        gate.speaking(AudioRoute.HEADPHONES)

        repeat(2) {
            gate.onSpeechStarted(T0 + 500 + it * 1_000L)
            gate.onVoiced(5_000, T0 + 1_500 + it * 1_000L)
            gate.onAccepted()
        }
        gate.onSpeechStarted(T0 + 5_000)

        val decision = gate.onVoiced(5_000, T0 + 9_000)
        assertEquals(
            BargeInReason.LIMIT_REACHED,
            (decision as BargeInDecision.Pending).reason,
        )
    }

    @Test
    fun `ответ закончился — политика выключена`() {
        val gate = gate()
        gate.speaking(AudioRoute.HEADPHONES)
        gate.onAnswerFinished()
        assertFalse(gate.isActive)

        assertTrue(gate.onVoiced(5_000, T0 + 9_000) is BargeInDecision.Ignored)
        assertTrue(gate.onSpeechStarted(T0 + 9_000).not())
    }

    @Test
    fun `reset чистит состояние между сессиями`() {
        val gate = gate()
        gate.speaking(AudioRoute.HEADPHONES)
        gate.onSpeechStarted(T0 + 500)
        gate.onVoiced(300, T0 + 800)
        gate.onAccepted()

        gate.reset()

        assertFalse(gate.isActive)
        assertEquals(0, gate.acceptedCount)
        assertEquals(AudioRoute.UNKNOWN, gate.route)
        assertNull(gate.lastPartial)
        assertFalse(gate.isCandidate)
    }

    // --- 4. сегменты whisper-пути ---------------------------------------------------------------

    @Test
    fun `сегмент на наушниках перебивает, на динамике нет`() {
        val headphones = gate()
        headphones.speaking(AudioRoute.HEADPHONES)
        assertTrue(headphones.onSegment(1_200, T0 + 2_000) is BargeInDecision.Trigger)

        val speaker = gate()
        speaker.speaking(AudioRoute.SPEAKER, aec = true)
        // Длительность сегмента — единственное, что есть; на динамике этого мало.
        assertTrue(speaker.onSegment(4_000, T0 + 5_000) is BargeInDecision.Pending)
    }

    // --- 5. эвристика эха -----------------------------------------------------------------------

    @Test
    fun `эхо определяется по доле слов ответа`() {
        val gate = gate()
        gate.speaking(AudioRoute.SPEAKER, aec = true, answer = ANSWER)

        assertTrue(gate.isEchoOfAssistant("сегодня в ставрополе"))
        assertTrue(gate.isEchoOfAssistant("Плюс двадцать, ветер слабый!"))
        assertFalse(gate.isEchoOfAssistant("стоп подожди"))
        assertFalse(gate.isEchoOfAssistant("марс хватит"))
    }

    @Test
    fun `без текста ответа эхо не определяется`() {
        val gate = gate()
        gate.speaking(AudioRoute.SPEAKER, aec = true, answer = null)
        assertFalse(gate.isEchoOfAssistant("сегодня в ставрополе"))
    }

    @Test
    fun `пустой текст ответа не делает любой partial эхом`() {
        val gate = gate()
        gate.speaking(AudioRoute.SPEAKER, aec = true, answer = "   ")
        assertFalse(gate.isEchoOfAssistant("стоп"))
    }

    @Test
    fun `кандидат виден циклу, пока речь не закончилась`() {
        val gate = gate()
        gate.speaking(AudioRoute.SPEAKER, aec = true)
        assertFalse(gate.isCandidate)

        gate.onSpeechStarted(T0 + 500)
        assertTrue("реплику нельзя выбрасывать посреди фразы", gate.isCandidate)

        gate.onSpeechEnded()
        assertFalse(gate.isCandidate)
    }
}
