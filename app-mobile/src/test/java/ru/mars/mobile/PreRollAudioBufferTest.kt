package ru.mars.mobile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Предбуфер микрофона — то, что возвращает первое слово, сказанное пока грузился движок.
 *
 * Тесты намеренно проверяют не «happy path», а места, где кольцо обычно и ломается:
 * переполнение (старое выбрасывается, новое остаётся), границы 16-битных отсчётов
 * (сдвиг на полбайта превращает речь в шипение), кусок длиннее всего буфера и
 * «сколько прочитал» больше, чем реально лежит в массиве.
 */
class PreRollAudioBufferTest {

    /** Байты 0,1,2,… — по ним видно, какие именно данные выжили. */
    private fun bytes(count: Int, from: Int = 0): ByteArray =
        ByteArray(count) { index -> (from + index).toByte() }

    @Test
    fun `пустой предбуфер отдаёт пустой массив`() {
        val buffer = PreRollAudioBuffer()

        assertEquals(0, buffer.size)
        assertTrue(buffer.isEmpty)
        assertEquals(0, buffer.drain().size)
    }

    @Test
    fun `накопленное отдаётся в хронологическом порядке`() {
        val buffer = PreRollAudioBuffer()

        buffer.append(bytes(4, from = 10), 4)
        buffer.append(bytes(2, from = 20), 2)

        assertEquals(6, buffer.size)
        assertArrayEquals(byteArrayOf(10, 11, 12, 13, 20, 21), buffer.drain())
    }

    @Test
    fun `drain очищает буфер, второй вызов пуст`() {
        val buffer = PreRollAudioBuffer()
        buffer.append(bytes(8), 8)

        assertEquals(8, buffer.drain().size)

        assertEquals(0, buffer.size)
        assertEquals(0, buffer.drain().size)
    }

    @Test
    fun `clear выбрасывает накопленное, не отдавая его`() {
        val buffer = PreRollAudioBuffer()
        buffer.append(bytes(6), 6)

        buffer.clear()

        assertEquals(0, buffer.size)
        assertEquals(0, buffer.drain().size)
    }

    @Test
    fun `переполнение оставляет самый свежий хвост`() {
        val buffer = PreRollAudioBuffer(maxBytes = 8)

        buffer.append(bytes(12), 12)

        // Влезли последние восемь байт: 4..11.
        assertEquals(8, buffer.size)
        assertArrayEquals(bytes(8, from = 4), buffer.drain())
    }

    @Test
    fun `переполнение по частям тоже выбрасывает старое`() {
        val buffer = PreRollAudioBuffer(maxBytes = 8)

        buffer.append(bytes(6, from = 0), 6)
        buffer.append(bytes(6, from = 100), 6)

        // Ёмкость 8: остались последние два байта первой пачки и вся вторая.
        assertArrayEquals(
            byteArrayOf(4, 5, 100, 101, 102, 103, 104, 105),
            buffer.drain(),
        )
    }

    @Test
    fun `кусок длиннее ёмкости не сдвигает отсчёты на полбайта`() {
        // Ёмкость 4 байта (два отсчёта), приходит 5 байт: начало хвоста обязано быть чётным.
        val buffer = PreRollAudioBuffer(maxBytes = 5)

        buffer.append(bytes(5, from = 0), 5)

        // Хвост с чётного смещения: байты 2,3,4 — а не 1,2,3 (иначе отсчёты разъехались бы).
        assertArrayEquals(byteArrayOf(2, 3, 4), buffer.drain())
    }

    @Test
    fun `нечётная ёмкость округляется до целого отсчёта`() {
        val buffer = PreRollAudioBuffer(maxBytes = 9)

        buffer.append(bytes(20), 20)

        // 9 → 8 байт: четыре отсчёта, ни одного разрезанного.
        assertEquals(8, buffer.size)
        assertEquals(0, buffer.drain().size % 2)
    }

    @Test
    fun `length больше массива не падает и не пишет мусор`() {
        val buffer = PreRollAudioBuffer()

        buffer.append(bytes(4, from = 7), 400)

        assertArrayEquals(byteArrayOf(7, 8, 9, 10), buffer.drain())
    }

    @Test
    fun `отрицательная и нулевая длина игнорируются`() {
        val buffer = PreRollAudioBuffer()
        buffer.append(bytes(4), 4)

        buffer.append(bytes(4), 0)
        buffer.append(bytes(4), -3)

        assertEquals(4, buffer.size)
    }

    @Test
    fun `нечётный хвост PCM не теряется и не выравнивается по своей воле`() {
        // Реальный захват вправе отдать нечётное число байт: второй байт отсчёта придёт
        // следующим куском, и предбуфер обязан сохранить оба ровно в том же порядке.
        val buffer = PreRollAudioBuffer()

        buffer.append(bytes(3, from = 0), 3)
        buffer.append(bytes(3, from = 3), 3)

        assertArrayEquals(bytes(6), buffer.drain())
    }

    @Test
    fun `предбуфер переиспользуется между репликами`() {
        val buffer = PreRollAudioBuffer(maxBytes = 8)

        buffer.append(bytes(6, from = 0), 6)
        buffer.clear()
        buffer.append(bytes(6, from = 50), 6)

        assertEquals(6, buffer.size)
        assertArrayEquals(bytes(6, from = 50), buffer.drain())
    }

    @Test
    fun `ёмкость по умолчанию — две секунды 16 кГц моно`() {
        // 16 000 отсчётов × 2 байта × 2 с. Меньше секунды не хватило бы на загрузку модели,
        // больше — лишняя память на каждом открытом микрофоне.
        assertEquals(64_000, PreRollAudioBuffer.DEFAULT_MAX_BYTES)

        val buffer = PreRollAudioBuffer()
        buffer.append(ByteArray(PreRollAudioBuffer.DEFAULT_MAX_BYTES + 1_000), 65_000)

        assertEquals(PreRollAudioBuffer.DEFAULT_MAX_BYTES, buffer.size)
    }
}
