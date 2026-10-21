package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты потокового режима [VoiceDialogLoop] — чистый JVM, фейковый [SpeechToText] вместо движка.
 *
 * Что именно проверяется (и почему только так): движок — нативная библиотека, которой в JVM нет,
 * поэтому шов [SpeechToText] подменяется фейком, а тесты проверяют ПОЛИТИКУ цикла: стадии
 * `listening → thinking → speaking → listening`, отправку хода по `Final` (а не по таймауту
 * тишины), ручной стоп через `finishUtterance`, плавную деградацию на whisper и — главное —
 * отсутствие двойной отправки, когда `Final` движка и сегмент whisper описывают одну и ту же фразу.
 */
class VoiceDialogLoopStreamingTest {

    /** Фейковый движок: считает вызовы и умеет выдавать события так, как это сделал бы движок. */
    private class FakeStt(
        override var isReady: Boolean = true,
    ) : SpeechToText {
        override val id: String = "fake-asr"

        override var events: SpeechToText.Events? = null

        var starts = 0
        var finishes = 0
        var cancels = 0
        var releases = 0

        /** Длины буферов, которые цикл отдал движку (цикл их не отдаёт — только хост). */
        val pcmLengths = mutableListOf<Int>()

        /** Что вернёт ручной стоп: текст, "" (нечего) или null (текст придёт в onFinal). */
        var finishResult: String? = ""

        override fun startUtterance() {
            starts++
        }

        override fun acceptPcm(pcm: ByteArray, length: Int) {
            pcmLengths += length
        }

        override fun finishUtterance(): String? {
            finishes++
            return finishResult
        }

        override fun cancelUtterance() {
            cancels++
        }

        override fun release() {
            releases++
        }

        fun partial(text: String) {
            events?.onPartial(text)
        }

        fun final(text: String) {
            events?.onFinal(text)
        }

        fun fail(reason: String = "движок упал") {
            events?.onFailed(reason)
        }

        fun speechStarted() {
            events?.onSpeechStarted()
        }
    }

    /** Хост-шпион: пишет всё, что попросил цикл. */
    private class RecordingHost : VoiceDialogLoop.Listener {
        val states = mutableListOf<VoiceDialogLoop.State>()
        val transcribeIds = mutableListOf<Long>()
        val sent = mutableListOf<Triple<Long, String, VoiceDialogLoop.SendMode>>()
        val partials = mutableListOf<String>()
        val fallbacks = mutableListOf<String>()
        var stopSpeakingCalls = 0

        override fun onStateChanged(state: VoiceDialogLoop.State) {
            states += state
        }

        override fun onTranscribeRequested(requestId: Long, recording: Recording) {
            transcribeIds += requestId
        }

        override fun onSendRequested(requestId: Long, text: String, mode: VoiceDialogLoop.SendMode) {
            sent += Triple(requestId, text, mode)
        }

        override fun onPartialTranscript(text: String) {
            partials += text
        }

        override fun onSttFallback(reason: String) {
            fallbacks += reason
        }

        override fun onStopSpeaking() {
            stopSpeakingCalls++
        }
    }

    private fun recording(ms: Long = 1_000L) = Recording(ByteArray(8), 16_000, ms)

    private fun loop(
        stt: SpeechToText,
        turnActive: Boolean = false,
        bargeIn: Boolean = false,
        route: AudioRoute = AudioRoute.UNKNOWN,
        aec: Boolean = false,
    ): Pair<RecordingHost, VoiceDialogLoop> {
        val host = RecordingHost()
        return host to VoiceDialogLoop(
            listener = host,
            bargeInEnabled = bargeIn,
            stt = stt,
            turnActive = { turnActive },
            audioRoute = { route },
            aecActive = { aec },
            nowMs = { clock },
        )
    }

    /** Часы цикла: окно эхо-успокоения не должно зависеть от реального времени в тестах. */
    private var clock = 1_000_000L

    // --- 1. стадии цикла ------------------------------------------------------------------------

    @Test
    fun `start открывает реплику у движка и цикл слушает`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt)

        assertTrue(loop.start())
        assertEquals(VoiceDialogLoop.State.LISTENING, loop.state)
        assertEquals("реплика открыта у движка", 1, stt.starts)
        assertEquals(VoiceDialogLoop.SttMode.STREAMING, loop.sttMode)
        assertTrue(loop.usesStreaming)
        assertEquals("деградации нет", emptyList<String>(), host.fallbacks)
    }

    @Test
    fun `Final отправляет ход и ведёт цикл по стадиям`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt)
        loop.start()

        stt.final("  привет марс ")
        assertEquals(
            listOf(Triple(1L, "привет марс", VoiceDialogLoop.SendMode.SEND)),
            host.sent,
        )
        assertEquals("ждём ответ", VoiceDialogLoop.State.THINKING, loop.state)
        assertTrue(loop.awaitingAnswer)

        assertTrue(loop.onAnswerStarted())
        assertEquals(VoiceDialogLoop.State.SPEAKING, loop.state)

        assertTrue(loop.onAnswerFinished())
        assertEquals(VoiceDialogLoop.State.LISTENING, loop.state)
        assertEquals("после ответа реплика открыта заново", 2, stt.starts)
        assertEquals(
            listOf(
                VoiceDialogLoop.State.LISTENING,
                VoiceDialogLoop.State.THINKING,
                VoiceDialogLoop.State.SPEAKING,
                VoiceDialogLoop.State.LISTENING,
            ),
            host.states,
        )
    }

    // --- 2. частичные результаты ----------------------------------------------------------------

    @Test
    fun `частичные результаты уходят хосту и не отправляют ход`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt)
        loop.start()

        stt.partial("при")
        stt.partial("привет")

        assertEquals(listOf("при", "привет"), host.partials)
        assertEquals("привет", loop.partialTranscript)
        assertEquals("ход не отправлен", 0, host.sent.size)
        assertEquals(VoiceDialogLoop.State.LISTENING, loop.state)
    }

    @Test
    fun `частичные результаты во время хода не показываются`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt)
        loop.start()
        stt.final("первый вопрос")
        assertEquals(VoiceDialogLoop.State.THINKING, loop.state)

        stt.partial("вто")

        assertEquals("чужая реплика не должна попасть в композер", emptyList<String>(), host.partials)
        assertNull(loop.partialTranscript)
    }

    // --- 3. ручной стоп -------------------------------------------------------------------------

    @Test
    fun `ручной стоп отправляет финальный текст и поздний Final игнорируется`() {
        val stt = FakeStt().apply { finishResult = "недоговорённое" }
        val (host, loop) = loop(stt)
        loop.start()

        assertTrue("ручной стоп отправил текст", loop.stopUtterance())
        assertEquals(1, stt.finishes)
        assertEquals(listOf("недоговорённое"), host.sent.map { it.second })

        // Движок всё-таки выдал endpoint после стопа — второй раз не отправляем.
        stt.final("недоговорённое")
        assertEquals("двойной отправки нет", 1, host.sent.size)
    }

    @Test
    fun `ручной стоп без синхронного текста ждёт Final и отправляет один раз`() {
        val stt = FakeStt().apply { finishResult = null }
        val (host, loop) = loop(stt)
        loop.start()

        assertFalse("текста синхронно нет", loop.stopUtterance())
        assertEquals("пока ничего не отправлено", 0, host.sent.size)

        stt.final("поздний финал")
        assertEquals(1, host.sent.size)
        stt.final("поздний финал")
        assertEquals("повтор не отправляется", 1, host.sent.size)
    }

    @Test
    fun `ручной стоп на пустой реплике возвращает в слушание`() {
        val stt = FakeStt().apply { finishResult = "" }
        val (host, loop) = loop(stt)
        loop.start()

        assertFalse(loop.stopUtterance())
        assertEquals(0, host.sent.size)
        assertEquals(VoiceDialogLoop.State.LISTENING, loop.state)
        assertEquals("реплика открыта заново", 2, stt.starts)
    }

    @Test
    fun `ручной стоп в старом режиме ничего не делает`() {
        val host = RecordingHost()
        val loop = VoiceDialogLoop(host)
        loop.start()
        loop.onSegment(recording())

        assertFalse("в whisper-пути реплику закрывает HandsFreeCapture", loop.stopUtterance())
        assertEquals(0, host.sent.size)
    }

    // --- 4. пустой финал ------------------------------------------------------------------------

    @Test
    fun `пустой Final не отправляет ход и снова слушает`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt)
        loop.start()

        stt.final("   ")

        assertEquals(0, host.sent.size)
        assertEquals(VoiceDialogLoop.State.LISTENING, loop.state)
        assertEquals("реплика переоткрыта", 2, stt.starts)
        assertNull(loop.partialTranscript)
    }

    // --- 5. двойная отправка --------------------------------------------------------------------

    @Test
    fun `сегмент в потоковом режиме не уходит в whisper`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt)
        loop.start()

        assertFalse("сегмент проигнорирован", loop.onSegment(recording()))
        assertEquals("whisper не вызывался", emptyList<Long>(), host.transcribeIds)
        assertEquals(1, loop.streamingSegmentsIgnored)
        assertEquals(0, loop.droppedSegments)
        assertEquals(0, host.sent.size)

        stt.final("фраза")
        assertEquals("ровно одно сообщение", 1, host.sent.size)
    }

    @Test
    fun `поздний whisper-колбэк в потоковом режиме игнорируется`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt)
        loop.start()
        stt.final("фраза")

        assertFalse(loop.onTranscript(1L, "фраза", turnActive = false))
        assertEquals(1, host.sent.size)
    }

    @Test
    fun `повторный Final во время хода не отправляет второе сообщение`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt)
        loop.start()
        stt.final("первая")
        assertEquals(VoiceDialogLoop.State.THINKING, loop.state)

        stt.final("первая")

        assertEquals("повтор того же финала не отправлен", 1, host.sent.size)
    }

    // --- 6. деградация --------------------------------------------------------------------------

    @Test
    fun `неготовый движок сразу уходит на whisper и сообщает причину`() {
        val stt = FakeStt(isReady = false)
        val (host, loop) = loop(stt)

        loop.start()

        assertEquals(VoiceDialogLoop.SttMode.SEGMENTS, loop.sttMode)
        assertFalse(loop.usesStreaming)
        assertEquals("реплика у движка не открывается", 0, stt.starts)
        assertEquals("причина названа", 1, host.fallbacks.size)
        assertTrue(host.fallbacks.single().contains("whisper"))

        // Старый путь работает целиком: сегмент → распознавание → отправка.
        assertTrue(loop.onSegment(recording()))
        assertEquals(listOf(1L), host.transcribeIds)
        assertTrue(loop.onTranscript(1L, "через whisper", turnActive = false))
        assertEquals(1, host.sent.size)
    }

    @Test
    fun `движок догрузился и refreshSttMode переключает на потоковый путь`() {
        val stt = FakeStt(isReady = false)
        val (host, loop) = loop(stt)
        loop.start()
        assertEquals(VoiceDialogLoop.SttMode.SEGMENTS, loop.sttMode)

        stt.isReady = true
        assertEquals(VoiceDialogLoop.SttMode.STREAMING, loop.refreshSttMode())
        assertEquals("реплика открыта у движка", 1, stt.starts)

        stt.final("потоком")
        assertEquals(1, host.sent.size)
        assertEquals("whisper больше не участвует", emptyList<Long>(), host.transcribeIds)
    }

    @Test
    fun `падение движка посреди сессии переводит на whisper`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt)
        loop.start()

        stt.fail("нет памяти")

        assertEquals(VoiceDialogLoop.SttMode.SEGMENTS, loop.sttMode)
        assertEquals(1, host.fallbacks.size)
        assertEquals("реплика движка отменена", 1, stt.cancels)
        assertEquals(VoiceDialogLoop.State.LISTENING, loop.state)

        // Следующая фраза идёт старым путём.
        assertTrue(loop.onSegment(recording()))
        assertEquals(listOf(1L), host.transcribeIds)
        // Финал от умершего движка больше ничего не отправляет.
        stt.final("фантом")
        assertEquals(0, host.sent.size)
    }

    @Test
    fun `без движка поведение цикла прежнее`() {
        val host = RecordingHost()
        val loop = VoiceDialogLoop(host)

        loop.start()
        assertEquals(VoiceDialogLoop.SttMode.SEGMENTS, loop.sttMode)
        assertEquals("деградацию не о чем сообщать", emptyList<String>(), host.fallbacks)
        assertTrue(loop.onSegment(recording()))
        assertTrue(loop.onTranscript(1L, "старый путь", turnActive = false))
        assertEquals(1, host.sent.size)
    }

    // --- 7. остановка ---------------------------------------------------------------------------

    @Test
    fun `stop отменяет реплику и не отправляет поздний Final`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt)
        loop.start()

        loop.stop()

        assertEquals(VoiceDialogLoop.State.IDLE, loop.state)
        assertEquals("реплика выброшена", 1, stt.cancels)
        stt.final("поздно")
        assertEquals(0, host.sent.size)
        assertTrue("цикл можно запустить заново", loop.start())
    }

    // --- 8. steer -------------------------------------------------------------------------------

    @Test
    fun `во время хода Final уходит steer`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, turnActive = true)
        loop.start()

        stt.final("ещё подумай")

        assertEquals(VoiceDialogLoop.SendMode.STEER, host.sent.single().third)
    }

    // --- 9. barge-in ----------------------------------------------------------------------------

    @Test
    fun `barge-in по событию движка останавливает озвучку и открывает новую реплику`() {
        val stt = FakeStt()
        val (host, loop) = loop(stt, bargeIn = true, route = AudioRoute.HEADPHONES)
        loop.start()
        stt.final("вопрос")
        loop.onAnswerStarted()
        clock += 1_000L
        assertEquals(VoiceDialogLoop.State.SPEAKING, loop.state)

        stt.speechStarted()
        assertEquals("одно событие движка — ещё не перебивание", 0, host.stopSpeakingCalls)
        assertEquals(VoiceDialogLoop.State.SPEAKING, loop.state)

        assertTrue(loop.onSpeechProgress(240))

        assertEquals(1, host.stopSpeakingCalls)
        assertEquals(VoiceDialogLoop.State.LISTENING, loop.state)
        assertEquals("монитор-реплика уже слушает человека: новую не открываем", 2, stt.starts)
        assertEquals("ход не отменяется", 1, host.sent.size)
    }
}
