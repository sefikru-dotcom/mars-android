package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты [WhisperStt] — whisper-путь за швом [SpeechToText], на фейковом транспорте.
 *
 * Проверяется то, что делает его полноценной заменой движка для деградации: он сам режет поток
 * [VoiceSegmenter] (endpoint-детекции у whisper нет), отдаёт WAV в `POST /api/transcribe`,
 * присылает текст в [SpeechToText.Events.onFinal], а ручной стоп закрывает реплику принудительно
 * и НЕ притворяется, что текст уже есть (возвращает `null` — сеть асинхронна).
 */
class WhisperSttTest {

    private companion object {
        const val SPEECH = 3_000
        const val SILENCE = 0
        const val CHUNK_SAMPLES = 1_600
        const val CHUNK_MS = 100L
    }

    private class Events : SpeechToText.Events {
        val finals = mutableListOf<String>()
        var speechStarts = 0

        override fun onSpeechStarted() {
            speechStarts++
        }

        override fun onFinal(text: String) {
            finals += text
        }
    }

    /** Стенной таймер для сегментера: шаг 100 мс на чанк, как в [VoiceSegmenterTest]. */
    private class Clock {
        var now = 0L

        fun advance() {
            now += CHUNK_MS
        }
    }

    private fun chunk(amplitude: Int): ByteArray {
        val out = ByteArray(CHUNK_SAMPLES * 2)
        for (index in 0 until CHUNK_SAMPLES) {
            out[index * 2] = (amplitude and 0xFF).toByte()
            out[index * 2 + 1] = ((amplitude shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun whisper(
        clock: Clock,
        transcribe: (ByteArray, (String?) -> Unit) -> Unit,
    ): WhisperStt = WhisperStt(transcribe = transcribe, nowMs = { clock.now })

    /** [count] чанков подряд с шагом 100 мс. */
    private fun WhisperStt.feed(count: Int, amplitude: Int, clock: Clock) {
        repeat(count) {
            acceptPcm(chunk(amplitude), CHUNK_SAMPLES * 2)
            clock.advance()
        }
    }

    @Test
    fun `фраза уходит в transcribe и текст возвращается событием onFinal`() {
        val uploads = mutableListOf<ByteArray>()
        val clock = Clock()
        val stt = whisper(clock) { wav, onResult ->
            uploads += wav
            onResult("включи музыку")
        }
        val events = Events()
        stt.events = events
        stt.startUtterance()

        // 1 с речи + пауза больше hangover: сегментер сам режет реплику.
        stt.feed(10, SPEECH, clock)
        stt.feed(12, SILENCE, clock)

        assertEquals(1, stt.requests)
        assertEquals(1, uploads.size)
        assertEquals("WAV-контейнер", "RIFF", String(uploads.single(), 0, 4))
        assertEquals(listOf("включи музыку"), events.finals)
        assertTrue(stt.isReady)
        assertEquals("whisper", stt.id)
    }

    @Test
    fun `ручной стоп досылает хвост реплики и возвращает null`() {
        val uploads = mutableListOf<ByteArray>()
        val clock = Clock()
        val stt = whisper(clock) { wav, onResult ->
            uploads += wav
            onResult("недоговорённое")
        }
        val events = Events()
        stt.events = events
        stt.startUtterance()

        // 700 мс речи: сегмент ещё не закрыт (нужен hangover), значит его закрывает стоп.
        stt.feed(7, SPEECH, clock)
        assertNull("текста синхронно нет", stt.finishUtterance())

        assertEquals(1, stt.requests)
        assertEquals(1, uploads.size)
        assertEquals(listOf("недоговорённое"), events.finals)
    }

    @Test
    fun `после ручного стопа звук не принимается`() {
        val clock = Clock()
        val stt = whisper(clock) { _, onResult -> onResult("x") }
        stt.startUtterance()
        stt.feed(7, SPEECH, clock)
        stt.finishUtterance()

        stt.acceptPcm(chunk(SPEECH), CHUNK_SAMPLES * 2)

        assertEquals("второго POST нет", 1, stt.requests)
    }

    @Test
    fun `звук до начала реплики не уходит в сеть`() {
        val clock = Clock()
        val stt = whisper(clock) { _, onResult -> onResult("x") }

        stt.feed(10, SPEECH, clock)
        assertEquals(0, stt.requests)
        assertEquals("реплика не открыта", "", stt.finishUtterance())
    }

    @Test
    fun `сбой транспорта не рвёт цикл`() {
        val clock = Clock()
        val stt = whisper(clock) { _, _ -> throw IllegalStateException("нет сети") }
        val events = Events()
        stt.events = events
        stt.startUtterance()

        stt.feed(10, SPEECH, clock)
        stt.feed(12, SILENCE, clock)

        assertEquals("пустой финал вместо исключения", listOf(""), events.finals)
    }

    @Test
    fun `отмена не отправляет реплику`() {
        val clock = Clock()
        val stt = whisper(clock) { _, onResult -> onResult("x") }
        stt.startUtterance()
        stt.feed(7, SPEECH, clock)

        stt.cancelUtterance()
        stt.feed(12, SILENCE, clock)

        assertEquals(0, stt.requests)
    }
}
