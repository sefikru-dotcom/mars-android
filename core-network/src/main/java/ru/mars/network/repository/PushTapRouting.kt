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

    /**
     * Сессия из `tag` уведомления — третий резервный источник.
     *
     * Сервер (`api/push_fcm.py`, `api/streaming.py`) метит push конкретного
     * диалога тегом `hermes-<sid>`: он есть даже тогда, когда в `data` нет ни
     * `session_id`, ни `url`. Служебный тег тестового пуша
     * ([TAG_PUSH_TEST]) адресатом НЕ является — иначе тап по «проверке связи»
     * открывал бы несуществующий диалог.
     *
     * null — тег не наш, пустой или в хвосте мусор (пробелы, `/`): такой
     * «id» заведомо не session_id, а склеенная строка.
     */
    fun sessionIdFromTag(tag: String?): String? {
        val raw = tag?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (!raw.startsWith(TAG_PREFIX, ignoreCase = true)) return null
        if (raw.equals(TAG_PUSH_TEST, ignoreCase = true)) return null
        val id = raw.substring(TAG_PREFIX.length).trim()
        if (id.isEmpty()) return null
        if (id.any { it.isWhitespace() || it == '/' }) return null
        return id
    }

    /**
     * Универсальное извлечение адресата из интента/уведомления.
     *
     * Источники перечислены по убыванию надёжности, потому что они приходят из
     * РАЗНЫХ мест одного и того же события:
     * 1. `explicitExtra` — наша собственная навигация (`extra_open_session_id` в
     *    интенте ChatActivity): её ставит код приложения, значит она точнее
     *    всего;
     * 2. `sessionId` — `session_id`/`push_session_id` из data-payload: прямая
     *    запись сервера, но её может не быть;
     * 3. `sessionIdFromUrl(url)` — deep-link (`session/<sid>`,
     *    `mars://chat/session/<sid>`), который сервер шлёт всегда вместе с
     *    тегом;
     * 4. `sessionIdFromTag(tag)` — последний резерв: `hermes-<sid>`.
     *
     * Возвращается ПЕРВЫЙ валидный (непустой после trim) id, а не склейка:
     * расхождение источников означает чужой/устаревший ключ, и открывать по
     * нему диалог нельзя.
     */
    fun extractSessionId(
        explicitExtra: String? = null,
        sessionId: String? = null,
        url: String? = null,
        tag: String? = null,
    ): String? =
        explicitExtra?.trim()?.takeIf { it.isNotEmpty() }
            ?: sessionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: sessionIdFromUrl(url)
            ?: sessionIdFromTag(tag)

    /** Префикс tag, которым сервер метит push конкретного диалога. */
    private const val TAG_PREFIX = "hermes-"

    /** Служебный tag тестового пуша: не адресат, а проверка доставки. */
    private const val TAG_PUSH_TEST = "hermes-push-test"
}
