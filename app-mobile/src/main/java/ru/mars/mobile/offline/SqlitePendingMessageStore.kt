package ru.mars.mobile.offline

import android.content.ContentValues
import android.database.Cursor
import ru.mars.mobile.ClearableLocalStore
import ru.mars.mobile.OfflineMessageQueuePolicy
import ru.mars.mobile.PendingMessageStore
import ru.mars.mobile.QueuedMessage

/**
 * Постоянная очередь неотправленных сообщений поверх SQLite ([OfflineDatabase]).
 *
 * Контракт [PendingMessageStore] (политика очереди живёт в
 * [ru.mars.mobile.OfflineMessageQueuePolicy] и не знает про БД):
 * - порядок FIFO задаёт `seq` (AUTOINCREMENT): он же переживает перезапуск процесса,
 *   поэтому очередь после смерти приложения отправляется в исходном порядке;
 * - [save] — полная перезапись снимка одной транзакцией: смерть процесса посреди записи
 *   оставляет либо старый, либо новый снимок, но не половину очереди;
 * - потолок [OfflineMessageQueuePolicy.DEFAULT_MAX_QUEUE_SIZE] применяется и здесь:
 *   очередь, записанная другим клиентом/старой версией, читается подрезанной до лимита
 *   (вытесняются самые старые), а не грузится целиком в память;
 * - [clearAll] стирает таблицу целиком (logout: ни одного неотправленного текста не остаётся).
 *
 * Все операции защищены runCatching: недоступная БД (диск, повреждённый файл) не должна
 * ронять экран — очередь деградирует до in-memory, кэш до «нет копии».
 */
class SqlitePendingMessageStore(
    private val database: OfflineDatabase,
    private val maxRows: Int = OfflineMessageQueuePolicy.DEFAULT_MAX_QUEUE_SIZE,
) : PendingMessageStore, ClearableLocalStore {

    override fun load(): List<QueuedMessage> = runCatching {
        // Читаем с хвоста: при переполнении нужны САМЫЕ НОВЫЕ записи (старые вытесняются).
        val rows = mutableListOf<QueuedMessageRow>()
        database.readableDatabase.query(
            OfflineDatabase.TABLE_QUEUE,
            null,
            null,
            null,
            null,
            null,
            "${OfflineDatabase.COL_SEQ} DESC",
            maxRows.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) rows.add(cursor.toRow())
        }
        rows.asReversed().mapNotNull { it.toMessage() }
    }.getOrDefault(emptyList())

    override fun save(messages: List<QueuedMessage>) {
        // Хранилище не расширяет политику: лишние (самые старые) записи отбрасываются.
        val bounded = OfflineQueueLimits.bound(messages, maxRows)
        runCatching {
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                db.delete(OfflineDatabase.TABLE_QUEUE, null, null)
                bounded.forEach { message ->
                    db.insertWithOnConflict(
                        OfflineDatabase.TABLE_QUEUE,
                        null,
                        QueuedMessageRow.from(message).toValues(),
                        android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** Число записей в очереди (диагностика/миграция); при недоступной БД — 0. */
    fun count(): Int = runCatching {
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${OfflineDatabase.TABLE_QUEUE}",
            null,
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }.getOrDefault(0)

    override fun clearAll() {
        runCatching { database.writableDatabase.delete(OfflineDatabase.TABLE_QUEUE, null, null) }
    }

    private fun Cursor.toRow(): QueuedMessageRow = QueuedMessageRow(
        id = getString(getColumnIndexOrThrow(OfflineDatabase.COL_ID)),
        text = getString(getColumnIndexOrThrow(OfflineDatabase.COL_TEXT)),
        sessionId = getString(getColumnIndexOrThrow(OfflineDatabase.COL_SESSION_ID)),
        enqueuedAtEpochMillis = getLong(getColumnIndexOrThrow(OfflineDatabase.COL_ENQUEUED_AT)),
        attempts = getInt(getColumnIndexOrThrow(OfflineDatabase.COL_ATTEMPTS)),
        voiceMode = getInt(getColumnIndexOrThrow(OfflineDatabase.COL_VOICE_MODE)) != 0,
    )

    private fun QueuedMessageRow.toValues(): ContentValues = ContentValues().apply {
        put(OfflineDatabase.COL_ID, id)
        put(OfflineDatabase.COL_TEXT, text)
        put(OfflineDatabase.COL_SESSION_ID, sessionId)
        put(OfflineDatabase.COL_ENQUEUED_AT, enqueuedAtEpochMillis)
        put(OfflineDatabase.COL_ATTEMPTS, attempts)
        put(OfflineDatabase.COL_VOICE_MODE, if (voiceMode) 1 else 0)
    }
}
