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

    /**
     * Whether hardware echo cancellation is actually ON for this session.
     *
     * Barge-in needs the truth, not the request: "we asked for AEC" is not "the platform enabled
     * it" (see [AudioCaptureDiagnostics.echoCanceled]). The default is `false` — a source that does
     * not know must not make the dialog loop believe the echo path is closed. Implementations keep
     * the answer after [release]: the flag describes the session that was opened.
     */
    fun echoCanceled(): Boolean = false
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
 *
 * When [onAutoStop] is set the loop also asks [SilenceDetector] after every chunk and ends the
 * session on silence — the second tap becomes unnecessary. The session is torn down exactly like
 * a manual [stop] (same buffer, same WAV, same release), the difference is only who collects the
 * result: the host gets it through [onAutoStop], and a later [stop] returns null instead of a
 * duplicate WAV.
 */
class VoiceRecorder(
    private val sourceFactory: AudioSourceFactory = AndroidAudioSourceFactory(),
    /**
     * Хост авто-стопа по тишине ([SilenceDetector]). `null` — детектор не включается вовсе:
     * поведение остаётся прежним (запись до тапа/лимита), поэтому тесты и TV-пути, которые
     * создают рекордер без колбэка, ничего не замечают. Колбэк вызывается на потоке чтения —
     * ровно один раз на сессию, уже после освобождения микрофона, и только с непустой записью.
     */
    private val onAutoStop: ((Recording) -> Unit)? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private class Session(val source: AudioSource, val startMs: Long) {
        val pcm = ByteArrayOutputStream()

        @Volatile var stopRequested = false

        @Volatile var cancelRequested = false

        @Volatile var elapsedMs = 0L

        /** Запись уже отдана через [onAutoStop]: повторный [stop] не должен вернуть второй WAV. */
        @Volatile var takenBySilence = false
    }

    private val lock = Any()

    /**
     * Один детектор на рекордер: сессии строго последовательны (одна живая за раз), поэтому
     * экземпляр переиспользуется и сбрасывается в [start]. Параметры порогов — дефолтные:
     * тонкая настройка под конкретный микрофон здесь не нужна, а ручной тап не отменяется.
     */
    private val silenceDetector = SilenceDetector()

    private var session: Session? = null

    private var worker: Thread? = null

    @Volatile private var running = false

    /**
     * true, если текущую (или только что законченную) сессию забрал авто-стоп по тишине.
     *
     * Нужен хосту, чтобы отличить «в записи не было звука» от «запись уже ушла в
     * [onAutoStop]»: тап, пришедший в момент авто-стопа, получает из [stop] null и без этого
     * флага показал бы «запись пустая» и отменил бы уже начатое распознавание. Сбрасывается
     * в [start].
     */
    @Volatile var autoStopTaken: Boolean = false
        private set

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
            autoStopTaken = false
            // Новая сессия — новые отсчёты: иначе «тишина» предыдущей записи сразу закрыла бы эту.
            silenceDetector.reset()
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
        // Авто-стоп уже отдал эту запись хосту: второй WAV означал бы второе распознавание и
        // второе сообщение. Возвращаем null — как и для пустой/отменённой сессии.
        if (current.takenBySilence) return null
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

    /**
     * Pull loop: read → store → check ceiling → check silence.
     *
     * The silence check runs only when a host asked for it ([onAutoStop] != null), so a recorder
     * created without the callback behaves exactly as before (tests, TV paths). On STOP_SILENCE
     * the loop exits through the same `finally` as a manual stop — buffer flushed, mic released,
     * [isRecording] false — and only then is the result handed to the host, so the callback can
     * never see a half-closed session.
     */
    private fun readLoop(current: Session) {
        val buf = ByteArray(READ_CHUNK_BYTES)
        var stoppedBySilence = false
        try {
            while (!current.stopRequested && !current.cancelRequested) {
                val read = try {
                    current.source.read(buf)
                } catch (ignored: Exception) {
                    break
                }
                if (read <= 0) break
                current.pcm.write(buf, 0, read)
                val now = nowMs()
                current.elapsedMs = maxOf(current.elapsedMs, now - current.startMs)
                // Ceiling first: сессия на лимите — это стоп по лимиту, а не «тишина».
                if (current.elapsedMs >= MAX_DURATION_MS) break
                if (onAutoStop != null &&
                    silenceDetector.onChunk(buf, now, read) == SilenceDetector.Decision.STOP_SILENCE
                ) {
                    stoppedBySilence = true
                    break
                }
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
        if (stoppedBySilence) deliverAutoStop(current)
    }

    /**
     * Hand a silence-stopped recording to [onAutoStop] at most once.
     *
     * The session is taken under the lock: if a manual [stop] won the race (it grabbed the
     * session first), the callback is skipped — the caller already owns that recording. A tap
     * that arrives after the take gets null from [stop] and sees [autoStopTaken], so the host
     * does not report an empty recording over a transcript that is already on its way.
     *
     * No exception may escape the reader thread: a throwing host callback is swallowed (the
     * recording is already encoded and the mic released, so there is nothing left to repair).
     */
    private fun deliverAutoStop(current: Session) {
        val callback = onAutoStop ?: return
        if (current.cancelRequested) return
        val pcm = current.pcm.toByteArray()
        if (pcm.isEmpty()) return
        val recording = Recording(PcmWavWriter.encode(pcm, SAMPLE_RATE, CHANNELS), SAMPLE_RATE, current.elapsedMs)
        val mine = synchronized(lock) {
            if (session === current) {
                current.takenBySilence = true
                autoStopTaken = true
                session = null
                true
            } else {
                false
            }
        }
        if (!mine) return
        try {
            callback(recording)
        } catch (ignored: Exception) {
            // Хост не должен ронять поток чтения: микрофон уже освобождён, запись отдана.
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
