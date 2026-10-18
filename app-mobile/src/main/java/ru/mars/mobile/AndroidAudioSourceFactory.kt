package ru.mars.mobile

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production [AudioSourceFactory]: the only place in the voice pipeline that touches the Android
 * audio stack.
 *
 * It opens an AudioRecord for 16 kHz mono PCM 16-bit from the microphone, and hands out a pull
 * source that reads directly from it. [VoiceRecorder] never references this class directly in the
 * paths exercised by unit tests, so JVM tests run with a fake factory and no device.
 *
 * The returned source releases the AudioRecord exactly once (repeated calls are ignored), which
 * lets both the reader thread's cleanup and [VoiceRecorder.stop]/[VoiceRecorder.cancel] unblock a
 * pending read without double-releasing the hardware.
 *
 * [create] requires RECORD_AUDIO. A denial (or a revocation between the UI's runtime check and the
 * open) arrives as a [SecurityException] and is rethrown as
 * [MicrophonePermissionDeniedException], so a refused microphone is never mistaken for a broken
 * audio stack.
 */
class AndroidAudioSourceFactory(
    private val sampleRate: Int = VoiceRecorder.SAMPLE_RATE,
) : AudioSourceFactory {

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun create(): AudioSource {
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        require(minBuffer > 0) { "AudioRecord.getMinBufferSize returned $minBuffer" }
        // Two read chunks of slack keeps the hardware buffer fed between reads.
        val bufferBytes = maxOf(minBuffer, VoiceRecorder.READ_CHUNK_BYTES * 2)

        // A denied or revoked RECORD_AUDIO surfaces here as a SecurityException. It is translated
        // into the domain type so the caller sees "the microphone was refused", never a generic
        // initialization failure that would look like broken hardware.
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelMask,
                encoding,
                bufferBytes,
            )
        } catch (denied: SecurityException) {
            throw MicrophonePermissionDeniedException(
                "AudioRecord could not be opened: RECORD_AUDIO is not granted",
                denied,
            )
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord is not initialized (state=${record.state})")
        }

        try {
            record.startRecording()
        } catch (denied: SecurityException) {
            record.release()
            throw MicrophonePermissionDeniedException(
                "AudioRecord could not start recording: RECORD_AUDIO is not granted",
                denied,
            )
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            throw IllegalStateException("AudioRecord failed to enter the recording state")
        }

        return AudioRecordSource(record)
    }

    private class AudioRecordSource(private val record: AudioRecord) : AudioSource {

        private val released = AtomicBoolean(false)

        override fun read(buf: ByteArray): Int = record.read(buf, 0, buf.size)

        override fun release() {
            if (!released.compareAndSet(false, true)) return
            try {
                record.stop()
            } catch (ignored: IllegalStateException) {
                // Already stopped or never started — release below still frees the hardware.
            }
            record.release()
        }
    }
}
