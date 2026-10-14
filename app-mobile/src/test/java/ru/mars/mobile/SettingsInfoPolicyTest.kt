package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Строки «Профиль»/«Клиент»/«Сервер» на экране «Настройки» строятся из реальных
 * данных (Build, credential Марса, package), а не из захардкоженных «Stef (Stavropol)»
 * и «Android / HyperOS (POCO F5)», которые были ложью на любом другом устройстве.
 *
 * Тексты намеренно не содержат запрещённых дизайн-системой глифов
 * (эмодзи, chevron, звезда, «x»-счётчик) — см. `theme-parity-audit.py`.
 */
class SettingsInfoPolicyTest {

    @Test
    fun `client label is built from real Build fields`() {
        assertEquals(
            "Android 15 · POCO F5",
            SettingsInfoPolicy.clientLabel(release = "15", manufacturer = "POCO", model = "POCO F5"),
        )
        assertEquals(
            "Android 14 · samsung SM-G991B",
            SettingsInfoPolicy.clientLabel(release = "14", manufacturer = "samsung", model = "SM-G991B"),
        )
        assertEquals(
            "Android 13 · Pixel 7",
            SettingsInfoPolicy.clientLabel(release = "13", manufacturer = "", model = "Pixel 7"),
        )
    }

    @Test
    fun `client label degrades honestly when Build fields are missing`() {
        assertEquals(
            "Android · Xiaomi",
            SettingsInfoPolicy.clientLabel(release = null, manufacturer = "Xiaomi", model = ""),
        )
        assertEquals(
            "Android · устройство",
            SettingsInfoPolicy.clientLabel(release = " ", manufacturer = null, model = null),
        )
        assertEquals(
            "устройство вместо выдуманной модели",
            "Android 15 · устройство",
            SettingsInfoPolicy.clientLabel(release = "15", manufacturer = "  ", model = " "),
        )
    }

    @Test
    fun `client label never contains hardcoded marketing text`() {
        val label = SettingsInfoPolicy.clientLabel("15", "POCO", "POCO F5")
        assertFalse(label.contains("HyperOS"))
        assertFalse(SettingsInfoPolicy.clientLabel(null, null, null).contains("Stef"))
    }

    @Test
    fun `profile label reflects real credential state`() {
        assertEquals("Не привязано", SettingsInfoPolicy.profileLabel(null))
        assertEquals("Не привязано", SettingsInfoPolicy.profileLabel("   "))
        assertEquals("Привязано · dev-1", SettingsInfoPolicy.profileLabel("dev-1"))
        assertEquals(
            "Привязано · 0123456789abcdef…",
            SettingsInfoPolicy.profileLabel("0123456789abcdef0123456789abcdef"),
        )
    }

    @Test
    fun `short id truncates only when longer than the limit`() {
        assertEquals("0123456789abcdef", SettingsInfoPolicy.shortId("0123456789abcdef"))
        assertEquals("0123456789abcdef…", SettingsInfoPolicy.shortId("0123456789abcdef0"))
        assertEquals("dev…", SettingsInfoPolicy.shortId("dev-1", maxChars = 3))
    }

    @Test
    fun `server row is shown from the passed base url without rewriting`() {
        assertEquals("https://example.invalid", SettingsInfoPolicy.serverLabel("https://example.invalid"))
    }

    @Test
    fun `labels carry no banned design glyphs`() {
        val banned = setOf(
            '\u2713', '\u2714', '\u2717', '\u2715', '\u00d7', '\u25CF',
            '\u26A0', '\u2B50', '\u200D', '\uFE0F',
        )
        val samples = listOf(
            SettingsInfoPolicy.clientLabel("15", "POCO", "POCO F5"),
            SettingsInfoPolicy.clientLabel(null, null, null),
            SettingsInfoPolicy.profileLabel(null),
            SettingsInfoPolicy.profileLabel("0123456789abcdef0123"),
        )
        for (sample in samples) {
            for (glyph in banned) {
                assertFalse("Глиф '$glyph' запрещён дизайн-системой: $sample", sample.contains(glyph))
            }
            assertTrue(sample.isNotBlank())
        }
    }
}
