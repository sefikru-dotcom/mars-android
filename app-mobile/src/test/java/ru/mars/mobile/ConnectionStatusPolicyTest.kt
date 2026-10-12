package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.HealthLevel

/**
 * Строка состояния на экране «Настройки» должна отражать реальный вердикт
 * пробы боевого хоста, а не всегдашний отказ, как было при пинге localhost.
 */
class ConnectionStatusPolicyTest {

    @Test
    fun healthyServerShowsOnlineWithMeasuredLatency() {
        val text = ConnectionStatusPolicy.statusText(HealthLevel.HEALTHY, 128L)

        assertTrue(text.startsWith("Онлайн"))
        assertTrue(text.contains("128"))
        assertTrue(ConnectionStatusPolicy.isOnline(HealthLevel.HEALTHY))
    }

    @Test
    fun slowServerIsReportedAsSlowAndNotOnline() {
        val text = ConnectionStatusPolicy.statusText(HealthLevel.DEGRADED, 2400L)

        assertTrue(text.contains("медленно"))
        assertTrue(text.contains("2400"))
        assertFalse(ConnectionStatusPolicy.isOnline(HealthLevel.DEGRADED))
    }

    @Test
    fun unreachableServerShowsUnavailableWithoutLatency() {
        val text = ConnectionStatusPolicy.statusText(HealthLevel.OFFLINE, 3000L)

        assertEquals("Сервер недоступен", text)
        assertFalse(text.contains("3000"))
        assertFalse(ConnectionStatusPolicy.isOnline(HealthLevel.OFFLINE))
    }

    @Test
    fun probePathNeverTargetsLocalhost() {
        assertEquals("health", ConnectionStatusPolicy.PROBE_PATH)
        assertFalse(ConnectionStatusPolicy.PROBE_PATH.contains("127.0.0.1"))
        assertFalse(ConnectionStatusPolicy.PROBE_PATH.contains(":"))
    }

    @Test
    fun statusTextsCarryNoBannedDesignGlyphs() {
        val banned = setOf(
            '\u2713', '\u2714', '\u2717', '\u2715', '\u00d7', '\u25CF',
            '\u26A0', '\u2B50', '\u200D', '\uFE0F',
        )
        val samples = HealthLevel.entries.map { ConnectionStatusPolicy.statusText(it, 100L) }

        for (sample in samples) {
            for (glyph in banned) {
                assertFalse("Глиф '$glyph' запрещён дизайн-системой: $sample", sample.contains(glyph))
            }
        }
    }
}
