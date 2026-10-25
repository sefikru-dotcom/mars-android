package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class MessageTimePolicyTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val msk: ZoneId = ZoneId.of("Europe/Moscow")

    private fun millis(date: String): Long =
        LocalDateTime.parse(date.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** Тот же момент, заданный в МОСКОВСКОМ времени (UTC+3, без DST). */
    private fun millisMsk(date: String): Long =
        LocalDateTime.parse(date.replace(' ', 'T')).toInstant(ZoneOffset.ofHours(3)).toEpochMilli()

    // ---- Разбор серверных меток --------------------------------------------------------------

    @Test
    fun `parses ISO offset, instant and naive server timestamps`() {
        val expected = millis("2026-10-11 14:05:03")
        assertEquals(expected, MessageTimePolicy.parseServerTimestamp("2026-10-11T14:05:03+00:00"))
        assertEquals(expected, MessageTimePolicy.parseServerTimestamp("2026-10-11T14:05:03Z"))
        assertEquals(expected, MessageTimePolicy.parseServerTimestamp("2026-10-11 14:05:03"))
        // Дробные секунды сохраняются: .123456 -> +123 мс к целой секунде.
        assertEquals(expected + 123, MessageTimePolicy.parseServerTimestamp("2026-10-11 14:05:03.123456"))
        // Миллисекундная форма дробной части тоже принимается.
        assertEquals(expected + 123, MessageTimePolicy.parseServerTimestamp("2026-10-11 14:05:03.123"))
        // Метка без секунд («14:05») — тоже валидный серверный формат.
        assertEquals(millis("2026-10-11 14:05:00"), MessageTimePolicy.parseServerTimestamp("2026-10-11 14:05"))
        // +03:00 offset shifts the instant back to UTC.
        assertEquals(expected, MessageTimePolicy.parseServerTimestamp("2026-10-11T17:05:03+03:00"))
        // Москва = UTC+3: 17:05 MSK — это 14:05 UTC.
        assertEquals(expected, MessageTimePolicy.parseServerTimestamp("2026-10-11T17:05:03+03:00"))
    }

    @Test
    fun `unknown or garbage timestamp falls back`() {
        assertEquals(MessageTimePolicy.UNKNOWN_TIMESTAMP, MessageTimePolicy.parseServerTimestamp(null))
        assertEquals(MessageTimePolicy.UNKNOWN_TIMESTAMP, MessageTimePolicy.parseServerTimestamp(""))
        assertEquals(MessageTimePolicy.UNKNOWN_TIMESTAMP, MessageTimePolicy.parseServerTimestamp("   "))
        assertEquals(MessageTimePolicy.UNKNOWN_TIMESTAMP, MessageTimePolicy.parseServerTimestamp("не дата"))
        assertEquals(MessageTimePolicy.UNKNOWN_TIMESTAMP, MessageTimePolicy.parseServerTimestamp("2026-13-45 99:99:99"))
        assertEquals(42L, MessageTimePolicy.parseServerTimestamp("мусор", fallback = 42L))
    }

    @Test
    fun `parses epoch seconds and millis from persisted history rows`() {
        // sidecar-строки истории несут `timestamp` — epoch-секунды (строка или число,
        // нормализованное SessionRepository); без этой ветки часы и день-разделители
        // всей истории молча терялись (P0-регрессия транскрипта).
        assertEquals(1_791_921_277_839L, MessageTimePolicy.parseServerTimestamp("1791921277.8392885"))
        assertEquals(1_791_921_277_000L, MessageTimePolicy.parseServerTimestamp("1791921277"))
        assertEquals(1_791_921_277_000L, MessageTimePolicy.parseServerTimestamp(" 1791921277 "))
        // 13+ значащих цифр — уже миллисекунды.
        assertEquals(1_791_921_277_839L, MessageTimePolicy.parseServerTimestamp("1791921277839"))
        // Живое значение из state.db (REAL): 2026-10-25 07:41:05 UTC.
        assertEquals(1_792_914_065_214L, MessageTimePolicy.parseServerTimestamp("1792914065.2146087"))
        // Экспоненциальная форма Double (если путь не нормализовал число в plain-string).
        assertEquals(1_791_921_277_839L, MessageTimePolicy.parseServerTimestamp("1.7919212778392885E9"))
        assertEquals(1_792_914_065_214L, MessageTimePolicy.parseServerTimestamp("1.7929140652146087E9"))
        // Мелкие/нулевые значения — не эпоха сообщений.
        assertEquals(MessageTimePolicy.UNKNOWN_TIMESTAMP, MessageTimePolicy.parseServerTimestamp("0"))
        assertEquals(MessageTimePolicy.UNKNOWN_TIMESTAMP, MessageTimePolicy.parseServerTimestamp("42"))
        // Абсурдно большая метка (год 5xxxx) не превращается в «час сообщения».
        assertEquals(MessageTimePolicy.UNKNOWN_TIMESTAMP, MessageTimePolicy.parseServerTimestamp("999999999999999"))
        // ISO-строки с цифрами внутри не должны уходить в epoch-ветку.
        assertEquals(millis("2026-10-11 14:05:03"), MessageTimePolicy.parseServerTimestamp("2026-10-11 14:05:03"))
    }

    // ---- Час карточки -----------------------------------------------------------------------

    @Test
    fun `clock label is HH-mm in the given zone, null for unknown`() {
        assertEquals("14:05", MessageTimePolicy.clockLabel(millis("2026-10-11 14:05:00"), zone))
        assertEquals("09:00", MessageTimePolicy.clockLabel(millis("2026-10-11 09:00:00"), zone))
        // MSK: 14:05 UTC == 17:05 +03:00.
        assertEquals("17:05", MessageTimePolicy.clockLabel(millis("2026-10-11 14:05:00"), msk))
        // Ведущие нули и границы суток в форматтере.
        assertEquals("00:00", MessageTimePolicy.clockLabel(millis("2026-10-11 00:00:00"), zone))
        assertEquals("23:59", MessageTimePolicy.clockLabel(millis("2026-10-11 23:59:59"), zone))
        assertNull(MessageTimePolicy.clockLabel(MessageTimePolicy.UNKNOWN_TIMESTAMP, zone))
        assertNull(MessageTimePolicy.clockLabel(-1L, zone))
    }

    @Test
    fun `epoch seconds from history render the Moscow clock, not UTC and not 1970`() {
        // 1791921277 = 2026-10-13 19:54:37 UTC = 22:54 MSK: час истории обязан быть
        // московским локальным временем владельца.
        val historyMillis = MessageTimePolicy.parseServerTimestamp("1791921277.8392885")
        assertEquals("22:54", MessageTimePolicy.clockLabel(historyMillis, msk))
        assertEquals("19:54", MessageTimePolicy.clockLabel(historyMillis, zone))
        // Если бы epoch-СЕКУНДЫ приняли за миллисекунды, час уехал бы в 1970 год —
        // такой карточки быть не должно (защита от путаницы секунды/миллисекунды).
        assertNotEquals("22:54", MessageTimePolicy.clockLabel(1_791_921_277L, msk))
        assertTrue(MessageTimePolicy.clockLabel(historyMillis, msk)!!.startsWith("2"))
    }

    @Test
    fun `every real wire timestamp form yields the same clock label`() {
        val expected = "17:05"
        val forms = listOf(
            "2026-10-11T14:05:03Z",              // ISO instant
            "2026-10-11T14:05:03+00:00",         // ISO offset
            "2026-10-11T17:05:03+03:00",         // ISO offset MSK
            "2026-10-11 14:05:03",               // naive, трактуется как UTC
            "2026-10-11 14:05",                  // naive без секунд
            "1791727503",                        // epoch-секунды строкой
            "1791727503.0",                      // epoch-секунды строкой с дробью
            "1791727503000",                     // epoch-миллисекунды строкой
        )
        for (form in forms) {
            assertEquals("форма $form", expected, MessageTimePolicy.clockLabel(MessageTimePolicy.parseServerTimestamp(form), msk))
        }
    }

    // ---- Разделители дней -------------------------------------------------------------------

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
    fun `day label crosses the year boundary with the year in the caption`() {
        val newYearNight = millisMsk("2026-01-01 00:30:00")
        // 23:30 MSK 31 декабря — «Вчера» для ночи 1 января.
        assertEquals("Вчера", MessageTimePolicy.dayLabel(millisMsk("2025-12-31 23:30:00"), newYearNight, msk))
        // Ровно новогодняя полночь — «Сегодня».
        assertEquals("Сегодня", MessageTimePolicy.dayLabel(millisMsk("2026-01-01 00:00:00"), newYearNight, msk))
        // Подпись прошлого года несёт год, текущего — нет (граница года).
        val january = millisMsk("2026-01-05 10:00:00")
        assertEquals("31 декабря 2025", MessageTimePolicy.dayLabel(millisMsk("2025-12-31 10:00:00"), january, msk))
        assertEquals("1 января", MessageTimePolicy.dayLabel(millisMsk("2026-01-01 10:00:00"), january, msk))
        assertEquals("3 января", MessageTimePolicy.dayLabel(millisMsk("2026-01-03 10:00:00"), january, msk))
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

    @Test
    fun `day separator follows the Moscow day, not the UTC day`() {
        // 20:59 и 21:01 UTC — ОДНИ сутки по UTC, но 23:59 и 00:01 по Москве.
        val lateMsk = millis("2026-10-10 20:59:00")
        val afterMidnightMsk = millis("2026-10-10 21:01:00")
        assertFalse(MessageTimePolicy.showDaySeparator(lateMsk, afterMidnightMsk, zone))
        assertTrue(MessageTimePolicy.showDaySeparator(lateMsk, afterMidnightMsk, msk))
        // Метки эпохи истории (строки с дробью) ведут себя так же.
        val prev = MessageTimePolicy.parseServerTimestamp("1791921277.8392885")   // 2026-10-13 22:54 MSK
        val next = MessageTimePolicy.parseServerTimestamp("1791960000")           // 2026-10-14 09:40 MSK
        assertTrue(MessageTimePolicy.showDaySeparator(prev, next, msk))
        assertFalse(MessageTimePolicy.showDaySeparator(prev, prev + 60_000, msk))
        // Граница года: 31 декабря 23:59 -> 1 января 00:01 MSK.
        assertTrue(
            MessageTimePolicy.showDaySeparator(
                millisMsk("2025-12-31 23:59:00"), millisMsk("2026-01-01 00:01:00"), msk,
            )
        )
    }
}
