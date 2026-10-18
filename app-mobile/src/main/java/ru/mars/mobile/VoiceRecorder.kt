package ru.mars.mobile

import android.Manifest
import androidx.annotation.RequiresPermission
import java.io.ByteArrayOutputStream

/** A pull-based PCM source: the seam that keeps [VoiceRecorder] free of the Android audio stack. */
interface AudioSource {

    /**
     * Fill [buf] with up to its size in bytes of PCM. Returns the number of bytes written, or a
     * value that is not positive when the stream has ended or the source was released underneath
     * the reader.
     */
    fun read(buf: ByteArray): Int

    /** Stop the hardware and free its resources. Safe to call more than once. */
    fun release()
}

/**
 * The platform refused to open the microphone because RECORD_AUDIO is not granted (denied by the
 * user, revoked at runtime, or restricted by the device policy).
 *
 * It deliberately extends [SecurityException]: callers keep the familiar failure type of a denied
 * permission, while the specific type lets the voice UI tell "microphone denied" apart from a
 * genuine AudioRecord initialization failure.
 */
class MicrophonePermissionDeniedException(
    message: String,
    cause: Throwable? = null,
) : SecurityException(message, cause)

/**
 * Creates a fresh [AudioSource] per recording session.
 *
 * Implementations open the microphone, so the call requires RECORD_AUDIO. The requirement is
 * declared on the abstraction (not only on the Android implementation) so every caller — including
 * the JVM tests' fakes — is checked by lint against the same contract: either hold the permission
 * or handle [SecurityException].
 */
interface AudioSourceFactory {
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun create(): AudioSource
}

/** A finished capture: WAV bytes ready to upload, plus the negotiated parameters. */
data class Recording(val wav: ByteArray, val sampleRate: Int, val durationMs: Long)

/**
 * Microphone recorder producing a WAV (16 kHz, mono, PCM 16-bit) suitable for server-side
 * speech recognition.
 *
 * The whole state machine — buffer accumulation, timing, the duration ceiling and the WAV
 * hand-off — is independent of Android and runs against the injected [AudioSourceFactory] and
 * clock, so unit tests drive it with a fake source and a fake clock. The Android audio stack
 * lives only in [AndroidAudioSourceFactory]; production code passes no factory and gets the
 * real one.
 *
 * Reading happens on a dedicated daemon thread, so [start], [stop] and [cancel] never block the
 * caller on microphone I/O. The pull loop ends on [stop], on [cancel], when the source ends, or
 * when elapsed time reaches [MAX_DURATION_MS] (auto-stop, so a stuck control cannot record
 * forever). The [AudioSource] is released on every exit path, including read failures.
 */
class VoiceRecorder(
    private val sourceFactory: AudioSourceFactory = AndroidAudioSourceFactory(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private class Session(val source: AudioSource, val startMs: Long) {
        val pcm = ByteArrayOutputStream()

        @Volatile var stopRequested = false

        @Volatile var cancelRequested = false

        @Volatile var elapsedMs = 0L
    }

    private val lock = Any()

    private var session: Session? = null

    private var worker: Thread? = null

    @Volatile private var running = false

    /** True from a successful [start] until the pull loop finishes (stop, cancel, ceiling, end). */
    val isRecording: Boolean get() = running

    /**
     * Begin capturing. Returns false without side effects when a session is already live, or
     * when the [AudioSourceFactory] cannot hand out a source (for example a denied microphone).
     */
    fun start(): Boolean {
        val workerThread: Thread
        synchronized(lock) {
            // One live session at a time: refuse while capturing OR while a finished session is
            // still awaiting stop()/cancel() collection.
            if (running || session != null || worker != null) return false
            val source = try {
                sourceFactory.create()
            } catch (denied: SecurityException) {
                // RECORD_AUDIO was denied or revoked between the UI permission check and the open.
                // Nothing has been allocated yet, so the recorder stays idle and a later start()
                // (after the user grants the permission) is free to try again.
                return false
            } catch (ignored: Exception) {
                return false
            }
            val fresh = Session(source, nowMs())
            session = fresh
            running = true
            workerThread = Thread({ readLoop(fresh) }, THREAD_NAME).apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
            worker = workerThread
        }
        workerThread.start()
        return true
    }

    /**
     * Finish capturing and return the encoded WAV. Returns null when there is no session, when
     * the session captured no audio, or when it was cancelled.
     */
    fun stop(): Recording? {
        val current = synchronized(lock) { session } ?: return null
        current.stopRequested = true
        // Unblock a pull that is already inside read(), so the worker can finish promptly.
        releaseQuietly(current.source)
        awaitWorker()
        synchronized(lock) {
            session = null
            worker = null
        }
        if (current.cancelRequested) return null
        val pcm = current.pcm.toByteArray()
        if (pcm.isEmpty()) return null
        return Recording(PcmWavWriter.encode(pcm, SAMPLE_RATE, CHANNELS), SAMPLE_RATE, current.elapsedMs)
    }

    /** Abort the current session, discarding the captured audio. No-op when nothing is recording. */
    fun cancel() {
        val current = synchronized(lock) { session } ?: return
        current.cancelRequested = true
        current.stopRequested = true
        releaseQuietly(current.source)
        awaitWorker()
        synchronized(lock) {
            session = null
            worker = null
        }
    }

    private fun readLoop(current: Session) {
        val buf = ByteArray(READ_CHUNK_BYTES)
        try {
            while (!current.stopRequested && !current.cancelRequested) {
                val read = try {
                    current.source.read(buf)
                } catch (ignored: Exception) {
                    break
                }
                if (read <= 0) break
                current.pcm.write(buf, 0, read)
                current.elapsedMs = maxOf(current.elapsedMs, nowMs() - current.startMs)
                if (current.elapsedMs >= MAX_DURATION_MS) break
            }
        } finally {
            current.elapsedMs = maxOf(current.elapsedMs, nowMs() - current.startMs)
            synchronized(lock) {
                if (worker === Thread.currentThread()) running = false
            }
            releaseQuietly(current.source)
            synchronized(lock) {
                if (worker === Thread.currentThread()) worker = null
            }
        }
    }

    private fun awaitWorker() {
        val thread = synchronized(lock) { worker } ?: return
        if (thread === Thread.currentThread()) return
        try {
            thread.join()
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun releaseQuietly(source: AudioSource) {
        try {
            source.release()
        } catch (ignored: Exception) {
            // A failing release must not mask the recording result.
        }
    }

    companion object {
        /** Server-side recognition was verified against 16 kHz mono PCM 16-bit WAV. */
        const val SAMPLE_RATE = 16_000

        const val CHANNELS = 1

        /** Roughly 100 ms of audio per read at 16 kHz mono PCM16 — keeps stop latency low. */
        const val READ_CHUNK_BYTES = SAMPLE_RATE / 10 * (PcmWavWriter.BITS_PER_SAMPLE / 8)

        /** Hard ceiling: the pull loop auto-stops here, so a stuck control cannot record forever. */
        const val MAX_DURATION_MS = 120_000L

        private const val THREAD_NAME = "mars-voice-recorder"
    }
}
