package ru.mars.network.model

/**
 * Канал push-уведомлений Марса: id/имя видны в системных настройках,
 * [highPriority] маппится платформенным слоем в IMPORTANCE_HIGH (heads-up,
 * звук) против IMPORTANCE_DEFAULT. Чистая модель — Android-типов не знает.
 */
data class PushChannelSpec(
    val id: String,
    val name: String,
    val highPriority: Boolean,
)

/**
 * Разобранное push-сообщение — готовый к показу набор полей. Решение о канале,
 * приоритете и notification id принято до этого объекта чистой политикой
 * `PushMessagePolicy` (JVM-тестируемо); платформенный слой (PushNotifications
 * в app-mobile, TvPush в app-tv) только рисует.
 *
 * @param url опциональный deep-link (`mars://chat/session/<id>` или https) —
 *   прокидывается в data тап-intent'а; null, если сервер его не прислал.
 * @param tag метка группировки/замены (например, id хода или cron-задачи).
 * @param notificationId id системного уведомления: повторная отправка того же
 *   события ЗАМЕНЯет прежнее уведомление, а не плодит дубликаты.
 */
data class PushNotificationSpec(
    val title: String,
    val body: String,
    val url: String?,
    val tag: String?,
    val channel: PushChannelSpec,
    val notificationId: Int,
    /**
     * Сессия, к которой относится событие (`session_id` data-payload). null — сервер
     * её не прислал. Нужна клиенту для маршрутизации тапа: уведомление «требуется
     * подтверждение» обязано открыть ИМЕННО тот диалог, а не последний открытый.
     */
    val sessionId: String? = null,
    /**
     * Тип события (`kind` data-payload: `approval`/`clarify`/`chat`/`turn`/`cron`),
     * дословно с сервера. null — не прислан. Решение «это запрос подтверждения»
     * принимает UI-слой ([ru.mars.tv.push.TvPushRouting] на ТВ), а не транспорт.
     */
    val kind: String? = null,
)
