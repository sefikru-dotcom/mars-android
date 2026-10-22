package ru.mars.mobile.fcm

import android.os.Handler
import android.os.Looper
import ru.mars.network.repository.PushSilentPolicy
import ru.mars.network.repository.PushSyncCommand
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Доставка тихих push-команд живому экрану.
 *
 * Тихий пуш приходит в [MarsFirebaseMessagingService] — в процессе, который может
 * быть и без единой Activity. Здесь две ветки:
 * - экран на переднем плане (есть подписчик) — команда уходит ему на main-поток;
 * - экрана нет — команда запоминается ([consumePending]) и выполняется при
 *   следующем onResume, а не теряется: approval, пришедший в фон, обязан
 *   появиться сразу после возврата в приложение.
 *
 * Работа с токеном (action=`token_resync`) сюда не идёт — её выполняет
 * вызывающий ([MarsFirebaseMessagingService]) через PushTokenSync.
 */
object PushSilentSync {

    /** Подписчик экрана; вызывается на main-потоке. */
    fun interface Listener {
        fun onSyncCommand(command: PushSyncCommand)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var pending: PushSyncCommand? = null

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Разносит команду подписчикам (main-поток) или откладывает до onResume. */
    fun handle(command: PushSyncCommand) {
        if (listeners.isEmpty()) {
            pending = command
            return
        }
        mainHandler.post {
            listeners.forEach { listener ->
                runCatching { listener.onSyncCommand(command) }
            }
        }
    }

    /** Забирает отложенную команду (ровно один раз). */
    fun consumePending(): PushSyncCommand? {
        val value = pending ?: return null
        pending = null
        return value
    }

    /** Сброс при logout: команды прошлого профиля не должны выполняться в новом. */
    fun reset() {
        pending = null
    }

    /** Удобная проверка «это вообще тихая команда» для сервиса. */
    fun parse(data: Map<String, String>): PushSyncCommand? = PushSilentPolicy.parse(data)
}
