package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class MessageTimePolicyTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun millis(date: String): Long =
        LocalDateTime.parse(date.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `parses ISO offset, instant and naive server timestamps`() {
        val expected = millis("2026-10-11 14:05:03")
        assertEquals(expected, MessageTimePolicy.parseServerTimestamp("2026-10-11T14:05:03+00:00"))
        assertEquals(expected, MessageTimePolicy.parseServerTimestamp("2026-10-11T14:05:03Z"))
        assertEquals(expected, MessageTimePolicy.parseServerTimestamp("2026-10-11 14:05:03"))
        // Дробные секунды сохраняются: .123456 -> +123 мс к целой секунде.
        assertEquals(expected + 123, MessageTimePolicy.parseServerTimestamp("2026-10-11 14:05:03.123456"))
        // +03:00 offset shifts the instant back to UTC.
        assertEquals(expected, MessageTimePolicy.parseServerTimestamp("2026-10-11T17:05:03+03:00"))
    }

    @Test
    fun `unknown or garbage timestamp falls back`() {
        assertEquals(MessageTimePolicy.UNKNOWN_TIMESTAMP, MessageTimePolicy.parseServerTimestamp(null))
        assertEquals(MessageTimePolicy.UNKNOWN_TIMESTAMP, MessageTimePolicy.parseServerTimestamp(""))
        assertEquals(MessageTimePolicy.UNKNOWN_TIMESTAMP, MessageTimePolicy.parseServerTimestamp("не дата"))
        assertEquals(42L, MessageTimePolicy.parseServerTimestamp("мусор", fallback = 42L))
    }

    @Test
    fun `clock label is HH-mm in the given zone, null for unknown`() {
        assertEquals("14:05", MessageTimePolicy.clockLabel(millis("2026-10-11 14:05:00"), zone))
        assertEquals("09:00", MessageTimePolicy.clockLabel(millis("2026-10-11 09:00:00"), zone))
        // MSK: 14:05 UTC == 17:05 +03:00.
        assertEquals("17:05", MessageTimePolicy.clockLabel(millis("2026-10-11 14:05:00"), ZoneId.of("Europe/Moscow")))
        assertNull(MessageTimePolicy.clockLabel(MessageTimePolicy.UNKNOWN_TIMESTAMP, zone))
    }

    @Test
    fun `day label is Segodnya-Vchera-date with genitive month and year for old days`() {
        val now = millis("2026-10-11 18:00:00")
        assertEquals("Сегодня", MessageTimePolicy.dayLabel(millis("2026-10-11 07:00:00"), now, zone))
        assertEquals("Вчера", MessageTimePolicy.dayLabel(millis("2026-10-10 23:59:00"), now, zone))
        assertEquals("9 октября", MessageTimePolicy.dayLabel(millis("2026-10-09 12:00:00"), now, zone))
        assertEquals("1 января", MessageTimePolicy.dayLabel(millis("2026-01-01 12:00:00"), now, zone))
        assertEquals("31 декабря 2025", MessageTimePolicy.dayLabel(millis("2025-12-31 12:00:00"), now, zone))
    }

    @Test
    fun `day separator on first known message and on day change only`() {
        val todayMorning = millis("2026-10-11 07:00:00")
        val todayEvening = millis("2026-10-11 19:00:00")
        val yesterday = millis("2026-10-10 22:00:00")
        // First message with a known timestamp opens the transcript with a separator.
        assertTrue(MessageTimePolicy.showDaySeparator(MessageTimePolicy.UNKNOWN_TIMESTAMP, todayMorning, zone))
        // Same local day — no separator.
        assertFalse(MessageTimePolicy.showDaySeparator(todayMorning, todayEvening, zone))
        // Day changed (23:59 -> 00:01) — separator even 2 minutes apart.
        assertTrue(
            MessageTimePolicy.showDaySeparator(
                millis("2026-10-10 23:59:00"), millis("2026-10-11 00:01:00"), zone,
            )
        )
        // Unknown next timestamp never produces a separator.
        assertFalse(MessageTimePolicy.showDaySeparator(todayMorning, MessageTimePolicy.UNKNOWN_TIMESTAMP, zone))
        assertFalse(MessageTimePolicy.showDaySeparator(yesterday, MessageTimePolicy.UNKNOWN_TIMESTAMP, zone))
    }
}
