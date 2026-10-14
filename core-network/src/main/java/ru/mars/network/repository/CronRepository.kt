package ru.mars.network.repository

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.mars.network.http.HttpClients
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.CronJob
import ru.mars.network.model.CronJobsResponse

sealed interface CronJobsResult {
    data class Loaded(val response: CronJobsResponse) : CronJobsResult
    data class Failed(val offline: Boolean, val httpCode: Int? = null) : CronJobsResult
}

/**
 * Access to scheduled cron jobs in the WebUI backend (/api/crons contract):
 * - GET /api/crons — `{"jobs": [...], "active_profile": "..."}`
 * - POST /api/crons/run — `{"id": "..."}`
 * - POST /api/crons/pause — `{"id": "..."}`
 * - POST /api/crons/resume — `{"id": "..."}`
 */
class CronRepository(private val client: OkHttpClient = HttpClients.rest) {

    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val moshi = Moshi.Builder().build()
    private val jsonMediaType = "application/json".toMediaType()

    suspend fun fetchJobs(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
    ): List<CronJob> = getCronJobs(baseUrl, credential).jobs

    suspend fun getCronJobs(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
    ): CronJobsResponse = when (val result = loadJobs(baseUrl, credential)) {
        is CronJobsResult.Loaded -> result.response
        is CronJobsResult.Failed -> CronJobsResponse(emptyList())
    }

    /** Typed outcome for UI consumers; legacy list APIs retain their empty-on-failure contract. */
    suspend fun loadJobs(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
    ): CronJobsResult = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegments("api/crons").build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${credential.accessToken}")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext CronJobsResult.Failed(
                    offline = false,
                    httpCode = response.code,
                )
                val body = response.body?.string().orEmpty()
                CronJobsResult.Loaded(parseCronJobs(body))
            }
        } catch (_: java.io.IOException) {
            CronJobsResult.Failed(offline = true)
        } catch (_: Exception) {
            CronJobsResult.Failed(offline = false)
        }
    }

    suspend fun runJob(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        jobId: String,
    ): Boolean = executePost(baseUrl, credential, "api/crons/run", jobId)

    suspend fun togglePause(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        jobId: String,
        pause: Boolean,
    ): Boolean {
        val segment = if (pause) "api/crons/pause" else "api/crons/resume"
        return executePost(baseUrl, credential, segment, jobId)
    }

    private suspend fun executePost(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        path: String,
        jobId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegments(path).build()
        val json = "{\"id\":\"${jobId.replace("\"", "\\\"")}\"}"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${credential.accessToken}")
            .header("Content-Type", "application/json")
            .post(json.toRequestBody(jsonMediaType))
            .build()
        try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    fun parseCronJobs(payload: String): CronJobsResponse {
        val root = parseMap(payload) ?: return CronJobsResponse(emptyList())
        val cronUnavailable = root["cron_unavailable"] == true
        val activeProfile = root["active_profile"]?.toString()
        val rawJobs = root["jobs"] as? List<*> ?: return CronJobsResponse(emptyList(), activeProfile, cronUnavailable)

        val jobs = rawJobs.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val id = map["id"]?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val name = map["name"]?.toString()?.takeIf(String::isNotBlank) ?: id

            val scheduleMap = map["schedule"] as? Map<*, *>
            val scheduleDisplay = map["schedule_display"]?.toString()
                ?: scheduleMap?.get("display")?.toString()
                ?: scheduleMap?.get("expr")?.toString()

            val enabled = map["enabled"] as? Boolean ?: true
            val state = map["state"]?.toString()
            val nextRunAt = map["next_run_at"]?.toString()
            val lastRunAt = map["last_run_at"]?.toString()
            val lastStatus = map["last_status"]?.toString()
            val prompt = map["prompt"]?.toString()
            val model = map["model"]?.toString()
            val command = map["command"]?.toString()

            CronJob(
                id = id,
                name = name,
                scheduleDisplay = scheduleDisplay,
                enabled = enabled,
                state = state,
                nextRunAt = nextRunAt,
                lastRunAt = lastRunAt,
                lastStatus = lastStatus,
                prompt = prompt,
                model = model,
                command = command,
            )
        }
        return CronJobsResponse(jobs, activeProfile, cronUnavailable)
    }

    private fun parseMap(json: String): Map<String, Any?>? =
        try {
            moshi.adapter<Map<String, Any?>>(mapType).fromJson(json)
        } catch (_: Exception) {
            null
        }
}
