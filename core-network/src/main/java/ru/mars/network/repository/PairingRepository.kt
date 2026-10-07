package ru.mars.network.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.PairingExchangeResponseParser
import ru.mars.network.model.PairingResponse
import ru.mars.network.model.PairingResponseParser
import ru.mars.network.model.PairingStatusResponse
import ru.mars.network.model.PairingStatusResponseParser

class PairingRepository(
    baseUrl: String,
    client: OkHttpClient = OkHttpClient()
) {
    private val baseHttpUrl = baseUrl.toHttpUrl().also(::validateBaseUrl)
    // Auth calls must not follow redirects: a redirect can otherwise disclose credentials
    // to a different origin or downgrade an HTTPS request to cleartext HTTP.
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun createPairing(): Result<PairingResponse> = request {
        executeWithUnversionedFallback(
            primary = url("api", "v1", "auth", "device-pairings"),
            fallback = url("api", "auth", "device-pairings"),
            request = { endpoint ->
                Request.Builder().url(endpoint).post(ByteArray(0).toRequestBody()).build()
            },
            error = "Pairing creation failed"
        ) { response -> PairingResponseParser.parse(response.body?.string().orEmpty()) }
    }

    suspend fun pollPairingStatus(pairId: String, pollSecret: String): Result<PairingStatusResponse> = request {
        requireIdentifier(pairId, "pairId")
        require(pollSecret.isNotBlank()) { "pollSecret must not be blank" }
        executeWithUnversionedFallback(
            primary = url("api", "v1", "auth", "device-pairings", "status"),
            fallback = url("api", "auth", "device-pairings", "status"),
            request = { endpoint ->
                Request.Builder()
                    .url(endpoint.newBuilder().addQueryParameter("pair_id", pairId).build())
                    .header("X-Hermes-Poll-Secret", pollSecret)
                    .get()
                    .build()
            },
            error = "Pairing status failed"
        ) { response -> PairingStatusResponseParser.parse(response.body?.string().orEmpty()) }
    }

    suspend fun exchangePairing(pairId: String, pollSecret: String): Result<ConsumedDeviceCredential> = request {
        requireIdentifier(pairId, "pairId")
        require(pollSecret.isNotBlank()) { "pollSecret must not be blank" }
        val body = "{\"poll_secret\":${jsonString(pollSecret)}}"
            .toRequestBody("application/json".toMediaType())
        executeWithUnversionedFallback(
            primary = url("api", "v1", "auth", "device-pairings", pairId, "exchange"),
            fallback = url("api", "auth", "device-pairings", pairId, "exchange"),
            request = { endpoint -> Request.Builder().url(endpoint).post(body).build() },
            error = "Pairing exchange failed"
        ) { response ->
            PairingExchangeResponseParser.parse(response.body?.string().orEmpty())
                .oneTimeCredential().consume()
                ?: error("Pairing exchange credential was already consumed")
        }
    }

    suspend fun revokeCredential(deviceId: String, accessToken: String): Result<Unit> = request {
        requireIdentifier(deviceId, "deviceId")
        require(accessToken.isNotBlank()) { "accessToken must not be blank" }
        executeWithUnversionedFallback(
            primary = url("api", "v1", "auth", "devices", deviceId, "revoke"),
            fallback = url("api", "auth", "devices", deviceId, "revoke"),
            request = { endpoint -> Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $accessToken")
                .post(ByteArray(0).toRequestBody()).build() },
            error = "Credential revoke failed"
        ) { Unit }
    }

    private fun <T> executeWithUnversionedFallback(
        primary: HttpUrl,
        fallback: HttpUrl,
        request: (HttpUrl) -> Request,
        error: String,
        parse: (okhttp3.Response) -> T
    ): T {
        client.newCall(request(primary)).execute().use { response ->
            if (response.code != 404 && response.code != 401) {
                check(response.isSuccessful) { "$error: ${response.code}" }
                return parse(response)
            }
        }
        client.newCall(request(fallback)).execute().use { response ->
            check(response.isSuccessful) { "$error: ${response.code}" }
            return parse(response)
        }
    }

    private fun url(vararg pathSegments: String): HttpUrl =
        baseHttpUrl.newBuilder().apply {
            pathSegments.forEach(::addPathSegment)
        }.build()

    private fun validateBaseUrl(url: HttpUrl) {
        require(url.username.isEmpty() && url.password.isEmpty()) {
            "baseUrl must not contain username or password"
        }
        val isLoopbackFixture = url.host == "127.0.0.1" || url.host == "::1"
        require(url.scheme == "https" || (url.scheme == "http" && isLoopbackFixture)) {
            "baseUrl must use HTTPS; cleartext is allowed only for 127.0.0.1 or ::1 fixtures"
        }
    }

    private fun requireIdentifier(value: String, name: String) {
        require(value.isNotBlank()) { "$name must not be blank" }
        require(value.none { it.isISOControl() }) { "$name contains a control character" }
    }

    private fun jsonString(value: String) = buildString {
        append('"')
        value.forEach { when (it) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(it)
        } }
        append('"')
    }

    private suspend fun <T> request(block: () -> T): Result<T> =
        withContext(Dispatchers.IO) { runCatching(block) }
}