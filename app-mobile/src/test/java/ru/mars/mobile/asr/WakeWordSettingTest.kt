package ru.mars.mobile.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Настройка «Слушать „Марс“»: дефолт, сохранение и отсутствие второго источника правды.
 *
 * Хранилище подменено парой лямбд — `SharedPreferences` на чистой JVM недоступен (инструментального
 * слоя в проекте нет), а поведение дефолта и записи от этого не меняется. Дефолт ВЫКЛЮЧЕН — это
 * требование владельца: постоянный микрофон расходует батарею, и включать его без выбора человека
 * нельзя.
 */
class WakeWordSettingTest {

    /** Фейковое prefs-хранилище: только булевы значения, как у настоящего файла настроек. */
    private class FakePrefs {
        val values = mutableMapOf<String, Boolean>()

        fun setting(): WakeWordSetting = WakeWordSetting(
            read = { key, default -> values[key] ?: default },
            write = { key, value -> values[key] = value },
        )
    }

    @Test
    fun `по умолчанию wake word выключен`() {
        val prefs = FakePrefs()

        assertFalse(
            "дефолт обязан быть выключен: постоянный микрофон — расход батареи, решение человека",
            prefs.setting().isEnabled(),
        )
        assertTrue("чтение не должно писать ключ в хранилище", prefs.values.isEmpty())
        assertFalse(DEFAULT_WAKE_WORD_ENABLED)
    }

    @Test
    fun `включение сохраняется под ключом wake_word`() {
        val prefs = FakePrefs()
        prefs.setting().setEnabled(true)

        assertEquals(true, prefs.values[KEY_WAKE_WORD])
        assertEquals("wake_word", KEY_WAKE_WORD)
        assertTrue("новый экран видит сохранённый выбор", prefs.setting().isEnabled())
    }

    @Test
    fun `сохранённое false не затирается дефолтом`() {
        val prefs = FakePrefs()
        prefs.setting().setEnabled(true)
        prefs.setting().setEnabled(false)

        assertEquals(false, prefs.values[KEY_WAKE_WORD])
        assertFalse(prefs.setting().isEnabled())
    }
}
