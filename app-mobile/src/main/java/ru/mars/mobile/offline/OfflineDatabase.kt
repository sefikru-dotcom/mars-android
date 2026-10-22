package ru.mars.mobile.offline

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ru.mars.mobile.ClearableLocalStore

/**
 * Единая SQLite-база офлайн-слоя мобильного клиента: очередь неотправленных сообщений
 * и key-value кэш диалогов ([ru.mars.network.cache.SessionOfflineCache]).
 *
 * Почему SQLite, а не SharedPreferences: очередь и кэш — это списки записей с порядком
 * (FIFO) и потолком размера. В prefs любая мутация — перезапись всего JSON-блоба целиком,
 * и смерть процесса посреди записи оставляла битый файл (данные терялись). Здесь каждая
 * мутация — транзакция: либо весь новый снимок очереди, либо прежний.
 *
 * Контракт:
 * - одна база на процесс ([OfflineStores] держит единственный экземпляр);
 * - схема версионируется; [onUpgrade]/[onDowngrade] пересоздают таблицы — очередь и кэш
 *   восстановимые данные (очередь теряется, но альтернатива — краш на старте);
 * - [clearAll] стирает обе таблицы одной транзакцией: logout не оставляет ни одного
 *   неотправленного текста и ни одной копии переписки.
 */
class OfflineDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION),
    ClearableLocalStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_QUEUE (
                $COL_SEQ INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_ID TEXT NOT NULL UNIQUE,
                $COL_TEXT TEXT NOT NULL,
                $COL_SESSION_ID TEXT,
                $COL_ENQUEUED_AT INTEGER NOT NULL DEFAULT 0,
                $COL_ATTEMPTS INTEGER NOT NULL DEFAULT 0,
                $COL_VOICE_MODE INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE $TABLE_KV (
                $COL_KEY TEXT PRIMARY KEY,
                $COL_VALUE TEXT NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    /**
     * Смена схемы: таблицы пересоздаются. Для очереди это потеря неотправленных сообщений,
     * но сохранение несовместимой схемы означало бы падение на каждом чтении; кэш
     * восстанавливается первым успешным ответом сервера.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_QUEUE")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_KV")
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onUpgrade(db, oldVersion, newVersion)
    }

    /** Полная очистка офлайн-данных (logout/реавторизация): очередь и кэш одной транзакцией. */
    override fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_QUEUE, null, null)
            db.delete(TABLE_KV, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        /** Имя файла базы в приватном каталоге приложения. */
        const val DATABASE_NAME = "mars-offline.db"

        /** Версия схемы: 1 — очередь + key-value кэш. */
        const val DATABASE_VERSION = 1

        const val TABLE_QUEUE = "pending_messages"
        const val TABLE_KV = "offline_kv"

        const val COL_SEQ = "seq"
        const val COL_ID = "id"
        const val COL_TEXT = "text"
        const val COL_SESSION_ID = "session_id"
        const val COL_ENQUEUED_AT = "enqueued_at"
        const val COL_ATTEMPTS = "attempts"
        const val COL_VOICE_MODE = "voice_mode"

        const val COL_KEY = "key"
        const val COL_VALUE = "value"
        const val COL_UPDATED_AT = "updated_at"
    }
}
