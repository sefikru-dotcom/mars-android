package ru.mars.mobile.offline

import android.content.Context
import ru.mars.mobile.AuthExitStores
import ru.mars.mobile.PrefsPendingMessageStore
import ru.mars.network.cache.OfflineCacheTtl
import ru.mars.network.cache.SessionOfflineCache
import java.util.concurrent.Executors

/**
 * Единственная точка доступа к офлайн-хранилищам мобильного клиента.
 *
 * Все экраны получают очередь и кэш отсюда, а не собирают SharedPreferences сами: иначе
 * ChatActivity и список диалогов разошлись бы по разным носителям (очередь в prefs, кэш в
 * SQLite) и logout чистил бы только половину. Здесь же живёт миграция данных, записанных
 * предыдущей версией клиента.
 *
 * Контракт:
 * - один экземпляр [OfflineDatabase] на процесс ([database]) — SQLite не любит несколько
 *   helper'ов на один файл;
 * - [pendingMessageStore] / [keyValueStore] — дешёвые обёртки, их можно создавать на экран;
 * - [sessionCache] уже настроен на TTL ([OfflineCacheTtl.DEFAULT_MILLIS]): просроченный
 *   снимок не показывается как «текущая переписка»;
 * - [warmUp] открывает базу в фоне на старте приложения, чтобы первое чтение кэша в
 *   onCreate не делало диск ввода-вывода на главной ветке;
 * - [clearAll] — полная очистка (logout): очередь + кэш одной транзакцией.
 */
object OfflineStores {

    @Volatile
    private var instance: OfflineDatabase? = null

    /** Фоновый прогрев: один поток на процесс, демон (не держит выход из приложения). */
    private val warmUpExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mars-offline-warmup").apply { isDaemon = true }
    }

    /** Открытая база офлайн-слоя (с миграцией legacy-prefs при первом открытии). */
    fun database(context: Context): OfflineDatabase {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: OfflineDatabase(context).also { db ->
                instance = db
                // Миграция — best-effort: сбой не должен мешать открыть пустую базу.
                runCatching { migrateLegacy(context.applicationContext, db) }
            }
        }
    }

    /** Очередь неотправленных сообщений на SQLite. */
    fun pendingMessageStore(context: Context): SqlitePendingMessageStore =
        SqlitePendingMessageStore(database(context))

    /** Key-value хранилище офлайн-кэша на SQLite. */
    fun keyValueStore(context: Context): SqliteKeyValueStore = SqliteKeyValueStore(database(context))

    /**
     * Кэш диалогов с TTL по умолчанию: снимок старше [OfflineCacheTtl.DEFAULT_MILLIS]
     * не отдаётся (экран покажет «нет копии», а не прошлогоднюю переписку).
     */
    fun sessionCache(
        context: Context,
        clock: () -> Long = { System.currentTimeMillis() },
    ): SessionOfflineCache = SessionOfflineCache(
        store = keyValueStore(context),
        clock = clock,
        maxAgeMillis = OfflineCacheTtl.DEFAULT_MILLIS,
    )

    /** Полная очистка офлайн-данных (logout/реавторизация). */
    fun clearAll(context: Context) {
        runCatching { database(context).clearAll() }
    }

    /** Открывает базу в фоне (вызывается из Application.onCreate). */
    fun warmUp(context: Context) {
        val appContext = context.applicationContext
        runCatching { warmUpExecutor.execute { runCatching { database(appContext) } } }
    }

    /** Закрывает базу и забывает экземпляр (тесты/полный выход из аккаунта). */
    fun close() {
        synchronized(this) {
            runCatching { instance?.close() }
            instance = null
        }
    }

    /**
     * Переносит данные предыдущей версии клиента (SharedPreferences) в SQLite.
     *
     * Без миграции обновление приложения стирало бы очередь неотправленных сообщений и
     * последний снимок диалогов — ровно то, ради чего офлайн-слой существует. Маркеры
     * в `offline_kv` делают миграцию одноразовой; legacy-файлы после переноса очищаются,
     * чтобы приватные тексты не лежали в двух местах.
     */
    private fun migrateLegacy(context: Context, db: OfflineDatabase) {
        val kv = SqliteKeyValueStore(db)

        if (kv.getString(MIGRATION_QUEUE_KEY) == null) {
            val legacy = PrefsPendingMessageStore(
                context.getSharedPreferences(AuthExitStores.PENDING_QUEUE_PREFS, Context.MODE_PRIVATE),
            )
            val pending = legacy.load()
            val queue = SqlitePendingMessageStore(db)
            if (pending.isNotEmpty() && queue.count() == 0) queue.save(pending)
            kv.putString(MIGRATION_QUEUE_KEY, "1")
            legacy.clearAll()
        }

        if (kv.getString(MIGRATION_CACHE_KEY) == null) {
            val prefs = context.getSharedPreferences(AuthExitStores.OFFLINE_CACHE_PREFS, Context.MODE_PRIVATE)
            // Кэш сессий писал только строки; нестроковые значения (если появятся) не трогаем.
            prefs.all.forEach { (key, value) ->
                if (value is String && kv.getString(key) == null) kv.putString(key, value)
            }
            kv.putString(MIGRATION_CACHE_KEY, "1")
            prefs.edit().clear().apply()
        }
    }

    private const val MIGRATION_QUEUE_KEY = "migrated:queue:v1"
    private const val MIGRATION_CACHE_KEY = "migrated:cache:v1"
}
