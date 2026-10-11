package ru.mars.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Unit tests for [TvDashboardClockSpec] — pure JVM (java.text only, no Android).
 * The dashboard header hierarchy: big 24-hour «HH:mm» primary line plus a full
 * Russian weekday+date secondary line. Fixed instant + explicit zone/locale keep
 * the assertions deterministic on any host.
 */
class TvDashboardClockSpecTest {

    /** 2026-10-11 12:34:00 UTC — a Sunday. */
    private val fixed: Date = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
        set(2026, Calendar.OCTOBER, 11, 12, 34, 56)
        set(Calendar.MILLISECOND, 0)
    }.time

    private val ru = Locale("ru")
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    @Test
    fun `time line is 24-hour HHmm`() {
        assertEquals("12:34", TvDashboardClockSpec.timeText(fixed, ru, utc))
    }

    @Test
    fun `time line follows the requested zone`() {
        val plus3 = TimeZone.getTimeZone("GMT+03:00")
        assertEquals("15:34", TvDashboardClockSpec.timeText(fixed, ru, plus3))
    }

    @Test
    fun `date line is the full russian weekday plus day and month`() {
        assertEquals("воскресенье, 11 октября", TvDashboardClockSpec.dateText(fixed, ru, utc))
    }

    @Test
    fun `both lines are plain text without glyph decoration`() {
        // Design-system parity (TvDesignSystemTest bans font glyphs module-wide):
        // the clock lines carry letters, digits and punctuation only.
        val lines = listOf(
            TvDashboardClockSpec.timeText(fixed, ru, utc),
            TvDashboardClockSpec.dateText(fixed, ru, utc),
        )
        lines.forEach { line ->
            assertFalse("clock line must not be blank", line.isBlank())
            line.codePoints().forEach { cp ->
                assertFalse("unexpected symbol class in «$line»", cp > 0x04FF && cp != 0x00B7)
            }
        }
    }
}
