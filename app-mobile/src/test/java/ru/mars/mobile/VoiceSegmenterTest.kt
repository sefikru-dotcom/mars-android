package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты [VoiceSegmenter] — чистый JVM, синтетический PCM16, время шагами по 100 мс.
 *
 * Почему синтетика, а не микрофон: сегментер решает, ГДЕ резать непрерывный поток на реплики, и
 * ошибка здесь либо теряет фразу (человек говорит, а STT ничего не получает), либо отдаёт один
 * и тот же звук дважды (два одинаковых сообщения в чате). На реальном микрофоне результат зависел
 * бы от комнаты и `Thread.sleep`, поэтому сигнал задаётся амплитудой, а время — параметром.
 *
 * Все чанки — 1600 сэмплов = 100 мс звука при 16 кГц (ровно [VoiceRecorder.READ_CHUNK_BYTES]),
 * первый чанк приходит в момент 0, шаг времени 100 мс: длительности считаются в уме.
 *
 * Набор покрывает ровно те случаи, где ломается наивная реализация «RMS выше порога → пиши»:
 * тишина, короткая фраза, две реплики подряд, шум на границе, стык чанков, тихий голос.
 */
class VoiceSegmenterTest {

    private companion object {
        /** Обычная речь у микрофона телефона (порог 400). */
        const val SPEECH = 3_000

        /** Очень тихий голос: ниже базового порога 400, но выше адаптивного минимума 150. */
        const val QUIET_SPEECH = 250

        /** Бытовой шум комнаты: ниже адаптивного минимума 150. */
        const val SOFT_NOISE = 120

        /** Заметный гул (вентилятор/улица): выше минимума, но стабильный. */
        const val LOUD_NOISE = 200

        const val CHUNK_SAMPLES = 1_600
        const val CHUNK_MS = 100L
    }

    // --- синтетический PCM ---------------------------------------------------------------------

    /** Чанк из [samples] одинаковых сэмплов [amplitude] (little-endian int16). */
    private fun chunk(amplitude: Int, samples: Int = CHUNK_SAMPLES): ByteArray {
        val out = ByteArray(samples * 2)
        for (index in 0 until samples) {
            out[index * 2] = (amplitude and 0xFF).toByte()
            out[index * 2 + 1] = ((amplitude shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** [count] чанков речи/тишины/шума подряд. */
    private fun run(amplitude: Int, count: Int): List<ByteArray> = List(count) { chunk(amplitude) }

    /** Прогнать чанки с шагом 100 мс, вернуть события по порядку. */
    private fun feed(
        segmenter: VoiceSegmenter,
        chunks: List<ByteArray>,
        stepMs: Long = CHUNK_MS,
    ): List<VoiceSegmenter.Event> {
        var now = 0L
        return chunks.map { bytes ->
            val event = segmenter.onChunk(bytes, now)
            now += stepMs
            event
        }
    }

    private fun List<VoiceSegmenter.Event>.segments(): List<VoiceSegmenter.Event.Segment> =
        filterIsInstance<VoiceSegmenter.Event.Segment>()

    private fun List<VoiceSegmenter.Event>.speechStarts(): Int =
        count { it is VoiceSegmenter.Event.SpeechStarted }

    /** Все сэмплы сегмента — значения из ожидаемого набора (сдвиг байта сразу виден). */
    private fun assertSamplesFrom(pcm: ByteArray, allowed: Set<Int>) {
        assertEquals("длина PCM должна быть кратна сэмплу", 0, pcm.size % 2)
        var index = 0
        while (index < pcm.size) {
            val low = pcm[index].toInt() and 0xFF
            val high = pcm[index + 1].toInt()
            val sample = (high shl 8) or low
            assertTrue(
                "сэмпл #${index / 2} = $sample не из ожидаемых $allowed — поток сдвинут на границе",
                sample in allowed,
            )
            index += 2
        }
    }

    // --- 1. тишина ------------------------------------------------------------------------------

    @Test
    fun `тишина не даёт ни одного сегмента`() {
        val segmenter = VoiceSegmenter()
        val events = feed(segmenter, run(0, 60))

        assertEquals("в тишине не должно быть ни одного сегмента", 0, events.segments().size)
        assertEquals("начало речи не должно объявляться", 0, events.speechStarts())
        assertEquals("в тишине порог опускается до адаптивного минимума", 150, segmenter.thresholdRms)
    }

    // --- 2. короткая фраза ----------------------------------------------------------------------

    @Test
    fun `короткая фраза — ровно один сегмент с речью`() {
        val segmenter = VoiceSegmenter()
        // 8 чанков речи (0.8 с) + 12 чанков тишины (1.2 с) — пауза закрывает реплику.
        val events = feed(segmenter, run(SPEECH, 8) + run(0, 12))
        val segments = events.segments()

        assertEquals("фраза должна дать ровно один сегмент", 1, segments.size)
        assertEquals("начало речи объявляется один раз", 1, events.speechStarts())
        val duration = segments.single().durationMs
        assertTrue(
            "длительность ${duration}мс: речь 800 мс + hangover 800 мс, с допуском на pre-roll",
            duration in 1_200..2_000,
        )
        assertSamplesFrom(segments.single().pcm, setOf(SPEECH, 0))
    }

    // --- 3. две реплики подряд ------------------------------------------------------------------

    @Test
    fun `две реплики подряд — два сегмента, звук не дублируется`() {
        val segmenter = VoiceSegmenter()
        val events = feed(segmenter, run(SPEECH, 8) + run(0, 12) + run(SPEECH, 8) + run(0, 12))
        val segments = events.segments()

        assertEquals("две реплики — два сегмента", 2, segments.size)
        assertEquals("начало речи объявлено дважды", 2, events.speechStarts())
        val total = segments.sumOf { it.pcm.size }
        val input = (8 + 12 + 8 + 12) * CHUNK_SAMPLES * 2
        assertTrue(
            "сегменты ($total байт) не могут быть длиннее входа ($input байт) — это дубль",
            total <= input,
        )
        segments.forEach { assertSamplesFrom(it.pcm, setOf(SPEECH, 0)) }
        assertNotEquals(
            "сегменты не должны быть пустыми",
            0,
            segments.sumOf { it.pcm.size },
        )
    }

    // --- 4. шум на границе ----------------------------------------------------------------------

    @Test
    fun `тихий шум на границе не режет фразу на два сегмента`() {
        val segmenter = VoiceSegmenter()
        val events = feed(segmenter, run(SOFT_NOISE, 10) + run(SPEECH, 8) + run(SOFT_NOISE, 10))
        val segments = events.segments()

        assertEquals("шум ниже порога не должен создавать отдельный сегмент", 1, segments.size)
        assertSamplesFrom(segments.single().pcm, setOf(SPEECH, SOFT_NOISE, 0))
    }

    @Test
    fun `гул выше минимума не превращается в реплику`() {
        val segmenter = VoiceSegmenter()
        // Гул 200 RMS: стартовый порог 150 сначала принял бы его за речь, но за guard-окно
        // (300 мс) шумовой пол поднимается до 200, порог — до потолка 400, и гул замолкает.
        val hum = feed(segmenter, run(LOUD_NOISE, 20))
        assertEquals("гул без речи не должен уходить в STT", 0, hum.segments().size)
        assertEquals("гул не должен объявлять начало речи", 0, hum.speechStarts())
        assertTrue(
            "порог должен подняться над гулом: ${segmenter.thresholdRms}",
            segmenter.thresholdRms > LOUD_NOISE,
        )

        // Пауза после гула тоже не даёт сегмента (и не «прилипает» к следующей реплике).
        val quiet = feed(segmenter, run(0, 12))
        assertEquals("тишина после гула — ни одного сегмента", 0, quiet.segments().size)
    }

    // --- 5. стык чанков -------------------------------------------------------------------------

    @Test
    fun `непарная граница чанка не сдвигает поток и не теряет сэмплы`() {
        val segmenter = VoiceSegmenter()
        // Один непрерывный поток, нарезанный по 3201 байту (1600.5 сэмпла): на каждой границе
        // остаётся половина сэмпла. Режем НЕЗАВИСИМЫЕ чанки, а именно непрерывную ленту — иначе
        // тест проверял бы артефакт собственной синтетики, а не стык буферов AudioRecord.
        val totalBytes = 20 * CHUNK_SAMPLES * 2
        val stream = ByteArray(totalBytes)
        for (index in 0 until 10 * CHUNK_SAMPLES) {
            stream[index * 2] = (SPEECH and 0xFF).toByte()
            stream[index * 2 + 1] = ((SPEECH shr 8) and 0xFF).toByte()
        }
        val chunks = (0 until totalBytes step 3_201).map { from ->
            stream.copyOfRange(from, minOf(from + 3_201, totalBytes))
        }
        val events = feed(segmenter, chunks)
        val segments = events.segments()

        assertEquals("фраза на непарной границе — один сегмент", 1, segments.size)
        // Если бы байт-хвост терялся или сдвигался, сэмплы стали бы 0xB8B8/0x0B0B — тест упал бы.
        assertSamplesFrom(segments.single().pcm, setOf(SPEECH, 0))
        assertTrue(
            "сегмент (${segments.single().pcm.size} байт) не может быть длиннее входа ($totalBytes)",
            segments.single().pcm.size <= totalBytes,
        )
    }

    // --- 6. очень тихий голос -------------------------------------------------------------------

    @Test
    fun `очень тихий голос ниже базового порога всё равно распознаётся`() {
        val segmenter = VoiceSegmenter()
        // Фон 20 RMS, речь 250 RMS — тише базового порога 400, но выше адаптивного 150.
        val events = feed(segmenter, run(20, 10) + run(QUIET_SPEECH, 10) + run(20, 12))
        val segments = events.segments()

        assertEquals("тихая речь должна дать сегмент", 1, segments.size)
        assertEquals("порог должен опуститься ниже базового 400", true, segmenter.thresholdRms < 400)
        assertSamplesFrom(segments.single().pcm, setOf(QUIET_SPEECH, 20))
    }

    // --- 7. потеря реплики ----------------------------------------------------------------------

    @Test
    fun `flush отдаёт хвост реплики и не отдаёт его второй раз`() {
        val segmenter = VoiceSegmenter()
        feed(segmenter, run(SPEECH, 8))

        val tail = segmenter.flush()
        assertTrue("flush обязан отдать недоговорённую реплику", tail is VoiceSegmenter.Event.Segment)
        assertTrue("хвост не пустой", (tail as VoiceSegmenter.Event.Segment).pcm.isNotEmpty())
        assertEquals(
            "повторный flush не должен отдавать тот же звук второй раз",
            VoiceSegmenter.Event.None,
            segmenter.flush(),
        )
    }

    @Test
    fun `речь с первого чанка не съедается защитой старта`() {
        val segmenter = VoiceSegmenter()
        val events = feed(segmenter, run(SPEECH, 8) + run(0, 12))
        val segments = events.segments()

        assertEquals("речь с самого начала должна быть услышана", 1, segments.size)
        assertEquals("начало речи объявлено", 1, events.speechStarts())
        // Первый сэмпл сегмента — уже речь: pre-roll вернул звук, который пришёл до подтверждения.
        val pcm = segments.single().pcm
        val firstSample = ((pcm[1].toInt() shl 8) or (pcm[0].toInt() and 0xFF))
        assertEquals("сегмент должен начинаться с речи, а не с тишины", SPEECH, firstSample)
    }

    @Test
    fun `короткий щелчок не становится репликой`() {
        val segmenter = VoiceSegmenter()
        // 5 чанков (500 мс): хватает на подтверждение начала после guard, но меньше 600 мс речи.
        val events = feed(segmenter, run(SPEECH, 5) + run(0, 12))

        assertEquals("щелчок не должен уходить в STT", 0, events.segments().size)
        assertEquals("но начало речи честно объявлено", 1, events.speechStarts())
    }

    // --- 8. длинная речь ------------------------------------------------------------------------

    @Test
    fun `длинная речь режется на 20 с и продолжение без потери звука`() {
        val segmenter = VoiceSegmenter()
        // 25 с речи: 200 чанков = ровно лимит сегмента, дальше продолжение.
        val events = feed(segmenter, run(SPEECH, 250) + run(0, 12))
        val segments = events.segments()

        assertEquals("длинная речь — два сегмента", 2, segments.size)
        assertEquals("первый сегмент ровно на лимите", 20_000L, segments[0].durationMs)
        assertTrue(
            "продолжение ~5 с, получено ${segments[1].durationMs}",
            segments[1].durationMs in 4_000..6_500,
        )
        assertEquals(
            "начало речи объявляется один раз на реплику",
            1,
            events.speechStarts(),
        )
    }

    // --- 9. сброс -------------------------------------------------------------------------------

    @Test
    fun `reset не переносит состояние в новую сессию`() {
        val segmenter = VoiceSegmenter()
        feed(segmenter, run(SPEECH, 8))
        segmenter.reset()

        assertEquals("после reset активного сегмента нет", VoiceSegmenter.Event.None, segmenter.flush())
        val events = feed(segmenter, run(0, 30))
        assertEquals("в новой сессии тишина не даёт сегментов", 0, events.segments().size)
    }
}
