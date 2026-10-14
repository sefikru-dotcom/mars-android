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
import ru.mars.network.model.CronDeliveryOption
import ru.mars.network.model.CronHistoryResponse
import ru.mars.network.model.CronJob
import ru.mars.network.model.CronJobsResponse
import ru.mars.network.model.CronRunDetail
import ru.mars.network.model.CronRunSummary
import ru.mars.network.model.CronRunUsage

sealed interface CronJobsResult {
    data class Loaded(val response: CronJobsResponse) : CronJobsResult
    data class Failed(val offline: Boolean, val httpCode: Int? = null) : CronJobsResult
}

/**
 * Исход POST-мутации планировщика (create/update/delete/run/pause/resume).
 * Сервер отвечает `{"ok":true,"job":{...}}` (delete: `{"ok":true,"job_id":...}`),
 * а при ошибке — `{"error":"..."}` (400/404); run может вернуть HTTP 200 с
 * `{"ok":false,"status":"already_running"}` — это Failed(error="already_running").
 */
sealed interface CronMutationResult {
    data class Ok(val job: CronJob?) : CronMutationResult
    data class Failed(val error: String?, val httpCode: Int? = null) : CronMutationResult
}

/**
 * Access to scheduled cron jobs in the WebUI backend (/api/crons contract,
 * hermes-webui api/routes.py):
 * - GET /api/crons — `{"jobs": [...], "active_profile": "..."}`
 * - GET /api/crons/history?job_id&offset&limit — `{"job_id","runs","total","offset"}`
 * - GET /api/crons/run?job_id&filename — `{"job_id","filename","content","snippet","usage"}`
 * - GET /api/crons/delivery-options — `{"platforms":[{"value","label"}]}`
 * - POST /api/crons/create — `{"prompt","schedule","name"?,"deliver"?,"model"?}` → `{"ok",job}`
 * - POST /api/crons/update — `{"job_id", <changed fields>}` → `{"ok",job}`
 * - POST /api/crons/delete|run|pause|resume — `{"job_id": "..."}`
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

    // ── CRUD планировщика (POST /api/crons/create|update|delete) ───────────────

    /** Создание задачи. Контракт сервера: require(body, "prompt", "schedule"). */
    suspend fun createJob(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        prompt: String,
        schedule: String,
        name: String? = null,
        deliver: String? = null,
        model: String? = null,
    ): CronMutationResult = executeJson(
        baseUrl,
        credential,
        "api/crons/create",
        CronRequestBodies.createBody(prompt, schedule, name, deliver, model),
    )

    /** Правка задачи: уходят только непустые поля + обязательный job_id. */
    suspend fun updateJob(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        jobId: String,
        prompt: String? = null,
        schedule: String? = null,
        name: String? = null,
        deliver: String? = null,
        model: String? = null,
    ): CronMutationResult = executeJson(
        baseUrl,
        credential,
        "api/crons/update",
        CronRequestBodies.updateBody(jobId, prompt, schedule, name, deliver, model),
    )

    /** Удаление задачи (на сервере remove_job; 404 если задачи нет). */
    suspend fun deleteJob(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        jobId: String,
    ): CronMutationResult = executeJson(
        baseUrl,
        credential,
        "api/crons/delete",
        CronRequestBodies.idBody(jobId),
    )

    // ── История и вывод прогонов (GET) ─────────────────────────────────────────

    /** Список прогонов задачи: GET /api/crons/history?job_id&offset&limit. */
    suspend fun fetchHistory(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        jobId: String,
        offset: Int = 0,
        limit: Int = 50,
    ): CronHistoryResponse = executeGet(
        baseUrl,
        credential,
        "api/crons/history",
        listOf("job_id" to jobId, "offset" to offset.toString(), "limit" to limit.toString()),
    ) { parseHistory(it) } ?: CronHistoryResponse(jobId)

    /** Полный вывод одного прогона: GET /api/crons/run?job_id&filename (null при ошибке/404). */
    suspend fun fetchRunDetail(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        jobId: String,
        filename: String,
    ): CronRunDetail? = executeGet(
        baseUrl,
        credential,
        "api/crons/run",
        listOf("job_id" to jobId, "filename" to filename),
    ) { parseRunDetail(it) }

    /** Опции доставки: GET /api/crons/delivery-options → platforms[]. */
    suspend fun fetchDeliveryOptions(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
    ): List<CronDeliveryOption> = executeGet(
        baseUrl,
        credential,
        "api/crons/delivery-options",
        emptyList(),
    ) { parseDeliveryOptions(it) } ?: emptyList()

    private suspend fun executePost(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        path: String,
        jobId: String,
    ): Boolean = (executeJson(baseUrl, credential, path, CronRequestBodies.idBody(jobId))
        is CronMutationResult.Ok)

    /** POST с готовым JSON-телом; разбор ответа — [parseMutationResult]. */
    private suspend fun executeJson(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        path: String,
        json: String,
    ): CronMutationResult = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegments(path).build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${credential.accessToken}")
            .header("Content-Type", "application/json")
            .post(json.toRequestBody(jsonMediaType))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when (val parsed = parseMutationResult(body)) {
                    is CronMutationResult.Ok ->
                        if (response.isSuccessful) parsed
                        else CronMutationResult.Failed(null, response.code)
                    is CronMutationResult.Failed -> parsed.copy(httpCode = response.code)
                }
            }
        } catch (_: Exception) {
            CronMutationResult.Failed(null, null)
        }
    }

    /** GET с query-параметрами; null при сетевой ошибке или не-2xx. */
    private suspend fun <T> executeGet(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        path: String,
        query: List<Pair<String, String>>,
        parse: (String) -> T,
    ): T? = withContext(Dispatchers.IO) {
        val builder = baseUrl.toHttpUrl().newBuilder().addPathSegments(path)
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        val request = Request.Builder()
            .url(builder.build())
            .header("Authorization", "Bearer ${credential.accessToken}")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parse(response.body?.string().orEmpty())
            }
        } catch (_: Exception) {
            null
        }
    }

    fun parseCronJobs(payload: String): CronJobsResponse {
        val root = parseMap(payload) ?: return CronJobsResponse(emptyList())
        val cronUnavailable = root["cron_unavailable"] == true
        val activeProfile = root["active_profile"]?.toString()
        val rawJobs = root["jobs"] as? List<*> ?: return CronJobsResponse(emptyList(), activeProfile, cronUnavailable)

        val jobs = rawJobs.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            mapJob(map)
        }
        return CronJobsResponse(jobs, activeProfile, cronUnavailable)
    }

    /** Разбор одной задачи из `jobs[]` / `job` серверного ответа. */
    private fun mapJob(map: Map<*, *>): CronJob? {
        val id = map["id"]?.toString()?.takeIf(String::isNotBlank) ?: return null
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

        return CronJob(
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

    // ── Парсеры ответов CRUD/истории (чистые JVM, покрыты тестами) ─────────────

    /** Ответ create/update/delete/run/pause/resume: `{"ok":true,"job":{...}}` или `{"error":...}`. */
    fun parseMutationResult(payload: String): CronMutationResult {
        val root = parseMap(payload) ?: return CronMutationResult.Failed(null)
        if (root["ok"] == true) {
            val jobMap = root["job"] as? Map<*, *>
            return CronMutationResult.Ok(jobMap?.let { mapJob(it) })
        }
        // run при занятой задаче: {"ok":false,"status":"already_running"} — берём status как причину.
        val error = root["error"]?.toString() ?: root["status"]?.toString()
        return CronMutationResult.Failed(error)
    }

    /** Ответ GET /api/crons/history: `{job_id, runs:[{filename,size,modified,usage}], total, offset}`. */
    fun parseHistory(payload: String): CronHistoryResponse {
        val root = parseMap(payload) ?: return CronHistoryResponse()
        if (root["error"] != null) return CronHistoryResponse(jobId = root["job_id"]?.toString())
        val runs = (root["runs"] as? List<*>).orEmpty().mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val filename = map["filename"]?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            CronRunSummary(
                filename = filename,
                sizeBytes = (map["size"] as? Number)?.toLong(),
                modifiedEpochSeconds = (map["modified"] as? Number)?.toDouble(),
                usage = parseUsage(map["usage"] as? Map<*, *>),
            )
        }
        return CronHistoryResponse(
            jobId = root["job_id"]?.toString(),
            runs = runs,
            total = (root["total"] as? Number)?.toInt() ?: runs.size,
            offset = (root["offset"] as? Number)?.toInt() ?: 0,
        )
    }

    /** Ответ GET /api/crons/run: `{job_id, filename, content, snippet, usage}`; `{"error":...}` → null. */
    fun parseRunDetail(payload: String): CronRunDetail? {
        val root = parseMap(payload) ?: return null
        if (root["error"] != null) return null
        val jobId = root["job_id"]?.toString() ?: return null
        return CronRunDetail(
            jobId = jobId,
            filename = root["filename"]?.toString(),
            content = root["content"]?.toString(),
            snippet = root["snippet"]?.toString(),
            usage = parseUsage(root["usage"] as? Map<*, *>),
        )
    }

    /** Ответ GET /api/crons/delivery-options: `{platforms:[{value,label}]}`. */
    fun parseDeliveryOptions(payload: String): List<CronDeliveryOption> {
        val root = parseMap(payload) ?: return emptyList()
        val platforms = root["platforms"] as? List<*> ?: return emptyList()
        return platforms.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val value = map["value"]?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            CronDeliveryOption(value = value, label = map["label"]?.toString() ?: value)
        }
    }

    /** usage-словарь прогона; пустой `{}` (серверная норма) → null. */
    private fun parseUsage(map: Map<*, *>?): CronRunUsage? {
        if (map.isNullOrEmpty()) return null
        return CronRunUsage(
            model = map["model"]?.toString(),
            provider = map["provider"]?.toString(),
            durationSeconds = (map["duration_seconds"] as? Number)?.toDouble(),
            estimatedCostUsd = (map["estimated_cost_usd"] as? Number)?.toDouble(),
            inputTokens = (map["input_tokens"] as? Number)?.toInt(),
            outputTokens = (map["output_tokens"] as? Number)?.toInt(),
            totalTokens = (map["total_tokens"] as? Number)?.toInt(),
        )
    }

    private fun parseMap(json: String): Map<String, Any?>? =
        try {
            moshi.adapter<Map<String, Any?>>(mapType).fromJson(json)
        } catch (_: Exception) {
            null
        }
}
