package ru.mars.mobile

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Живые каналы внимания открытого диалога: `/api/approval/stream` и `/api/clarify/stream`.
 *
 * Зачем: раньше карточка approval/clarify появлялась только из кадра хода (SSE-стрим ответа)
 * или из тихого push/возврата на экран. Если ход уже закончился, а агент ждёт команду,
 * пользователь видел карточку лишь после ручного возврата в приложение. Теперь сервер сам
 * пушит изменение очереди — задержка ограничена сетью, а не периодом опроса.
 *
 * Почему подписка живёт в Activity, а не в контроллере: адресат — конкретный `session_id`,
 * а владелец жизненного цикла экрана — Activity. Контракт:
 * - [startAttentionFeed] на onResume и при смене диалога;
 * - [stopAttentionFeed] на onPause/onStop и при закрытии диалога — отмена job'а закрывает
 *   in-flight OkHttp call, поэтому в фоне сокет не висит;
 * - падение потока не теряет очередь: репозиторий сам переходит на
 *   `GET /api/approval/pending` / `GET /api/clarify/pending` и возвращается на SSE.
 *
 * Контроллер ([InteractiveRequestController]) получает уже разобранные обновления и сам
 * решает, показывать карточку, обновлять её или снимать (в том числе `pending: null`).
 */
internal fun ChatActivity.startAttentionFeed() {
    val sessionId = currentSessionId?.takeIf { it.isNotBlank() }
    if (sessionId == null) {
        stopAttentionFeed()
        return
    }
    if (attentionFeedSessionId == sessionId &&
        approvalFeedJob?.isActive == true &&
        clarifyFeedJob?.isActive == true
    ) {
        return
    }
    // Экран ещё не инициализирован (onCreate не дошёл до хранилища) — подписку откроет onResume.
    if (!::store.isInitialized) return
    val credential = store.read() ?: return

    stopAttentionFeed()
    attentionFeedSessionId = sessionId
    approvalFeedJob = lifecycleScope.launch {
        liveEvents.subscribeToApprovalStream(ChatActivity.BASE_URL, credential, sessionId).collect { event ->
            val update = event.payloadOrNull ?: return@collect
            if (::interactiveController.isInitialized) interactiveController.onApprovalStreamUpdate(update)
        }
    }
    clarifyFeedJob = lifecycleScope.launch {
        liveEvents.subscribeToClarifyStream(ChatActivity.BASE_URL, credential, sessionId).collect { event ->
            val update = event.payloadOrNull ?: return@collect
            if (::interactiveController.isInitialized) interactiveController.onClarifyStreamUpdate(update)
        }
    }
}

/** Закрывает оба канала внимания; повторный вызов безопасен. */
internal fun ChatActivity.stopAttentionFeed() {
    approvalFeedJob?.cancel()
    clarifyFeedJob?.cancel()
    approvalFeedJob = null
    clarifyFeedJob = null
    attentionFeedSessionId = null
}

/**
 * Диалог сменился (список, восстановление, deep-link, «новый чат», закрытие диалога):
 * подписка принадлежит прежнему `session_id` и обязана быть пересоздана.
 *
 * В фоне подписку не открываем — это сделает onResume, иначе экран, который не виден,
 * держал бы два SSE-соединения.
 */
internal fun ChatActivity.onActiveSessionChanged() {
    stopAttentionFeed()
    if (attentionFeedWanted) startAttentionFeed()
}
