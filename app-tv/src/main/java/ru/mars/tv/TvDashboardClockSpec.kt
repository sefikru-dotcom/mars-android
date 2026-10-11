package ru.mars.tv

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure clock presentation of the TV dashboard header (no Android, no I/O —
 * JVM-testable). The dashboard is the screen a TV shows for minutes at a time:
 * the big HH:mm clock alone reads as a widget, so the header carries a full
 * weekday+date line under it (muted, secondary — the hierarchy decision lives
 * in the layout, the FORMATTING decision lives here and is unit-tested).
 *
 * No glyph/emoji decoration: the design-system guard (TvDesignSystemTest) bans
 * font glyphs module-wide; text-only lines keep the header in the palette.
 */
object TvDashboardClockSpec {

    /** Big primary clock line: 24-hour «HH:mm». */
    fun timeText(
        date: Date,
        locale: Locale = Locale.getDefault(),
        zone: TimeZone = TimeZone.getDefault(),
    ): String = formatter("HH:mm", locale, zone).format(date)

    /** Secondary line under the clock: full weekday + day + month («воскресенье, 11 октября»). */
    fun dateText(
        date: Date,
        locale: Locale = Locale.getDefault(),
        zone: TimeZone = TimeZone.getDefault(),
    ): String = formatter("EEEE, d MMMM", locale, zone).format(date)

    private fun formatter(pattern: String, locale: Locale, zone: TimeZone) =
        SimpleDateFormat(pattern, locale).apply { timeZone = zone }
}
