package ru.mars.mobile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for [PcmWavWriter] — the pure JVM RIFF/WAVE encoder. No Android, no Robolectric:
 * every byte of the container is read back and checked against the canonical 44-byte PCM header.
 */
class PcmWavWriterTest {

    private fun signature(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.US_ASCII)

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun pcm(samples: Int): ByteArray = ByteArray(samples * 2) { (it and 0xFF).toByte() }

    @Test
    fun `header carries the canonical RIFF chunk signatures`() {
        val wav = PcmWavWriter.encode(pcm(1600), 16_000, 1)
        assertEquals("RIFF", signature(wav, 0))
        assertEquals("WAVE", signature(wav, 8))
        assertEquals("fmt ", signature(wav, 12))
        assertEquals("data", signature(wav, 36))
    }

    @Test
    fun `header declares 16 kHz mono PCM 16-bit parameters`() {
        val wav = PcmWavWriter.encode(pcm(1600), 16_000, 1)
        assertEquals(16, u32(wav, 16))          // fmt sub-chunk size
        assertEquals(1, u16(wav, 20))           // PCM format code
        assertEquals(1, u16(wav, 22))           // channels
        assertEquals(16_000, u32(wav, 24))      // sample rate
        assertEquals(32_000, u32(wav, 28))      // byte rate = 16000 * 1 * 2
        assertEquals(2, u16(wav, 32))           // block align = channels * bytes per sample
        assertEquals(16, u16(wav, 34))          // bits per sample
    }

    @Test
    fun `sub-chunk sizes match the payload and total length`() {
        val payload = pcm(1600)
        val wav = PcmWavWriter.encode(payload, 16_000, 1)
        assertEquals(44 + payload.size, wav.size)
        assertEquals(36 + payload.size, u32(wav, 4))   // RIFF size = total - 8
        assertEquals(payload.size, u32(wav, 40))       // data size
        assertArrayEquals(payload, wav.copyOfRange(44, wav.size))
    }

    @Test
    fun `poly-channel encoding scales block align and byte rate`() {
        val wav = PcmWavWriter.encode(pcm(1600), 16_000, 2)
        assertEquals(2, u16(wav, 22))
        assertEquals(4, u16(wav, 32))
        assertEquals(64_000, u32(wav, 28))
    }

    @Test
    fun `empty payload yields a valid header-only wav with zero data size`() {
        val wav = PcmWavWriter.encode(ByteArray(0), 16_000, 1)
        assertEquals(44, wav.size)
        assertEquals(36, u32(wav, 4))
        assertEquals(0, u32(wav, 40))
        assertEquals("data", signature(wav, 36))
    }

    @Test
    fun `invalid parameters are rejected instead of emitting a broken container`() {
        assertThrows(IllegalArgumentException::class.java) {
            PcmWavWriter.encode(pcm(1), 0, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PcmWavWriter.encode(pcm(1), 16_000, 0)
        }
    }
}
