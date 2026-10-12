package ru.mars.network.repository

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.mars.network.model.ConsumedDeviceCredential
import ru.mars.network.model.InsightsDailyTokens
import ru.mars.network.model.InsightsHourActivity
import ru.mars.network.model.InsightsModelUsage
import ru.mars.network.model.InsightsSnapshot
import ru.mars.network.model.InsightsWeekdayActivity

/**
 * [InsightsRepository] contract tests on MockWebServer, against the REAL payload
 * captured from the deployed `/opt/hermes-webui/api/route_insights.py`
 * (`build_insights_response`, `days=7`, synthetic isolated session index):
 * single-`days` query mapping with server-side clamping, full usage-snapshot
 * parsing (totals/models/daily_tokens/activity_by_day/activity_by_hour),
 * tolerant degradation on absent/garbage fields and the honest error ladder —
 * the route has no 400 validation and no 503 branches; non-2xx only comes from
 * the auth guard or an unexpected failure and maps to [InsightsResult.Failed].
 */
class InsightsRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: InsightsRepository
    private val credential = ConsumedDeviceCredential(
        accessToken = "native-token", tokenType = "Bearer", deviceId = "device-1",
        scopes = listOf("chat:send"), expiresIn = 3600,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = InsightsRepository()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl() = server.url("/").toString()

    private fun enqueue(code: Int = 200, body: String) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    companion object {
        /**
         * Real `GET /api/insights?days=7` response produced by the deployed
         * `build_insights_response` over a synthetic session index (2026-10-12):
         * 4 sessions in-window across 3 models (one cost-less "unknown" with
         * `cache_hit_percent: null`), one out-of-window session excluded,
         * zero-filled `daily_tokens`, 7 weekday and 24 hour buckets.
         */
        val REAL_USAGE_PAYLOAD = """
{
  "period_days": 7,
  "total_sessions": 4,
  "total_messages": 72,
  "total_input_tokens": 304540,
  "total_output_tokens": 89640,
  "total_cache_read_tokens": 914110,
  "total_cache_hit_percent": 75,
  "total_tokens": 394180,
  "total_cost": 1.8555,
  "models": [
    {"model": "claude-sonnet-4-5", "sessions": 2, "input_tokens": 202740, "output_tokens": 57320, "cache_read_tokens": 902110, "cache_hit_percent": 82, "total_tokens": 260060, "cost": 1.4355, "session_share": 50, "token_share": 66, "cost_share": 77},
    {"model": "gpt-5.5", "sessions": 1, "input_tokens": 94500, "output_tokens": 30210, "cache_read_tokens": 12000, "cache_hit_percent": 11, "total_tokens": 124710, "cost": 0.42, "session_share": 25, "token_share": 32, "cost_share": 23},
    {"model": "unknown", "sessions": 1, "input_tokens": 7300, "output_tokens": 2110, "cache_read_tokens": 0, "cache_hit_percent": null, "total_tokens": 9410, "cost": 0.0, "session_share": 25, "token_share": 2, "cost_share": 0}
  ],
  "daily_tokens": [
    {"date": "2026-10-06", "input_tokens": 0, "output_tokens": 0, "cache_read_tokens": 0, "sessions": 0, "cost": 0.0},
    {"date": "2026-10-07", "input_tokens": 7300, "output_tokens": 2110, "cache_read_tokens": 0, "sessions": 1, "cost": 0.0},
    {"date": "2026-10-08", "input_tokens": 0, "output_tokens": 0, "cache_read_tokens": 0, "sessions": 0, "cost": 0.0},
    {"date": "2026-10-09", "input_tokens": 0, "output_tokens": 0, "cache_read_tokens": 0, "sessions": 0, "cost": 0.0},
    {"date": "2026-10-10", "input_tokens": 94500, "output_tokens": 30210, "cache_read_tokens": 12000, "sessions": 1, "cost": 0.42},
    {"date": "2026-10-11", "input_tokens": 0, "output_tokens": 0, "cache_read_tokens": 0, "sessions": 0, "cost": 0.0},
    {"date": "2026-10-12", "input_tokens": 202740, "output_tokens": 57320, "cache_read_tokens": 902110, "sessions": 2, "cost": 1.4355}
  ],
  "activity_by_day": [{"day": "Mon", "sessions": 2}, {"day": "Tue", "sessions": 0}, {"day": "Wed", "sessions": 1}, {"day": "Thu", "sessions": 0}, {"day": "Fri", "sessions": 0}, {"day": "Sat", "sessions": 1}, {"day": "Sun", "sessions": 0}],
  "activity_by_hour": [
    {"hour": 0, "sessions": 0},
    {"hour": 1, "sessions": 0},
    {"hour": 2, "sessions": 0},
    {"hour": 3, "sessions": 0},
    {"hour": 4, "sessions": 1},
    {"hour": 5, "sessions": 0},
    {"hour": 6, "sessions": 0},
    {"hour": 7, "sessions": 0},
    {"hour": 8, "sessions": 0},
    {"hour": 9, "sessions": 1},
    {"hour": 10, "sessions": 0},
    {"hour": 11, "sessions": 0},
    {"hour": 12, "sessions": 0},
    {"hour": 13, "sessions": 0},
    {"hour": 14, "sessions": 1},
    {"hour": 15, "sessions": 0},
    {"hour": 16, "sessions": 0},
    {"hour": 17, "sessions": 0},
    {"hour": 18, "sessions": 0},
    {"hour": 19, "sessions": 0},
    {"hour": 20, "sessions": 0},
    {"hour": 21, "sessions": 1},
    {"hour": 22, "sessions": 0},
    {"hour": 23, "sessions": 0}
  ]
}
        """.trimIndent()
    }

    @Test
    fun `getInsights sends no query params by default and authorizes with the device bearer`() = runBlocking {
        enqueue(body = "{}")
        repository.getInsights(baseUrl(), credential)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/insights", recorded.path)
        assertEquals("Bearer native-token", recorded.getHeader("Authorization"))
        Unit
    }

    @Test
    fun `getInsights passes days through unchanged - clamping is the server's job`() = runBlocking {
        enqueue(body = "{}")
        repository.getInsights(baseUrl(), credential, days = 90)
        assertEquals("/api/insights?days=90", server.takeRequest().path)
        enqueue(body = "{}")
        // Out-of-range values are NOT rejected by the route (no 400 branch exists):
        // the server clamps to 1..365 itself, the client must not pretend otherwise.
        repository.getInsights(baseUrl(), credential, days = 1000)
        assertEquals("/api/insights?days=1000", server.takeRequest().path)
        Unit
    }

    @Test
    fun `getInsights parses the real deployed usage payload into the full snapshot`() = runBlocking {
        enqueue(body = REAL_USAGE_PAYLOAD)
        val result = repository.getInsights(baseUrl(), credential, days = 7)
        assertEquals("/api/insights?days=7", server.takeRequest().path)
        val snapshot = (result as InsightsResult.Snapshot).snapshot
        assertEquals(expectedSnapshot, snapshot)
        // Contract invariants the UI relies on: server-sorted models, zero-filled
        // daily series of exactly period_days rows, 7 weekday + 24 hour buckets.
        assertEquals(7, snapshot.dailyTokens.size)
        assertEquals(7, snapshot.activityByDay.size)
        assertEquals(24, snapshot.activityByHour.size)
        assertEquals(listOf("claude-sonnet-4-5", "gpt-5.5", "unknown"), snapshot.models.map { it.model })
    }

    @Test
    fun `parseSnapshot degrades to safe defaults on an empty payload`() {
        assertEquals(InsightsSnapshot(), repository.parseSnapshot(emptyMap()))
    }

    @Test
    fun `parseSnapshot tolerates junk rows nulls and numeric strings`() {
        val root = mapOf(
            "period_days" to "30",
            "total_sessions" to 2,
            "total_cost" to "0.5",
            "total_cache_hit_percent" to null,
            "models" to listOf("junk", null, mapOf("model" to "x", "sessions" to "3", "cost" to 0.1)),
            "daily_tokens" to listOf(null, mapOf("date" to 20261012, "input_tokens" to "10")),
            "activity_by_day" to listOf(mapOf("day" to "Mon")),
            "activity_by_hour" to listOf(mapOf("hour" to "5", "sessions" to 1), "junk"),
            "not_a_list" to mapOf("a" to 1),
        )
        val snapshot = repository.parseSnapshot(root)
        assertEquals(30, snapshot.periodDays)
        assertEquals(2, snapshot.totalSessions)
        assertEquals(0.5, snapshot.totalCost, 0.0)
        assertEquals(null, snapshot.totalCacheHitPercent)
        assertEquals(
            listOf(InsightsModelUsage(model = "x", sessions = 3, cost = 0.1)),
            snapshot.models,
        )
        assertEquals(
            listOf(InsightsDailyTokens(date = "20261012", inputTokens = 10L)),
            snapshot.dailyTokens,
        )
        assertEquals(listOf(InsightsWeekdayActivity("Mon", 0)), snapshot.activityByDay)
        assertEquals(listOf(InsightsHourActivity(5, 1)), snapshot.activityByHour)
    }

    @Test
    fun `getInsights maps non-2xx to Failed with the server error text when present`() = runBlocking {
        enqueue(code = 401, body = """{"error":"Missing token"}""")
        assertEquals(
            InsightsResult.Failed("Missing token"),
            repository.getInsights(baseUrl(), credential),
        )
        enqueue(code = 403, body = """{"error":"Scope \"device:chat\" required"}""")
        assertTrue(
            (repository.getInsights(baseUrl(), credential) as InsightsResult.Failed)
                .message.startsWith("Scope"),
        )
        enqueue(code = 500, body = """{"error":"boom"}""")
        assertEquals(InsightsResult.Failed("boom"), repository.getInsights(baseUrl(), credential))
        enqueue(code = 500, body = "not json")
        assertEquals(InsightsResult.Failed("HTTP 500"), repository.getInsights(baseUrl(), credential))
        Unit
    }

    @Test
    fun `getInsights maps an unparseable 200 body to Failed`() = runBlocking {
        enqueue(body = "<html>proxy error</html>")
        val result = repository.getInsights(baseUrl(), credential)
        assertEquals(InsightsResult.Failed("HTTP 200: unparseable body"), result)
    }

    @Test
    fun `getInsights maps a transport failure to Failed`() = runBlocking {
        server.shutdown()
        val result = repository.getInsights(baseUrl(), credential)
        assertTrue(result is InsightsResult.Failed)
        assertTrue((result as InsightsResult.Failed).message.isNotBlank())
    }
}

/**
 * Expected mapping of [REAL_USAGE_PAYLOAD] — derived field-by-field from the
 * same captured JSON (server sorts models by -cost; totals echo the sum of
 * in-window sessions; `unknown` model has null cache-hit and zero cost).
 */
private val expectedSnapshot = InsightsSnapshot(
        periodDays = 7,
        totalSessions = 4,
        totalMessages = 72,
        totalInputTokens = 304540L,
        totalOutputTokens = 89640L,
        totalCacheReadTokens = 914110L,
        totalCacheHitPercent = 75,
        totalTokens = 394180L,
        totalCost = 1.8555,
        models = listOf(
            InsightsModelUsage(
                model = "claude-sonnet-4-5", sessions = 2,
                inputTokens = 202740L, outputTokens = 57320L, cacheReadTokens = 902110L,
                cacheHitPercent = 82, totalTokens = 260060L, cost = 1.4355,
                sessionShare = 50, tokenShare = 66, costShare = 77,
            ),
            InsightsModelUsage(
                model = "gpt-5.5", sessions = 1,
                inputTokens = 94500L, outputTokens = 30210L, cacheReadTokens = 12000L,
                cacheHitPercent = 11, totalTokens = 124710L, cost = 0.42,
                sessionShare = 25, tokenShare = 32, costShare = 23,
            ),
            InsightsModelUsage(
                model = "unknown", sessions = 1,
                inputTokens = 7300L, outputTokens = 2110L, cacheReadTokens = 0L,
                cacheHitPercent = null, totalTokens = 9410L, cost = 0.0,
                sessionShare = 25, tokenShare = 2, costShare = 0,
            ),
        ),
        dailyTokens = listOf(
            InsightsDailyTokens(
                date = "2026-10-06", inputTokens = 0L, outputTokens = 0L,
                cacheReadTokens = 0L, sessions = 0, cost = 0.0,
            ),
            InsightsDailyTokens(
                date = "2026-10-07", inputTokens = 7300L, outputTokens = 2110L,
                cacheReadTokens = 0L, sessions = 1, cost = 0.0,
            ),
            InsightsDailyTokens(
                date = "2026-10-08", inputTokens = 0L, outputTokens = 0L,
                cacheReadTokens = 0L, sessions = 0, cost = 0.0,
            ),
            InsightsDailyTokens(
                date = "2026-10-09", inputTokens = 0L, outputTokens = 0L,
                cacheReadTokens = 0L, sessions = 0, cost = 0.0,
            ),
            InsightsDailyTokens(
                date = "2026-10-10", inputTokens = 94500L, outputTokens = 30210L,
                cacheReadTokens = 12000L, sessions = 1, cost = 0.42,
            ),
            InsightsDailyTokens(
                date = "2026-10-11", inputTokens = 0L, outputTokens = 0L,
                cacheReadTokens = 0L, sessions = 0, cost = 0.0,
            ),
            InsightsDailyTokens(
                date = "2026-10-12", inputTokens = 202740L, outputTokens = 57320L,
                cacheReadTokens = 902110L, sessions = 2, cost = 1.4355,
            ),
        ),
        activityByDay = listOf(InsightsWeekdayActivity("Mon", 2), InsightsWeekdayActivity("Tue", 0), InsightsWeekdayActivity("Wed", 1), InsightsWeekdayActivity("Thu", 0), InsightsWeekdayActivity("Fri", 0), InsightsWeekdayActivity("Sat", 1), InsightsWeekdayActivity("Sun", 0)),
        activityByHour = listOf(InsightsHourActivity(0, 0), InsightsHourActivity(1, 0), InsightsHourActivity(2, 0), InsightsHourActivity(3, 0), InsightsHourActivity(4, 1), InsightsHourActivity(5, 0), InsightsHourActivity(6, 0), InsightsHourActivity(7, 0), InsightsHourActivity(8, 0), InsightsHourActivity(9, 1), InsightsHourActivity(10, 0), InsightsHourActivity(11, 0), InsightsHourActivity(12, 0), InsightsHourActivity(13, 0), InsightsHourActivity(14, 1), InsightsHourActivity(15, 0), InsightsHourActivity(16, 0), InsightsHourActivity(17, 0), InsightsHourActivity(18, 0), InsightsHourActivity(19, 0), InsightsHourActivity(20, 0), InsightsHourActivity(21, 1), InsightsHourActivity(22, 0), InsightsHourActivity(23, 0)),
    )
