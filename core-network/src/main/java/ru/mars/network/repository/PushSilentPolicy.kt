package ru.mars.network.repository

/**
 * Разбор ТИХОГО (silent) push-сообщения — data-only команды синхронизации, которая
 * обновляет данные клиента и НЕ показывает уведомление.
 *
 * Зачем отдельная политика, а не «parse вернул null — значит молчим»: тихий push
 * несёт РАБОТУ (перечитать pending approval/clarify, перечитать список диалогов,
 * перерегистрировать токен), и решение «что именно обновить» должно быть
 * JVM-тестируемым, а не размазанным по Android-сервису.
 *
 * Контракт сервера (`api/push_fcm.send_fcm_silent`):
 *
 *     {"silent": "1", "kind": "sync", "action": "refresh_pending", "session_id": "..."}
 *
 * Правила:
 * - сообщение тихое, если `silent` ∈ {1,true,yes,on} ИЛИ `kind == "sync"`;
 * - тихое сообщение НИКОГДА не показывается как уведомление, даже если сервер
 *   по ошибке приложил title/body (иначе «синхронизация» будит пользователя);
 * - неизвестное/пустое `action` не отбрасывает команду: выбирается безопасный
 *   дефолт — `refresh_pending`, когда есть `session_id`, иначе `refresh_sessions`;
 * - не-тихое сообщение → null (его обрабатывает [PushMessagePolicy]).
 */
data class PushSyncCommand(
    val action: String,
    val sessionId: String?,
) {
    /** Перечитать pending approval/clarify конкретного диалога. */
    val refreshesPending: Boolean get() = action == PushSilentPolicy.ACTION_REFRESH_PENDING

    /** Перечитать список диалогов (новое сообщение в другом чате). */
    val refreshesSessions: Boolean get() = action == PushSilentPolicy.ACTION_REFRESH_SESSIONS

    /** Запросить у FCM актуальный токен и перерегистрировать его на сервере. */
    val refreshesToken: Boolean get() = action == PushSilentPolicy.ACTION_TOKEN_RESYNC
}

object PushSilentPolicy {

    const val ACTION_REFRESH_PENDING = "refresh_pending"
    const val ACTION_REFRESH_SESSIONS = "refresh_sessions"
    const val ACTION_TOKEN_RESYNC = "token_resync"

    /** Все поддерживаемые действия синхронизации. */
    val ACTIONS: Set<String> = setOf(ACTION_REFRESH_PENDING, ACTION_REFRESH_SESSIONS, ACTION_TOKEN_RESYNC)

    const val KIND_SYNC = "sync"

    private const val KEY_SILENT = "silent"
    private const val KEY_KIND = "kind"
    private const val KEY_ACTION = "action"
    private const val KEY_SESSION_ID = "session_id"

    private val TRUTHY = setOf("1", "true", "yes", "on")

    /** Тихий ли пуш: флаг `silent` или `kind=sync`. */
    fun isSilent(data: Map<String, String>): Boolean {
        val flag = data[KEY_SILENT]?.trim()?.lowercase().orEmpty()
        if (flag in TRUTHY) return true
        return data[KEY_KIND]?.trim()?.lowercase() == KIND_SYNC
    }

    /** Команда синхронизации; null — сообщение не тихое. */
    fun parse(data: Map<String, String>): PushSyncCommand? {
        if (!isSilent(data)) return null
        val sessionId = data[KEY_SESSION_ID]?.trim()?.takeIf { it.isNotEmpty() }
        val requested = data[KEY_ACTION]?.trim()?.lowercase().orEmpty()
        val action = when {
            requested in ACTIONS -> requested
            sessionId != null -> ACTION_REFRESH_PENDING
            else -> ACTION_REFRESH_SESSIONS
        }
        return PushSyncCommand(action = action, sessionId = sessionId)
    }
}
