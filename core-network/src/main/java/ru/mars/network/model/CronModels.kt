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
