package ru.mars.mobile

import ru.mars.network.model.SessionItem
import ru.mars.network.model.SessionTitleUpdate

/**
 * Что делать с кадром `title` (серверный авто-заголовок сессии, доставляется
 * side-каналом [SessionTitleUpdate]). Решение в виде [SessionTitleDecision]:
 * какую сессию и каким заголовком переименовать на экране.
 */
data class SessionTitleDecision(val sessionId: String, val title: String)

/**
 * Политика живого заголовка — чистая (без Android), JVM-тесты.
 *
 * Зачем: кадр `title` приходит посреди хода и адресован конкретной сессии, а экран
 * мог за это время уехать в другой диалог (или кадр пришёл от потока, который уже
 * не связан с открытой сессией). Правила:
 * - пустой/пробельный заголовок — не заголовок, кадр игнорируется;
 * - `session_id` кадра главнее: он называет сессию сам; если сервер его не прислал,
 *   берётся сессия, к которой привязан поток ([boundSessionId]);
 * - чужой поток (id не совпал ни с привязкой, ни с открытой сессией) экран не трогает;
 * - повтор того же текста не перерисовывает шапку (кадр может прийти дважды).
 */
object SessionTitlePolicy {

    fun decide(
        update: SessionTitleUpdate,
        boundSessionId: String?,
        currentSessionId: String?,
        currentTitle: String?,
    ): SessionTitleDecision? {
        val title = update.title.trim()
        if (title.isEmpty()) return null
        val target = update.sessionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: boundSessionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        if (boundSessionId != null && target != boundSessionId) return null
        if (currentSessionId != null && target != currentSessionId) return null
        if (title == currentTitle?.trim()) return null
        return SessionTitleDecision(target, title)
    }

    /** Строка списка диалогов после переименования: остальные строки не копируются. */
    fun renamed(sessions: List<SessionItem>, sessionId: String, title: String): List<SessionItem> {
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index < 0 || sessions[index].title == title) return sessions
        return sessions.toMutableList().apply { this[index] = this[index].copy(title = title) }
    }
}
