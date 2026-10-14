package ru.mars.network.repository

import ru.mars.network.model.PushChannelSpec
import ru.mars.network.model.PushNotificationSpec

/**
 * Чистый разбор входящего FCM-сообщения в модель уведомления — без Android-типов,
 * полностью покрыт JVM-тестами (PushMessagePolicyTest).
 *
 * Сервер Марса (волна 4, серверная половина) отправляет DATA-only сообщения,
 * чтобы клиент рисовал уведомление сам и в foreground, и в background;
 * notification-payload поддерживается как fallback (система показывает его
 * сама, когда процесс спит, а onMessageReceived получает его только в
 * foreground).
 *
 * Правила:
 * - data имеет приоритет над notification-payload;
 * - полностью пустое сообщение (ни title, ни body ни в одном из payload'ов)
 *   не показывается вовсе — null;
 * - `kind` выбирает канал: chat/approval/clarify/turn/cron — события, требующие
 *   внимания владельца (высокий приоритет, heads-up); всё прочее и неизвестное —
 *   общий канал с обычным приоритетом;
 * - `notification_id` (если прислан числом) используется как есть; иначе id
 *   детерминированно выводится из tag → session_id → title+body, чтобы повтор
 *   того же события заменял прежнее уведомление.
 */
object PushMessagePolicy {

    /** Заголовок по умолчанию, когда тело есть, а заголовка сервер не прислал. */
    const val DEFAULT_TITLE = "Марс"

    /** Канал событий чата/автономности: высокий приоритет, heads-up, звук. */
    val CHANNEL_CHAT = PushChannelSpec(
        id = "mars_push_chat",
        name = "Марс — чат и задания",
        highPriority = true,
    )

    /** Общий канал: обычные уведомления без heads-up. */
    val CHANNEL_GENERAL = PushChannelSpec(
        id = "mars_push_general",
        name = "Марс — уведомления",
        highPriority = false,
    )

    /** Все каналы клиента — платформенный слой создаёт их списком. */
    val CHANNELS: List<PushChannelSpec> = listOf(CHANNEL_CHAT, CHANNEL_GENERAL)

    /** Значения `kind`, требующие внимания (высокоприоритетный канал). */
    private val HIGH_PRIORITY_KINDS = setOf("chat", "approval", "clarify", "turn", "cron")

    /** Ключи data-payload контракта. */
    private const val KEY_TITLE = "title"
    private const val KEY_BODY = "body"
    private const val KEY_URL = "url"
    private const val KEY_TAG = "tag"
    private const val KEY_KIND = "kind"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_NOTIFICATION_ID = "notification_id"

    /**
     * Разбирает сообщение; null, когда показывать нечего.
     *
     * @param notificationTitle title из notification-payload FCM (может отсутствовать).
     * @param notificationBody body из notification-payload FCM (может отсутствовать).
     * @param data data-payload FCM как есть (пустая карта для notification-only).
     */
    fun parse(
        notificationTitle: String?,
        notificationBody: String?,
        data: Map<String, String>,
    ): PushNotificationSpec? {
        val title = firstNonBlank(data[KEY_TITLE], notificationTitle)
        val body = firstNonBlank(data[KEY_BODY], notificationBody)
        if (title == null && body == null) return null

        val kind = data[KEY_KIND]?.trim()?.lowercase().orEmpty()
        val channel = if (kind in HIGH_PRIORITY_KINDS) CHANNEL_CHAT else CHANNEL_GENERAL

        val notificationId = data[KEY_NOTIFICATION_ID]?.trim()?.toIntOrNull()
            ?: deriveNotificationId(data[KEY_TAG], data[KEY_SESSION_ID], title, body)

        return PushNotificationSpec(
            title = title ?: DEFAULT_TITLE,
            body = body.orEmpty(),
            url = data[KEY_URL]?.trim()?.takeIf { it.isNotEmpty() },
            tag = data[KEY_TAG]?.trim()?.takeIf { it.isNotEmpty() },
            channel = channel,
            notificationId = notificationId,
        )
    }

    private fun firstNonBlank(vararg candidates: String?): String? =
        candidates.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun deriveNotificationId(
        tag: String?,
        sessionId: String?,
        title: String?,
        body: String?,
    ): Int {
        val key = firstNonBlank(tag, sessionId) ?: "${title.orEmpty()}\u0000${body.orEmpty()}"
        return key.hashCode()
    }
}
