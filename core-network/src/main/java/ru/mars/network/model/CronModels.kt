package ru.mars.network.model

data class CronJob(
    val id: String,
    val name: String,
    val scheduleDisplay: String? = null,
    val enabled: Boolean = true,
    val state: String? = null,
    val nextRunAt: String? = null,
    val lastRunAt: String? = null,
    val lastStatus: String? = null,
    val prompt: String? = null,
    val model: String? = null,
    val command: String? = null,
)

data class CronJobsResponse(
    val jobs: List<CronJob>,
    val activeProfile: String? = null,
    val cronUnavailable: Boolean = false,
)

/**
 * Метаданные расхода одного прогона (usage-словарь сервера:
 * hermes-webui api/route_cron_output.usage_metadata — model/provider/cost/
 * duration/tokens; пустой словарь сервер возвращает как {}).
 */
data class CronRunUsage(
    val model: String? = null,
    val provider: String? = null,
    val durationSeconds: Double? = null,
    val estimatedCostUsd: Double? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
)

/** Один прогон в истории: GET /api/crons/history → runs[]. */
data class CronRunSummary(
    val filename: String,
    val sizeBytes: Long? = null,
    val modifiedEpochSeconds: Double? = null,
    val usage: CronRunUsage? = null,
)

/** Ответ истории: GET /api/crons/history?job_id&offset&limit. */
data class CronHistoryResponse(
    val jobId: String? = null,
    val runs: List<CronRunSummary> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
)

/** Полный вывод прогона: GET /api/crons/run?job_id&filename. */
data class CronRunDetail(
    val jobId: String?,
    val filename: String?,
    val content: String?,
    val snippet: String?,
    val usage: CronRunUsage? = null,
)

/** Опция доставки: GET /api/crons/delivery-options → platforms[]. */
data class CronDeliveryOption(
    val value: String,
    val label: String,
)
