package ru.mars.network.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.mars.network.api.MarsApi
import ru.mars.network.http.HttpClients
import ru.mars.network.model.ConsumedDeviceCredential

/** Исход снятия токена (logout) — та же конвенция, что у [PushRegisterOutcome]. */
sealed interface PushUnregisterOutcome {
    /** Сервер снял токен (2xx) — пушей этому устройству больше не будет. */
    data object Success : PushUnregisterOutcome

    /** Снимать нечего: нет device-credential или пустой токен. */
    data class Skipped(val reason: String) : PushUnregisterOutcome

    /** HTTP-ошибка или сбой транспорта; токен всё равно удаляется локально. */
    data class Failure(val message: String) : PushUnregisterOutcome
}

/**
 * Единая точка работы клиента с FCM-токеном: регистрация при входе и снятие при
 * выходе. Тонкая фасадная обёртка над проверенным сетевым слоем
 * ([PushTokenRepository] для register, тот же транспорт/авторизация для
 * unregister) — приложению не нужно знать два разных класса и две копии разбора
 * ошибок.
 *
 * Контракт путей и тел — [PushRegistrationPayload] (единственное место).
 * Авторизация — существующий device-credential (`Bearer <accessToken>`).
 *
 * Никогда не бросает (кроме отмены корутины): всё сводится к явному исходу.
 */
class PushRepository(
    private val client: OkHttpClient = HttpClients.rest,
    private val baseUrl: String = MarsApi.BASE_URL,
) {

    private val tokenRepository = PushTokenRepository(client, baseUrl)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** Регистрирует токен устройства на сервере. */
    suspend fun registerToken(
        credential: ConsumedDeviceCredential?,
        token: String,
        deviceName: String,
    ): PushRegisterOutcome = tokenRepository.registerToken(credential, token, deviceName)

    /**
     * Снимает токен с сервера (logout / смена аккаунта). Идемпотентно: сервер
     * отвечает 200 и на неизвестный токен, поэтому повторный вызов безопасен.
     */
    suspend fun unregisterToken(
        credential: ConsumedDeviceCredential?,
        token: String,
    ): PushUnregisterOutcome {
        if (credential == null) return PushUnregisterOutcome.Skipped("no device credential")
        if (token.isBlank()) return PushUnregisterOutcome.Skipped("blank fcm token")

        val url = unregisterUrl() ?: return PushUnregisterOutcome.Failure("bad base url: $baseUrl")
        val body = PushRegistrationPayload.unregisterBody(token).toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${credential.accessToken}")
            .post(body)
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        PushUnregisterOutcome.Success
                    } else {
                        val text = response.body?.string().orEmpty()
                        PushUnregisterOutcome.Failure("HTTP ${response.code} ${serverReason(text)}")
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                PushUnregisterOutcome.Failure(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun unregisterUrl(): HttpUrl? = runCatching {
        baseUrl.toHttpUrl().newBuilder().addPathSegments(PushRegistrationPayload.UNREGISTER_PATH).build()
    }.getOrNull()

    /** `{"error": "..."}` из ответа сервера — короткая человекочитаемая причина. */
    private fun serverReason(body: String): String =
        """"error"\s*:\s*"([^"]*)"""".toRegex().find(body)?.groupValues?.get(1).orEmpty()
}
