package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MetricsTimestamp] делает «ts» ответа дашборда («14.10.2026 в 16:30:28 (МСК)»)
 * машиночитаемым моментом, чтобы статус-строка показывала честную давность данных,
 * а не время запроса (P0-4, дефект 3).
 */
class MetricsTimestampTest {

    /** Живой формат дашборда: дата/время в МСК (UTC+3) плюс хвост «(МСК)». */
    private val LIVE_TS = "14.10.2026 в 16:30:28 (МСК)"

    /** 14.10.2026 16:30:28 МСК = 13:30:28 UTC. */
    private val LIVE_TS_MS = 1_791_984_628_000L

    @Test fun parsesLiveDashboardTsAsMskEpoch() {
        assertEquals(LIVE_TS_MS, MetricsTimestamp.parseMs(LIVE_TS))
    }

    @Test fun ageTextFromResponseTs() {
        assertEquals("только что", MetricsTimestamp.ageText(LIVE_TS, LIVE_TS_MS + 30_000L))
        assertEquals("5 мин назад", MetricsTimestamp.ageText(LIVE_TS, LIVE_TS_MS + 5 * 60_000L + 999L))
        assertEquals("2 ч назад", MetricsTimestamp.ageText(LIVE_TS, LIVE_TS_MS + 2 * 3_600_000L))
        assertEquals("3 дн назад", MetricsTimestamp.ageText(LIVE_TS, LIVE_TS_MS + 3 * 86_400_000L))
    }

    @Test fun futureTsClampsToJustNow() {
        assertEquals("только что", MetricsTimestamp.ageText(LIVE_TS, LIVE_TS_MS - 60_000L))
    }

    @Test fun unparseableTsGivesNullNotCrash() {
        assertNull(MetricsTimestamp.parseMs(""))
        assertNull(MetricsTimestamp.parseMs("   "))
        assertNull("ISO-формат легаси не парсится МСК-паттерном", MetricsTimestamp.parseMs("2026-10-07T11:32:05Z"))
        assertNull(MetricsTimestamp.parseMs("вчера"))
        assertNull(MetricsTimestamp.parseMs("99.99.9999 в 25:61:61 (МСК)"))
        assertNull(MetricsTimestamp.ageText("", LIVE_TS_MS))
        assertNull(MetricsTimestamp.ageText("мусор", LIVE_TS_MS))
    }
}
