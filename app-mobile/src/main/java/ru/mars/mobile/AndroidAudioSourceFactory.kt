package ru.mars.mobile

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
 */
class AndroidAudioSourceFactory(
    private val sampleRate: Int = VoiceRecorder.SAMPLE_RATE,
) : AudioSourceFactory {

    override fun create(): AudioSource {
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT

        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        require(minBuffer > 0) { "AudioRecord.getMinBufferSize returned $minBuffer" }
        // Two read chunks of slack keeps the hardware buffer fed between reads.
        val bufferBytes = maxOf(minBuffer, VoiceRecorder.READ_CHUNK_BYTES * 2)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelMask,
            encoding,
            bufferBytes,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord is not initialized (state=${record.state})")
        }

        record.startRecording()
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
