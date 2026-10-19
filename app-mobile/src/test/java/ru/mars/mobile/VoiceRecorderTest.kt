package ru.mars.mobile

import java.util.Arrays
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [VoiceRecorder] — pure JVM, no Android and no Robolectric.
 *
 * The recorder is driven through its injected [AudioSourceFactory] and clock: a fake source hands
 * out predetermined PCM chunks (optionally blocking or failing) and a fake clock advances with
 * reads, so duration, the auto-stop ceiling and resource release are asserted without any sleep
 * and without wall-clock timing. The real AudioRecord path lives only in AndroidAudioSourceFactory
 * and is never instantiated here.
 */
class VoiceRecorderTest {

    private val chunk = VoiceRecorder.READ_CHUNK_BYTES

    private companion object {
        /** Заметно выше порога SilenceDetector (400) — обычная речь у микрофона телефона. */
        const val SPEECH_AMPLITUDE = 8_000
    }

    // --- fakes ---------------------------------------------------------------------------------

    private class FakeClock {
        @Volatile private var current = 0L

        fun now(): Long = current

        fun advanceBy(ms: Long) {
            current += ms
        }
    }

    private class FakeFactory(private val factory: () -> AudioSource) : AudioSourceFactory {
        var created = 0
            private set

        override fun create(): AudioSource {
            created += 1
            return factory()
        }
    }

    /**
     * Synthetic [AudioSource]. Returns [bytesPerRead] non-zero bytes per read until
     * [readsBeforeEnd], can block a read on [gate] (released by [release]), can pause on a chosen
     * read until [pauseGate] opens (then keeps returning DATA, unlike [blockFromRead] which ends
     * the stream), can throw on a chosen read, and reports its read count with an [onRead] hook
     * (used to advance the fake clock).
     */
    private class FakeAudioSource(
        private val bytesPerRead: Int = VoiceRecorder.READ_CHUNK_BYTES,
        private val readsBeforeEnd: Int = Int.MAX_VALUE,
        private val blockFromRead: Int? = null,
        private val gate: CountDownLatch? = null,
        private val pauseAtRead: Int? = null,
        private val pauseGate: CountDownLatch? = null,
        private val failOnRead: Int? = null,
        private val onRead: ((Int) -> Unit)? = null,
    ) : AudioSource {

        private val counter = AtomicInteger(0)

        @Volatile var releases = 0
            private set

        override fun read(buf: ByteArray): Int {
            val n = counter.incrementAndGet()
            if (failOnRead != null && n == failOnRead) throw RuntimeException("simulated read failure")
            // Pause: the test needs the loop alive at a known point; after the gate opens the read
            // still carries audio, so the captured size is unaffected.
            if (pauseAtRead != null && n == pauseAtRead) {
                pauseGate?.await(5, TimeUnit.SECONDS)
            }
            if (blockFromRead != null && n >= blockFromRead) {
                val g = gate ?: return -1
                try {
                    // Released by stop()/cancel(); on timeout the read reports the stream as ended
                    // so the reader can never hang a test forever.
                    if (!g.await(5, TimeUnit.SECONDS)) return -1
                } catch (ignored: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return -1
                }
                return -1
            }
            if (n > readsBeforeEnd) return -1
            val count = minOf(bytesPerRead, buf.size)
            Arrays.fill(buf, 0, count, DATA_BYTE)
            onRead?.invoke(n)
            return count
        }

        override fun release() {
            releases += 1
            gate?.countDown()
        }

        private companion object {
            const val DATA_BYTE: Byte = 7
        }
    }

    /**
     * Synthetic source with a scripted amplitude per read: lets a test play "speech, then a pause"
     * without any real audio. Every read is a full buffer of the same constant sample, so the RMS
     * the detector sees is exactly that amplitude. After the script is exhausted the stream ends,
     * so a test that forgot to assert the stop still terminates instead of spinning.
     */
    private class ScriptedAudioSource(
        private val amplitudes: List<Int>,
        private val onRead: ((Int) -> Unit)? = null,
    ) : AudioSource {

        private val counter = AtomicInteger(0)

        @Volatile var releases = 0
            private set

        override fun read(buf: ByteArray): Int {
            val n = counter.incrementAndGet()
            if (n > amplitudes.size) return -1
            val amplitude = amplitudes[n - 1]
            var index = 0
            while (index + 1 < buf.size) {
                buf[index] = (amplitude and 0xFF).toByte()
                buf[index + 1] = ((amplitude shr 8) and 0xFF).toByte()
                index += 2
            }
            onRead?.invoke(n)
            return buf.size
        }

        override fun release() {
            releases += 1
        }
    }

    /** Watchdog only — never used for duration assertions, which go through the fake clock. */
    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for: $message")
            Thread.onSpinWait()
        }
    }

    // --- start / stop --------------------------------------------------------------------------

    @Test
    fun `start begins recording and stop returns a wav with plausible duration`() {
        val clock = FakeClock()
        val firstRead = CountDownLatch(1)
        val pauseGate = CountDownLatch(1)
        val source = FakeAudioSource(
            readsBeforeEnd = 3,
            pauseAtRead = 2,
            pauseGate = pauseGate,
            onRead = { n ->
                if (n == 1) firstRead.countDown()
                clock.advanceBy(100)
            },
        )
        val recorder = VoiceRecorder(FakeFactory { source }) { clock.now() }

        assertFalse(recorder.isRecording)
        assertTrue(recorder.start())
        assertTrue(recorder.isRecording)
        assertTrue(firstRead.await(5, TimeUnit.SECONDS))
        // The loop is parked inside read(2), so `isRecording` is observed while capturing for
        // certain — asserting it after the drain would be a race the source can win.
        assertTrue("still capturing while parked in read", recorder.isRecording)

        // Release the pause: the source drains after the third chunk and the pull loop ends.
        pauseGate.countDown()
        awaitCondition("source drained") { !recorder.isRecording }

        val recording = recorder.stop()
        assertNotNull(recording)
        recording!!
        assertEquals(44 + 3 * chunk, recording.wav.size)
        assertEquals(VoiceRecorder.SAMPLE_RATE, recording.sampleRate)
        assertEquals(300L, recording.durationMs)
        assertEquals("RIFF", String(recording.wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(recording.wav, 8, 4, Charsets.US_ASCII))
        assertTrue("source must be released", source.releases >= 1)
        assertFalse(recorder.isRecording)
    }

    @Test
    fun `stop mid-recording returns the audio captured so far`() {
        val clock = FakeClock()
        val gate = CountDownLatch(1)
        val firstRead = CountDownLatch(1)
        val source = FakeAudioSource(
            blockFromRead = 2,
            gate = gate,
            onRead = { n ->
                if (n == 1) {
                    clock.advanceBy(100)
                    firstRead.countDown()
                }
            },
        )
        val recorder = VoiceRecorder(FakeFactory { source }) { clock.now() }

        assertTrue(recorder.start())
        assertTrue(firstRead.await(5, TimeUnit.SECONDS))
        assertTrue("still capturing while blocked in read", recorder.isRecording)

        val recording = recorder.stop()
        assertNotNull(recording)
        recording!!
        assertEquals(44 + chunk, recording.wav.size)
        assertEquals(100L, recording.durationMs)
        assertTrue(source.releases >= 1)
        assertFalse(recorder.isRecording)
    }

    @Test
    fun `second start while recording is rejected and the session survives`() {
        val gate = CountDownLatch(1)
        val firstRead = CountDownLatch(1)
        val source = FakeAudioSource(blockFromRead = 2, gate = gate, onRead = { if (it == 1) firstRead.countDown() })
        val recorder = VoiceRecorder(FakeFactory { source }) { 0L }

        assertTrue(recorder.start())
        assertTrue(firstRead.await(5, TimeUnit.SECONDS))
        assertFalse(recorder.start())
        assertTrue(recorder.isRecording)

        recorder.cancel()
        assertFalse(recorder.isRecording)
    }

    @Test
    fun `stop without start returns null and never creates a source`() {
        var created = 0
        val recorder = VoiceRecorder(FakeFactory { created += 1; FakeAudioSource(readsBeforeEnd = 0) }) { 0L }

        assertNull(recorder.stop())
        assertEquals("no session, so no source is opened", 0, created)
        assertFalse(recorder.isRecording)
    }

    // --- cancel --------------------------------------------------------------------------------

    @Test
    fun `cancel discards the audio and releases the source`() {
        val gate = CountDownLatch(1)
        val firstRead = CountDownLatch(1)
        val source = FakeAudioSource(blockFromRead = 2, gate = gate, onRead = { if (it == 1) firstRead.countDown() })
        val recorder = VoiceRecorder(FakeFactory { source }) { 0L }

        assertTrue(recorder.start())
        assertTrue(firstRead.await(5, TimeUnit.SECONDS))
        recorder.cancel()

        assertFalse(recorder.isRecording)
        assertNull(recorder.stop())
        assertTrue(source.releases >= 1)

        // cancel with no live session is a harmless no-op
        recorder.cancel()
        assertFalse(recorder.isRecording)
    }

    @Test
    fun `start is possible again after cancel`() {
        val recorder = VoiceRecorder(FakeFactory { FakeAudioSource(readsBeforeEnd = 0) }) { 0L }

        assertTrue(recorder.start())
        recorder.cancel()
        assertTrue(recorder.start())
        recorder.cancel()
        assertFalse(recorder.isRecording)
    }

    // --- duration ceiling ----------------------------------------------------------------------

    @Test
    fun `recording auto-stops at the duration ceiling`() {
        val clock = FakeClock()
        val source = FakeAudioSource(onRead = { clock.advanceBy(100) })
        val recorder = VoiceRecorder(FakeFactory { source }) { clock.now() }

        assertTrue(recorder.start())
        awaitCondition("auto-stop at the ceiling") { !recorder.isRecording }

        assertTrue(clock.now() >= VoiceRecorder.MAX_DURATION_MS)
        val recording = recorder.stop()
        assertNotNull(recording)
        recording!!
        assertEquals(VoiceRecorder.MAX_DURATION_MS, recording.durationMs)
        assertEquals(44 + (VoiceRecorder.MAX_DURATION_MS / 100).toInt() * chunk, recording.wav.size)
        assertTrue(source.releases >= 1)
    }

    // --- auto-stop by silence ------------------------------------------------------------------

    @Test
    fun `silence auto-stops the recording and hands it out exactly once`() {
        val clock = FakeClock()
        val delivered = CopyOnWriteArrayList<Recording>()
        // 5 чанков речи (амплитуда 8000 выше порога детектора), дальше — тишина.
        val source = ScriptedAudioSource(
            amplitudes = List(5) { SPEECH_AMPLITUDE } + List(60) { 0 },
            onRead = { clock.advanceBy(100) },
        )
        val recorder = VoiceRecorder(
            sourceFactory = FakeFactory { source },
            onAutoStop = { recording -> delivered += recording },
            nowMs = { clock.now() },
        )

        assertTrue(recorder.start())
        awaitCondition("auto-stop on silence") { delivered.size == 1 && !recorder.isRecording }

        assertEquals("запись отдаётся ровно один раз", 1, delivered.size)
        val recording = delivered.single()
        // 5 чанков речи + 13 чанков тишины, пока не накопятся 1.2 с hangover: 18 чанков = 1.8 с
        // (длительность считается от start(), а первый чанк приходит на метке 100 мс).
        assertEquals(1_800L, recording.durationMs)
        assertEquals(44 + 18 * chunk, recording.wav.size)
        assertEquals("RIFF", String(recording.wav, 0, 4, Charsets.US_ASCII))
        assertTrue("микрофон освобождён к моменту колбэка", source.releases >= 1)
        assertTrue("хост видит, что запись забрал авто-стоп", recorder.autoStopTaken)
        assertNull("повторный stop после авто-стопа не отдаёт второй WAV", recorder.stop())
        assertNull("и следующий тоже", recorder.stop())
    }

    @Test
    fun `auto stop is off when no callback is passed`() {
        val clock = FakeClock()
        // 200 чанков тишины = 20 с: намного больше minNoSpeechMs детектора (3 с).
        val source = ScriptedAudioSource(List(200) { 0 }, onRead = { clock.advanceBy(100) })
        val recorder = VoiceRecorder(sourceFactory = FakeFactory { source }, nowMs = { clock.now() })

        assertTrue(recorder.start())
        awaitCondition("source drained") { !recorder.isRecording }

        val recording = recorder.stop()
        assertNotNull("без колбэка запись идёт до конца источника", recording)
        recording!!
        assertEquals(20_000L, recording.durationMs)
        assertEquals(44 + 200 * chunk, recording.wav.size)
        assertFalse("детектор не включался", recorder.autoStopTaken)
    }

    @Test
    fun `recorder is reusable after an auto stop`() {
        val clock = FakeClock()
        val delivered = CopyOnWriteArrayList<Recording>()
        val autoStopped = ScriptedAudioSource(List(5) { SPEECH_AMPLITUDE } + List(60) { 0 }, onRead = { clock.advanceBy(100) })
        val manual = ScriptedAudioSource(List(2) { SPEECH_AMPLITUDE }, onRead = { clock.advanceBy(100) })
        val sources = ArrayDeque(listOf(autoStopped, manual))
        val recorder = VoiceRecorder(
            sourceFactory = FakeFactory { sources.removeFirst() },
            onAutoStop = { recording -> delivered += recording },
            nowMs = { clock.now() },
        )

        assertTrue(recorder.start())
        awaitCondition("first session auto-stopped") { delivered.size == 1 && !recorder.isRecording }

        // Новая сессия: детектор и флаг авто-стопа сброшены, обычный stop снова отдаёт запись.
        assertTrue(recorder.start())
        assertFalse("новая сессия не считается авто-стопнутой", recorder.autoStopTaken)
        awaitCondition("second session drained") { !recorder.isRecording }

        val recording = recorder.stop()
        assertNotNull("ручной stop после авто-стопа работает", recording)
        recording!!
        assertEquals(200L, recording.durationMs)
        assertEquals(44 + 2 * chunk, recording.wav.size)
        assertEquals("второго авто-стопа не было", 1, delivered.size)
    }

    // --- failure paths -------------------------------------------------------------------------

    @Test
    fun `factory failure leaves the recorder idle`() {
        val recorder = VoiceRecorder(FakeFactory { throw IllegalStateException("microphone denied") }) { 0L }

        assertFalse(recorder.start())
        assertFalse(recorder.isRecording)
        assertNull(recorder.stop())
    }

    // --- permission denial ---------------------------------------------------------------------

    @Test
    fun `microphone denial is a security exception so callers keep one failure type`() {
        val denial = MicrophonePermissionDeniedException("RECORD_AUDIO is not granted")

        assertTrue(
            "start() relies on the denial being a SecurityException",
            SecurityException::class.java.isAssignableFrom(denial.javaClass),
        )
    }

    @Test
    fun `permission denial leaves the recorder idle and reports failure`() {
        var created = 0
        val recorder = VoiceRecorder(
            FakeFactory {
                created += 1
                throw MicrophonePermissionDeniedException("RECORD_AUDIO is not granted")
            },
        ) { 0L }

        assertFalse(recorder.start())
        assertEquals("the factory is asked exactly once per start", 1, created)
        assertFalse(recorder.isRecording)
        assertNull("no session survives a denial", recorder.stop())
        // Harmless no-ops: they would blow up or hand back a session if the denial had leaked state.
        recorder.cancel()
        assertFalse(recorder.isRecording)
        assertNull(recorder.stop())
    }

    @Test
    fun `raw security exception from the factory is treated as a denial`() {
        val recorder = VoiceRecorder(FakeFactory { throw SecurityException("RECORD_AUDIO revoked") }) { 0L }

        assertFalse(recorder.start())
        assertFalse(recorder.isRecording)
        assertNull(recorder.stop())
    }

    @Test
    fun `start succeeds after a denial once the permission is granted`() {
        val clock = FakeClock()
        var attempts = 0
        val source = FakeAudioSource(readsBeforeEnd = 2, onRead = { clock.advanceBy(100) })
        val recorder = VoiceRecorder(
            FakeFactory {
                attempts += 1
                if (attempts == 1) throw MicrophonePermissionDeniedException("RECORD_AUDIO is not granted")
                source
            },
        ) { clock.now() }

        assertFalse(recorder.start())
        assertFalse("a denial must not leave the recorder half-started", recorder.isRecording)

        assertTrue("the retry after the grant must be accepted", recorder.start())
        assertEquals(2, attempts)
        awaitCondition("source drained") { !recorder.isRecording }

        val recording = recorder.stop()
        assertNotNull("the granted session captures audio normally", recording)
        recording!!
        assertEquals(44 + 2 * chunk, recording.wav.size)
        assertEquals(200L, recording.durationMs)
        assertTrue("the granted session releases its source", source.releases >= 1)
        assertFalse(recorder.isRecording)
    }

    @Test
    fun `denial after a finished session leaves the recorder reusable`() {
        var attempts = 0
        val source = FakeAudioSource(readsBeforeEnd = 0)
        val recorder = VoiceRecorder(
            FakeFactory {
                attempts += 1
                if (attempts == 2) throw MicrophonePermissionDeniedException("RECORD_AUDIO revoked")
                source
            },
        ) { 0L }

        assertTrue(recorder.start())
        recorder.cancel()

        assertFalse("a revoked permission is reported, not a stale session", recorder.start())
        assertFalse(recorder.isRecording)
        assertNull(recorder.stop())

        assertTrue("a later grant still works", recorder.start())
        recorder.cancel()
        assertFalse(recorder.isRecording)
    }

    @Test
    fun `read failure ends the session and still releases the source`() {
        val clock = FakeClock()
        val source = FakeAudioSource(failOnRead = 2, onRead = { clock.advanceBy(100) })
        val recorder = VoiceRecorder(FakeFactory { source }) { clock.now() }

        assertTrue(recorder.start())
        awaitCondition("reader thread stops after the failing read") { !recorder.isRecording }

        val recording = recorder.stop()
        assertNotNull("the chunk read before the failure is kept", recording)
        recording!!
        assertEquals(44 + chunk, recording.wav.size)
        assertEquals(100L, recording.durationMs)
        assertTrue(source.releases >= 1)
    }
}
