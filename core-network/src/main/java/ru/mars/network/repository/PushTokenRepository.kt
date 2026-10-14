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

/** Явный исход регистрации токена — вместо «молча ничего» (конвенция CreateSessionOutcome/UploadOutcome). */
sealed interface PushRegisterOutcome {
    /** Сервер принял токен (2xx). */
    data class Success(val token: String) : PushRegisterOutcome

    /** Регистрация честно пропущена: нет device-credential (не залогинен) или пустой токен. */
    data class Skipped(val reason: String) : PushRegisterOutcome

    /** HTTP-ошибка (с `error` сервера, когда есть) или сбой транспорта. */
    data class Failure(val message: String) : PushRegisterOutcome
}

/**
 * Регистрация FCM-токена устройства на сервере Марса.
 *
 * Контракт — [PushRegistrationPayload] (единственное место с путём и формой
 * тела). Авторизация: существующий device-credential (`Bearer <accessToken>`,
 * та же схема, что у SessionRepository/ChatRepository) — новая не изобретается.
 *
 * Никогда не бросает: транспортные сбои сворачиваются в [PushRegisterOutcome.Failure]
 * (кроме отмены корутины). Повторный вызов с тем же токеном идемпотентен на
 * стороне сервера; клиент дополнительно дедуплицирует вызовы слоем выше.
 */
class PushTokenRepository(
    private val client: OkHttpClient = HttpClients.rest,
    private val baseUrl: String = MarsApi.BASE_URL,
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun registerToken(
        credential: ConsumedDeviceCredential?,
        token: String,
        deviceName: String,
    ): PushRegisterOutcome {
        if (credential == null) return PushRegisterOutcome.Skipped("no device credential")
        if (token.isBlank()) return PushRegisterOutcome.Skipped("blank fcm token")

        val url = baseUrl.toHttpUrlSafe() ?: return PushRegisterOutcome.Failure("bad base url: $baseUrl")
        val body = PushRegistrationPayload.body(token = token, deviceName = deviceName)
            .toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${credential.accessToken}")
            .post(body)
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        PushRegisterOutcome.Success(token)
                    } else {
                        PushRegisterOutcome.Failure("HTTP ${response.code} ${serverReason(text)}")
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                PushRegisterOutcome.Failure(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun String.toHttpUrlSafe(): HttpUrl? = runCatching {
        toHttpUrl().newBuilder().addPathSegments(PushRegistrationPayload.REGISTER_PATH).build()
    }.getOrNull()

    /** `{"error": "..."}` из ответа сервера — короткая человекочитаемая причина. */
    private fun serverReason(body: String): String =
        """"error"\s*:\s*"([^"]*)"""".toRegex().find(body)?.groupValues?.get(1).orEmpty()
}
