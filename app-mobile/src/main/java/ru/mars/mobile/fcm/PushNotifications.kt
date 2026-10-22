package ru.mars.mobile.fcm

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
import ru.mars.mobile.ChatActivity
import ru.mars.mobile.MarsTheme
import ru.mars.mobile.R
import ru.mars.network.model.PushChannelSpec
import ru.mars.network.model.PushNotificationSpec
import ru.mars.network.repository.PushMessagePolicy
import ru.mars.network.repository.PushTapRouting

/**
 * Android-клей для push-уведомлений Марса: каналы, показ и runtime-разрешение.
 *
 * Вся СОДЕРЖАТЕЛЬНАЯ логика (что показывать, какой канал, какой приоритет,
 * какой notification id, куда ведёт тап) живёт в чистых
 * [ru.mars.network.repository.PushMessagePolicy] / [PushTapRouting] и покрыта
 * JVM-тестами; здесь только маппинг её решения в framework-типы.
 *
 * Дизайн — минимализм в духе MarsTheme: акцент [MarsTheme.ACCENT] как цвет
 * уведомления (тот же #EF4444, что и @color/mars_accent в манифесте), системный
 * звук канала по умолчанию, без вибрации и без крупных кнопок: тап открывает
 * ИМЕННО тот диалог, к которому относится событие.
 */
object PushNotifications {

    /** Request-код системного диалога POST_NOTIFICATIONS (Android 13+). */
    const val REQUEST_POST_NOTIFICATIONS = 0x5055 // "PU"

    /** Extra интента: тип события (`approval`/`clarify`/`chat`/…). */
    const val EXTRA_PUSH_KIND = "extra_push_kind"

    /**
     * Создаёт оба канала ([PushMessagePolicy.CHANNEL_CHAT] и
     * [PushMessagePolicy.CHANNEL_GENERAL]), если их ещё нет. Повторный вызов —
     * no-op: параметры уже созданного канала система менять не даёт, поэтому
     * идемпотентность важнее «свежести» настроек.
     */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        PushMessagePolicy.CHANNELS.forEach { createChannel(manager, it) }
    }

    /** Показывает уведомление из чистой модели; молча пропускает без POST_NOTIFICATIONS. */
    fun show(context: Context, spec: PushNotificationSpec) {
        ensureChannels(context)
        if (!canPost(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = Notification.Builder(context, spec.channel.id)
            .setSmallIcon(R.drawable.ic_mars)
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setStyle(Notification.BigTextStyle().bigText(spec.body))
            .setColor(MarsTheme.ACCENT)
            .setContentIntent(tapIntent(context, spec))
            .setAutoCancel(true)
            .build()
        manager.notify(spec.notificationId, notification)
    }

    /** Android 13+: запрос POST_NOTIFICATIONS один раз при входе в чат (без него баннеры молчат). */
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

    /**
     * Тап по уведомлению: чат + `session_id` события.
     *
     * Решение «какой диалог» принимает [PushTapRouting]: `session_id` из data,
     * иначе сессия из deep-link `url`. Без адресата экран открывается как есть
     * (последний диалог) — подменять его «на угад» нельзя.
     * SINGLE_TOP + CLEAR_TOP: уже открытый чат получает onNewIntent и
     * переключается на нужный диалог, а не плодит копии в стеке.
     */
    private fun tapIntent(context: Context, spec: PushNotificationSpec): PendingIntent {
        val target = PushTapRouting.resolve(spec)
        val sessionId = target.sessionId ?: PushTapRouting.sessionIdFromUrl(spec.url)
        val intent = Intent(context, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            spec.url?.let { data = Uri.parse(it) }
            sessionId?.let { putExtra(ChatActivity.EXTRA_OPEN_SESSION_ID, it) }
            target.kind?.let { putExtra(EXTRA_PUSH_KIND, it) }
        }
        return PendingIntent.getActivity(
            context,
            spec.notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun createChannel(manager: NotificationManager, channel: PushChannelSpec) {
        if (manager.getNotificationChannel(channel.id) != null) return
        val importance =
            if (channel.highPriority) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
        manager.createNotificationChannel(
            NotificationChannel(channel.id, channel.name, importance).apply {
                // Минимализм: звук системный по умолчанию, без вибрации и bypass DND.
                enableVibration(false)
                enableLights(true)
                lightColor = MarsTheme.ACCENT
            },
        )
    }
}
