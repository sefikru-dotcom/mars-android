package ru.mars.mobile.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import ru.mars.mobile.PushTokenSync
import ru.mars.network.repository.PushMessagePolicy

/**
 * Приёмник FCM-сообщений мобильного клиента Марса.
 *
 * Три ветки, все решаются ЧИСТЫМИ политиками (JVM-тесты), сервис только клеит:
 *
 * 1. ТИХИЙ пуш (`silent=1`/`kind=sync`) — синхронизация без уведомления:
 *    `action=token_resync` перерегистрирует токен, остальные действия уходят в
 *    [PushSilentSync] (перечитать pending approval/clarify, список диалогов).
 * 2. ВИДИМЫЙ data-only пуш — [PushMessagePolicy] выбирает канал/id/маршрут,
 *    [PushNotifications] рисует уведомление (работает и в foreground, и в
 *    background: сервер шлёт data-only именно для этого).
 * 3. notification-payload в background система показывает сама (onMessageReceived
 *    при этом не вызывается) — для этого в манифесте заданы
 *    default_notification_{icon,color,channel_id}.
 *
 * [onNewToken] — первая выдача и ротация токена: немедленная перерегистрация
 * ([PushTokenSync]); без credential регистрация честно пропускается и повторится
 * при следующем входе в чат.
 */
class MarsFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data

        val sync = PushSilentSync.parse(data)
        if (sync != null) {
            if (sync.refreshesToken) PushTokenSync.syncNow(this) else PushSilentSync.handle(sync)
            return
        }

        val spec = PushMessagePolicy.parse(
            notificationTitle = message.notification?.title,
            notificationBody = message.notification?.body,
            data = data,
        ) ?: return
        PushNotifications.show(this, spec)
    }

    override fun onNewToken(token: String) {
        PushTokenSync.enqueueRegistration(this, token)
    }
}
