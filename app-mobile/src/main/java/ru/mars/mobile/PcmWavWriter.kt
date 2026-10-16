package ru.mars.mobile

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes a raw PCM payload into a canonical RIFF/WAVE container.
 *
 * Pure JVM on purpose: the encoder is the part of the voice pipeline that has to be verified
 * without a device, so it never touches the Android audio stack. Input is expected as
 * interleaved little-endian signed 16-bit samples — exactly what AudioRecord produces with
 * AudioFormat.ENCODING_PCM_16BIT, and the shape the server-side recognizer accepts
 * (16 kHz, mono). The written header is the canonical 44-byte PCM header: RIFF chunk, WAVE
 * form, fmt sub-chunk with PCM format code 1 and 16 bits per sample, then the data sub-chunk.
 * An empty payload yields a valid 44-byte WAV whose data sub-chunk size is zero.
 */
object PcmWavWriter {

    /** Signed 16-bit little-endian samples — fixed for the voice pipeline. */
    const val BITS_PER_SAMPLE = 16

    private const val PCM_FORMAT_CODE = 1
    private const val HEADER_BYTES = 44

    /**
     * Wrap [pcm] in a WAV container for [sampleRate] and [channels].
     *
     * @throws IllegalArgumentException when [sampleRate] or [channels] is not positive.
     */
    fun encode(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(channels > 0) { "channels must be positive: $channels" }

        val blockAlign = channels * (BITS_PER_SAMPLE / 8)
        val byteRate = sampleRate * blockAlign
        val dataSize = pcm.size

        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put(ascii("RIFF"))
        header.putInt(HEADER_BYTES - 8 + dataSize)
        header.put(ascii("WAVE"))
        header.put(ascii("fmt "))
        header.putInt(16)
        header.putShort(PCM_FORMAT_CODE.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(BITS_PER_SAMPLE.toShort())
        header.put(ascii("data"))
        header.putInt(dataSize)

        val out = ByteArrayOutputStream(HEADER_BYTES + dataSize)
        out.write(header.array(), 0, HEADER_BYTES)
        out.write(pcm, 0, dataSize)
        return out.toByteArray()
    }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)
}
