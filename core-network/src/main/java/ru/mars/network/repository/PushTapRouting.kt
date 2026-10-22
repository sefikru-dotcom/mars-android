package ru.mars.network.repository

import ru.mars.network.model.PushNotificationSpec

/**
 * Чистая маршрутизация тапа по push-уведомлению в мобильном клиенте — без
 * Android-типов, покрыта JVM-тестами (PushTapRoutingTest).
 *
 * Зачем отдельный слой: платформенный `PushNotifications` только собирает Intent,
 * а РЕШЕНИЕ «какой диалог открыть и требует ли событие решения» должно быть
 * проверяемо без эмулятора. Push про approval/clarify обязан открывать ИМЕННО
 * тот диалог, к которому относится событие (`session_id`), а не последний
 * открытый; иначе подтверждение приходит «в пустоту» (на ТВ это был разрыв L —
 * TvPushRouting).
 *
 * Правила:
 * - `session_id`/`kind` нормализуются (trim + lowercase для kind); пустое → null;
 * - [isDecision] — только `approval`/`clarify`: эти уведомления нельзя просто
 *   «открыть посмотреть», они ждут ответа владельца.
 */
data class PushTapTarget(
    val sessionId: String?,
    val kind: String?,
) {
    /** Есть ли к чему привязывать тап (конкретный диалог). */
    val hasSession: Boolean get() = !sessionId.isNullOrEmpty()

    /** Событие ждёт решения владельца (approval/clarify). */
    val isDecision: Boolean get() = kind in DECISION_KINDS

    companion object {
        /** Типы событий, требующие ответа (а не просто просмотра). */
        val DECISION_KINDS: Set<String> = setOf("approval", "clarify")

        /** Тап без адресата: экран остаётся на текущем диалоге. */
        val NONE = PushTapTarget(sessionId = null, kind = null)
    }
}

object PushTapRouting {

    /** Маршрут тапа по разобранному уведомлению. */
    fun resolve(spec: PushNotificationSpec): PushTapTarget =
        resolve(sessionId = spec.sessionId, kind = spec.kind)

    /** Маршрут тапа по «сырым» extras интента (после перезапуска процесса). */
    fun resolve(sessionId: String?, kind: String?): PushTapTarget {
        val normalizedSession = sessionId?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedKind = kind?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (normalizedSession == null && normalizedKind == null) return PushTapTarget.NONE
        return PushTapTarget(sessionId = normalizedSession, kind = normalizedKind)
    }

    /**
     * Сессия из deep-link (`mars://chat/session/<id>`) — резервный источник, когда
     * сервер не положил `session_id` в data, но положил `url`. null — ссылки нет
     * или она не про диалог.
     */
    fun sessionIdFromUrl(url: String?): String? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val marker = "session/"
        val index = raw.indexOf(marker)
        if (index < 0) return null
        val tail = raw.substring(index + marker.length)
        val id = tail.substringBefore('?').substringBefore('#').trim()
        return id.takeIf { it.isNotEmpty() }
    }
}
