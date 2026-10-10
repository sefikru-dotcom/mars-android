package ru.mars.network.cache

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import ru.mars.network.model.SessionItem
import ru.mars.network.model.SessionMessage

/**
 * Офлайн-кэш списка сессий и последних сообщений диалогов.
 *
 * Назначение: при сбое сети (репозитории на неудачном HTTP отвечают пустым результатом,
 * а не исключением) экран показывает последний сохранённый снимок вместо пустого списка,
 * а открытый диалог — сохранённую копию транскрипта.
 *
 * Контракт:
 * - Класс чистый: зависит только от порта [KeyValueStore], без Android-фреймворка и сети —
 *   полностью покрыт JVM unit-тестами.
 * - Ничего не бросает: битый JSON, чужая версия формата или падающее хранилище дают
 *   «данных нет» (null/false), а не crash; следующая успешная запись перетирает мусор.
 * - Объём ограничен: не более [MAX_CACHED_SESSIONS] сессий (серверный список уже
 *   отсортирован от свежих к старым, поэтому хвост отбрасывается), не более
 *   [MAX_CACHED_MESSAGES_PER_SESSION] последних сообщений на диалог, длинные строки
 *   усекаются — SharedPreferences не место для мегабайтов.
 * - Данные приватные: кэш стирается при выходе из аккаунта ([clear]) вместе с остальными
 *   локальными plaintext-хранилищами.
 * - [SessionItem.activeTurn] намеренно НЕ кэшируется: это рантайм-метаданные живого хода
 *   (stream_id, очередь сервера, attention), за время офлайна они гарантированно устаревают.
 *   Кэшированная строка загружается с idle-состоянием — решение о reattach принимает только
 *   живой ответ сервера.
 */
class SessionOfflineCache(
    private val store: KeyValueStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Сохраняет снимок списка сессий; false — хранилище не приняло запись. */
    fun saveSessions(items: List<SessionItem>): Boolean = runCatching {
        val rows = items.take(MAX_CACHED_SESSIONS).map { sessionToRow(it) }
        store.putString(
            KEY_SESSIONS,
            mapAdapter.toJson(envelope(mapOf("sessions" to rows))),
        )
        true
    }.getOrDefault(false)

    /** Сохранённый снимок сессий; null — кэша нет, пустой список или данные битые. */
    fun loadSessions(): CachedSessions? = runCatching {
        val body = readEnvelope(store.getString(KEY_SESSIONS) ?: return null) ?: return null
        val rows = body["sessions"] as? List<*> ?: return null
        CachedSessions(
            savedAtEpochMillis = (body["saved_at"] as? Number)?.toLong() ?: 0L,
            sessions = rows.mapNotNull { rowToSession(it) },
        )
    }.getOrNull()

    /**
     * Единая точка решения «свежие данные или кэш» для списка сессий:
     * непустой свежий ответ сохраняется и возвращается; пустой (сбой сети или реально
     * нет диалогов) заменяется последним кэшем, а без кэша — пустым списком.
     */
    fun reconcile(fresh: List<SessionItem>): List<SessionItem> {
        if (fresh.isNotEmpty()) {
            saveSessions(fresh)
            return fresh
        }
        return loadSessions()?.sessions ?: emptyList()
    }

    /**
     * Сохраняет последние сообщения диалога (хвост не длиннее
     * [MAX_CACHED_MESSAGES_PER_SESSION]); false — хранилище не приняло запись.
     */
    fun saveMessages(sessionId: String, messages: List<SessionMessage>): Boolean {
        if (sessionId.isBlank()) return false
        return runCatching {
            val rows = messages.takeLast(MAX_CACHED_MESSAGES_PER_SESSION).map { messageToRow(it) }
            store.putString(
                messagesKey(sessionId),
                mapAdapter.toJson(
                    envelope(mapOf("session_id" to sessionId, "messages" to rows)),
                ),
            )
            true
        }.getOrDefault(false)
    }

    /** Сохранённые сообщения диалога; null — кэша нет или данные битые. */
    fun loadMessages(sessionId: String): List<SessionMessage>? {
        if (sessionId.isBlank()) return null
        return runCatching {
            val body = readEnvelope(store.getString(messagesKey(sessionId)) ?: return null) ?: return null
            val rows = body["messages"] as? List<*> ?: return null
            rows.mapNotNull { rowToMessage(it) }
        }.getOrNull()
    }

    /** Удаляет кэш сообщений одного диалога (сессия удалена/завершена). */
    fun removeMessages(sessionId: String) {
        if (sessionId.isBlank()) return
        runCatching { store.remove(messagesKey(sessionId)) }
    }

    /** Полное стирание кэша — вызывается на всех путях выхода из авторизации. */
    fun clear() {
        runCatching { store.clearAll() }
    }

    private fun envelope(extra: Map<String, Any?>): Map<String, Any?> =
        mapOf("v" to CACHE_VERSION, "saved_at" to clock()) + extra

    /** Разбор конверта; null — мусор, чужая версия или не-объект (данные считаются отсутствующими). */
    private fun readEnvelope(raw: String): Map<String, Any?>? {
        val parsed = mapAdapter.fromJson(raw) ?: return null
        val version = (parsed["v"] as? Number)?.toInt()
        if (version != CACHE_VERSION) return null
        return parsed
    }

    private fun sessionToRow(item: SessionItem): Map<String, Any?> = mapOf(
        "id" to item.id,
        "title" to item.title?.take(MAX_TITLE_CHARS),
        "preview" to item.preview?.take(MAX_PREVIEW_CHARS),
        "created_at" to item.createdAt,
        "updated_at" to item.updatedAt,
        "message_count" to item.messageCount,
        "session_source" to item.sessionSource,
        "source_tag" to item.sourceTag,
        "raw_source" to item.rawSource,
        "source_label" to item.sourceLabel,
        "read_only" to item.readOnly,
        "parent_session_id" to item.parentSessionId,
    )

    /** Строка без id не восстанавливается в [SessionItem] и отбрасывается. */
    private fun rowToSession(row: Any?): SessionItem? {
        val map = row as? Map<*, *> ?: return null
        val id = map["id"] as? String ?: return null
        if (id.isBlank()) return null
        return SessionItem(
            id = id,
            title = map.stringOrNull("title"),
            preview = map.stringOrNull("preview"),
            createdAt = map.stringOrNull("created_at"),
            updatedAt = map.stringOrNull("updated_at"),
            messageCount = (map["message_count"] as? Number)?.toInt(),
            sessionSource = map.stringOrNull("session_source"),
            sourceTag = map.stringOrNull("source_tag"),
            rawSource = map.stringOrNull("raw_source"),
            sourceLabel = map.stringOrNull("source_label"),
            readOnly = map["read_only"] as? Boolean ?: false,
            parentSessionId = map.stringOrNull("parent_session_id"),
        )
    }

    private fun messageToRow(message: SessionMessage): Map<String, Any?> = mapOf(
        "id" to message.id,
        "role" to message.role,
        "content" to message.content.take(MAX_CONTENT_CHARS),
        "created_at" to message.createdAt,
        "event_id" to message.eventId,
    )

    private fun rowToMessage(row: Any?): SessionMessage? {
        val map = row as? Map<*, *> ?: return null
        return SessionMessage(
            id = map.stringOrNull("id"),
            role = map.string("role").ifBlank { "assistant" },
            content = map.string("content"),
            createdAt = map.stringOrNull("created_at"),
            eventId = map.stringOrNull("event_id"),
        )
    }

    private fun Map<*, *>.string(key: String): String = this[key] as? String ?: ""

    private fun Map<*, *>.stringOrNull(key: String): String? = this[key] as? String

    private fun messagesKey(sessionId: String): String = KEY_MESSAGES_PREFIX + sessionId

    private companion object {
        const val CACHE_VERSION = 1
        const val KEY_SESSIONS = "sessions"
        const val KEY_MESSAGES_PREFIX = "messages:"

        /** Сколько сессий хранится (список сервера newest-first — хвост наименее ценен). */
        const val MAX_CACHED_SESSIONS = 100

        /** Сколько последних сообщений диалога хранится. */
        const val MAX_CACHED_MESSAGES_PER_SESSION = 50

        /** Потолки длины полей — приватное хранилище не должно раздуваться. */
        const val MAX_TITLE_CHARS = 200
        const val MAX_PREVIEW_CHARS = 300
        const val MAX_CONTENT_CHARS = 8_000

        val MAP_TYPE = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        val moshi = Moshi.Builder().build()
        val mapAdapter = moshi.adapter<Map<String, Any?>>(MAP_TYPE)
    }
}

/** Снимок кэшированного списка сессий с моментом сохранения (для подписи «данные на …»). */
data class CachedSessions(
    val savedAtEpochMillis: Long,
    val sessions: List<SessionItem>,
)
