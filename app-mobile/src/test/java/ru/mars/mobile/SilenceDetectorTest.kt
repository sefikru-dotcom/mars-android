package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты [SilenceDetector] — чистый JVM, синтетический PCM16 и инжектированные метки времени.
 *
 * Почему так: детектор решает, когда оборвать запись, и ошибка здесь либо режет человека на
 * полуслове, либо не останавливает запись вообще. Проверять это на реальном микрофоне нельзя —
 * результат зависел бы от комнаты и от `Thread.sleep`. Поэтому сигнал синтетический (постоянная
 * амплитуда на чанк), а время идёт шагами по 100 мс, ровно как [VoiceRecorder.READ_CHUNK_BYTES]
 * при 16 кГц: каждое решение можно посчитать в уме и сверить с точным номером чанка.
 *
 * Все чанки ниже — 160 сэмплов = 100 мс звука; первый чанк приходит в момент 100 мс, поэтому
 * прошедшее время для чанка n равно `100 * (n - 1)`.
 */
class SilenceDetectorTest {

    // --- синтетический PCM ---------------------------------------------------------------------

    /** Чанк из [samples] одинаковых сэмплов [amplitude] (little-endian int16). */
    private fun chunk(amplitude: Int, samples: Int = SAMPLES_PER_CHUNK): ByteArray {
        val out = ByteArray(samples * 2)
        for (index in 0 until samples) {
            out[index * 2] = (amplitude and 0xFF).toByte()
            out[index * 2 + 1] = ((amplitude shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Прогнать чанки с шагом [stepMs] и вернуть решения по порядку. */
    private fun SilenceDetector.feed(chunks: List<ByteArray>, stepMs: Long = 100L): List<SilenceDetector.Decision> {
        var now = stepMs
        return chunks.map { bytes ->
            val decision = onChunk(bytes, now)
            now += stepMs
            decision
        }
    }

    private fun List<SilenceDetector.Decision>.stopIndexes(): List<Int> =
        withIndex().filter { it.value == SilenceDetector.Decision.STOP_SILENCE }.map { it.index }

    // --- тишина с самого начала ----------------------------------------------------------------

    @Test
    fun `silence from the very start never stops before the minimum duration`() {
        val detector = SilenceDetector()
        // 35 чанков = 3.4 с тишины: минимум 3 с достигается на 31-м чанке.
        val decisions = detector.feed(List(35) { chunk(0) })

        // До минимума — ни одного стопа, даже когда hangover давно накоплен (он набирается к 16-му).
        assertTrue(
            "до minNoSpeechMs стопа быть не должно: ${decisions.stopIndexes()}",
            decisions.take(30).all { it == SilenceDetector.Decision.CONTINUE },
        )
        assertEquals(
            "ровно один стоп, на чанке, где истекли 3000 мс",
            listOf(30),
            decisions.stopIndexes(),
        )
        // После стопа решение больше не повторяется: хост уже завершает сессию.
        assertTrue(decisions.drop(31).all { it == SilenceDetector.Decision.CONTINUE })
    }

    @Test
    fun `hangover alone does not stop a recording where nobody spoke`() {
        val detector = SilenceDetector()
        // 20 чанков = 1.9 с: hangover (1.2 с) истёк, но минимум длительности — нет.
        val decisions = detector.feed(List(20) { chunk(0) })

        assertTrue(
            "тишина без речи не должна обрывать запись раньше минимума",
            decisions.all { it == SilenceDetector.Decision.CONTINUE },
        )
    }

    // --- речь → пауза --------------------------------------------------------------------------

    @Test
    fun `speech then a pause longer than hangover stops exactly once`() {
        val detector = SilenceDetector()
        val chunks = List(10) { chunk(SPEECH_AMPLITUDE) } + List(20) { chunk(0) }

        val decisions = detector.feed(chunks)

        // Речь — 10 чанков (1 с), затем тишина; 1.2 с тишины набирается на 23-м чанке.
        assertEquals("один стоп за сессию", listOf(22), decisions.stopIndexes())
        assertTrue(
            "до накопления hangover запись продолжается",
            decisions.take(22).all { it == SilenceDetector.Decision.CONTINUE },
        )
        assertTrue(
            "повторного стопа нет",
            decisions.drop(23).all { it == SilenceDetector.Decision.CONTINUE },
        )
    }

    @Test
    fun `short pause inside speech does not stop the recording`() {
        val detector = SilenceDetector()
        // 5 чанков речи → 5 чанков паузы (0.5 с < 1.2 с) → снова речь.
        val chunks = List(5) { chunk(SPEECH_AMPLITUDE) } +
            List(5) { chunk(0) } +
            List(5) { chunk(SPEECH_AMPLITUDE) } +
            List(5) { chunk(0) }

        val decisions = detector.feed(chunks)

        assertTrue(
            "пауза короче hangover — это ещё речь: ${decisions.stopIndexes()}",
            decisions.all { it == SilenceDetector.Decision.CONTINUE },
        )
    }

    @Test
    fun `silence counter restarts after every chunk above the threshold`() {
        val detector = SilenceDetector()
        // Тишина набирается до 1.1 с, затем один громкий чанк сбрасывает счётчик, затем снова
        // тишина: стоп должен случиться через полный hangover после сброса, а не сразу.
        val chunks = List(11) { chunk(0) } + listOf(chunk(SPEECH_AMPLITUDE)) + List(15) { chunk(0) }

        val decisions = detector.feed(chunks)

        // Громкий чанк — 12-й (метка 1200 мс); 1.2 с тишины после него — 25-й чанк.
        assertEquals(listOf(24), decisions.stopIndexes())
    }

    // --- порог ---------------------------------------------------------------------------------

    @Test
    fun `noise just below the threshold counts as silence and just above as speech`() {
        // Пороги уменьшены, чтобы поведение читалось по номерам чанков; логика та же.
        val quiet = SilenceDetector(thresholdRms = 400, hangoverMs = 500, startGuardMs = 100, minNoSpeechMs = 2_000)
        val loud = SilenceDetector(thresholdRms = 400, hangoverMs = 500, startGuardMs = 100, minNoSpeechMs = 2_000)

        // 399 — ниже порога: это тишина, значит стоп только по минимуму длительности (2000 мс).
        val quietDecisions = quiet.feed(List(25) { chunk(399) })
        assertEquals("ниже порога — стоп по минимуму, а не по hangover", listOf(20), quietDecisions.stopIndexes())

        // 401 — выше порога: это уже речь, поэтому после паузы хватает hangover (500 мс).
        val loudDecisions = loud.feed(List(2) { chunk(401) } + List(10) { chunk(0) })
        assertEquals("выше порога — стоп по hangover, задолго до минимума", listOf(7), loudDecisions.stopIndexes())
    }

    @Test
    fun `single loud chunk is enough to mark the recording as speech`() {
        val detector = SilenceDetector(thresholdRms = 400, hangoverMs = 300, startGuardMs = 0, minNoSpeechMs = 10_000)

        val decisions = detector.feed(listOf(chunk(0), chunk(5_000), chunk(0), chunk(0), chunk(0), chunk(0)))

        // Без речи стоп ждал бы 10 с; здесь речь была — хватает 300 мс тишины после неё.
        assertEquals(listOf(5), decisions.stopIndexes())
    }

    // --- форма чанков --------------------------------------------------------------------------

    @Test
    fun `odd byte counts and empty chunks never crash the detector`() {
        val detector = SilenceDetector()
        var now = 0L

        // Нечётные длины: последний байт — половина сэмпла, он игнорируется.
        for (length in listOf(0, 1, 3, 7, 1_601)) {
            val buffer = chunk(0, samples = 900)
            now += 100
            assertEquals(
                "чанк длиной $length не должен останавливать запись",
                SilenceDetector.Decision.CONTINUE,
                detector.onChunk(buffer, now, length),
            )
        }

        // Громкий нечётный чанк — речь; затем тишина всё равно останавливает запись.
        val speech = chunk(SPEECH_AMPLITUDE, samples = 900)
        now += 100
        assertEquals(SilenceDetector.Decision.CONTINUE, detector.onChunk(speech, now, 1_601))
        repeat(20) {
            now += 100
            assertEquals(SilenceDetector.Decision.CONTINUE, detector.onChunk(speech, now, 1_601))
        }
        var stopped = false
        repeat(20) {
            now += 100
            if (detector.onChunk(chunk(0, samples = 900), now, 1_601) == SilenceDetector.Decision.STOP_SILENCE) {
                stopped = true
            }
        }
        assertTrue("после речи тишина обязана остановить запись", stopped)
    }

    @Test
    fun `chunk with a negative sample is measured by energy not by signed wrap`() {
        // -8000 = 0xE0C0 little-endian: проверяем, что знак сэмпла не превращается в «тишину».
        val detector = SilenceDetector(thresholdRms = 400, hangoverMs = 100, startGuardMs = 0, minNoSpeechMs = 10_000)
        val negative = chunk(-8_000)
        val decisions = detector.feed(listOf(negative) + List(6) { chunk(0) })

        assertEquals(
            "громкий отрицательный сэмпл — это речь, значит стоп только по hangover",
            listOf(2),
            decisions.stopIndexes(),
        )
    }

    // --- повторное использование ---------------------------------------------------------------

    @Test
    fun `reset makes the detector reusable for the next session`() {
        val detector = SilenceDetector()
        val first = detector.feed(List(30) { chunk(SPEECH_AMPLITUDE) } + List(20) { chunk(0) })
        // 30 чанков речи, затем тишина: 1.2 с набирается на 43-м чанке.
        assertEquals("первая сессия остановилась", listOf(42), first.stopIndexes())

        detector.reset()

        val second = detector.feed(List(30) { chunk(SPEECH_AMPLITUDE) } + List(20) { chunk(0) })
        assertEquals("после reset детектор снова работает с нуля", listOf(42), second.stopIndexes())
    }

    @Test
    fun `timestamps that do not advance keep the detector quiet`() {
        val detector = SilenceDetector()

        // nowMs одинаковый (часы хоста не сдвинулись): время не идёт, значит и тишина не копится.
        val decisions = List(50) { detector.onChunk(chunk(0), 1_000L) }

        assertTrue(decisions.all { it == SilenceDetector.Decision.CONTINUE })
    }

    private companion object {
        /** 160 сэмплов при 16 кГц — ровно 100 мс, как READ_CHUNK_BYTES у рекордера. */
        const val SAMPLES_PER_CHUNK = 160

        /** Заметно выше порога 400: обычная речь у микрофона телефона. */
        const val SPEECH_AMPLITUDE = 8_000
    }
}
