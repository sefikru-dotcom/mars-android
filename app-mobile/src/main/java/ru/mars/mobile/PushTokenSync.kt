package ru.mars.mobile

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.runBlocking
import ru.mars.network.credential.AndroidKeyStoreCredentialStore
import ru.mars.network.repository.PushRegisterOutcome
import ru.mars.network.repository.PushRepository
import ru.mars.network.repository.PushUnregisterOutcome
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Регистрация и снятие FCM-токена на сервере Марса
 * (`POST /api/push/fcm/register` / `/api/push/fcm/unregister`).
 *
 * Авторизация — СУЩЕСТВУЮЩИЙ device-credential из AndroidKeyStore
 * ([AndroidKeyStoreCredentialStore]); новая схема не изобретается. Имя
 * устройства — [DeviceIdentity.stableDeviceName] (модель железа).
 *
 * Вызовы:
 * - [syncNow] — при входе в ChatActivity (покрывает первый запуск, перезапуск
 *   после логина и ротацию токена, пропущенную офлайн), а также по тихому пушу
 *   `action=token_resync`;
 * - [enqueueRegistration] — из `MarsFirebaseMessagingService.onNewToken`;
 * - [unregisterNow] — при logout: токен снимается с сервера и удаляется локально,
 *   иначе устройство продолжает получать пуши прошлого профиля.
 *
 * Всё на одном фоновом executor'е (FCM-колбэки и так не в UI-потоке), с
 * идемпотентной дедупликацией: один и тот же токен повторно не регистрируется.
 * Без credential — честный пропуск ([PushRegisterOutcome.Skipped]), при следующем
 * входе в чат sync повторится. Сетевой контракт покрыт JVM-тестами
 * (PushRepositoryTest, PushTokenRepositoryTest, PushRegistrationPayloadTest);
 * этот класс — только клей.
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
            val token = currentToken() ?: return@execute
            register(app, token)
        }
    }

    /** Немедленная регистрация уже известного токена (onNewToken). */
    fun enqueueRegistration(context: Context, token: String) {
        val app = context.applicationContext
        executor.execute { register(app, token) }
    }

    /**
     * Снимает токен с сервера и удаляет его локально (logout / смена аккаунта).
     *
     * Credential читается СИНХРОННО на вызывающем потоке: logout сразу после
     * этого вызова стирает хранилище, и асинхронное чтение внутри executor'а
     * могло бы увидеть уже пустой credential — тогда сервер оставил бы живой
     * токен прошлого профиля.
     */
    fun unregisterNow(context: Context) {
        val app = context.applicationContext
        val credential = runCatching { AndroidKeyStoreCredentialStore(app).read() }.getOrNull() ?: return
        val known = lastRegisteredToken
        executor.execute {
            val token = known ?: currentToken() ?: return@execute
            val outcome = runCatching {
                runBlocking { PushRepository().unregisterToken(credential, token) }
            }.getOrElse { PushUnregisterOutcome.Failure(it.message ?: it.javaClass.simpleName) }
            lastRegisteredToken = null
            // Локальный токен удаляется в любом случае: даже если сервер не ответил,
            // следующая регистрация (после нового логина) получит СВЕЖИЙ токен.
            runCatching { Tasks.await(FirebaseMessaging.getInstance().deleteToken()) }
            if (outcome !is PushUnregisterOutcome.Success) {
                // Best-effort: сервер почистит токен при первой неудачной доставке
                // (FCM ответит UNREGISTERED только после удаления — не гарантия),
                // поэтому повторная попытка делается при следующем logout.
            }
        }
    }

    private fun register(app: Context, token: String) {
        if (token.isNotBlank() && token == lastRegisteredToken) return
        val credential = runCatching { AndroidKeyStoreCredentialStore(app).read() }.getOrNull() ?: return
        val outcome = runCatching {
            runBlocking {
                PushRepository().registerToken(credential, token, DeviceIdentity.stableDeviceName())
            }
        }.getOrElse { PushRegisterOutcome.Failure(it.message ?: it.javaClass.simpleName) }
        if (outcome is PushRegisterOutcome.Success) lastRegisteredToken = token
    }

    private fun currentToken(): String? = runCatching {
        Tasks.await(FirebaseMessaging.getInstance().token, TOKEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }.getOrNull()

    private const val TOKEN_TIMEOUT_SECONDS = 20L
}
