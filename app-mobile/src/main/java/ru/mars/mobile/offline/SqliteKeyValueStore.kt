package ru.mars.mobile.offline

import android.content.ContentValues
import ru.mars.mobile.ClearableLocalStore
import ru.mars.network.cache.KeyValueStore

/**
 * [KeyValueStore] поверх SQLite ([OfflineDatabase], таблица `offline_kv`).
 *
 * Зачем: [ru.mars.network.cache.SessionOfflineCache] хранит снимок списка диалогов и
 * транскрипты по ключам, а в SharedPreferences каждая запись — перезапись целого файла
 * при следующем commit и никакой атомарности между «сообщения диалога» и «LRU-индекс».
 * В SQLite запись одной строки атомарна и не зависит от размера остального кэша.
 *
 * Контракт [KeyValueStore] сохранён полностью (включая [keys] для восстановления
 * LRU-индекса и [clearAll] для logout), поэтому логика кэша и её тесты не меняются —
 * меняется только носитель. Ошибки БД пробрасываются: кэш уже обёрнут в runCatching
 * на своей стороне и деградирует к «копии нет», а не к падению экрана.
 */
class SqliteKeyValueStore(private val database: OfflineDatabase) : KeyValueStore, ClearableLocalStore {

    override fun getString(key: String): String? = database.readableDatabase.query(
        OfflineDatabase.TABLE_KV,
        arrayOf(OfflineDatabase.COL_VALUE),
        "${OfflineDatabase.COL_KEY} = ?",
        arrayOf(key),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    override fun putString(key: String, value: String) {
        val values = ContentValues().apply {
            put(OfflineDatabase.COL_KEY, key)
            put(OfflineDatabase.COL_VALUE, value)
            put(OfflineDatabase.COL_UPDATED_AT, System.currentTimeMillis())
        }
        database.writableDatabase.insertWithOnConflict(
            OfflineDatabase.TABLE_KV,
            null,
            values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    override fun remove(key: String) {
        database.writableDatabase.delete(
            OfflineDatabase.TABLE_KV,
            "${OfflineDatabase.COL_KEY} = ?",
            arrayOf(key),
        )
    }

    override fun keys(): Set<String> {
        val keys = LinkedHashSet<String>()
        database.readableDatabase.query(
            OfflineDatabase.TABLE_KV,
            arrayOf(OfflineDatabase.COL_KEY),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor -> while (cursor.moveToNext()) keys.add(cursor.getString(0)) }
        return keys
    }

    override fun clearAll() {
        database.writableDatabase.delete(OfflineDatabase.TABLE_KV, null, null)
    }
}
