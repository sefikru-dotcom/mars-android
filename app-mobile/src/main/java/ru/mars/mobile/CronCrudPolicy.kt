package ru.mars.mobile

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import ru.mars.network.model.CronRunSummary
import ru.mars.network.model.CronRunUsage

/** Форма расписания, распознаваемая клиентом (зеркалит серверный parse_schedule). */
enum class CronScheduleKind {
    /** Период: «30m», «every 2h». */
    INTERVAL,

    /** Разовый запуск через интервал: «in 30m». */
    ONE_SHOT_DELAY,

    /** Естественная фраза: «every monday 9am», «weekdays at 9am». */
    NATURAL_WEEKLY,

    /** Cron-выражение из 5–6 полей: «0 9 * * *». */
    CRON_EXPR,

    /** ISO-временная метка (разово): «2026-10-14T07:00:00». */
    TIMESTAMP,

    /** Не распознано — форма не даст отправить, сервер бы ответил 400. */
    INVALID,
}

/** Результат проверки формы создания/правки задачи. */
data class CronFormValidation(
    val ok: Boolean,
    val promptError: String?,
    val scheduleError: String?,
)

/**
 * Чистая JVM-политика CRUD планировщика: валидация формы по правилам серверного
 * cron.jobs.parse_schedule, форматирование истории прогонов (GET /api/crons/history)
 * и тексты подтверждений. UI (CronActivity) ничего не вычисляет сам.
 */
object CronCrudPolicy {

    private val DURATION = Regex("^\\d+\\s*[smhdw]$")
    private val CRON_FIELD = Regex("^[A-Za-z\\d*\\-,/]+$")
    private val DAY_WORDS = listOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "mon", "tue", "wed", "thu", "fri", "sat", "sun",
        "weekday", "weekdays", "weekend", "daily", "day", "morning", "evening", "night",
    )

    private const val SCHEDULE_HINT = "Неверное расписание. Примеры: «30m», «every 2h», " +
        "«in 30m», «every monday 9am», «0 9 * * *», «2026-10-14T07:00:00»"

    fun classifySchedule(raw: String): CronScheduleKind {
        val schedule = raw.trim()
        if (schedule.isEmpty()) return CronScheduleKind.INVALID
        val lower = schedule.lowercase(Locale.US)

        if (lower.startsWith("in ")) {
            val rest = lower.removePrefix("in ").trim()
            return if (DURATION.matches(rest)) CronScheduleKind.ONE_SHOT_DELAY else CronScheduleKind.INVALID
        }
        if (lower.startsWith("every ")) {
            val rest = lower.removePrefix("every ").trim()
            return when {
                DURATION.matches(rest) -> CronScheduleKind.INTERVAL
                hasDayWord(rest) -> CronScheduleKind.NATURAL_WEEKLY
                else -> CronScheduleKind.INVALID
            }
        }
        val parts = schedule.split(Regex("\\s+"))
        if (parts.size in 5..6 && parts.all { CRON_FIELD.matches(it) }) return CronScheduleKind.CRON_EXPR
        if (Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(lower)) return CronScheduleKind.TIMESTAMP
        if (DURATION.matches(lower)) return CronScheduleKind.INTERVAL
        if (hasDayWord(lower) && Regex("\\bat\\b").containsMatchIn(lower)) return CronScheduleKind.NATURAL_WEEKLY
        return CronScheduleKind.INVALID
    }

    fun validateForm(prompt: String, schedule: String): CronFormValidation {
        val promptError = if (prompt.isBlank()) "Введите промпт задачи" else null
        val scheduleError = if (classifySchedule(schedule) == CronScheduleKind.INVALID) SCHEDULE_HINT else null
        return CronFormValidation(
            ok = promptError == null && scheduleError == null,
            promptError = promptError,
            scheduleError = scheduleError,
        )
    }

    fun deleteConfirmText(jobName: String): String =
        "Удалить задачу «$jobName»? Действие необратимо: история прогонов останется только на сервере."

    fun emptyHistoryText(): String = "История прогонов пуста"

    /** Строка истории: «14.10.2026 07:00 · 1.5 KB · gpt-x · 12.5s»; без меты — имя файла. */
    fun formatRunRow(summary: CronRunSummary, zone: ZoneId = ZoneId.systemDefault()): String {
        val parts = mutableListOf<String>()
        val epoch = summary.modifiedEpochSeconds
        parts += if (epoch != null) {
            RUN_TIME_FORMAT.withZone(zone).format(Instant.ofEpochSecond(epoch.toLong()))
        } else {
            summary.filename
        }
        formatBytes(summary.sizeBytes).takeIf(String::isNotBlank)?.let(parts::add)
        formatUsage(summary.usage).takeIf(String::isNotBlank)?.let(parts::add)
        return parts.joinToString(" · ")
    }

    fun formatBytes(sizeBytes: Long?): String {
        if (sizeBytes == null) return ""
        val value = sizeBytes.toDouble()
        return when {
            value < 1024 -> "$sizeBytes B"
            value < 1024 * 1024 -> oneDecimal(value / 1024) + " KB"
            else -> oneDecimal(value / (1024 * 1024)) + " MB"
        }
    }

    /** Краткая сводка usage прогона: «gpt-x · 12.5s · $0.002 · 100 tok»; пусто, если мет нет. */
    fun formatUsage(usage: CronRunUsage?): String {
        if (usage == null) return ""
        val parts = mutableListOf<String>()
        usage.model?.takeIf(String::isNotBlank)?.let(parts::add)
        usage.durationSeconds?.let { parts.add(trimNumber(it) + "s") }
        usage.estimatedCostUsd?.let { parts.add("$" + trimNumber(it, decimals = 4)) }
        usage.totalTokens?.let { parts.add("$it tok") }
        return parts.joinToString(" · ")
    }

    private fun hasDayWord(value: String): Boolean = DAY_WORDS.any { Regex("\\b$it\\b").containsMatchIn(value) }

    private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun trimNumber(value: Double, decimals: Int = 1): String =
        String.format(Locale.US, "%.${decimals}f", value).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    private val RUN_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
}
