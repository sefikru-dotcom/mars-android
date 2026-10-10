package ru.mars.network.cache

import android.content.SharedPreferences

/**
 * Порт строкового key-value хранилища для офлайн-кэша.
 *
 * Кэш ([SessionOfflineCache]) зависит только от этого интерфейса, поэтому вся логика
 * сериализации, лимитов и устойчивости к битым данным покрыта JVM unit-тестами;
 * Android-кодом является только тонкий адаптер [SharedPreferencesKeyValueStore].
 */
interface KeyValueStore {
    /** Значение по ключу; null, если записи нет. */
    fun getString(key: String): String?

    /** Сохраняет/перезаписывает значение по ключу. */
    fun putString(key: String, value: String)

    /** Удаляет запись по ключу (если её нет — no-op). */
    fun remove(key: String)

    /** Стирает всё хранилище (гигиена выхода из аккаунта: приватные данные не переживают logout). */
    fun clearAll()
}

/**
 * [KeyValueStore] поверх приватного SharedPreferences-файла. Записи через apply()
 * (асинхронный диск), очистка через commit() — синхронно, по контракту logout-cleanup:
 * следующая авторизация не должна видеть данные предыдущей даже при мгновенном финише экрана.
 */
class SharedPreferencesKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun clearAll() {
        prefs.edit().clear().commit()
    }
}
