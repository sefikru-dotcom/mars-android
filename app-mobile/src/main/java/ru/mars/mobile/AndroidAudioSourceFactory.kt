package ru.mars.mobile

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production [AudioSourceFactory]: the only place in the voice pipeline that touches the Android
 * audio stack.
 *
 * It opens an AudioRecord for 16 kHz mono PCM 16-bit and hands out a pull source that reads
 * directly from it. [VoiceRecorder] never references this class directly in the paths exercised by
 * unit tests, so JVM tests run with a fake factory and no device. Every decision *about* the open —
 * which source to try, in what order, when to fall back, which effects to request — lives in the
 * Android-free [AudioCapturePolicy]/[AudioCaptureSelector]; this class only executes it against the
 * platform.
 *
 * Source choice (see [AudioCapturePolicy.candidates]): with [echoCancellation] on (the default)
 * the first attempt is [MediaRecorder.AudioSource.VOICE_COMMUNICATION], which makes the platform
 * engage its echo canceller and noise suppressor on the capture route, and the explicit fallback is
 * [MediaRecorder.AudioSource.MIC]. Without that, barge-in hears the assistant's own speaker output:
 * [SilenceDetector]/[VoiceSegmenter] take the playback for human speech, the loop cuts the answer
 * off and starts over. A refused microphone (`SecurityException`) is never retried on the fallback
 * source — the permission is missing for all of them.
 *
 * Effects: after the record is initialized, [AcousticEchoCanceler] and [NoiseSuppressor] are created
 * on the record's audio session. Hardware AEC is the safety net for devices where VOICE_COMMUNICATION
 * alone is not enough. Availability is a per-device lottery, so a missing effect (`isAvailable` false,
 * `create` returning null) or a refusal (`setEnabled` error / exception) is not a failure: capture
 * continues without it, and the outcome is reported in logcat as a stable one-line summary
 * ([AudioCaptureDiagnostics.logLine]) for on-device comparison.
 *
 * The returned source releases the AudioRecord and its effects exactly once (repeated calls are
 * ignored), which lets both the reader thread's cleanup and [VoiceRecorder.stop]/[VoiceRecorder.cancel]
 * unblock a pending read without double-releasing the hardware.
 *
 * [create] requires RECORD_AUDIO. A denial (or a revocation between the UI's runtime check and the
 * open) arrives as a [SecurityException] and is rethrown as
 * [MicrophonePermissionDeniedException], so a refused microphone is never mistaken for a broken
 * audio stack.
 *
 * @param echoCancellation request VOICE_COMMUNICATION plus hardware AEC/NS. `false` reproduces the
 *   old MIC-only capture exactly — the switch for A/B measurements on a real device.
 * @param noiseSuppression request [NoiseSuppressor] alongside the echo canceller.
 */
class AndroidAudioSourceFactory(
    private val sampleRate: Int = VoiceRecorder.SAMPLE_RATE,
    private val echoCancellation: Boolean = AudioCapturePolicy.DEFAULT_ECHO_CANCELLATION,
    private val noiseSuppression: Boolean = AudioCapturePolicy.DEFAULT_NOISE_SUPPRESSION,
) : AudioSourceFactory {

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun create(): AudioSource {
        val bufferBytes = bufferBytesFor(sampleRate)

        // The selector owns the retry loop; the closure below performs one platform open and keeps
        // the wrapper so effects can be attached to the session that actually opened.
        var opened: AudioRecordSource? = null
        val capture = AudioCaptureSelector(
            openSource = { candidate ->
                openRecord(candidate, bufferBytes).also { opened = it }
            },
            onAttemptFailed = { candidate, error ->
                Log.w(TAG, "capture source ${candidate.id} did not open, falling back", error)
            },
        ).open(echoCancellation)

        val record = opened ?: throw IllegalStateException("capture opened without an AudioRecord")
        val activation = AudioCapturePolicy.activateEffects(
            AudioCapturePolicy.effectPlan(echoCancellation, noiseSuppression),
        ) { kind -> acquireEffect(kind, record.audioSessionId) }
        record.attachEffects(activation.effects)

        Log.i(
            TAG,
            AudioCaptureDiagnostics(
                captureSource = capture.captureSource,
                fallbackUsed = capture.fallbackUsed,
                echoCancellationRequested = echoCancellation,
                noiseSuppressionRequested = noiseSuppression,
                activation = activation,
            ).logLine(),
        )
        return record
    }

    /** Минимальный буфер записи; проверяется один раз — от кандидата источника он не зависит. */
    private fun bufferBytesFor(sampleRate: Int): Int {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "AudioRecord.getMinBufferSize returned $minBuffer" }
        // Two read chunks of slack keeps the hardware buffer fed between reads.
        return maxOf(minBuffer, VoiceRecorder.READ_CHUNK_BYTES * 2)
    }

    /**
     * Одна попытка открыть AudioRecord на [capture]. Любой отказ (неинициализированное состояние,
     * отказ startRecording) освобождает запись и уходит наверх исключением — решение о следующем
     * кандидате принимает [AudioCaptureSelector].
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun openRecord(capture: CaptureSource, bufferBytes: Int): AudioRecordSource {
        // A denied or revoked RECORD_AUDIO surfaces here as a SecurityException. It is translated
        // into the domain type so the caller sees "the microphone was refused", never a generic
        // initialization failure that would look like broken hardware.
        val record = try {
            AudioRecord(
                mediaRecorderSource(capture),
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (denied: SecurityException) {
            throw MicrophonePermissionDeniedException(
                "AudioRecord could not be opened from ${capture.id}: RECORD_AUDIO is not granted",
                denied,
            )
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException(
                "AudioRecord is not initialized for ${capture.id} (state=${record.state})",
            )
        }

        try {
            record.startRecording()
        } catch (denied: SecurityException) {
            record.release()
            throw MicrophonePermissionDeniedException(
                "AudioRecord could not start recording from ${capture.id}: RECORD_AUDIO is not granted",
                denied,
            )
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            throw IllegalStateException(
                "AudioRecord failed to enter the recording state for ${capture.id}",
            )
        }

        return AudioRecordSource(record)
    }

    private fun mediaRecorderSource(capture: CaptureSource): Int = when (capture) {
        CaptureSource.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        CaptureSource.MIC -> MediaRecorder.AudioSource.MIC
    }

    /**
     * Пытается включить один аппаратный эффект на сессии [sessionId]. Отсутствие эффекта и отказ
     * включения одинаково безопасны для захвата: возвращается [EffectAcquisition.Unavailable] /
     * [EffectAcquisition.Failed], исключение наружу не выходит.
     */
    private fun acquireEffect(kind: CaptureEffectKind, sessionId: Int): EffectAcquisition {
        val available = runCatching {
            when (kind) {
                CaptureEffectKind.ACOUSTIC_ECHO_CANCELER -> AcousticEchoCanceler.isAvailable()
                CaptureEffectKind.NOISE_SUPPRESSOR -> NoiseSuppressor.isAvailable()
            }
        }.getOrDefault(false)
        if (!available) return EffectAcquisition.Unavailable

        val effect: AudioEffect = runCatching {
            when (kind) {
                CaptureEffectKind.ACOUSTIC_ECHO_CANCELER -> AcousticEchoCanceler.create(sessionId)
                CaptureEffectKind.NOISE_SUPPRESSOR -> NoiseSuppressor.create(sessionId)
            }
        }.getOrNull() ?: return EffectAcquisition.Unavailable

        val enabled = runCatching {
            effect.enabled = true
            effect.enabled
        }.getOrDefault(false)
        if (!enabled) {
            // Держать созданный, но выключенный эффект не за чем: он только занимает сессию.
            runCatching { effect.release() }
            Log.w(TAG, "effect ${kind.id} is present but could not be enabled")
            return EffectAcquisition.Failed
        }
        return EffectAcquisition.Enabled(PlatformCaptureEffect(kind.id, effect))
    }

    private class PlatformCaptureEffect(
        override val name: String,
        private val effect: AudioEffect,
    ) : CaptureEffect {
        override fun release() {
            runCatching { effect.release() }
        }
    }

    /**
     * Pull source over one AudioRecord plus the hardware effects enabled on its session.
     *
     * [release] is guarded by the same `AtomicBoolean` as before: the reader thread, [VoiceRecorder.stop]
     * and a cancel can all reach it, and the hardware (record and effects) must be freed exactly once.
     * Effects are attached right after the open, on the creating thread, before the source is handed
     * to a reader; [attachEffects] still handles the theoretical "already released" case by freeing
     * them immediately instead of leaking them.
     */
    private class AudioRecordSource(private val record: AudioRecord) : AudioSource {

        private val released = AtomicBoolean(false)

        @Volatile
        private var effects: AudioCaptureEffects = AudioCaptureEffects()

        val audioSessionId: Int get() = record.audioSessionId

        fun attachEffects(activated: AudioCaptureEffects) {
            effects = activated
            if (released.get()) activated.release()
        }

        override fun read(buf: ByteArray): Int = record.read(buf, 0, buf.size)

        override fun release() {
            if (!released.compareAndSet(false, true)) return
            try {
                record.stop()
            } catch (ignored: IllegalStateException) {
                // Already stopped or never started — release below still frees the hardware.
            }
            // Эффекты снимаются до release() записи: они привязаны к её сессии, и после
            // освобождения AudioRecord их release() уже не гарантирован.
            effects.release()
            record.release()
        }
    }

    private companion object {
        const val TAG = "MarsAudioCapture"
    }
}
