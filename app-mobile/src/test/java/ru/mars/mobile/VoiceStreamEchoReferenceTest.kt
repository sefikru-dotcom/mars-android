package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Регрессия по блокеру «эхо второй и следующих фраз ответа принимается за речь человека».
 *
 * Что было сломано (HEAD c666a04): цикл получал снимок произносимого текста ОДИН раз, на старте
 * ответа (`onAnswerStarted(spokenAnswerText())`). Вторая и следующие фразы того же хода
 * отвергались, потому что цикл уже в `SPEAKING`, — и echo-гейт сравнивал partial/Final с текстом
 * первой фразы. На динамике без AEC это давало две ошибки сразу: эхо второй фразы проходило как
 * «слова, которых нет в ответе» (ложное перебивание) и, наоборот, настоящая речь, совпавшая с
 * первой фразой, глушилась.
 *
 * Фикс — явное обновление echo-ссылки для каждой РЕАЛЬНО зазвучавшей фразы
 * ([VoiceDialogLoop.onAnswerSpeechStarted]). Текст, который ещё не звучал, в ссылку не попадает:
 * иначе человек не смог бы перебить словами, которые Марс ещё не произнёс.
 *
 * Живая акустика (POCO F5) этими тестами не проверяется — здесь только детерминированная логика
 * «какой текст считается эхом».
 */
class VoiceStreamEchoReferenceTest {

    private companion object {
        const val FIRST = "Сегодня в Ставрополе плюс двадцать, ветер слабый."
        const val SECOND = "Завтра похолодает до пяти градусов, возможен дождь."
        const val USER = "стоп подожди"
    }

    /** Фейковый движок: тест сам решает, что «услышал» микрофон. */
    private class FakeStt : SpeechToText {
        override val id: String = "fake-asr"
        override var isReady: Boolean = true
        override var events: SpeechToText.Events? = null

        var starts = 0

        fun speechStarted() = events?.onSpeechStarted()
        fun partial(text: String) = events?.onPartial(text)
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
        val bargeIns = mutableListOf<BargeInEvent>()
        var stopSpeakingCalls = 0

        override fun onStateChanged(state: VoiceDialogLoop.State) = Unit

        override fun onSendRequested(requestId: Long, text: String, mode: VoiceDialogLoop.SendMode) {
            sent += text
        }

        override fun onStopSpeaking() {
            stopSpeakingCalls++
        }

        override fun onBargeIn(event: BargeInEvent) {
            bargeIns += event
        }
    }

    private var clock = 1_000_000L

    private fun loop(
        stt: SpeechToText,
        route: AudioRoute,
        aec: Boolean = false,
    ): Pair<Host, VoiceDialogLoop> {
        val host = Host()
        return host to VoiceDialogLoop(
            listener = host,
            bargeInEnabled = true,
            stt = stt,
            audioRoute = { route },
            aecActive = { aec },
            nowMs = { clock },
        )
    }

    /**
     * Довести цикл до «Марс говорит первую фразу», затем объявить вторую: ровно то, что делает
     * хост на каждой новой зазвучавшей фразе. Окно эхо-успокоения пройдено.
     */
    private fun speakingTwoPhrases(
        loop: VoiceDialogLoop,
        stt: FakeStt,
        secondPhrase: String? = SECOND,
    ) {
        loop.start()
        stt.final("какая погода")
        loop.onAnswerSpeechStarted(FIRST)
        clock += 1_000L
        if (secondPhrase != null) loop.onAnswerSpeechStarted(secondPhrase)
    }

    // --- 1. эхо второй фразы ---------------------------------------------------------------

    @Test
    fun `эхо второй фразы не становится репликой человека`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.SPEAKER)
        speakingTwoPhrases(loop, stt)

        // Движок распознал нашу же вторую фразу целиком.
        stt.final("завтра похолодает до пяти градусов возможен дождь")

        assertEquals("эхо не должно уходить в чат как вопрос", 1, host.sent.size)
        assertEquals(0, host.stopSpeakingCalls)
        assertEquals(1, loop.echoFinalsDropped)
        assertEquals(VoiceDialogLoop.State.SPEAKING, loop.state)
    }

    @Test
    fun `эхо на стыке фраз остаётся эхом`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.SPEAKER, aec = false)
        speakingTwoPhrases(loop, stt)

        // ASR-буфер захватил хвост первой фразы и начало второй — так и бывает на стыке.
        stt.speechStarted()
        clock += 500L
        loop.onSpeechProgress(500L)
        stt.partial("ветер слабый плюс двадцать завтра похолодает")

        assertEquals("стык фраз — это тот же ответ, а не человек", 0, host.stopSpeakingCalls)
        assertEquals(VoiceDialogLoop.State.SPEAKING, loop.state)
    }

    @Test
    fun `partial второй фразы на динамике без AEC не перебивает`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.SPEAKER, aec = false)
        speakingTwoPhrases(loop, stt)

        // Эхо идёт дольше порога: без обновлённой ссылки это выглядело бы как речь человека.
        stt.speechStarted()
        clock += 500L
        loop.onSpeechProgress(500L)
        stt.partial("завтра похолодает до пяти градусов")

        assertEquals("динамик не должен замолкать от собственного эха", 0, host.stopSpeakingCalls)
        assertEquals(VoiceDialogLoop.State.SPEAKING, loop.state)
    }

    @Test
    fun `настоящая речь после обновления ссылки всё равно перебивает`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.SPEAKER, aec = false)
        speakingTwoPhrases(loop, stt)

        stt.speechStarted()
        clock += 500L
        loop.onSpeechProgress(500L)
        stt.partial(USER)

        assertEquals("чужие слова — доказательство перебивания", 1, host.stopSpeakingCalls)
        assertEquals(1, host.bargeIns.size)
        assertEquals(BargeInEvidence.TRANSCRIPT, host.bargeIns.single().evidence)
        assertEquals(VoiceDialogLoop.State.LISTENING, loop.state)
    }

    @Test
    fun `final настоящей речи после обновления ссылки уходит новым ходом`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.SPEAKER, aec = false)
        speakingTwoPhrases(loop, stt)

        stt.final(USER)

        assertEquals(listOf("какая погода", USER), host.sent)
        assertEquals(1, host.stopSpeakingCalls)
        assertEquals(0, loop.echoFinalsDropped)
    }

    // --- 2. границы: что именно попадает в echo-ссылку --------------------------------------

    @Test
    fun `текст, который ещё не звучал, не считается эхом`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.SPEAKER)
        // Хост объявил только первую фразу: вторая ещё не ушла в звук.
        speakingTwoPhrases(loop, stt, secondPhrase = null)

        stt.final("завтра похолодает до пяти градусов возможен дождь")

        assertEquals(
            "неозвученный текст нельзя выдавать за эхо: человек вправе это сказать",
            listOf("какая погода", "завтра похолодает до пяти градусов возможен дождь"),
            host.sent,
        )
        assertEquals(0, loop.echoFinalsDropped)
    }

    @Test
    fun `обновление ссылки не перезапускает ответ`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.SPEAKER)
        loop.start()
        stt.final("какая погода")
        loop.onAnswerSpeechStarted(FIRST)
        clock += 1_000L
        val startsBeforeSecondPhrase = stt.starts

        // Ответ продолжается: вторая фраза только обновляет эталон эха.
        assertTrue(loop.onAnswerSpeechStarted(SECOND))

        assertEquals(VoiceDialogLoop.State.SPEAKING, loop.state)
        assertEquals(
            "вторая фраза не открывает новую реплику",
            startsBeforeSecondPhrase,
            stt.starts,
        )
        assertEquals(0, host.stopSpeakingCalls)
    }

    @Test
    fun `первая фраза после паузы снова переводит цикл в SPEAKING`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, route = AudioRoute.SPEAKER)
        speakingTwoPhrases(loop, stt)

        // Очередь опустела (пауза в ходе): цикл слушает, ход при этом не закончен.
        loop.onAnswerFinished()
        assertEquals(VoiceDialogLoop.State.LISTENING, loop.state)

        // Следующая фраза того же хода зазвучала — снова SPEAKING, без дубля ответа.
        assertTrue(loop.onAnswerSpeechStarted("Третья фраза ответа."))
        assertEquals(VoiceDialogLoop.State.SPEAKING, loop.state)
        assertEquals(1, host.sent.size)
    }
}
