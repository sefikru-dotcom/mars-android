package ru.mars.network.cache

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import ru.mars.network.model.ActiveTurnInfo
import ru.mars.network.model.SessionAttention
import ru.mars.network.model.SessionItem

/**
 * Персистентность офлайн-состояния сессий: снимки списка сессий и состояния активного
 * хода (active turn) в [KeyValueStore] с метаданными времени и TTL-валидацией.
 *
 * Зачем отдельно от [SessionOfflineCache]: кэш намеренно НЕ хранит [SessionItem.activeTurn]
 * (стрим-метаданные устаревают мгновенно), а этой политике нужно пережить смерть процесса —
 * после перезапуска приложения клиент решает, можно ли сделать reattach к стриму
 * ([ActiveTurnInfo.canReattach]), не дожидаясь сети. Устаревание здесь контролируется не
 * «никогда», а честным TTL: снимок старше [ttlMillis] считается протухшим и удаляется.
 *
 * Контракт (тот же, что у остальных кэшей модуля):
 * - Класс чистый: только порт [KeyValueStore] + Moshi, без Android-фреймворка и сети —
 *   полностью покрыт JVM unit-тестами.
 * - Ничего не бросает: битый JSON, чужая версия формата, невозможный timestamp или
 *   падающее хранилище дают «данных нет» (null/false), а не crash. Повреждённая запись
 *   при чтении удаляется — следующая успешная запись перетирает мусор (recovery).
 * - Частичное повреждение переживает целиком: из списка строк выживают те, что удалось
 *   разобрать; полностью неразбираемый конверт отбрасывается целиком.
 * - Ключи политики не пересекаются с ключами [SessionOfflineCache] (`sessions`,
 *   `messages:`, `msg_lru`) — префикс [KEY_PREFIX]; [clear] удаляет ТОЛЬКО свои записи.
 * - Данные приватные: на logout хранилище стирается целиком (clearAll у адаптера),
 *   записи этой политики уходят вместе с ним.
 *
 * Формат записи (конверт): `{"v": 1, "saved_at": <epoch millis>, "ttl_ms": <ttl>,
 * "sessions": [...]}` для списка и `{"v": 1, ..., "session_id": ..., "active_turn": {...}}`
 * для активного хода. В отличие от офлайн-кэша строка сессии здесь сохраняет activeTurn.
 */
class OfflineSessionPersistencePolicy(
    private val store: KeyValueStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Срок жизни снимка; прочитанное старше этого возраста считается протухшим. */
    ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {

    /** Валидный TTL из конструктора (мс); отрицательный/нулевой запрещает чтение снимков. */
    val ttlMillis: Long = ttlMillis.coerceAtLeast(0L)

    /** Снимок списка сессий с метаданными времени сохранения. */
    data class SessionSnapshot(
        val savedAtEpochMillis: Long,
        val sessions: List<SessionItem>,
    )

    /** Снимок активного хода одной сессии с метаданными времени сохранения. */
    data class ActiveTurnSnapshot(
        val sessionId: String,
        val savedAtEpochMillis: Long,
        val turn: ActiveTurnInfo,
    )

    // --- список сессий --------------------------------------------------------------------------

    /**
     * Сохраняет снимок списка сессий (хвост сверх [MAX_SNAPSHOT_SESSIONS] отбрасывается —
     * SharedPreferences не место для мегабайтов). false — хранилище не приняло запись.
     */
    fun saveSessions(sessions: List<SessionItem>): Boolean = runCatching {
        val rows = sessions.take(MAX_SNAPSHOT_SESSIONS).map { sessionToRow(it) }
        store.putString(KEY_SESSIONS, mapAdapter.toJson(envelope(mapOf("sessions" to rows))))
        true
    }.getOrDefault(false)

    /**
     * Загружает снимок списка сессий. null — записи нет, она протухла по TTL, повреждена
     * или хранилище недоступно; протухшие/битые записи при этом удаляются из хранилища.
     */
    fun loadSessions(): SessionSnapshot? = runCatching {
        val raw = store.getString(KEY_SESSIONS) ?: return null
        val (savedAt, body) = validateEnvelope(raw, KEY_SESSIONS) ?: return null
        val rows = body["sessions"] as? List<*> ?: run {
            store.remove(KEY_SESSIONS)
            return null
        }
        SessionSnapshot(
            savedAtEpochMillis = savedAt,
            sessions = rows.mapNotNull { rowToSession(it) },
        )
    }.getOrNull()

    // --- активный ход ---------------------------------------------------------------------------

    /**
     * Сохраняет состояние активного хода сессии (stream id, стриминг, очередь, attention).
     * Пустой [sessionId] отклоняется; false — хранилище не приняло запись.
     */
    fun saveActiveTurn(sessionId: String, turn: ActiveTurnInfo): Boolean {
        if (sessionId.isBlank()) return false
        return runCatching {
            val key = turnKey(sessionId)
            store.putString(
                key,
                mapAdapter.toJson(
                    envelope(
                        mapOf("session_id" to sessionId, "active_turn" to turnToRow(turn)),
                    ),
                ),
            )
            true
        }.getOrDefault(false)
    }

    /**
     * Загружает снимок активного хода сессии. null — нет записи, протухла по TTL, бита
     * или [sessionId] пустой; протухшие/битые записи удаляются (recovery).
     */
    fun loadActiveTurn(sessionId: String): ActiveTurnSnapshot? {
        if (sessionId.isBlank()) return null
        return runCatching {
            val key = turnKey(sessionId)
            val raw = store.getString(key) ?: return null
            val (savedAt, body) = validateEnvelope(raw, key) ?: return null
            val turn = rowToTurn(body["active_turn"]) ?: run {
                store.remove(key)
                return null
            }
            ActiveTurnSnapshot(sessionId = sessionId, savedAtEpochMillis = savedAt, turn = turn)
        }.getOrNull()
    }

    /** Удаляет снимок активного хода одной сессии (ход завершился/сессия удалена). */
    fun clearActiveTurn(sessionId: String) {
        if (sessionId.isBlank()) return
        runCatching { store.remove(turnKey(sessionId)) }
    }

    // --- гигиена --------------------------------------------------------------------------------

    /**
     * Удаляет ВСЕ записи этой политики (снимок списка + снимки ходов всех сессий),
     * не трогая чужие ключи того же хранилища (например, кэши [SessionOfflineCache]).
     */
    fun clear() {
        runCatching {
            val own = store.keys().filter { it.startsWith(KEY_PREFIX) }
            own.forEach { store.remove(it) }
        }
    }

    // --- конверт и TTL --------------------------------------------------------------------------

    private fun envelope(extra: Map<String, Any?>): Map<String, Any?> =
        mapOf("v" to FORMAT_VERSION, "saved_at" to clock(), "ttl_ms" to ttlMillis) + extra

    /**
     * Разбирает и валидирует конверт: формат, версия, метка времени, TTL. Любая проблема —
     * запись удаляется из хранилища и возвращается null (corrupt/stale data recovery).
     */
    private fun validateEnvelope(raw: String, key: String): Pair<Long, Map<String, Any?>>? {
        val parsed = runCatching { mapAdapter.fromJson(raw) }.getOrNull()
        if (parsed == null || (parsed["v"] as? Number)?.toInt() != FORMAT_VERSION) {
            store.remove(key)
            return null
        }
        val savedAt = (parsed["saved_at"] as? Number)?.toLong()
        if (savedAt == null) {
            store.remove(key)
            return null
        }
        val age = clock() - savedAt
        val effectiveTtl = (parsed["ttl_ms"] as? Number)?.toLong()?.coerceAtLeast(0L) ?: ttlMillis
        // Протух по TTL. Отрицательный возраст дальше допуска — часы уехали в будущее
        // (перевод времени, сбой NTP): снимку нельзя верить, считаем повреждённым.
        if (age > effectiveTtl || age < -CLOCK_SKEW_TOLERANCE_MS) {
            store.remove(key)
            return null
        }
        return savedAt to parsed
    }

    // --- маппинг строк (без рефлексии, по конвенции модуля) ---------------------------------------

    private fun sessionToRow(item: SessionItem): Map<String, Any?> = mapOf(
        "id" to item.id,
        "title" to item.title,
        "preview" to item.preview,
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
        "active_turn" to turnToRow(item.activeTurn),
    )

    private fun rowToSession(row: Any?): SessionItem? {
        if (row !is Map<*, *>) return null
        val id = (row["id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return SessionItem(
            id = id,
            title = row["title"] as? String,
            preview = row["preview"] as? String,
            createdAt = row["created_at"] as? String,
            updatedAt = row["updated_at"] as? String,
            messageCount = (row["message_count"] as? Number)?.toInt(),
            activeTurn = rowToTurn(row["active_turn"]) ?: ActiveTurnInfo(),
            sessionSource = row["session_source"] as? String,
            sourceTag = row["source_tag"] as? String,
            rawSource = row["raw_source"] as? String,
            sourceLabel = row["source_label"] as? String,
            readOnly = (row["read_only"] as? Boolean) ?: false,
            parentSessionId = row["parent_session_id"] as? String,
            model = row["model"] as? String,
            modelProvider = row["model_provider"] as? String,
        )
    }

    private fun turnToRow(turn: ActiveTurnInfo): Map<String, Any?> = mapOf(
        "active_stream_id" to turn.activeStreamId,
        "is_streaming" to turn.isStreaming,
        "has_pending_user_message" to turn.hasPendingUserMessage,
        "pending_started_at" to turn.pendingStartedAtEpochSeconds,
        "attention" to turn.attention?.let {
            mapOf("kind" to it.kind, "count" to it.count, "severity" to it.severity)
        },
    )

    /** null — значение отсутствует или не похоже на объект хода (мусор игнорируется). */
    private fun rowToTurn(value: Any?): ActiveTurnInfo? {
        if (value == null) return null
        if (value !is Map<*, *>) return null
        return ActiveTurnInfo(
            activeStreamId = value["active_stream_id"] as? String,
            isStreaming = (value["is_streaming"] as? Boolean) ?: false,
            hasPendingUserMessage = (value["has_pending_user_message"] as? Boolean) ?: false,
            pendingStartedAtEpochSeconds = (value["pending_started_at"] as? Number)?.toDouble(),
            attention = rowToAttention(value["attention"]),
        )
    }

    private fun rowToAttention(value: Any?): SessionAttention? {
        if (value !is Map<*, *>) return null
        val kind = value["kind"] as? String ?: return null
        return SessionAttention(
            kind = kind,
            count = (value["count"] as? Number)?.toInt() ?: 0,
            severity = value["severity"] as? String,
        )
    }

    private fun turnKey(sessionId: String): String = KEY_TURN_PREFIX + sessionId

    companion object {
        /** Версия формата конверта; чужая версия читается как «данных нет» и удаляется. */
        const val FORMAT_VERSION = 1

        /** Общий префикс ключей политики — не пересекается с ключами [SessionOfflineCache]. */
        const val KEY_PREFIX = "offline_policy:"
        const val KEY_SESSIONS = KEY_PREFIX + "sessions"
        const val KEY_TURN_PREFIX = KEY_PREFIX + "turn:"

        /** TTL снимка по умолчанию: сутки. Дальше стрим-состояние точно не актуально. */
        const val DEFAULT_TTL_MILLIS: Long = 24 * 60 * 60 * 1000L

        /** Допустимый уход часов в будущее при валидации saved_at (мс). */
        const val CLOCK_SKEW_TOLERANCE_MS: Long = 5 * 60 * 1000L

        /** Предел снимка списка сессий — по образцу [SessionOfflineCache.MAX_CACHED_SESSIONS]. */
        const val MAX_SNAPSHOT_SESSIONS = 100

        private val MAP_TYPE =
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        private val moshi = Moshi.Builder().build()
        private val mapAdapter = moshi.adapter<Map<String, Any?>>(MAP_TYPE)
    }
}
