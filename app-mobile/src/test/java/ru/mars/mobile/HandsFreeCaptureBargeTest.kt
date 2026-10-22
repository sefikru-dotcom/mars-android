package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Duplex-часть barge-in: микрофон во время озвучки.
 *
 * Главная мысль, которую фиксируют эти тесты: [HandsFreeCapture] НЕ знает про состояния диалога и
 * не должен знать. Он открыт всё время, поэтому пока [VoiceDialogLoop] в `SPEAKING`, звук
 * продолжает идти и в сырой поток (потоковый ASR), и в сегментер — иначе перебить было бы нечем.
 * Всё, что capture обязан добавить к прежнему поведению, — это события `SpeechStarted` /
 * `SpeechProgress` (длительность речи для политики) и честный [HandsFreeCapture.echoCanceled].
 *
 * Проверяется без Android: [AudioSource] с очередью чанков и счётчик вместо часов.
 */
class HandsFreeCaptureBargeTest {

    private companion object {
        const val SPEECH = 3_000
        const val SILENCE = 0
        const val CHUNK_SAMPLES = 1_600
        const val CHUNK_MS = 100L
    }

    /** Источник с очередью чанков и управляемым ответом про AEC. */
    private class FakeSource(private val aec: Boolean = false) : AudioSource {
        private val monitor = Object()
        private val chunks = ArrayDeque<ByteArray>()
        private var closed = false

        @Volatile var released = false
            private set

        fun push(chunk: ByteArray) {
            synchronized(monitor) {
                chunks.addLast(chunk)
                monitor.notifyAll()
            }
        }

        fun close() {
            synchronized(monitor) {
                closed = true
                monitor.notifyAll()
            }
        }

        override fun read(buf: ByteArray): Int {
            val chunk: ByteArray
            synchronized(monitor) {
                while (chunks.isEmpty() && !closed) {
                    try {
                        monitor.wait(200)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return -1
                    }
                }
                if (chunks.isEmpty()) return -1
                chunk = chunks.removeFirst()
            }
            val copy = minOf(buf.size, chunk.size)
            System.arraycopy(chunk, 0, buf, 0, copy)
            return copy
        }

        override fun release() {
            released = true
            close()
        }

        override fun echoCanceled(): Boolean = aec
    }

    /** Слушатель, который пишет порядок событий: он и есть проверяемый контракт. */
    private class Recorder(private val failProgress: Boolean = false) : HandsFreeCapture.Listener {
        val events = CopyOnWriteArrayList<String>()
        val progress = CopyOnWriteArrayList<Long>()
        val starts = CountDownLatch(1)
        val progressLatch = CountDownLatch(1)
        val segments = CopyOnWriteArrayList<Recording>()
        val segmentLatch = CountDownLatch(1)

        override fun onSpeechStarted() {
            events.add("start")
            starts.countDown()
        }

        override fun onSpeechProgress(voicedMs: Long) {
            events.add("progress:$voicedMs")
            progress.add(voicedMs)
            progressLatch.countDown()
            if (failProgress) throw IllegalStateException("хост упал на тике длительности")
        }

        override fun onSegment(recording: Recording) {
            events.add("segment")
            segments.add(recording)
            segmentLatch.countDown()
        }

        override fun onCaptureFailed() {
            events.add("failed")
        }
    }

    private fun chunk(amplitude: Int, samples: Int = CHUNK_SAMPLES): ByteArray {
        val out = ByteArray(samples * 2)
        for (index in 0 until samples) {
            out[index * 2] = (amplitude and 0xFF).toByte()
            out[index * 2 + 1] = ((amplitude shr 8) and 0xFF).toByte()
        }
        return out
    }

    private class FakeClock {
        private var now = 0L

        fun next(): Long {
            val value = now
            now += CHUNK_MS
            return value
        }
    }

    private fun capture(source: FakeSource, clock: FakeClock = FakeClock()) = HandsFreeCapture(
        sourceFactory = object : AudioSourceFactory {
            override fun create(): AudioSource = source
        },
        nowMs = clock::next,
    )

    @Test
    fun `во время озвучки микрофон продолжает отдавать звук и длительность речи`() {
        val source = FakeSource()
        val capture = capture(source)
        val recorder = Recorder()
        val rawChunks = CopyOnWriteArrayList<Int>()

        assertTrue(capture.start(recorder) { _, length -> rawChunks.add(length) })

        // Цикл в этот момент в SPEAKING: capture об этом не знает и знать не должен.
        // 8 чанков = 800 мс: 300 мс start-guard + 200 мс подтверждения + запас.
        repeat(8) { source.push(chunk(SPEECH)) }

        assertTrue("начало речи должно дойти", recorder.starts.await(3, TimeUnit.SECONDS))
        assertTrue("тик длительности должен дойти", recorder.progressLatch.await(3, TimeUnit.SECONDS))
        assertTrue("сырой поток идёт параллельно озвучке", rawChunks.isNotEmpty())
        assertTrue(
            "каждый буфер уходит в ASR: ${rawChunks.size} буферов на ${recorder.progress.size} тиков",
            rawChunks.size >= recorder.progress.size,
        )
        assertTrue("длительность речи растёт", recorder.progress.last() >= 200L)
        assertEquals("длительность только растёт", recorder.progress.sorted(), recorder.progress.toList())

        capture.cancel()
    }

    @Test
    fun `SpeechStarted приходит раньше первого тика длительности`() {
        val source = FakeSource()
        val capture = capture(source)
        val recorder = Recorder()
        assertTrue(capture.start(recorder))

        repeat(8) { source.push(chunk(SPEECH)) }
        assertTrue(recorder.progressLatch.await(3, TimeUnit.SECONDS))

        val firstStart = recorder.events.indexOfFirst { it == "start" }
        val firstProgress = recorder.events.indexOfFirst { it.startsWith("progress:") }
        assertTrue("старт речи должен быть объявлен", firstStart >= 0)
        assertTrue("тик длительности должен быть после старта", firstProgress > firstStart)

        capture.cancel()
    }

    @Test
    fun `спад речи даёт ровно один нулевой тик`() {
        val source = FakeSource()
        val capture = capture(source)
        val recorder = Recorder()
        assertTrue(capture.start(recorder))

        repeat(8) { source.push(chunk(SPEECH)) }
        assertTrue(recorder.progressLatch.await(3, TimeUnit.SECONDS))

        // Пауза закрывает сегмент: хост должен узнать, что речь кончилась, и сбросить попытку.
        repeat(20) { source.push(chunk(SILENCE)) }
        capture.stop()

        val zeroTicks = recorder.progress.count { it == 0L }
        assertEquals("ровно один спад на фразу", 1, zeroTicks)
        assertEquals("спад — последний тик", 0L, recorder.progress.last())
    }

    @Test
    fun `тишина не даёт ни старта речи, ни длительности`() {
        val source = FakeSource()
        val capture = capture(source)
        val recorder = Recorder()
        assertTrue(capture.start(recorder))

        repeat(20) { source.push(chunk(SILENCE)) }
        // Дать потоку чтения обработать всю очередь: события приходят асинхронно.
        assertFalse("тишина — не речь", recorder.starts.await(500, TimeUnit.MILLISECONDS))
        assertTrue("тиков длительности на тишине быть не должно", recorder.progress.isEmpty())

        capture.cancel()
    }

    @Test
    fun `вторая фраза снова объявляет начало речи и считает длительность заново`() {
        val source = FakeSource()
        val capture = capture(source)
        val starts = CopyOnWriteArrayList<Long>()
        val segments = CopyOnWriteArrayList<Recording>()
        val secondSegment = CountDownLatch(2)

        assertTrue(capture.start(object : HandsFreeCapture.Listener {
            override fun onSpeechStarted() {
                starts.add(System.nanoTime())
            }

            override fun onSegment(recording: Recording) {
                segments.add(recording)
                secondSegment.countDown()
            }
        }))

        repeat(8) { source.push(chunk(SPEECH)) }
        repeat(14) { source.push(chunk(SILENCE)) }
        repeat(8) { source.push(chunk(SPEECH)) }
        repeat(14) { source.push(chunk(SILENCE)) }

        assertTrue("обе фразы должны дойти", secondSegment.await(6, TimeUnit.SECONDS))
        assertEquals("начало речи объявляется на каждую фразу, а не один раз", 2, starts.size)
        assertEquals(2, segments.size)

        capture.cancel()
    }

    @Test
    fun `исключение на тике длительности не рвёт захват`() {
        val source = FakeSource()
        val capture = capture(source)
        val recorder = Recorder(failProgress = true)
        assertTrue(capture.start(recorder))

        repeat(8) { source.push(chunk(SPEECH)) }
        repeat(14) { source.push(chunk(SILENCE)) }

        assertTrue(
            "микрофон важнее хоста: сегмент всё равно должен дойти",
            recorder.segmentLatch.await(5, TimeUnit.SECONDS),
        )
        assertTrue("тики продолжали идти после исключения", recorder.progress.size > 1)

        capture.cancel()
    }

    @Test
    fun `echoCanceled говорит правду об источнике, а не о запросе`() {
        val withAec = FakeSource(aec = true)
        val captureWithAec = capture(withAec)
        assertTrue(captureWithAec.start(Recorder()))
        assertTrue("источник подтвердил AEC", captureWithAec.echoCanceled)
        captureWithAec.cancel()

        val withoutAec = FakeSource(aec = false)
        val captureWithoutAec = capture(withoutAec)
        assertTrue(captureWithoutAec.start(Recorder()))
        assertFalse("источник не подтвердил AEC — политика не имеет права на это рассчитывать", captureWithoutAec.echoCanceled)
        captureWithoutAec.cancel()

        // После закрытия сессии флаг не должен «врать» в сторону наличия AEC.
        assertFalse(captureWithoutAec.echoCanceled)
    }

    @Test
    fun `stop во время речи закрывает микрофон и не даёт поздних тиков`() {
        val source = FakeSource()
        val capture = capture(source)
        val recorder = Recorder()
        assertTrue(capture.start(recorder))

        repeat(8) { source.push(chunk(SPEECH)) }
        assertTrue(recorder.progressLatch.await(3, TimeUnit.SECONDS))

        capture.stop()
        val afterStop = recorder.progress.size
        val eventsAfterStop = recorder.events.size

        // Поток чтения уже завершён: новых тиков и сегментов быть не может.
        assertFalse(capture.isCapturing)
        assertTrue("источник освобождён", source.released)
        Thread.sleep(150)
        assertEquals("после stop тиков больше нет", afterStop, recorder.progress.size)
        assertEquals("после stop событий больше нет", eventsAfterStop, recorder.events.size)
    }
}
