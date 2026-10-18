package ru.mars.mobile

import java.util.Arrays
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

    // --- failure paths -------------------------------------------------------------------------

    @Test
    fun `factory failure leaves the recorder idle`() {
        val recorder = VoiceRecorder(FakeFactory { throw IllegalStateException("microphone denied") }) { 0L }

        assertFalse(recorder.start())
        assertFalse(recorder.isRecording)
        assertNull(recorder.stop())
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
