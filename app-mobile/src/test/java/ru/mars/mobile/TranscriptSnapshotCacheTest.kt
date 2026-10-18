package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кэш снимков карточек: на кадре стрима меняется одно сообщение, остальные снимки
 * переиспользуются по тождеству экземпляра — без пересчёта день-разделителей.
 * Главная гарантия — результат ВСЕГДА равен наивному пересчёту (иначе UI залипнет
 * на устаревшем снимке).
 */
class TranscriptSnapshotCacheTest {

    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L

    private var builds = 0

    private fun cache(): TranscriptSnapshotCache = TranscriptSnapshotCache { message, previous, epoch, streaming ->
        builds++
        CardSnapshotPolicy.of(message, previous, epoch, streaming)
    }

    private fun message(text: String, timestamp: Long = t0) = ChatMessage(
        role = "assistant",
        text = text,
        timestamp = timestamp,
    )

    /** Наивный пересчёт — эталон поведения прежнего currentSnapshots(). */
    private fun naive(messages: List<ChatMessage>, epoch: Long, typing: Boolean): List<CardSnapshot> {
        var previousTimestamp = MessageTimePolicy.UNKNOWN_TIMESTAMP
        val lastIndex = messages.size - 1
        return messages.mapIndexed { index, message ->
            val snapshot = CardSnapshotPolicy.of(message, previousTimestamp, epoch, typing && index == lastIndex)
            if (message.timestamp > MessageTimePolicy.UNKNOWN_TIMESTAMP) previousTimestamp = message.timestamp
            snapshot
        }
    }

    @Test
    fun `тот же список не пересчитывается и отдаёт те же экземпляры`() {
        val sut = cache()
        val messages = listOf(message("а"), message("б"))

        val first = sut.update(messages, chromeEpoch = 1L, typing = true)
        builds = 0
        val second = sut.update(messages, chromeEpoch = 1L, typing = true)

        assertEquals(0, builds)
        assertSame(first[0], second[0])
        assertSame(first[1], second[1])
        assertEquals(naive(messages, 1L, true), second)
    }

    @Test
    fun `дописанное сообщение пересчитывается одно, история переиспользуется`() {
        val sut = cache()
        val first = message("а")
        val second = message("б")
        val initial = listOf(first, second)
        val before = sut.update(initial, chromeEpoch = 1L, typing = true)
        builds = 0

        val grown = initial + message("в")
        val after = sut.update(grown, chromeEpoch = 1L, typing = true)

        // Два пересчёта: новое сообщение + прежний хвост, потерявший стрим-флаг
        // (он больше не последний). История до них переиспользуется как есть.
        assertEquals(2, builds)
        assertSame(before[0], after[0])
        assertEquals(naive(grown, 1L, true), after)
    }

    @Test
    fun `изменённое сообщение пересчитывается, соседи остаются теми же`() {
        val sut = cache()
        val a = message("а")
        val b = message("б")
        val c = message("в")
        val before = sut.update(listOf(a, b, c), chromeEpoch = 1L, typing = true)
        builds = 0

        // Стрим дописывает текст в среднее сообщение (copy — новый экземпляр).
        val b2 = b.copy(text = "б+")
        val after = sut.update(listOf(a, b2, c), chromeEpoch = 1L, typing = true)

        assertEquals(1, builds)
        assertSame(before[0], after[0])
        assertNotSame(before[1], after[1])
        assertSame(before[2], after[2])
        assertEquals(naive(listOf(a, b2, c), 1L, true), after)
    }

    @Test
    fun `смена эпохи хрома пересчитывает все снимки`() {
        val sut = cache()
        val messages = listOf(message("а"), message("б"))
        sut.update(messages, chromeEpoch = 1L, typing = false)
        builds = 0

        val after = sut.update(messages, chromeEpoch = 2L, typing = false)

        assertEquals(2, builds)
        assertEquals(naive(messages, 2L, false), after)
    }

    @Test
    fun `переключение стрим-флага пересчитывает только последнее сообщение`() {
        val sut = cache()
        val messages = listOf(message("а"), message("б"))
        sut.update(messages, chromeEpoch = 1L, typing = false)
        builds = 0

        val after = sut.update(messages, chromeEpoch = 1L, typing = true)

        assertEquals(1, builds)
        assertTrue(after.last().streamingFlag)
        assertEquals(naive(messages, 1L, true), after)
    }

    @Test
    fun `сдвиг метки времени пересчитывает соседа справа ради день-разделителя`() {
        val sut = cache()
        val a = message("а", timestamp = t0)
        val b = message("б", timestamp = t0 + 2 * day)
        val c = message("в", timestamp = t0 + 2 * day + 1_000L)
        sut.update(listOf(a, b, c), chromeEpoch = 1L, typing = false)
        builds = 0

        // Метка первого сообщения уехала на другие сутки: у «б» меняется день-разделитель.
        val a2 = a.copy(timestamp = t0 + 3 * day)
        val messages = listOf(a2, b, c)
        val after = sut.update(messages, chromeEpoch = 1L, typing = false)

        assertEquals(2, builds)
        assertTrue(after[0].showDaySeparator)
        assertEquals(naive(messages, 1L, false), after)
    }

    @Test
    fun `после invalidate снимки считаются заново`() {
        val sut = cache()
        val messages = listOf(message("а"))
        sut.update(messages, chromeEpoch = 1L, typing = false)
        sut.invalidate()
        builds = 0

        val after = sut.update(messages, chromeEpoch = 1L, typing = false)

        assertEquals(1, builds)
        assertEquals(naive(messages, 1L, false), after)
    }

    @Test
    fun `серия кадров стрима совпадает с наивным пересчётом на каждом кадре`() {
        val sut = cache()
        val history = listOf(message("давний", timestamp = t0), message("вчера", timestamp = t0 + day))
        val bubble = message("", timestamp = t0 + 2 * day)
        val messages = (history + bubble).toMutableList()

        repeat(12) { frame ->
            messages[messages.lastIndex] = messages.last().copy(text = "ответ ".repeat(frame + 1))
            val cached = sut.update(messages, chromeEpoch = 1L, typing = true)
            assertEquals("кадр $frame", naive(messages, 1L, true), cached)
        }
    }
}
