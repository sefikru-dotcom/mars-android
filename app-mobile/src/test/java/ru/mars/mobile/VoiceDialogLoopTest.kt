package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты [VoiceDialogLoop] — чистый JVM, фейковый хост вместо Activity/сети/микрофона.
 *
 * Проверяется то, что нельзя проверить на устройстве без флаки: последовательность состояний
 * hands-free цикла, отсутствие второго сообщения при повторном колбэке распознавания, отсутствие
 * потери реплики, пришедшей во время ответа, и поведение barge-in (останавливает озвучку, но не
 * отменяет ход).
 *
 * Хост в тестах ничего не делает: он только считает запросы — как в [ChatActivity], где цикл
 * просит «распознай» и «отправь», а саму работу выполняет существующий код.
 */
class VoiceDialogLoopTest {

    /** Хост-шпион: пишет всё, что попросил цикл. */
    private class RecordingHost : VoiceDialogLoop.Listener {
        val states = mutableListOf<VoiceDialogLoop.State>()
        val transcribeIds = mutableListOf<Long>()
        val sent = mutableListOf<Triple<Long, String, VoiceDialogLoop.SendMode>>()
        var stopSpeakingCalls = 0
        var dropped = 0

        override fun onStateChanged(state: VoiceDialogLoop.State) {
            states += state
        }

        override fun onTranscribeRequested(requestId: Long, recording: Recording) {
            transcribeIds += requestId
        }

        override fun onSendRequested(requestId: Long, text: String, mode: VoiceDialogLoop.SendMode) {
            sent += Triple(requestId, text, mode)
        }

        override fun onStopSpeaking() {
            stopSpeakingCalls++
        }

        override fun onSegmentDropped() {
            dropped++
        }
    }

    private fun recording(ms: Long = 1_000L) = Recording(ByteArray(8), 16_000, ms)

    /** Часы цикла: окно эхо-успокоения не должно зависеть от реального времени в тестах. */
    private var clock = 1_000_000L

    private fun hostAndLoop(
        bargeIn: Boolean = false,
        route: AudioRoute = AudioRoute.UNKNOWN,
    ): Pair<RecordingHost, VoiceDialogLoop> {
        val host = RecordingHost()
        return host to VoiceDialogLoop(
            listener = host,
            bargeInEnabled = bargeIn,
            audioRoute = { route },
            nowMs = { clock },
        )
    }

    // --- 1. полный цикл -------------------------------------------------------------------------

    @Test
    fun `полный цикл слушаю-думаю-говорю-снова слушаю`() {
        val (host, loop) = hostAndLoop()
        assertTrue(loop.start())
        assertEquals(VoiceDialogLoop.State.LISTENING, loop.state)

        assertTrue(loop.onSegment(recording()))
        assertEquals(VoiceDialogLoop.State.THINKING, loop.state)
        assertEquals(listOf(1L), host.transcribeIds)

        assertTrue(loop.onTranscript(1L, "  привет марс ", turnActive = false))
        assertEquals(listOf(Triple(1L, "привет марс", VoiceDialogLoop.SendMode.SEND)), host.sent)
        assertTrue("цикл ждёт ответа", loop.awaitingAnswer)

        assertTrue(loop.onAnswerStarted())
        assertEquals(VoiceDialogLoop.State.SPEAKING, loop.state)

        assertTrue(loop.onAnswerFinished())
        assertEquals("hands-free: после ответа снова слушаем", VoiceDialogLoop.State.LISTENING, loop.state)
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

    // --- 2. дубль -------------------------------------------------------------------------------

    @Test
    fun `повторный колбэк распознавания не отправляет второе сообщение`() {
        val (host, loop) = hostAndLoop()
        loop.start()
        loop.onSegment(recording())

        assertTrue(loop.onTranscript(1L, "первый", turnActive = false))
        // Тот же ответ доставлен ещё раз (retry транспорта / двойной колбэк) — игнорируется.
        assertFalse(loop.onTranscript(1L, "первый", turnActive = false))

        assertEquals("ровно одно сообщение", 1, host.sent.size)
    }

    @Test
    fun `поздний колбэк прошлого хода игнорируется`() {
        val (host, loop) = hostAndLoop()
        loop.start()
        loop.onSegment(recording())
        loop.onTranscript(1L, "первый", turnActive = false)
        loop.onAnswerStarted()
        loop.onAnswerFinished()

        loop.onSegment(recording())
        assertEquals("второй запрос распознавания", listOf(1L, 2L), host.transcribeIds)
        // Ответ на ПЕРВЫЙ запрос пришёл с опозданием — второй раз "первый" не отправляется.
        assertFalse(loop.onTranscript(1L, "первый", turnActive = false))
        assertEquals(1, host.sent.size)
    }

    @Test
    fun `во время хода текст уходит steer, а не вторым сообщением`() {
        val (host, loop) = hostAndLoop()
        loop.start()
        loop.onSegment(recording())
        loop.onTranscript(1L, "ещё подумай", turnActive = true)

        assertEquals(VoiceDialogLoop.SendMode.STEER, host.sent.single().third)
    }

    // --- 3. потеря реплики ----------------------------------------------------------------------

    @Test
    fun `реплика во время распознавания не теряется`() {
        val (host, loop) = hostAndLoop()
        loop.start()
        loop.onSegment(recording(1_000))
        assertEquals(1, host.transcribeIds.size)

        // Человек говорит вторую фразу, пока первая ещё распознаётся.
        assertFalse("второй сегмент не запускает второе распознавание", loop.onSegment(recording(2_000)))
        assertNotNull("реплика ждёт своей очереди", loop.pendingSegment)

        // Первый ход завершился — ожидающая реплика сразу уходит в распознавание.
        loop.onTranscript(1L, "первая", turnActive = false)
        loop.onAnswerStarted()
        loop.onAnswerFinished()

        assertEquals("обе реплики дошли до распознавания", listOf(1L, 2L), host.transcribeIds)
        assertNull("слот освобождён", loop.pendingSegment)
    }

    @Test
    fun `третья реплика не копится в очереди`() {
        val (_, loop) = hostAndLoop()
        loop.start()
        loop.onSegment(recording())
        loop.onSegment(recording())
        loop.onSegment(recording())

        assertEquals("слот один", 1, loop.droppedSegments)
        assertNotNull(loop.pendingSegment)
    }

    // --- 4. barge-in ----------------------------------------------------------------------------

    @Test
    fun `без флага речь во время озвучки не перебивает ответ`() {
        val (host, loop) = hostAndLoop(bargeIn = false)
        loop.start()
        loop.onSegment(recording())
        loop.onTranscript(1L, "вопрос", turnActive = false)
        loop.onAnswerStarted()

        assertFalse("перебивание выключено", loop.onSpeechStarted())
        assertFalse("сегмент во время озвучки отброшен", loop.onSegment(recording()))
        assertEquals("озвучка не остановлена", 0, host.stopSpeakingCalls)
        assertEquals("ход не тронут", VoiceDialogLoop.State.SPEAKING, loop.state)
        assertEquals(1, host.sent.size)
    }

    @Test
    fun `с флагом barge-in на наушниках перебивает и не отменяет ход`() {
        val (host, loop) = hostAndLoop(bargeIn = true, route = AudioRoute.HEADPHONES)
        loop.start()
        loop.onSegment(recording())
        loop.onTranscript(1L, "вопрос", turnActive = false)
        loop.onAnswerStarted()
        clock += 1_000L

        assertFalse("одного SpeechStarted мало: нужно подтверждение", loop.onSpeechStarted())
        assertEquals("озвучка ещё звучит", 0, host.stopSpeakingCalls)

        assertTrue("подтверждённая речь перебивает", loop.onSpeechProgress(260))
        assertEquals("озвучка остановлена", 1, host.stopSpeakingCalls)
        assertEquals("цикл снова слушает", VoiceDialogLoop.State.LISTENING, loop.state)
        assertEquals("ход не отменяется: сообщений не добавилось", 1, host.sent.size)

        // Перебитая реплика принимается как новая и уходит в распознавание.
        assertTrue(loop.onSegment(recording()))
        assertEquals(listOf(1L, 2L), host.transcribeIds)
    }

    @Test
    fun `на динамике сегмент во время озвучки не перебивает — текста нет, длительность ничего не доказывает`() {
        val (host, loop) = hostAndLoop(bargeIn = true, route = AudioRoute.SPEAKER)
        loop.start()
        loop.onSegment(recording())
        loop.onTranscript(1L, "вопрос", turnActive = false)
        loop.onAnswerStarted("длинный ответ про погоду")
        clock += 1_000L

        assertFalse(loop.onSpeechStarted())
        assertFalse("сегмент отброшен", loop.onSegment(recording(ms = 4_000L)))
        assertFalse(loop.onSpeechProgress(4_000))
        assertEquals("озвучка не остановлена", 0, host.stopSpeakingCalls)
        assertEquals(VoiceDialogLoop.State.SPEAKING, loop.state)
        assertEquals("сегмент без текста на динамике не подтверждает перебивание", 1, loop.bargeInRejected)
    }

    @Test
    fun `barge-in не срабатывает в слушании и в раздумье`() {
        val (host, loop) = hostAndLoop(bargeIn = true)
        loop.start()
        assertFalse("в слушании перебивать нечего", loop.onSpeechStarted())
        loop.onSegment(recording())
        assertFalse("во время распознавания перебивать нечего", loop.onSpeechStarted())
        assertEquals(0, host.stopSpeakingCalls)
    }

    // --- 5. ошибки и остановка ------------------------------------------------------------------

    @Test
    fun `пустой результат распознавания возвращает в слушание`() {
        val (host, loop) = hostAndLoop()
        loop.start()
        loop.onSegment(recording())

        assertFalse(loop.onTranscript(1L, "   ", turnActive = false))
        assertEquals(VoiceDialogLoop.State.LISTENING, loop.state)
        assertEquals("ничего не отправлено", 0, host.sent.size)
    }

    @Test
    fun `отказ распознавания не рвёт цикл`() {
        val (_, loop) = hostAndLoop()
        loop.start()
        loop.onSegment(recording())
        assertTrue(loop.onTranscriptFailed(1L))
        assertEquals(VoiceDialogLoop.State.LISTENING, loop.state)
        assertTrue("цикл жив и принимает следующую реплику", loop.onSegment(recording()))
    }

    @Test
    fun `устаревший отказ распознавания игнорируется`() {
        val (_, loop) = hostAndLoop()
        loop.start()
        loop.onSegment(recording())
        assertFalse(loop.onTranscriptFailed(99L))
        assertEquals("состояние не сброшено чужим id", VoiceDialogLoop.State.THINKING, loop.state)
    }

    @Test
    fun `после stop цикл не принимает сегменты и не отправляет`() {
        val (host, loop) = hostAndLoop()
        loop.start()
        loop.onSegment(recording())
        loop.stop()

        assertEquals(VoiceDialogLoop.State.IDLE, loop.state)
        assertFalse(loop.onSegment(recording()))
        assertFalse(loop.onTranscript(1L, "поздно", turnActive = false))
        assertEquals(0, host.sent.size)
        assertTrue("после stop цикл можно запустить заново", loop.start())
    }

    @Test
    fun `ответ без озвучки всё равно возвращает в слушание`() {
        val (_, loop) = hostAndLoop()
        loop.start()
        loop.onSegment(recording())
        loop.onTranscript(1L, "вопрос", turnActive = false)
        // TTS недоступен/выключен: хост сразу сообщает о конце хода.
        assertTrue(loop.onAnswerFinished())
        assertEquals(VoiceDialogLoop.State.LISTENING, loop.state)
        assertFalse(loop.awaitingAnswer)
    }
}
