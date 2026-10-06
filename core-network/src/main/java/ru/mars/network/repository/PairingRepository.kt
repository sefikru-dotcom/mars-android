package ru.mars.network.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import ru.mars.network.model.*

class PairingRepository(private val baseUrl: String, private val client: OkHttpClient = OkHttpClient()) {
    suspend fun pair(code: String, deviceName: String): Result<PairingResponse> = request {
        val body = JSONObject().put("code", code).put("deviceName", deviceName).put("platform", "android-tv")
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/auth/device-pairings")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Pairing failed: ${response.code}" }
            val json = JSONObject(response.body?.string().orEmpty())
            PairingResponse(json.optString("deviceId", null), json.optString("accessToken", null), json.optString("status", null), json.optString("expiresAt", null))
        }
    }
    suspend fun listPairings(): Result<List<DevicePairing>> = request {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/auth/device-pairings").get().build()
        client.newCall(request).execute().use { response -> check(response.isSuccessful); emptyList() }
    }
    private suspend fun <T> request(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(block) }
}
