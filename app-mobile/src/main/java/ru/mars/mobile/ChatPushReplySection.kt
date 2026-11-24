package ru.mars.mobile

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent

/**
 * Ответ прямо из push-уведомления.
 *
 * Путь короткий, но с двумя ловушками, которые здесь и решаются:
 * 1. Текст приходит в `RemoteInput`, а не в обычном extra, и читать его надо ДО
 *    `setIntent`: результаты RemoteInput живут в интенте-доставке, и `getIntent()`
 *    после подмены их уже не содержит.
 * 2. Отправлять можно только в загруженном диалоге. Если уведомление про другой
 *    диалог, текст ждёт в [ChatActivity.pendingPushReply], пока сессия откроется:
 *    отправить сразу значило бы положить ответ в текущую переписку, то есть не туда,
 *    куда человек отвечал.
 *
 * Правила «можно ли» живут в [PushReplyPolicy]: здесь только перенос текста.
 */
internal fun ChatActivity.handlePushReply(intent: Intent) {
    val raw = RemoteInput.getResultsFromIntent(intent)
        ?.getCharSequence(PushReplyPolicy.KEY_TEXT)
        ?.toString()
        ?: intent.getStringExtra(PushReplyPolicy.EXTRA_TEXT)
    // Погасить уведомление, на которое ответили: иначе ответ виден в шторке как
    // непрочитанное, и человек отвечает второй раз.
    val postedId = intent.getIntExtra(PushReplyPolicy.EXTRA_NOTIFICATION_ID, Int.MIN_VALUE)
    if (postedId != Int.MIN_VALUE) {
        getSystemService(NotificationManager::class.java)?.cancel(postedId)
    }
    val text = PushReplyPolicy.normalizeReply(raw) ?: return
    val target = intent.getStringExtra(PushReplyPolicy.EXTRA_SESSION_ID)?.trim().orEmpty()
    if (target.isEmpty()) {
        explicitStatus = PushReplyPolicy.failureNotice("не указан диалог")
        setStatus()
        return
    }
    // Диалог ещё не загружен (холодный старт) — открываем его с отложенным текстом.
    if (target != currentSessionId) {
        // Ответ адресован другому диалогу: текст ждёт загрузки (её завершает
        // ChatSessionSection), иначе он ушёл бы в открытую переписку — не туда,
        // куда человек отвечал в шторке.
        pendingPushReply = text
        loadSessionById(target)
        return
    }
    if (!PushReplyPolicy.canReply(target, currentSessionReadOnly, currentSessionIsSubagent)) {
        explicitStatus = PushReplyPolicy.failureNotice("сессия только для чтения")
        setStatus()
        return
    }
    input.setText(text)
    input.setSelection(text.length)
    sendMessage()
}
