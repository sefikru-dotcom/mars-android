package ru.mars.mobile

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.runBlocking
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.repository.PushRegisterOutcome
import ru.mars.network.repository.PushTokenRepository
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Регистрация FCM-токена на сервере Марса (`POST /api/push/fcm/register`).
 *
 * Авторизация — СУЩЕСТВУЮЩИЙ device-credential из AndroidKeyStore
 * ([AndroidKeyStoreCredentialStore]); новая схема не изобретается. Имя
 * устройства — [DeviceIdentity.stableDeviceName] (модель железа).
 *
 * Вызовы:
 * - [syncNow] — при входе в ChatActivity (покрывает первый запуск, перезапуск
 *   после логина и ротацию токена, пропущенную офлайн);
 * - [enqueueRegistration] — из `MarsPushMessagingService.onNewToken`.
 *
 * Всё на одном фоновом executor'е (FCM-колбэки и так не в UI-потоке), с
 * идемпотентной дедупликацией: один и тот же токен повторно не регистрируется.
 * Без credential — честный пропуск ([PushRegisterOutcome.Skipped]), при следующем
 * входе в чат sync повторится. Сетевой контракт покрыт JVM-тестами
 * (PushTokenRepositoryTest, PushRegistrationPayloadTest); этот класс — только клей.
 */
object PushTokenSync {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mars-push-token-sync").apply { isDaemon = true }
    }

    @Volatile
    private var lastRegisteredToken: String? = null

    /** Запрашивает текущий токен у FCM и регистрирует его на сервере (фон, fire-and-forget). */
    fun syncNow(context: Context) {
        val app = context.applicationContext
        executor.execute {
            val token = runCatching {
                Tasks.await(FirebaseMessaging.getInstance().token, TOKEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrNull() ?: return@execute
            register(app, token)
        }
    }

    /** Немедленная регистрация уже известного токена (onNewToken). */
    fun enqueueRegistration(context: Context, token: String) {
        val app = context.applicationContext
        executor.execute { register(app, token) }
    }

    private fun register(app: Context, token: String) {
        if (token.isNotBlank() && token == lastRegisteredToken) return
        val credential = runCatching { AndroidKeyStoreCredentialStore(app).read() }.getOrNull() ?: return
        val outcome = runCatching {
            runBlocking {
                PushTokenRepository().registerToken(credential, token, DeviceIdentity.stableDeviceName())
            }
        }.getOrElse { PushRegisterOutcome.Failure(it.message ?: it.javaClass.simpleName) }
        if (outcome is PushRegisterOutcome.Success) lastRegisteredToken = token
    }

    private const val TOKEN_TIMEOUT_SECONDS = 20L
}
