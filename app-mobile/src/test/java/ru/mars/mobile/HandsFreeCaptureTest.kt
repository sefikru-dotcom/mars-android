package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Тесты [HandsFreeCapture] — тоже чистый JVM: вместо микрофона [AudioSource] с очередью чанков,
 * вместо системных часов — счётчик.
 *
 * Проверяется то, что нельзя увидеть в юнит-тестах сегментера: сегмент действительно доезжает до
 * хоста в формате серверного контракта (WAV 16 кГц моно), последняя фраза не теряется при выходе
 * из режима, отмена её не отправляет, а хост, бросивший исключение в колбэке, не убивает чтение.
 */
class HandsFreeCaptureTest {

    private companion object {
        const val SPEECH = 3_000
        const val CHUNK_SAMPLES = 1_600
        const val CHUNK_MS = 100L
    }

    /** Источник с очередью: тест кладёт чанки, поток чтения их забирает. */
    private class FakeSource : AudioSource {
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
    }

    private class Recorder : HandsFreeCapture.Listener {
        val segments = CopyOnWriteArrayList<Recording>()
        val starts = CopyOnWriteArrayList<Long>()
        val failed = CopyOnWriteArrayList<Boolean>()
        val latch = CountDownLatch(1)

        override fun onSpeechStarted() {
            starts.add(System.nanoTime())
        }

        override fun onSegment(recording: Recording) {
            segments.add(recording)
            latch.countDown()
        }

        override fun onCaptureFailed() {
            failed.add(true)
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

    /** Часы, которые двигаются ровно на 100 мс за прочитанный чанк. */
    private class FakeClock {
        private var now = 0L

        fun next(): Long {
            val value = now
            now += CHUNK_MS
            return value
        }
    }

    @Test
    fun `реплика доезжает до хоста как WAV 16 кГц моно`() {
        val source = FakeSource()
        val clock = FakeClock()
        val capture = HandsFreeCapture(
            sourceFactory = object : AudioSourceFactory {
                override fun create(): AudioSource = source
            },
            nowMs = clock::next,
        )
        val recorder = Recorder()

        assertTrue(capture.start(recorder))
        repeat(8) { source.push(chunk(SPEECH)) }
        repeat(12) { source.push(chunk(0)) }

        assertTrue("сегмент должен прийти за 3 с", recorder.latch.await(3, TimeUnit.SECONDS))
        val segment = recorder.segments.first()
        assertEquals(VoiceRecorder.SAMPLE_RATE, segment.sampleRate)
        assertTrue("WAV начинается с RIFF", segment.wav[0] == 'R'.code.toByte())
        assertTrue("длительность реплики правдоподобна: ${segment.durationMs}", segment.durationMs in 800..2_500)
        assertTrue("начало речи объявлено", recorder.starts.isNotEmpty())

        capture.cancel()
        assertTrue("источник освобождён", source.released)
        assertFalse(capture.isCapturing)
    }

    @Test
    fun `stop отдаёт недоговорённую реплику, а cancel — нет`() {
        // stop: человек выключил режим на середине фразы — фраза не должна пропасть.
        val source = FakeSource()
        val clock = FakeClock()
        val capture = HandsFreeCapture(
            sourceFactory = object : AudioSourceFactory {
                override fun create(): AudioSource = source
            },
            nowMs = clock::next,
        )
        val recorder = Recorder()
        assertTrue(capture.start(recorder))
        repeat(8) { source.push(chunk(SPEECH)) }
        Thread.sleep(300)

        capture.stop()

        assertEquals("недоговорённая фраза должна уйти хосту", 1, recorder.segments.size)

        // cancel: сегмент уже начат, но режим выключили — в STT ничего не уходит.
        val source2 = FakeSource()
        val capture2 = HandsFreeCapture(
            sourceFactory = object : AudioSourceFactory {
                override fun create(): AudioSource = source2
            },
            nowMs = clock::next,
        )
        val recorder2 = Recorder()
        assertTrue(capture2.start(recorder2))
        repeat(8) { source2.push(chunk(SPEECH)) }
        Thread.sleep(300)

        capture2.cancel()

        assertEquals("отменённая реплика не должна уходить в STT", 0, recorder2.segments.size)
    }

    @Test
    fun `повторный start не открывает второй микрофон`() {
        val source = FakeSource()
        val capture = HandsFreeCapture(
            sourceFactory = object : AudioSourceFactory {
                override fun create(): AudioSource = source
            },
        )
        assertTrue(capture.start(Recorder()))
        assertFalse("второй захват поверх первого запрещён", capture.start(Recorder()))
        capture.cancel()
    }

    @Test
    fun `исключение в колбэке хоста не рвёт поток чтения`() {
        val source = FakeSource()
        val clock = FakeClock()
        val capture = HandsFreeCapture(
            sourceFactory = object : AudioSourceFactory {
                override fun create(): AudioSource = source
            },
            nowMs = clock::next,
        )
        val segments = CopyOnWriteArrayList<Recording>()
        val second = CountDownLatch(1)

        assertTrue(capture.start(object : HandsFreeCapture.Listener {
            override fun onSegment(recording: Recording) {
                segments.add(recording)
                if (segments.size == 1) throw IllegalStateException("UI упал")
                second.countDown()
            }
        }))

        repeat(8) { source.push(chunk(SPEECH)) }
        repeat(12) { source.push(chunk(0)) }
        repeat(8) { source.push(chunk(SPEECH)) }
        repeat(12) { source.push(chunk(0)) }

        assertTrue(
            "после исключения хоста цикл должен продолжить слушать",
            second.await(4, TimeUnit.SECONDS),
        )
        capture.cancel()
    }

    @Test
    fun `микрофон не открылся — start возвращает false без потока`() {
        val capture = HandsFreeCapture(
            sourceFactory = object : AudioSourceFactory {
                override fun create(): AudioSource = throw SecurityException("denied")
            },
        )
        assertFalse(capture.start(Recorder()))
        assertFalse(capture.isCapturing)
    }
}
