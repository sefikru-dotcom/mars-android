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
        val request = Request.Builder()
            .url(url("api", "v1", "auth", "device-pairings"))
            .post(ByteArray(0).toRequestBody())
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Pairing creation failed: ${response.code}" }
            PairingResponseParser.parse(response.body?.string().orEmpty())
        }
    }

    suspend fun pollPairingStatus(pairId: String): Result<PairingStatusResponse> = request {
        requireIdentifier(pairId, "pairId")
        val statusUrl = url("api", "v1", "auth", "device-pairings", "status").newBuilder()
            .addQueryParameter("pair_id", pairId)
            .build()
        val request = Request.Builder().url(statusUrl).get().build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Pairing status failed: ${response.code}" }
            PairingStatusResponseParser.parse(response.body?.string().orEmpty())
        }
    }

    suspend fun exchangePairing(pairId: String, pollSecret: String): Result<ConsumedDeviceCredential> = request {
        requireIdentifier(pairId, "pairId")
        require(pollSecret.isNotBlank()) { "pollSecret must not be blank" }
        val body = "{\"poll_secret\":${jsonString(pollSecret)}}"
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url("api", "v1", "auth", "device-pairings", pairId, "exchange"))
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Pairing exchange failed: ${response.code}" }
            PairingExchangeResponseParser.parse(response.body?.string().orEmpty())
                .oneTimeCredential().consume()
                ?: error("Pairing exchange credential was already consumed")
        }
    }

    suspend fun revokeCredential(deviceId: String, accessToken: String): Result<Unit> = request {
        requireIdentifier(deviceId, "deviceId")
        require(accessToken.isNotBlank()) { "accessToken must not be blank" }
        val request = Request.Builder()
            .url(url("api", "v1", "auth", "devices", deviceId, "revoke"))
            .header("Authorization", "Bearer $accessToken")
            .post(ByteArray(0).toRequestBody()).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Credential revoke failed: ${response.code}" }
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