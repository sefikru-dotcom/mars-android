package ru.mars.mobile.offline

import ru.mars.mobile.QueuedMessage

/**
 * Строка таблицы очереди в том виде, в каком она лежит в SQLite.
 *
 * Отдельный тип нужен, чтобы отображение «строка БД ↔ [QueuedMessage]» было чистой
 * функцией без Cursor/SQLite: битые строки (пустой текст или id — например, обрезанная
 * миграцией запись) отбрасываются на чтении, а не всплывают пустым эхом в транскрипте.
 * Покрыто JVM-тестами ([ru.mars.mobile.OfflineQueueStorageTest]).
 */
data class QueuedMessageRow(
    val id: String,
    val text: String,
    val sessionId: String?,
    val enqueuedAtEpochMillis: Long,
    val attempts: Int,
    val voiceMode: Boolean,
) {
    /** Запись очереди; null — строка непригодна (пустой текст/id). */
    fun toMessage(): QueuedMessage? {
        if (id.isBlank() || text.isBlank()) return null
        return QueuedMessage(
            id = id,
            text = text,
            sessionId = sessionId?.takeIf { it.isNotBlank() },
            enqueuedAtEpochMillis = enqueuedAtEpochMillis,
            attempts = attempts.coerceAtLeast(0),
            voiceMode = voiceMode,
        )
    }

    companion object {
        /** Строка БД из записи очереди (обратное к [toMessage]). */
        fun from(message: QueuedMessage): QueuedMessageRow = QueuedMessageRow(
            id = message.id,
            text = message.text,
            sessionId = message.sessionId,
            enqueuedAtEpochMillis = message.enqueuedAtEpochMillis,
            attempts = message.attempts,
            voiceMode = message.voiceMode,
        )
    }
}
