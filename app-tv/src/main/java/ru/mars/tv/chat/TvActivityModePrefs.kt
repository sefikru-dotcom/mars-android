package ru.mars.tv.chat

import android.content.SharedPreferences

/**
 * SharedPreferences-адаптер [TvActivityModeStorage] (тонкий Android-слой: только
 * чтение/запись стабильного wire-значения, вся логика режимов — в чистом
 * [TvActivityDisplayMode]/[TvActivityModeSelection], покрытом JVM-тестами).
 * Имя файла настроек и ключ совпадают с мобильным клиентом, чтобы режим читался
 * одинаково на обоих клиентах одного устройства.
 */
class TvActivityModePrefs(private val prefs: SharedPreferences) : TvActivityModeStorage {

    override fun load(): TvActivityDisplayMode =
        TvActivityDisplayMode.fromWire(prefs.getString(KEY_MODE, null))

    override fun save(mode: TvActivityDisplayMode) {
        prefs.edit().putString(KEY_MODE, mode.wire).apply()
    }

    companion object {
        /** Файл настроек режима показа хода (паритет с мобильным «mars-activity-mode»). */
        const val PREFS_FILE = "mars-activity-mode"
        const val KEY_MODE = "activity_display_mode"
    }
}
