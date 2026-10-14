package ru.mars.mobile.service

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import ru.mars.mobile.PushTokenSync
import ru.mars.network.repository.PushMessagePolicy

/**
 * Приёмник FCM-сообщений Марса.
 *
 * - [onMessageReceived]: data-only сообщения (сервер отправляет именно их, чтобы
 *   клиент сам рисовал уведомление и в foreground, и в background) разбираются
 *   чистой [PushMessagePolicy] в модель и показываются через [PushNotifications].
 *   Сообщение с notification-payload в background система показывает сама —
 *   onMessageReceived при этом не вызывается, это штатное поведение FCM.
 * - [onNewToken]: ротация/первая выдача токена — немедленная перерегистрация
 *   на сервере ([PushTokenSync]); без credential регистрация честно пропускается.
 */
class MarsPushMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val spec = PushMessagePolicy.parse(
            notificationTitle = message.notification?.title,
            notificationBody = message.notification?.body,
            data = message.data,
        ) ?: return
        PushNotifications.show(this, spec)
    }

    override fun onNewToken(token: String) {
        PushTokenSync.enqueueRegistration(this, token)
    }
}
