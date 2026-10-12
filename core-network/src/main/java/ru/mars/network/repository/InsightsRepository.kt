package ru.mars.network.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.mars.network.http.HttpClients
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.InsightsDailyTokens
import ru.mars.network.model.InsightsHourActivity
import ru.mars.network.model.InsightsModelUsage
import ru.mars.network.model.InsightsSnapshot
import ru.mars.network.model.InsightsWeekdayActivity
import java.io.IOException

/** Outcome of `GET /api/insights`. */
sealed interface InsightsResult {
    /** 2xx with a parseable usage snapshot. */
    data class Snapshot(val snapshot: InsightsSnapshot) : InsightsResult

    /**
     * Any non-2xx (auth-guard 401/403 `{"error": …}`, unexpected 500), an
     * unparseable 2xx body, or a transport failure. The route itself has no
     * validation branches — `days` is clamped server-side, never rejected.
     */
    data class Failed(val message: String) : InsightsResult
}

/**
 * `GET /api/insights?days=` — usage analytics of the WebUI backend (contract
 * verified read-only against the deployed `/opt/hermes-webui/api/route_insights.py`).
 *
 * [days] is the ONLY query param and it can never be "invalid": the server
 * parses it tolerantly, clamps into 1..365 and defaults to 30 when absent or
 * unparsable (`period_days` in the snapshot echoes the applied value). The
 * client passes the caller's value through as-is so the UI shows the window
 * the server actually aggregated; there is no 400 branch to surface.
 *
 * Payload: totals (`total_sessions`/`total_messages`/`total_*_tokens`/
 * `total_cache_hit_percent`/`total_cost`), per-model breakdown (`models[]`),
 * zero-filled per-day token series (`daily_tokens[]`), weekday buckets
 * (`activity_by_day[]`, Mon..Sun) and hour buckets (`activity_by_hour[]`,
 * 0..23). Parsing is tolerant: absent/null/garbage fields degrade to safe
 * defaults instead of failing the whole snapshot.
 */
class InsightsRepository(private val client: OkHttpClient = HttpClients.rest) {

    suspend fun getInsights(
        baseUrl: String,
        credential: ConsumedDeviceCredential,
        days: Int? = null,
    ): InsightsResult = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/insights")
            .intParam("days", days)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${credential.accessToken}")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val root = TolerantJson.parseMap(body)
                val code = response.code
                when {
                    root == null && code in 200..299 ->
                        InsightsResult.Failed("HTTP $code: unparseable body")
                    code in 200..299 -> InsightsResult.Snapshot(parseSnapshot(root!!))
                    else -> InsightsResult.Failed(errorOf(root, code))
                }
            }
        } catch (error: IOException) {
            // IOException messages carry host/path at worst — never request headers, no token leak.
            InsightsResult.Failed(error.message ?: "Network error")
        }
    }

    private fun errorOf(root: Map<String, Any?>?, code: Int): String =
        TolerantJson.string(root, "error") ?: "HTTP $code"

    // ── parsing (tolerant: every counter/series degrades to zero/empty when absent) ──

    internal fun parseSnapshot(root: Map<String, Any?>): InsightsSnapshot = InsightsSnapshot(
        periodDays = TolerantJson.int(root, "period_days"),
        totalSessions = TolerantJson.int(root, "total_sessions"),
        totalMessages = TolerantJson.int(root, "total_messages"),
        totalInputTokens = TolerantJson.long(root, "total_input_tokens"),
        totalOutputTokens = TolerantJson.long(root, "total_output_tokens"),
        totalCacheReadTokens = TolerantJson.long(root, "total_cache_read_tokens"),
        // Null is a legitimate server value (nothing meaningful to display).
        totalCacheHitPercent = TolerantJson.intOrNull(root, "total_cache_hit_percent"),
        totalTokens = TolerantJson.long(root, "total_tokens"),
        totalCost = TolerantJson.doubleOrNull(root, "total_cost") ?: 0.0,
        models = TolerantJson.listField(root, "models").mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null // junk row — drop it
            InsightsModelUsage(
                model = TolerantJson.string(map, "model").orEmpty(),
                sessions = TolerantJson.int(map, "sessions"),
                inputTokens = TolerantJson.long(map, "input_tokens"),
                outputTokens = TolerantJson.long(map, "output_tokens"),
                cacheReadTokens = TolerantJson.long(map, "cache_read_tokens"),
                cacheHitPercent = TolerantJson.intOrNull(map, "cache_hit_percent"),
                totalTokens = TolerantJson.long(map, "total_tokens"),
                cost = TolerantJson.doubleOrNull(map, "cost") ?: 0.0,
                sessionShare = TolerantJson.int(map, "session_share"),
                tokenShare = TolerantJson.int(map, "token_share"),
                costShare = TolerantJson.int(map, "cost_share"),
            )
        },
        dailyTokens = TolerantJson.listField(root, "daily_tokens").mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            InsightsDailyTokens(
                date = TolerantJson.string(map, "date").orEmpty(),
                inputTokens = TolerantJson.long(map, "input_tokens"),
                outputTokens = TolerantJson.long(map, "output_tokens"),
                cacheReadTokens = TolerantJson.long(map, "cache_read_tokens"),
                sessions = TolerantJson.int(map, "sessions"),
                cost = TolerantJson.doubleOrNull(map, "cost") ?: 0.0,
            )
        },
        activityByDay = TolerantJson.listField(root, "activity_by_day").mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            InsightsWeekdayActivity(
                day = TolerantJson.string(map, "day").orEmpty(),
                sessions = TolerantJson.int(map, "sessions"),
            )
        },
        activityByHour = TolerantJson.listField(root, "activity_by_hour").mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            InsightsHourActivity(
                hour = TolerantJson.int(map, "hour"),
                sessions = TolerantJson.int(map, "sessions"),
            )
        },
    )

    private fun HttpUrl.Builder.intParam(name: String, value: Int?): HttpUrl.Builder =
        apply { value?.let { addQueryParameter(name, it.toString()) } }
}
