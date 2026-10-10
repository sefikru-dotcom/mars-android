package ru.mars.network.cache

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import ru.mars.network.model.TodoItem
import ru.mars.network.model.TodoSnapshot
import ru.mars.network.model.TodoSummary

/**
 * Офлайн-кэш снимков `todo_state` ([TodoSnapshot]).
 *
 * Назначение то же, что у [SessionOfflineCache]/[KnowledgeOfflineCache]: репозитории на
 * неудачном HTTP отвечают пустым результатом, и без кэша сбой сети неотличим от «задач
 * нет» — экран показывает пустоту как норму. Кэш отдаёт последний снимок списка задач
 * (глобальный или привязанный к сессии), а [getSnapshotTimestamp] позволяет подписать
 * возраст данных («снимок на …»).
 *
 * Контракт (повторяет [SessionOfflineCache]):
 * - Класс чистый: зависит только от порта [KeyValueStore], без Android-фреймворка и сети —
 *   полностью покрыт JVM unit-тестами.
 * - Ничего не бросает: битый JSON, чужая версия формата или падающее хранилище дают
 *   «данных нет» (null/false), а не crash; следующая успешная запись перетирает мусор.
 * - Объём ограничен: не более [MAX_CACHED_TODOS] задач в снимке, длинные строки
 *   (контент задачи, id, статус) усекаются — SharedPreferences не место для мегабайтов.
 * - Сессионных снимков хранится не более [MAX_CACHED_SESSIONS] штук: порядок использования
 *   ведёт LRU-индекс (сохранение и чтение снимка продвигают сессию в голову), самый давно
 *   не используемый снимок вытесняется вместе с данными. Глобальный снимок
 *   ([sessionId] == null или пробельный) живёт в фиксированном слоте и в LRU не участвует —
 *   он один и просто перезаписывается.
 * - Данные приватные: кэш стирается при выходе из аккаунта ([clear]) вместе с остальными
 *   локальными plaintext-хранилищами.
 * - Рантайм-поля живого конверта ([TodoSnapshot.streamId], [TodoSnapshot.source])
 *   намеренно НЕ кэшируются: за время офлайна они гарантированно устаревают
 *   (аналог [ru.mars.network.model.SessionItem.activeTurn] в [SessionOfflineCache]).
 *   [TodoSnapshot.sessionId] восстанавливается из ключа слота.
 *
 * Не потокобезопасен: вызывать из одного места (экран/репозиторий-координатор), как
 * и [SessionOfflineCache].
 */
class TodosOfflineCache(
    private val store: KeyValueStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Сохраняет снимок задач. Пробельный/пустой [sessionId] нормализуется в null
     * (глобальный слот); сессионный снимок дополнительно продвигается в голову LRU.
     * false — хранилище не приняло запись.
     */
    fun saveTodos(sessionId: String?, snapshot: TodoSnapshot): Boolean {
        val slot = normalizeSessionId(sessionId)
        return runCatching {
            store.putString(snapshotKey(slot), mapAdapter.toJson(snapshotToEnvelope(slot, snapshot)))
            if (slot != null) touchLru(slot)
            true
        }.getOrDefault(false)
    }

    /**
     * Сохранённый снимок задач; null — кэша нет или данные битые. Успешное чтение
     * сессионного снимка продвигает его в LRU (признак «сессию недавно открывали»).
     */
    fun loadTodos(sessionId: String?): TodoSnapshot? {
        val slot = normalizeSessionId(sessionId)
        val snapshot = runCatching { readSnapshot(slot) }.getOrNull()
        if (snapshot != null && slot != null) touchLru(slot)
        return snapshot
    }

    /**
     * Момент сохранения снимка (epoch millis) для расчёта возраста кэша; null — снимка
     * нет или данные битые. LRU не продвигает: это служебное чтение метаданных,
     * а не открытие списка задач.
     */
    fun getSnapshotTimestamp(sessionId: String?): Long? = runCatching {
        val raw = store.getString(snapshotKey(normalizeSessionId(sessionId))) ?: return null
        val body = readEnvelope(raw) ?: return null
        (body["saved_at"] as? Number)?.toLong()
    }.getOrNull()

    /** Полное стирание кэша — вызывается на всех путях выхода из авторизации. */
    fun clear() {
        runCatching { store.clearAll() }
    }

    // --- LRU-индекс сессионных снимков -----------------------------------------------------------

    /**
     * Продвигает сессию в голову LRU-индекса; снимки сверх [MAX_CACHED_SESSIONS]
     * вытесняются (данные удаляются из хранилища, начиная с самых давно не используемых).
     */
    private fun touchLru(sessionId: String) {
        val order = listOf(sessionId) + readLru().filter { it != sessionId }
        order.drop(MAX_CACHED_SESSIONS).forEach { evicted ->
            runCatching { store.remove(snapshotKey(evicted)) }
        }
        writeLru(order.take(MAX_CACHED_SESSIONS))
    }

    /**
     * Порядок использования сессионных снимков (свежий первым). Битая или отсутствующая
     * служебная запись не ошибка и не потеря данных: индекс восстанавливается
     * перечислением ключей хранилища ([KeyValueStore.keys]) по префиксу.
     */
    private fun readLru(): List<String> {
        val parsed = runCatching { store.getString(KEY_LRU)?.let { listAdapter.fromJson(it) } }.getOrNull()
        if (parsed != null) return parsed.filter { it.isNotBlank() }
        return runCatching {
            store.keys()
                .filter { it.startsWith(KEY_SESSION_PREFIX) }
                .map { it.removePrefix(KEY_SESSION_PREFIX) }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun writeLru(order: List<String>) {
        runCatching { store.putString(KEY_LRU, listAdapter.toJson(order)) }
    }

    // --- сериализация ----------------------------------------------------------------------------

    private fun snapshotToEnvelope(slot: String?, snapshot: TodoSnapshot): Map<String, Any?> = mapOf(
        "v" to CACHE_VERSION,
        "saved_at" to clock(),
        "session_id" to slot,
        "todos" to snapshot.todos.take(MAX_CACHED_TODOS).map { todoToRow(it) },
        "summary" to mapOf(
            "total" to snapshot.summary.total,
            "pending" to snapshot.summary.pending,
            "in_progress" to snapshot.summary.inProgress,
            "completed" to snapshot.summary.completed,
            "cancelled" to snapshot.summary.cancelled,
        ),
        "version" to snapshot.version,
        "ts" to snapshot.tsEpochSeconds,
    )

    /** Разбор конверта; null — мусор, чужая версия или не-объект (данные считаются отсутствующими). */
    private fun readEnvelope(raw: String): Map<String, Any?>? {
        val parsed = mapAdapter.fromJson(raw) ?: return null
        val version = (parsed["v"] as? Number)?.toInt()
        if (version != CACHE_VERSION) return null
        return parsed
    }

    private fun readSnapshot(slot: String?): TodoSnapshot? {
        val raw = store.getString(snapshotKey(slot)) ?: return null
        val body = readEnvelope(raw) ?: return null
        val rows = body["todos"] as? List<*> ?: return null
        val summary = body["summary"] as? Map<*, *>
        return TodoSnapshot(
            todos = rows.mapNotNull { rowToTodo(it) },
            summary = TodoSummary(
                total = summary?.int("total") ?: 0,
                pending = summary?.int("pending") ?: 0,
                inProgress = summary?.int("in_progress") ?: 0,
                completed = summary?.int("completed") ?: 0,
                cancelled = summary?.int("cancelled") ?: 0,
            ),
            version = (body["version"] as? Number)?.toInt() ?: 1,
            tsEpochSeconds = (body["ts"] as? Number)?.toDouble(),
            sessionId = slot,
            streamId = null,
            source = null,
        )
    }

    private fun todoToRow(item: TodoItem): Map<String, Any?> = mapOf(
        "id" to item.id?.take(MAX_ID_CHARS),
        "content" to item.content.take(MAX_TODO_TEXT_CHARS),
        "status" to item.status.take(MAX_STATUS_CHARS),
    )

    private fun rowToTodo(row: Any?): TodoItem? {
        val map = row as? Map<*, *> ?: return null
        return TodoItem(
            id = map["id"] as? String,
            content = map["content"] as? String ?: "",
            status = map["status"] as? String ?: "pending",
        )
    }

    private fun Map<*, *>.int(key: String): Int? = (this[key] as? Number)?.toInt()

    /** Пробельный/пустой id неотличим от отсутствия: нормализуем в глобальный слот (null). */
    private fun normalizeSessionId(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    private fun snapshotKey(slot: String?): String =
        if (slot == null) KEY_GLOBAL else KEY_SESSION_PREFIX + slot

    private companion object {
        const val CACHE_VERSION = 1

        /** Фиксированный слот глобального снимка (sessionId == null); в LRU не участвует. */
        const val KEY_GLOBAL = "todos_global"
        const val KEY_SESSION_PREFIX = "todos:"

        /** Служебный ключ LRU-индекса сессионных снимков (JSON-список id, свежий первым). */
        const val KEY_LRU = "todos_lru"

        /** Сколько задач хранится в одном снимке (хвост отбрасывается). */
        const val MAX_CACHED_TODOS = 200

        /** Сколько сессионных снимков хранится одновременно (LRU-вытеснение). */
        const val MAX_CACHED_SESSIONS = 20

        /** Потолки длины полей — приватное хранилище не должно раздуваться. */
        const val MAX_TODO_TEXT_CHARS = 500
        const val MAX_ID_CHARS = 128
        const val MAX_STATUS_CHARS = 32

        val MAP_TYPE = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        val LIST_TYPE = Types.newParameterizedType(List::class.java, String::class.java)
        val moshi = Moshi.Builder().build()
        val mapAdapter = moshi.adapter<Map<String, Any?>>(MAP_TYPE)
        val listAdapter = moshi.adapter<List<String>>(LIST_TYPE)
    }
}
