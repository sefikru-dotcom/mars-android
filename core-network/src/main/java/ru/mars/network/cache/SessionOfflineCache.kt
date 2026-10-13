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
 * - Кэшей сообщений хранится не более [MAX_CACHED_MESSAGE_SESSIONS] штук: порядок
 *   использования ведёт LRU-индекс (сохранение и открытие диалога продвигают его
 *   в голову), самый давно не используемый кэш вытесняется вместе с данными.
 * - Локальный поиск [search]: регистронезависимый подстрочный запрос по заголовку,
 *   id, preview и контенту кэшированных сообщений; сниппет нормализован и ограничен
 *   [MAX_SNIPPET_CHARS] символами, запрос — [MAX_SEARCH_QUERY_CHARS].
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
            touchLru(sessionId)
            true
        }.getOrDefault(false)
    }

    /** Сохранённые сообщения диалога; null — кэша нет или данные битые. */
    fun loadMessages(sessionId: String): List<SessionMessage>? {
        if (sessionId.isBlank()) return null
        val messages = runCatching {
            val body = readEnvelope(store.getString(messagesKey(sessionId)) ?: return null) ?: return null
            val rows = body["messages"] as? List<*> ?: return null
            rows.mapNotNull { rowToMessage(it) }
        }.getOrNull()
        // Успешное чтение — признак «диалог недавно открывали»: продвигаем в LRU.
        if (messages != null) touchLru(sessionId)
        return messages
    }

    /** Удаляет кэш сообщений одного диалога (сессия удалена/завершена). */
    fun removeMessages(sessionId: String) {
        if (sessionId.isBlank()) return
        runCatching { store.remove(messagesKey(sessionId)) }
        dropFromLru(sessionId)
    }

    /** Полное стирание кэша — вызывается на всех путях выхода из авторизации. */
    fun clear() {
        runCatching { store.clearAll() }
    }

    // --- локальный офлайн-поиск ------------------------------------------------------------------

    /**
     * Регистронезависимый подстрочный поиск по кэшированным сессиям: заголовок,
     * идентификатор, preview и — когда в метаданных совпадений нет — контент
     * кэшированных сообщений диалога.
     *
     * Контракт:
     * - порядок результатов — порядок кэша (серверный newest-first), не более [limit] строк;
     * - пустой/пробельный запрос и отсутствие кэша дают пустой список;
     * - запрос длиннее [MAX_SEARCH_QUERY_CHARS] усекается (защита от мегабайтного ввода);
     * - ничего не бросает: сбой хранилища — пустой результат;
     * - поиск НЕ продвигает LRU-индекс: массовое чтение чужих транскриптов ради
     *   сниппета не должно вытеснять недавно открытый диалог.
     */
    fun search(query: String, limit: Int = MAX_SEARCH_RESULTS): List<SessionSearchResult> {
        val q = query.trim().take(MAX_SEARCH_QUERY_CHARS)
        if (q.isEmpty() || limit <= 0) return emptyList()
        val sessions = loadSessions()?.sessions ?: return emptyList()
        return runCatching {
            val results = ArrayList<SessionSearchResult>(minOf(limit, sessions.size))
            for (item in sessions) {
                if (results.size >= limit) break
                val matched = LinkedHashSet<SessionSearchField>()
                if (item.title?.contains(q, ignoreCase = true) == true) matched += SessionSearchField.TITLE
                if (item.id.contains(q, ignoreCase = true)) matched += SessionSearchField.ID
                if (item.preview?.contains(q, ignoreCase = true) == true) matched += SessionSearchField.PREVIEW
                var snippet: String? = null
                if (SessionSearchField.PREVIEW in matched) {
                    snippet = snippetAround(item.preview.orEmpty(), q)
                } else if (matched.isEmpty()) {
                    val hit = readMessagesNoTouch(item.id)
                        ?.firstOrNull { it.content.contains(q, ignoreCase = true) }
                    if (hit != null) {
                        matched += SessionSearchField.MESSAGE
                        snippet = snippetAround(hit.content, q)
                    }
                }
                if (matched.isEmpty()) continue
                if (snippet.isNullOrBlank()) {
                    snippet = item.preview?.takeIf { it.isNotBlank() }?.let { snippetAround(it, q) }
                }
                results += SessionSearchResult(item, snippet?.take(MAX_SNIPPET_CHARS), matched)
            }
            results
        }.getOrDefault(emptyList())
    }

    /** Читает кэш сообщений, не продвигая LRU-индекс (внутренний вход поиска). */
    private fun readMessagesNoTouch(sessionId: String): List<SessionMessage>? {
        if (sessionId.isBlank()) return null
        return runCatching {
            val body = readEnvelope(store.getString(messagesKey(sessionId)) ?: return null) ?: return null
            val rows = body["messages"] as? List<*> ?: return null
            rows.mapNotNull { rowToMessage(it) }
        }.getOrNull()
    }

    /**
     * Нормализованная выдержка вокруг первого совпадения: переводы строк схлопываются
     * в одиночные пробелы, вокруг запроса остаётся [SNIPPET_CONTEXT_CHARS] символов
     * контекста, обрезка помечается «…». Итог не длиннее [MAX_SNIPPET_CHARS].
     */
    private fun snippetAround(text: String, query: String): String {
        val normalized = text.split('\n', '\r')
            .joinToString(" ") { it.trim() }
            .replace(Regex(" {2,}"), " ")
            .trim()
        if (normalized.isEmpty()) return ""
        val index = normalized.indexOf(query, ignoreCase = true)
        val body = if (index < 0) {
            normalized
        } else {
            val start = maxOf(0, index - SNIPPET_CONTEXT_CHARS)
            val end = minOf(normalized.length, index + query.length + SNIPPET_CONTEXT_CHARS)
            (if (start > 0) "…" else "") +
                normalized.substring(start, end) +
                (if (end < normalized.length) "…" else "")
        }
        return if (body.length > MAX_SNIPPET_CHARS) body.take(MAX_SNIPPET_CHARS - 1) + "…" else body
    }

    // --- LRU-индекс кэшей сообщений --------------------------------------------------------------

    /**
     * Продвигает сессию в голову LRU-индекса; кэши сверх [MAX_CACHED_MESSAGE_SESSIONS]
     * вытесняются (данные удаляются из хранилища, начиная с самых давно не используемых).
     */
    private fun touchLru(sessionId: String) {
        val order = listOf(sessionId) + readLru().filter { it != sessionId }
        order.drop(MAX_CACHED_MESSAGE_SESSIONS).forEach { evicted ->
            runCatching { store.remove(messagesKey(evicted)) }
        }
        writeLru(order.take(MAX_CACHED_MESSAGE_SESSIONS))
    }

    private fun dropFromLru(sessionId: String) {
        writeLru(readLru().filter { it != sessionId })
    }

    /**
     * Порядок использования кэшей сообщений (свежий первым). Битая или отсутствующая
     * служебная запись не ошибка и не потеря данных: индекс восстанавливается
     * перечислением ключей хранилища ([KeyValueStore.keys]) по префиксу.
     */
    private fun readLru(): List<String> {
        val parsed = runCatching { store.getString(KEY_LRU)?.let { listAdapter.fromJson(it) } }.getOrNull()
        if (parsed != null) return parsed.filter { it.isNotBlank() }
        return runCatching {
            store.keys()
                .filter { it.startsWith(KEY_MESSAGES_PREFIX) }
                .map { it.removePrefix(KEY_MESSAGES_PREFIX) }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun writeLru(order: List<String>) {
        runCatching { store.putString(KEY_LRU, listAdapter.toJson(order)) }
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
        "model" to item.model,
        "model_provider" to item.modelProvider,
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
            model = map.stringOrNull("model"),
            modelProvider = map.stringOrNull("model_provider"),
        )
    }

    private fun messageToRow(message: SessionMessage): Map<String, Any?> = mapOf(
        "id" to message.id,
        "role" to message.role,
        "content" to message.content.take(MAX_CONTENT_CHARS),
        "created_at" to message.createdAt,
        "event_id" to message.eventId,
        "tool_name" to message.toolName,
        "tool_call_id" to message.toolCallId,
    )

    private fun rowToMessage(row: Any?): SessionMessage? {
        val map = row as? Map<*, *> ?: return null
        return SessionMessage(
            id = map.stringOrNull("id"),
            role = map.string("role").ifBlank { "assistant" },
            content = map.string("content"),
            createdAt = map.stringOrNull("created_at"),
            eventId = map.stringOrNull("event_id"),
            // Старые кэши (до tool-полей) просто вернут null — строки всё равно
            // свернутся в карточку с нейтральным именем на слое отображения.
            toolName = map.stringOrNull("tool_name"),
            toolCallId = map.stringOrNull("tool_call_id"),
        )
    }

    private fun Map<*, *>.string(key: String): String = this[key] as? String ?: ""

    private fun Map<*, *>.stringOrNull(key: String): String? = this[key] as? String

    private fun messagesKey(sessionId: String): String = KEY_MESSAGES_PREFIX + sessionId

    private companion object {
        const val CACHE_VERSION = 1
        const val KEY_SESSIONS = "sessions"
        const val KEY_MESSAGES_PREFIX = "messages:"

        /** Служебный ключ LRU-индекса кэшей сообщений (JSON-список id, свежий первым). */
        const val KEY_LRU = "msg_lru"

        /** Сколько сессий хранится (список сервера newest-first — хвост наименее ценен). */
        const val MAX_CACHED_SESSIONS = 100

        /** Сколько последних сообщений диалога хранится. */
        const val MAX_CACHED_MESSAGES_PER_SESSION = 50

        /** Сколько кэшей сообщений РАЗНЫХ диалогов хранится одновременно (LRU-вытеснение). */
        const val MAX_CACHED_MESSAGE_SESSIONS = 20

        /** Потолки локального поиска: число результатов, длина запроса и сниппета. */
        const val MAX_SEARCH_RESULTS = 50
        const val MAX_SEARCH_QUERY_CHARS = 200
        const val MAX_SNIPPET_CHARS = 200

        /** Контекст (в символах) вокруг совпадения, оставляемый в сниппете. */
        const val SNIPPET_CONTEXT_CHARS = 60

        /** Потолки длины полей — приватное хранилище не должно раздуваться. */
        const val MAX_TITLE_CHARS = 200
        const val MAX_PREVIEW_CHARS = 300
        const val MAX_CONTENT_CHARS = 8_000

        val MAP_TYPE = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        val LIST_TYPE = Types.newParameterizedType(List::class.java, String::class.java)
        val moshi = Moshi.Builder().build()
        val mapAdapter = moshi.adapter<Map<String, Any?>>(MAP_TYPE)
        val listAdapter = moshi.adapter<List<String>>(LIST_TYPE)
    }
}

/** Поле кэшированной сессии, в котором локальный поиск нашёл совпадение. */
enum class SessionSearchField { TITLE, ID, PREVIEW, MESSAGE }

/**
 * Результат локального поиска по офлайн-кэшу ([SessionOfflineCache.search]).
 *
 * [snippet] — короткая нормализованная выдержка вокруг первого совпадения
 * (из preview либо из контента сообщения); null, когда совпали только
 * заголовок/идентификатор, а preview пуст. [matchedIn] перечисляет все поля,
 * давшие совпадение, — UI подсвечивает их без повторного сканирования.
 */
data class SessionSearchResult(
    val session: SessionItem,
    val snippet: String?,
    val matchedIn: Set<SessionSearchField>,
)

/** Снимок кэшированного списка сессий с моментом сохранения (для подписи «данные на …»). */
data class CachedSessions(
    val savedAtEpochMillis: Long,
    val sessions: List<SessionItem>,
)
