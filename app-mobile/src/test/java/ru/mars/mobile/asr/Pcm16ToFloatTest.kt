package ru.mars.mobile.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты конвертации PCM int16 LE -> float32 -1..1.
 *
 * Это не формальность: sherpa-onnx на int16 молча возвращает пустую строку (проверено
 * на сервере), поэтому единственный признак «правильного» входа — арифметика ниже.
 * Отдельно проверяется перенос непарного байта между вызовами: AudioRecord может
 * отдать нечётное число байт (например, при обрезании буфера), и без переноса весь
 * дальнейший поток был бы сдвинут на байт — распознавание превратилось бы в шум.
 */
class Pcm16ToFloatTest {

    @Test
    fun silenceIsZero() {
        val converter = Pcm16ToFloat()
        val samples = converter.convert(byteArrayOf(0, 0, 0, 0), 4)
        assertArrayEquals(floatArrayOf(0f, 0f), samples, 0f)
    }

    @Test
    fun extremesMapToMinusOneAndAlmostOne() {
        val converter = Pcm16ToFloat()
        // 32767 = 0x7FFF -> 0x7FFF/32768; -32768 = 0x8000 -> ровно -1.0
        val samples = converter.convert(byteArrayOf(0xFF.toByte(), 0x7F, 0x00, 0x80.toByte()), 4)
        assertEquals(2, samples.size)
        assertEquals(32767f / 32768f, samples[0], 0f)
        assertEquals(-1f, samples[1], 0f)
    }

    @Test
    fun littleEndianOrderIsRespected() {
        val converter = Pcm16ToFloat()
        // 0x00 0x01 = 256 (LE), а не 1
        val samples = converter.convert(byteArrayOf(0x00, 0x01), 2)
        assertEquals(256f / 32768f, samples[0], 0f)
    }

    @Test
    fun negativeSampleKeepsSign() {
        val converter = Pcm16ToFloat()
        // 0xFFFF = -1
        val samples = converter.convert(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), 2)
        assertEquals(-1f / 32768f, samples[0], 0f)
    }

    @Test
    fun oddByteIsCarriedToNextCall() {
        val converter = Pcm16ToFloat()
        val first = converter.convert(byteArrayOf(0x00, 0x01, 0x02), 3)
        assertEquals(1, first.size)
        assertEquals(256f / 32768f, first[0], 0f)

        // Младший байт 0x02 + старший 0x00 -> 2
        val second = converter.convert(byteArrayOf(0x00), 1)
        assertEquals(1, second.size)
        assertEquals(2f / 32768f, second[0], 0f)
    }

    @Test
    fun emptyAndNonPositiveLengthGiveNothing() {
        val converter = Pcm16ToFloat()
        assertTrue(converter.convert(ByteArray(0), 0).isEmpty())
        assertTrue(converter.convert(byteArrayOf(1, 2), 0).isEmpty())
        assertTrue(converter.convert(byteArrayOf(1, 2), -5).isEmpty())
    }

    @Test
    fun lengthBeyondBufferIsClamped() {
        val converter = Pcm16ToFloat()
        val samples = converter.convert(byteArrayOf(0x00, 0x01), 64)
        assertEquals(1, samples.size)
        assertEquals(256f / 32768f, samples[0], 0f)
    }

    @Test
    fun resetDropsPendingByte() {
        val converter = Pcm16ToFloat()
        converter.convert(byteArrayOf(0x02), 1)
        converter.reset()
        // Без reset 0x02 склеился бы с 0x01 и дал 258; после reset это отдельный сэмпл.
        val samples = converter.convert(byteArrayOf(0x01, 0x00), 2)
        assertEquals(1, samples.size)
        assertEquals(1f / 32768f, samples[0], 0f)
    }
}
