package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Тесты [HandsFreeCapture.RawPcmListener] — сырой поток в потоковый ASR.
 *
 * Почему отдельно от [HandsFreeCaptureTest]: там проверяется нарезка на реплики (whisper-путь), а
 * здесь — второй потребитель ТОГО ЖЕ потока. Ошибка в нём ломает либо движок (звук приходит после
 * нарезки, то есть с задержкой hangover), либо whisper-fallback (сегменты перестают доезжать),
 * либо сам захват (исключение движка убивает поток чтения микрофона).
 */
class HandsFreeCaptureRawPcmTest {

    private companion object {
        const val SPEECH = 3_000
        const val SILENCE = 0
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

    /** Слушатель сегментов: нужен, чтобы убедиться, что нарезка не сломана. */
    private class Segments : HandsFreeCapture.Listener {
        val segments = CopyOnWriteArrayList<Recording>()
        val latch = CountDownLatch(1)

        override fun onSegment(recording: Recording) {
            segments.add(recording)
            latch.countDown()
        }
    }

    /** Потребитель сырого потока: пишет длины буферов и имя потока. */
    private class RawSink(
        private val failOnFirst: Boolean = false,
    ) : HandsFreeCapture.RawPcmListener {
        val lengths = CopyOnWriteArrayList<Int>()
        val threads = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(1)

        override fun onRawPcm(pcm: ByteArray, length: Int) {
            threads.add(Thread.currentThread().name)
            lengths.add(length)
            latch.countDown()
            if (failOnFirst && lengths.size == 1) throw IllegalStateException("движок упал")
        }
    }

    private class FakeClock {
        private var now = 0L

        fun next(): Long {
            val value = now
            now += CHUNK_MS
            return value
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

    private fun capture(source: FakeSource, clock: FakeClock) = HandsFreeCapture(
        sourceFactory = object : AudioSourceFactory {
            override fun create(): AudioSource = source
        },
        nowMs = clock::next,
    )

    /** Дождаться, пока поток чтения отдаст [expected] буферов (чтение идёт асинхронно). */
    private fun awaitCount(sink: RawSink, expected: Int, timeoutMs: Long = 5_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (sink.lengths.size >= expected) return true
            Thread.sleep(10)
        }
        return sink.lengths.size >= expected
    }

    @Test
    fun `сырой поток доезжает параллельно нарезке`() {
        val source = FakeSource()
        val clock = FakeClock()
        val capture = capture(source, clock)
        val segments = Segments()
        val raw = RawSink()

        assertTrue(capture.start(segments, raw))
        repeat(10) { source.push(chunk(SPEECH)) }
        repeat(12) { source.push(chunk(SILENCE)) }

        assertTrue("нарезка работает", segments.latch.await(5, TimeUnit.SECONDS))
        assertTrue("сырой поток идёт", raw.latch.await(5, TimeUnit.SECONDS))
        assertTrue("поток чтения дочитал всё", awaitCount(raw, 22))
        assertEquals(
            "буферы отдаются целиком, по 100 мс",
            List(raw.lengths.size) { CHUNK_SAMPLES * 2 },
            raw.lengths.toList(),
        )
        assertNotEquals(
            "колбэк не на главном потоке",
            Thread.currentThread().name,
            raw.threads.first(),
        )
        capture.cancel()
    }

    @Test
    fun `исключение потребителя не рвёт захват`() {
        val source = FakeSource()
        val clock = FakeClock()
        val capture = capture(source, clock)
        val segments = Segments()
        val raw = RawSink(failOnFirst = true)

        assertTrue(capture.start(segments, raw))
        repeat(10) { source.push(chunk(SPEECH)) }
        repeat(12) { source.push(chunk(SILENCE)) }

        assertTrue(
            "whisper-путь пережил падение движка",
            segments.latch.await(5, TimeUnit.SECONDS),
        )
        assertTrue("поток чтения продолжил работу", raw.lengths.size > 1)
        capture.cancel()
    }

    @Test
    fun `без сырого потребителя поведение прежнее`() {
        val source = FakeSource()
        val clock = FakeClock()
        val capture = capture(source, clock)
        val segments = Segments()

        assertTrue(capture.start(segments))
        repeat(10) { source.push(chunk(SPEECH)) }
        repeat(12) { source.push(chunk(SILENCE)) }

        assertTrue(segments.latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, segments.segments.size)
        capture.cancel()
    }

    @Test
    fun `после выключения режима сырой поток больше не идёт`() {
        val source = FakeSource()
        val clock = FakeClock()
        val capture = capture(source, clock)
        val segments = Segments()
        val raw = RawSink()

        assertTrue(capture.start(segments, raw))
        repeat(10) { source.push(chunk(SPEECH)) }
        assertTrue(raw.latch.await(5, TimeUnit.SECONDS))
        capture.cancel()
        val seen = raw.lengths.size

        source.push(chunk(SPEECH))
        Thread.sleep(150)

        assertEquals("после cancel новых буферов нет", seen, raw.lengths.size)
    }
}
