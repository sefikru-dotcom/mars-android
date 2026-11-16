package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Защита голосового режима от самоперебивания: когда микрофон можно открывать сам, а когда нельзя
 * ни при каких обстоятельствах.
 *
 * Контроллер целиком в JVM не поднимается (он держит `Handler` главного `Looper` и `Activity`),
 * поэтому проверяемая логика вынесена в чистые функции — [ContinuousListenPolicy] и
 * [OfflineTtsSpeechWatch] — и проверяется здесь в каждом условии. Плюс отдельно проверяется цикл
 * серверного голосового режима ([VoiceDialogLoop]): его защита от «эха, ставшего новым ходом»
 * живёт в самом цикле и от Android не зависит.
 */
class LocalVoiceControllerTest {

    // --- пауза перед автостартом микрофона ------------------------------------------------------

    @Test
    fun `пауза перед автостартом не короче реверберации комнаты`() {
        // 650 мс: хвост аудиобуфера AudioTrack + реверберация. 400 мс не хватало — эхо ответа
        // попадало в микрофон и уходило новым вопросом.
        assertEquals(650L, LocalVoiceController.CONTINUOUS_RESTART_DELAY_MS)
    }

    @Test
    fun `пауза отсчитывается от реального конца речи`() {
        val delay = LocalVoiceController.CONTINUOUS_RESTART_DELAY_MS
        // Только что договорил — ждём всю паузу.
        assertEquals(delay, ContinuousListenPolicy.remainingQuietMs(1_000L, 1_000L, delay))
        // Прошло 400 мс из 650 — остаётся 250.
        assertEquals(250L, ContinuousListenPolicy.remainingQuietMs(1_000L, 1_400L, delay))
        // Пауза выдержана — открывать можно.
        assertEquals(0L, ContinuousListenPolicy.remainingQuietMs(1_000L, 1_650L, delay))
        assertEquals(0L, ContinuousListenPolicy.remainingQuietMs(1_000L, 60_000L, delay))
    }

    @Test
    fun `речи ещё не было — ждать нечего`() {
        assertEquals(0L, ContinuousListenPolicy.remainingQuietMs(0L, 5_000L, 650L))
    }

    // --- автостарт микрофона: шесть причин молчать ----------------------------------------------

    /** Покой после ответа: единственная комбинация, в которой микрофон открывается сам. */
    private fun canRestart(
        released: Boolean = false,
        paused: Boolean = false,
        continuous: Boolean = true,
        answerPending: Boolean = false,
        streamedTurn: Boolean = false,
        ttsSpeaking: Boolean = false,
        phase: LocalVoicePhase = LocalVoicePhase.IDLE,
    ): Boolean = ContinuousListenPolicy.canRestart(
        released = released,
        paused = paused,
        continuous = continuous,
        answerPending = answerPending,
        streamedTurn = streamedTurn,
        ttsSpeaking = ttsSpeaking,
        phase = phase,
    )

    @Test
    fun `в покое после ответа микрофон открывается сам`() {
        assertTrue(canRestart())
    }

    @Test
    fun `пока звучит речь Марса микрофон не открывается`() {
        assertFalse("динамик попал бы в микрофон", canRestart(ttsSpeaking = true))
        // Даже если фаза уже успела стать IDLE: сигнал движка важнее снимка состояния.
        assertFalse(canRestart(ttsSpeaking = true, phase = LocalVoicePhase.IDLE))
    }

    @Test
    fun `пока ответ не отдан в синтез микрофон не открывается`() {
        assertFalse("заглушка договорила, а ответ впереди", canRestart(answerPending = true))
    }

    @Test
    fun `пока идёт потоковая озвучка микрофон не открывается`() {
        assertFalse("ответ ещё докатывается дельтами", canRestart(streamedTurn = true))
    }

    @Test
    fun `в любой занятой фазе микрофон не открывается`() {
        assertFalse(canRestart(phase = LocalVoicePhase.LISTENING))
        assertFalse(canRestart(phase = LocalVoicePhase.THINKING))
        assertFalse(canRestart(phase = LocalVoicePhase.SPEAKING))
    }

    @Test
    fun `без непрерывного режима микрофон сам не открывается`() {
        assertFalse("обычный чат: одна реплика на одно нажатие", canRestart(continuous = false))
    }

    @Test
    fun `фон и освобождённый контроллер не открывают микрофон`() {
        assertFalse(canRestart(paused = true))
        assertFalse(canRestart(released = true))
    }

    // --- серверный голосовой режим: эхо не должно стать новым ходом -----------------------------

    private companion object {
        const val ANSWER = "Сегодня в Ставрополе плюс двадцать, ветер слабый"
        const val USER = "стоп подожди"
    }

    /** Фейковый движок распознавания: счётчики и умение выдать `Final` в нужный момент. */
    private class FakeStt : SpeechToText {
        override val id: String = "fake-asr"
        override var isReady: Boolean = true
        override var events: SpeechToText.Events? = null
        var starts = 0

        fun final(text: String) = events?.onFinal(text)

        override fun startUtterance() {
            starts++
        }

        override fun acceptPcm(pcm: ByteArray, length: Int) = Unit

        override fun finishUtterance(): String? = ""

        override fun cancelUtterance() = Unit

        override fun release() = Unit
    }

    private class Host : VoiceDialogLoop.Listener {
        val sent = mutableListOf<String>()
        var stopSpeakingCalls = 0

        override fun onSendRequested(requestId: Long, text: String, mode: VoiceDialogLoop.SendMode) {
            sent += text
        }

        override fun onStopSpeaking() {
            stopSpeakingCalls++
        }
    }

    private var clock = 1_000_000L

    private fun loop(
        stt: SpeechToText,
        route: AudioRoute = AudioRoute.SPEAKER,
        aec: Boolean = false,
    ): Pair<Host, VoiceDialogLoop> {
        val host = Host()
        val loop = VoiceDialogLoop(
            listener = host,
            bargeInEnabled = true,
            stt = stt,
            audioRoute = { route },
            aecActive = { aec },
            nowMs = { clock },
        )
        return host to loop
    }

    @Test
    fun `эхо ответа на динамике не становится новым ходом`() {
        val stt = FakeStt()
        val (host, voiceLoop) = loop(stt)
        voiceLoop.start()
        stt.final("какая погода")
        voiceLoop.onAnswerStarted(ANSWER)
        clock += 1_000L

        // ASR услышал динамик и «распознал» сам ответ.
        stt.final(ANSWER)

        assertEquals("ответ Марса не стал репликой человека", 1, host.sent.size)
        assertEquals(1, voiceLoop.echoFinalsDropped)
        assertEquals(0, host.stopSpeakingCalls)
        assertEquals(VoiceDialogLoop.State.SPEAKING, voiceLoop.state)
    }

    @Test
    fun `без текста ответа на динамике финал не отправляется в чат`() {
        val stt = FakeStt()
        val (host, voiceLoop) = loop(stt)
        voiceLoop.start()
        stt.final("какая погода")
        // Хост не сообщил, что именно звучит: эталона эха нет, проверять «это эхо или человек» нечем.
        voiceLoop.onAnswerStarted(null)
        clock += 1_000L

        stt.final("сегодня плюс двадцать ветер слабый")

        assertEquals("догадка не отправляется в чат", listOf("какая погода"), host.sent)
        assertEquals(1, voiceLoop.echoFinalsDropped)
        assertEquals(VoiceDialogLoop.State.SPEAKING, voiceLoop.state)
    }

    @Test
    fun `без текста ответа на наушниках речь человека по-прежнему проходит`() {
        val stt = FakeStt()
        val (host, voiceLoop) = loop(stt, route = AudioRoute.HEADPHONES)
        voiceLoop.start()
        stt.final("какая погода")
        voiceLoop.onAnswerStarted(null)
        clock += 1_000L

        // На наушниках эха нет по конструкции: финал — это человек, его терять нельзя.
        stt.final(USER)

        assertEquals(listOf("какая погода", USER), host.sent)
        assertEquals(1, host.stopSpeakingCalls)
    }
}
