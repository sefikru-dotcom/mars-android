package ru.mars.network.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.mars.network.model.PairingResponse
import ru.mars.network.model.PairingResponseParser
import ru.mars.network.model.PairingStatusResponse
import ru.mars.network.model.PairingStatusResponseParser

class PairingRepository(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient()
) {
    suspend fun createPairing(): Result<PairingResponse> = request {
        val request = Request.Builder()
            .url(url("api/auth/device-pairings"))
            .post(ByteArray(0).toRequestBody())
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Pairing creation failed: ${response.code}" }
            PairingResponseParser.parse(response.body?.string().orEmpty())
        }
    }

    suspend fun pollPairingStatus(pairId: String, pollSecret: String): Result<PairingStatusResponse> = request {
        require(pairId.isNotBlank()) { "pairId must not be blank" }
        require(pollSecret.isNotBlank()) { "pollSecret must not be blank" }
        val statusUrl = url("api/auth/device-pairings/status").newBuilder()
            .addQueryParameter("pair_id", pairId)
            .addQueryParameter("poll_secret", pollSecret)
            .build()
        val request = Request.Builder().url(statusUrl).get().build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Pairing status failed: ${response.code}" }
            PairingStatusResponseParser.parse(response.body?.string().orEmpty())
        }
    }

    private fun url(path: String) = "${baseUrl.trimEnd('/')}/$path".toHttpUrl()

    private suspend fun <T> request(block: () -> T): Result<T> =
        withContext(Dispatchers.IO) { runCatching(block) }
}
