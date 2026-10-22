package ru.mars.mobile

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Очередь сообщений, буферизованных при обрыве связи, с последующей автоотправкой.
 *
 * Решение хоста простое: [NetworkState.sendAllowed] == false → текст уходит в очередь
 * (локальное эхо в транскрипте со статусом PENDING делает ChatUiState.beginQueuedEcho),
 * связь вернулась ([NetworkTransition.restored], открытие диалога, завершение хода) →
 * сообщения текущего диалога отправляются по одному в исходном порядке FIFO.
 *
 * Контракт чистой политики (покрыта JVM-тестами):
 * - очередь постоянна: порт [PendingMessageStore] переживает пересоздание экрана (поворот) и
 *   смерть процесса; основной адаптер — SQLite
 *   ([ru.mars.mobile.offline.SqlitePendingMessageStore]), старый SharedPreferences-адаптер
 *   [PrefsPendingMessageStore] остался для миграции уже сохранённых очередей;
 * - сообщения привязаны к диалогу ([QueuedMessage.sessionId], null — новый чат) и
 *   отправляются только когда их диалог открыт — чужой транскрипт не загрязняется;
 * - лимиты: не более [maxQueueSize] записей (при переполнении вытесняется самая старая)
 *   и не более [maxAttempts] попыток отправки на сообщение — бесконечный цикл ретраев
 *   на устойчивой серверной ошибке невозможен;
 * - гигиена выхода: [PrefsPendingMessageStore] реализует [ClearableLocalStore] и
 *   стирается вместе с черновиками при logout/реавторизации.
 *
 * Вложения не буферизуются: их загрузка — отдельные multipart-запросы, при обрыве связи
 * хост показывает явный статус и сохраняет чипы вложений до восстановления.
 */

/**
 * Сообщение, ожидающее восстановления связи. [sessionId] == null — новый чат.
 *
 * [voiceMode] — сообщение было начато голосом и обязано остаться голосовым при повторе.
 * Без этого поля повтор считал бы режим заново по состоянию цикла (к этому моменту он уже
 * выключен) и отправил бы `voice_mode: false`: сервер применил бы обычный ответ, а человек
 * считал бы, что говорит в голосовом режиме. Ретрай обязан сохранять намерение хода.
 */
data class QueuedMessage(
    val id: String,
    val text: String,
    val sessionId: String?,
    val enqueuedAtEpochMillis: Long,
    val attempts: Int = 0,
    val voiceMode: Boolean = false,
)

/** Исход запроса отправки при текущем состоянии сети. */
sealed interface SendDecision {
    /** Сеть позволяет — отправлять штатным потоком. */
    data object SendNow : SendDecision

    /** Сообщение буферизовано в очереди (хост показывает локальное эхо и чистит композитор). */
    data class Buffer(val message: QueuedMessage) : SendDecision

    /** Пустой текст — отправлять/буферизовать нечего. */
    data object Rejected : SendDecision
}

/** Порт постоянного хранения очереди; приложение реализует его через SharedPreferences. */
interface PendingMessageStore {
    /** Восстановление очереди (порядок FIFO); битые данные → пустой список. */
    fun load(): List<QueuedMessage>

    /** Полная перезапись очереди текущим снимком. */
    fun save(messages: List<QueuedMessage>)
}

/**
 * [PendingMessageStore] поверх приватного SharedPreferences: один ключ, JSON-массив
 * (org.json корректно экранирует любые тексты). Битый JSON читается как пустая очередь —
 * без падений и без полей-сюрпризов: неизвестные ключи игнорируются, отсутствующие
 * получают дефолты. Очистка синхронная (commit) по контракту logout-cleanup.
 */
class PrefsPendingMessageStore(private val prefs: SharedPreferences) : PendingMessageStore, ClearableLocalStore {

    override fun load(): List<QueuedMessage> = runCatching {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val text = obj.optString(FIELD_TEXT, "")
            val id = obj.optString(FIELD_ID, "")
            if (text.isBlank() || id.isBlank()) return@mapNotNull null
            QueuedMessage(
                id = id,
                text = text,
                sessionId = obj.optString(FIELD_SESSION_ID, "").ifBlank { null },
                enqueuedAtEpochMillis = obj.optLong(FIELD_ENQUEUED_AT, 0L),
                attempts = obj.optInt(FIELD_ATTEMPTS, 0),
                // Старые записи поля не имеют: читаются как текстовый ход (false), и это
                // честно — голосовым он не был.
                voiceMode = obj.optBoolean(FIELD_VOICE_MODE, false),
            )
        }
    }.getOrDefault(emptyList())

    override fun save(messages: List<QueuedMessage>) {
        val array = JSONArray()
        messages.forEach { message ->
            array.put(
                JSONObject()
                    .put(FIELD_ID, message.id)
                    .put(FIELD_TEXT, message.text)
                    .put(FIELD_SESSION_ID, message.sessionId ?: "")
                    .put(FIELD_ENQUEUED_AT, message.enqueuedAtEpochMillis)
                    .put(FIELD_ATTEMPTS, message.attempts)
                    .put(FIELD_VOICE_MODE, message.voiceMode),
            )
        }
        prefs.edit().putString(KEY_QUEUE, array.toString()).apply()
    }

    override fun clearAll() {
        prefs.edit().remove(KEY_QUEUE).commit()
    }

    private companion object {
        const val KEY_QUEUE = "offline-queue"
        const val FIELD_ID = "id"
        const val FIELD_TEXT = "text"
        const val FIELD_SESSION_ID = "session_id"
        const val FIELD_ENQUEUED_AT = "enqueued_at"
        const val FIELD_ATTEMPTS = "attempts"
        const val FIELD_VOICE_MODE = "voice_mode"
    }
}

/**
 * Чистая логика очереди (см. контракт в шапке файла). Каждая мутация сразу сохраняет
 * снимок в [PendingMessageStore]; падающее хранилище не ломает in-memory очередь
 * (runCatching) — теряется только переживание смерти процесса.
 */
class OfflineMessageQueuePolicy(
    private val store: PendingMessageStore,
    private val maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val queue = ArrayDeque<QueuedMessage>()
    private var idCounter = 0

    init {
        runCatching { queue.addAll(store.load()) }
    }

    /** Текущий размер очереди (для баннера). */
    val size: Int get() = queue.size

    /** Снимок очереди в порядке отправки (FIFO). */
    fun snapshot(): List<QueuedMessage> = queue.toList()

    /** Сообщения очереди, принадлежащие диалогу [sessionId] (null — новый чат), FIFO. */
    fun pendingFor(sessionId: String?): List<QueuedMessage> = queue.filter { it.sessionId == sessionId }

    /**
     * Точка входа отправки: [canSend] — текущее [NetworkState.sendAllowed].
     * Пустой текст отклоняется всегда; при сети — штатная отправка; без сети — буферизация.
     */
    fun onSendRequested(
        canSend: Boolean,
        text: String,
        sessionId: String?,
        voiceMode: Boolean = false,
    ): SendDecision = when {
        text.isBlank() -> SendDecision.Rejected
        canSend -> SendDecision.SendNow
        else -> SendDecision.Buffer(enqueue(text, sessionId, voiceMode))
    }

    /** Добавляет сообщение в хвост очереди; при переполнении вытесняет самое старое. */
    fun enqueue(text: String, sessionId: String?, voiceMode: Boolean = false): QueuedMessage {
        val message = QueuedMessage(
            id = "q-${clock()}-${idCounter++}",
            text = text,
            sessionId = sessionId,
            enqueuedAtEpochMillis = clock(),
            voiceMode = voiceMode,
        )
        while (queue.size >= maxQueueSize) queue.removeFirst()
        queue.addLast(message)
        persist()
        return message
    }

    /**
     * Извлекает следующее сообщение диалога [sessionId] для отправки (FIFO).
     * Извлечение сразу сохраняется: смерть процесса во время отправки теряет одно
     * сообщение — осознанный обмен на отсутствие дублей после восстановления.
     */
    fun nextFor(sessionId: String?): QueuedMessage? {
        val index = queue.indexOfFirst { it.sessionId == sessionId }
        if (index < 0) return null
        val message = queue.removeAt(index)
        persist()
        return message
    }

    /**
     * Неудачная отправка: сообщение возвращается в ГОЛОВУ очереди (порядок FIFO
     * сохраняется) со счётчиком попыток +1. false — лимит [maxAttempts] исчерпан,
     * сообщение окончательно удалено (хост показывает статус «не доставлено»).
     */
    fun requeue(message: QueuedMessage): Boolean {
        if (message.attempts + 1 >= maxAttempts) return false
        queue.addFirst(message.copy(attempts = message.attempts + 1))
        persist()
        return true
    }

    /** Удаляет все сообщения диалога (сессия удалена/завершена). */
    fun removeMessagesFor(sessionId: String) {
        if (queue.removeAll { it.sessionId == sessionId }) persist()
    }

    /**
     * Перепривязывает сообщения диалога [fromSessionId] (null — новый чат) к диалогу
     * [toSessionId]. Смысл: первая отправка офлайн-очереди нового чата создаёт сессию —
     * остальные сообщения того же всплеска должны продолжить ЕЁ, а не плодить отдельные
     * сессии (и не застревать в очереди под null-ключом). Возвращает число перепривязанных.
     */
    fun adoptSession(fromSessionId: String?, toSessionId: String): Int {
        if (toSessionId.isEmpty() || fromSessionId == toSessionId) return 0
        var moved = 0
        val updated = queue.map {
            if (it.sessionId == fromSessionId) {
                moved++
                it.copy(sessionId = toSessionId)
            } else {
                it
            }
        }
        if (moved > 0) {
            queue.clear()
            queue.addAll(updated)
            persist()
        }
        return moved
    }

    /** Полная очистка очереди. */
    fun clear() {
        queue.clear()
        persist()
    }

    private fun persist() {
        runCatching { store.save(queue.toList()) }
    }

    companion object {
        /**
         * Потолок очереди: при переполнении самые старые сообщения вытесняются.
         *
         * 100 — сознательный запас на долгий офлайн (метро, самолёт, «нет сети весь день»):
         * 32 сообщения обрывались на середине рабочей переписки, а стоимость строки в SQLite
         * на порядок меньше, чем потеря сообщения. Тот же потолок применяет хранилище
         * ([ru.mars.mobile.offline.SqlitePendingMessageStore]) — даже очередь, записанная
         * старой версией с другим лимитом, читается подрезанной.
         */
        const val DEFAULT_MAX_QUEUE_SIZE = 100

        /** Лимит попыток отправки одного сообщения (первая + ретраи). */
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}
