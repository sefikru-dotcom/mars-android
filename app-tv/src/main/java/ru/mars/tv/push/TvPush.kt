package ru.mars.tv.push

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.model.PushNotificationSpec
import ru.mars.network.repository.PushMessagePolicy
import ru.mars.network.repository.PushRegisterOutcome
import ru.mars.network.repository.PushTokenRepository
import ru.mars.tv.R
import ru.mars.tv.TvChatActivity
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * FCM в ТВ-клиенте Марса: тот же чистый контракт core-network
 * ([PushMessagePolicy] / [PushTokenRepository]), что и в app-mobile — здесь
 * только платформенный клей без дублирования логики.
 *
 * Отличия от мобильного намеренные и минимальные:
 * - без цветовой кастомизации уведомления (ТВ-шторка системная, хардкод-цвета
 *   запрещены design-гейтом; иконка — общий ic_mars);
 * - тап открывает [TvChatActivity] (чат на ТВ есть, push имеет смысл);
 * - имя устройства — модель ТВ-приставки ([Build.MODEL]).
 */

/** Показ уведомлений и каналы (список каналов — общий, из core-network). */
object TvPushNotifications {

    const val REQUEST_POST_NOTIFICATIONS = 0x5056

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        PushMessagePolicy.CHANNELS.forEach { spec ->
            if (manager.getNotificationChannel(spec.id) != null) return@forEach
            val importance =
                if (spec.highPriority) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
            manager.createNotificationChannel(
                NotificationChannel(spec.id, spec.name, importance).apply { enableVibration(false) },
            )
        }
    }

    fun show(context: Context, spec: PushNotificationSpec) {
        ensureChannels(context)
        if (!canPost(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val intent = Intent(context, TvChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            spec.url?.let { data = Uri.parse(it) }
        }
        val pending = PendingIntent.getActivity(
            context,
            spec.notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, spec.channel.id)
            .setSmallIcon(R.drawable.ic_mars)
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setStyle(Notification.BigTextStyle().bigText(spec.body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(spec.notificationId, notification)
    }

    /** Android 13+ (современные Google TV): без разрешения баннеры молчат. */
    fun ensurePostPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_POST_NOTIFICATIONS,
        )
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}

/** Регистрация FCM-токена на сервере — зеркало мобильного PushTokenSync (device-credential из KeyStore). */
object TvPushTokenSync {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mars-tv-push-token-sync").apply { isDaemon = true }
    }

    @Volatile
    private var lastRegisteredToken: String? = null

    fun syncNow(context: Context) {
        val app = context.applicationContext
        executor.execute {
            val token = runCatching {
                Tasks.await(FirebaseMessaging.getInstance().token, 20, TimeUnit.SECONDS)
            }.getOrNull() ?: return@execute
            register(app, token)
        }
    }

    fun enqueueRegistration(context: Context, token: String) {
        val app = context.applicationContext
        executor.execute { register(app, token) }
    }

    private fun register(app: Context, token: String) {
        if (token.isNotBlank() && token == lastRegisteredToken) return
        val credential = runCatching { AndroidKeyStoreCredentialStore(app).read() }.getOrNull() ?: return
        val deviceName = Build.MODEL.ifBlank { "mars_tv" }
        val outcome = runCatching {
            runBlocking { PushTokenRepository().registerToken(credential, token, deviceName) }
        }.getOrElse { PushRegisterOutcome.Failure(it.message ?: it.javaClass.simpleName) }
        if (outcome is PushRegisterOutcome.Success) lastRegisteredToken = token
    }
}

/** Приёмник FCM-сообщений ТВ-клиента (data-only разбираются чистой PushMessagePolicy). */
class TvPushMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val spec = PushMessagePolicy.parse(
            notificationTitle = message.notification?.title,
            notificationBody = message.notification?.body,
            data = message.data,
        ) ?: return
        TvPushNotifications.show(this, spec)
    }

    override fun onNewToken(token: String) {
        TvPushTokenSync.enqueueRegistration(this, token)
    }
}
