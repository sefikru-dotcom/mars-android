package ru.mars.network.model

/**
 * Wire models for `GET /api/insights` (contract verified read-only against the
 * deployed `/opt/hermes-webui/api/route_insights.py` — `build_insights_response`).
 *
 * The endpoint has exactly ONE query param:
 * - `days` — window length in days. The server clamps it into 1..365
 *   (`min(max(int(days), 1), 365)`) and falls back to 30 when the param is
 *   absent or unparsable. There is NO 400 validation branch and NO 503
 *   "unavailable" branch in this route: it aggregates local WebUI session data
 *   (plus CLI sessions from state.db) and answers 200.
 *
 * 200 payload: `{period_days, total_sessions, total_messages, total_input_tokens,
 * total_output_tokens, total_cache_read_tokens, total_cache_hit_percent,
 * total_tokens, total_cost, models[], daily_tokens[], activity_by_day[],
 * activity_by_hour[]}`. Cache-hit percents are whole ints 0..100 or null when
 * there is nothing meaningful to show; costs are floats rounded to 6 decimals;
 * `daily_tokens` is zero-filled to exactly `period_days` rows (oldest first);
 * `activity_by_day` is 7 weekday buckets (`Mon`..`Sun`) and `activity_by_hour`
 * is 24 hour buckets — both always present, possibly all-zero.
 *
 * Non-2xx answers come from the API auth guard (401/403 `{"error": …}`) or an
 * unexpected upstream failure (500) — never from filter validation.
 */

/** One `models[]` row; the server sorts rows by (-cost, -sessions, model). */
data class InsightsModelUsage(
    val model: String = "",
    val sessions: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    /** Whole percent 0..100; null when the server had no meaningful denominator. */
    val cacheHitPercent: Int? = null,
    /** `input_tokens + output_tokens` (cache reads excluded), as computed server-side. */
    val totalTokens: Long = 0,
    val cost: Double = 0.0,
    /** Whole-percent shares (0..100) of the window totals. */
    val sessionShare: Int = 0,
    val tokenShare: Int = 0,
    val costShare: Int = 0,
)

/**
 * One `daily_tokens[]` row — one calendar day of the window. The server
 * zero-fills days without activity, so a row with all-zero counters is normal.
 */
data class InsightsDailyTokens(
    val date: String = "",
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val sessions: Int = 0,
    val cost: Double = 0.0,
) {
    /** `input + output`, mirroring the server's `total_tokens` definition. */
    val totalTokens: Long get() = inputTokens + outputTokens
}

/** One `activity_by_day[]` row — day-of-week bucket; `day` is `Mon`..`Sun` (always 7 rows). */
data class InsightsWeekdayActivity(
    val day: String = "",
    val sessions: Int = 0,
)

/** One `activity_by_hour[]` row — hour-of-day bucket 0..23 (always 24 rows). */
data class InsightsHourActivity(
    val hour: Int = 0,
    val sessions: Int = 0,
)

/**
 * Full `GET /api/insights` usage snapshot. Every field degrades to a safe
 * default when absent/null/garbage (tolerant parsing in `InsightsRepository`).
 */
data class InsightsSnapshot(
    /** Server-echoed window length after clamping (1..365); 0 when absent. */
    val periodDays: Int = 0,
    val totalSessions: Int = 0,
    val totalMessages: Int = 0,
    val totalInputTokens: Long = 0,
    val totalOutputTokens: Long = 0,
    val totalCacheReadTokens: Long = 0,
    /** Whole percent 0..100; null when there were no cache reads / prompt tokens. */
    val totalCacheHitPercent: Int? = null,
    /** `total_input_tokens + total_output_tokens` (cache reads excluded). */
    val totalTokens: Long = 0,
    val totalCost: Double = 0.0,
    val models: List<InsightsModelUsage> = emptyList(),
    val dailyTokens: List<InsightsDailyTokens> = emptyList(),
    val activityByDay: List<InsightsWeekdayActivity> = emptyList(),
    val activityByHour: List<InsightsHourActivity> = emptyList(),
)
