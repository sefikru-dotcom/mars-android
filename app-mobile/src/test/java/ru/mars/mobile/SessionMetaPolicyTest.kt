package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class SessionMetaPolicyTest {

    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.parse("2026-10-11T18:00:00").toInstant(zone).toEpochMilli()

    private fun minutesAgo(min: Long): Long = now - min * 60_000

    @Test
    fun `relative label buckets - just now, minutes, hours, yesterday, days, date`() {
        assertEquals("только что", SessionMetaPolicy.relativeLabel(minutesAgo(0), now, zone))
        assertEquals("только что", SessionMetaPolicy.relativeLabel(minutesAgo(0) + 30_000, now, zone))
        assertEquals("5 мин назад", SessionMetaPolicy.relativeLabel(minutesAgo(5), now, zone))
        assertEquals("59 мин назад", SessionMetaPolicy.relativeLabel(minutesAgo(59), now, zone))
        // Same local day: hours.
        assertEquals("3 ч назад", SessionMetaPolicy.relativeLabel(minutesAgo(180), now, zone))
        // Yesterday evening (26 h ago crosses the day boundary).
        assertEquals("вчера", SessionMetaPolicy.relativeLabel(minutesAgo(26 * 60), now, zone))
        assertEquals("3 дн назад", SessionMetaPolicy.relativeLabel(now - 3L * 86_400_000, now, zone))
        assertEquals("2 октября", SessionMetaPolicy.relativeLabel(now - 9L * 86_400_000, now, zone))
    }

    @Test
    fun `future clock skew reads as just now, unknown timestamp as blank`() {
        assertEquals("только что", SessionMetaPolicy.relativeLabel(now + 60_000, now, zone))
        assertEquals("", SessionMetaPolicy.relativeLabel(MessageTimePolicy.UNKNOWN_TIMESTAMP, now, zone))
    }

    @Test
    fun `message count uses Russian plural rules`() {
        assertEquals("1 сообщение", SessionMetaPolicy.messagesLabel(1))
        assertEquals("2 сообщения", SessionMetaPolicy.messagesLabel(2))
        assertEquals("4 сообщения", SessionMetaPolicy.messagesLabel(4))
        assertEquals("5 сообщений", SessionMetaPolicy.messagesLabel(5))
        assertEquals("11 сообщений", SessionMetaPolicy.messagesLabel(11))
        assertEquals("21 сообщение", SessionMetaPolicy.messagesLabel(21))
        assertEquals("22 сообщения", SessionMetaPolicy.messagesLabel(22))
        assertEquals("111 сообщений", SessionMetaPolicy.messagesLabel(111))
        assertNull(SessionMetaPolicy.messagesLabel(null))
        assertNull(SessionMetaPolicy.messagesLabel(0))
    }

    @Test
    fun `meta line joins time and count, degrades gracefully, null when empty`() {
        assertEquals(
            "5 мин назад · 12 сообщений",
            SessionMetaPolicy.metaLine(minutesAgo(5), 12, now, zone),
        )
        assertEquals("вчера", SessionMetaPolicy.metaLine(minutesAgo(26 * 60), null, now, zone))
        assertEquals("3 сообщения", SessionMetaPolicy.metaLine(null, 3, now, zone))
        assertEquals("3 сообщения", SessionMetaPolicy.metaLine(MessageTimePolicy.UNKNOWN_TIMESTAMP, 3, now, zone))
        assertNull(SessionMetaPolicy.metaLine(null, null, now, zone))
        assertNull(SessionMetaPolicy.metaLine(MessageTimePolicy.UNKNOWN_TIMESTAMP, 0, now, zone))
    }
}
