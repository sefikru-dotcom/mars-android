package ru.mars.tv.cron

import ru.mars.network.model.CronJob
import ru.mars.network.model.CronJobsResponse

/**
 * Pure presentation policy of the TV «Cron» monitoring screen (no Android, no I/O —
 * JVM-testable, [ru.mars.tv.todos.TvTodosPolicy] parity). Owns every DECISION:
 * system-status header, per-job wording, filtering and focus restoration;
 * [TvCronActivity] only renders what the policy returns.
 *
 * Wording is mobile `CronActivity` parity («Активна» / «На паузе», «След.» / «Посл.»)
 * with one deliberate divergence: NO invented fallbacks. Mobile renders a missing
 * schedule as «По расписанию» and a missing last status as «ok»; a monitoring screen
 * must not fabricate facts, so absent fields are omitted ([scheduleLine], [timingLine]
 * return `null` when there is nothing real to show) and the UI hides the row.
 */
object TvCronPolicy {

    /** [restoreSelection] result when there is nothing to focus. */
    const val NO_SELECTION = -1

    /** Server explicitly reported the cron subsystem is down (`cron_unavailable`). */
    const val CRON_UNAVAILABLE = "Планировщик недоступен на сервере"

    /**
     * Empty job list without the unavailable flag: [ru.mars.network.repository.CronRepository]
     * fails soft, so an outage and a genuinely empty schedule look identical here — the copy
     * stays honest about both.
     */
    const val EMPTY_LIST = "Список задач пуст или сервер недоступен"

    /** The filtered view of a non-empty list came out empty. */
    const val EMPTY_FILTER = "Нет задач с выбранным фильтром"

    /** Header prefix of the active Hermes profile. */
    const val PROFILE_PREFIX = "Профиль: "

    /** Filter row, in D-pad order (left to right). */
    enum class CronFilter(val label: String) {
        ALL("Все"),
        ACTIVE("Активные"),
        PAUSED("На паузе"),
    }

    /** Semantic dot token — the Activity maps it to theme colors, the policy stays resource-free. */
    enum class StateToken { ACTIVE, PAUSED }

    /** One rendered job card: everything the TV shows, nothing it computes. */
    data class JobRow(
        val id: String,
        val title: String,
        val stateLabel: String,
        val token: StateToken,
        /** «Расписание: X · model» — `null` when the job carries neither field. */
        val scheduleLine: String?,
        /** «След.: … · Посл.: … (status)» — `null` when the job never ran and has no next run. */
        val timingLine: String?,
    )

    /** State badge of a job — mobile parity. */
    fun stateLabel(enabled: Boolean): String = if (enabled) "Активна" else "На паузе"

    /**
     * System-status header line: the explicit unavailable flag wins, otherwise the
     * active profile (when the server sent one) plus the job count. «задач» is declined
     * ([jobsWord]); a zero count is rendered, not hidden — zero IS the monitored fact.
     */
    fun statusHeader(response: CronJobsResponse): String {
        if (response.cronUnavailable) return CRON_UNAVAILABLE
        val parts = buildList {
            response.activeProfile?.trim()?.takeIf { it.isNotEmpty() }?.let { add("$PROFILE_PREFIX$it") }
            add("${jobsWord(response.jobs.size)}: ${response.jobs.size}")
        }
        return parts.joinToString(" · ")
    }

    /** Russian plural of «задача» for the header (1 задача, 2 задачи, 5 задач, 11/21…). */
    fun jobsWord(count: Int): String {
        val mod10 = count % 10
        val mod100 = count % 100
        return when {
            mod10 == 1 && mod100 != 11 -> "Задача"
            mod10 in 2..4 && mod100 !in 12..14 -> "Задачи"
            else -> "Задач"
        }
    }

    /** «Расписание: <display> · <model>»; each part only when present, `null` when both are absent. */
    fun scheduleLine(job: CronJob): String? {
        val parts = buildList {
            job.scheduleDisplay?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Расписание: $it") }
            job.model?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        }
        return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
    }

    /**
     * «След.: <next> · Посл.: <last> (<status>)»; timestamps are rendered verbatim
     * (the server's own display strings), the last-status suffix only when the server
     * actually reported one — never an invented «ok». `null` when nothing ran yet.
     */
    fun timingLine(job: CronJob): String? {
        val parts = buildList {
            job.nextRunAt?.trim()?.takeIf { it.isNotEmpty() }?.let { add("След.: $it") }
            job.lastRunAt?.trim()?.takeIf { it.isNotEmpty() }?.let { last ->
                val status = job.lastStatus?.trim()?.takeIf { it.isNotEmpty() }
                add(if (status != null) "Посл.: $last ($status)" else "Посл.: $last")
            }
        }
        return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
    }

    /** Whether [job] passes [filter]. */
    fun matches(job: CronJob, filter: CronFilter): Boolean = when (filter) {
        CronFilter.ALL -> true
        CronFilter.ACTIVE -> job.enabled
        CronFilter.PAUSED -> !job.enabled
    }

    /** Display rows under [filter], server order preserved. */
    fun rows(jobs: List<CronJob>, filter: CronFilter): List<JobRow> =
        jobs.filter { matches(it, filter) }.map { job ->
            JobRow(
                id = job.id,
                title = job.name,
                stateLabel = stateLabel(job.enabled),
                token = if (job.enabled) StateToken.ACTIVE else StateToken.PAUSED,
                scheduleLine = scheduleLine(job),
                timingLine = timingLine(job),
            )
        }

    /**
     * Empty-list line inside the data area: the server-reported unavailability wins,
     * then the honest soft-failure copy for an empty list, then [EMPTY_FILTER] when
     * jobs exist but the current filter passes none of them. Filter-agnostic by
     * construction: any non-[CronFilter.ALL] filter reaching this branch with a
     * non-empty list means "nothing matched".
     */
    fun emptyLine(response: CronJobsResponse): String = when {
        response.cronUnavailable -> CRON_UNAVAILABLE
        response.jobs.isEmpty() -> EMPTY_LIST
        else -> EMPTY_FILTER
    }

    /**
     * Focus restoration after a re-render (filter switch, auto-refresh): the previous
     * index clamped into the new list — edges STAY, never wrap; [NO_SELECTION] when empty.
     */
    fun restoreSelection(previous: Int, size: Int): Int =
        if (size <= 0) NO_SELECTION else previous.coerceIn(0, size - 1)
}
