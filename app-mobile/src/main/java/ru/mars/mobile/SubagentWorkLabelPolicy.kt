package ru.mars.mobile

import ru.mars.network.model.WorkerStatusInfo
import kotlin.math.roundToLong

/**
 * Тон подписи субагента: цвет выбирает хост по палитре MarsTheme.
 * Имён ровно столько, сколько статусов знает сервер (`worker_status.status`).
 */
enum class SubagentWorkTone { RUNNING, DONE, ERROR, STOPPED, UNKNOWN }

/**
 * Готовая подпись строки субагента в списке диалогов: тема работы, статус
 * и длительность одной строкой. [active] — работа ещё идёт: хост рисует
 * индикатор активности, глифов в тексте нет (дизайн-система их банит).
 */
data class SubagentWorkLabel(
    val text: String,
    val tone: SubagentWorkTone,
    val active: Boolean,
)

/**
 * Чистая политика строки субагента (паритет с WebUI `sessions.js`
 * `childLabelFor`): вместо безликой роли «субагент · только чтение» строка
 * показывает ТЕМУ РАБОТЫ ребёнка — `worker_status.goal`.
 *
 * Контракт:
 * - `goal` обрезается до [MAX_GOAL_CHARS] символов (как `slice(0,64)` в WebUI);
 *   пустой/отсутствующий goal не выдумывается — нейтральный [FALLBACK_GOAL],
 *   а не «Untitled»;
 * - статус — короткое русское слово; для `completed` к нему добавляется
 *   «за <длительность>», для `running` — сколько уже работает (от `started_at`,
 *   иначе `duration_seconds`), как в WebUI;
 * - отсутствие `worker_status` целиком и неизвестный статус — тон [SubagentWorkTone.UNKNOWN],
 *   ничего не падает и не подставляет догадок.
 *
 * Без Android-импортов — покрывается JVM unit-тестами.
 */
object SubagentWorkLabelPolicy {

    /** Предел темы в подписи: длиннее — обрезаем (паритет с WebUI). */
    const val MAX_GOAL_CHARS = 64

    /** Нейтральный фолбэк вместо выдуманной темы. */
    const val FALLBACK_GOAL = "Субагент"

    /** Разделитель частей подписи. */
    const val SEPARATOR = " · "

    private val WHITESPACE = Regex("\\s+")

    /**
     * Подпись строки. [nowEpochSeconds] — текущее время в epoch-СЕКУНДАХ: по нему
     * считается «сколько уже работает» для running (передаётся явно, чтобы
     * политика оставалась детерминированной).
     */
    fun label(status: WorkerStatusInfo?, nowEpochSeconds: Double): SubagentWorkLabel {
        val tone = toneOf(status?.status)
        val head = statusPhrase(tone, status, nowEpochSeconds)
        return SubagentWorkLabel(
            text = head + SEPARATOR + goalLabel(status?.goal),
            tone = tone,
            active = tone == SubagentWorkTone.RUNNING,
        )
    }

    /** Тема работы: нормализованные пробелы, обрезка до [MAX_GOAL_CHARS]; пусто — [FALLBACK_GOAL]. */
    fun goalLabel(goal: String?): String {
        val normalized = goal?.replace(WHITESPACE, " ")?.trim().orEmpty()
        if (normalized.isEmpty()) return FALLBACK_GOAL
        return normalized.take(MAX_GOAL_CHARS).trim()
    }

    /** Тон по дословному серверному статусу; всё незнакомое — [SubagentWorkTone.UNKNOWN]. */
    fun toneOf(rawStatus: String?): SubagentWorkTone = when (rawStatus?.trim()?.lowercase()) {
        "running" -> SubagentWorkTone.RUNNING
        "completed" -> SubagentWorkTone.DONE
        "failed" -> SubagentWorkTone.ERROR
        "stopped", "interrupted" -> SubagentWorkTone.STOPPED
        else -> SubagentWorkTone.UNKNOWN
    }

    /**
     * Статусная часть подписи. `running` — «работает 2м 14с», `completed` —
     * «выполнено за 2м 14с»; без измеренной длительности (null, 0, отрицательная,
     * NaN) — просто «работает» / «выполнено»: нулевая длительность не выдаётся
     * за измеренную.
     */
    private fun statusPhrase(
        tone: SubagentWorkTone,
        status: WorkerStatusInfo?,
        nowEpochSeconds: Double,
    ): String = when (tone) {
        SubagentWorkTone.RUNNING -> {
            val elapsed = formatDuration(runningSeconds(status, nowEpochSeconds))
            if (elapsed != null) "работает $elapsed" else "работает"
        }
        SubagentWorkTone.DONE -> {
            val elapsed = formatDuration(status?.durationSeconds)
            if (elapsed != null) "выполнено за $elapsed" else "выполнено"
        }
        SubagentWorkTone.ERROR -> "провал"
        SubagentWorkTone.STOPPED -> "остановлен"
        SubagentWorkTone.UNKNOWN -> "статус неизвестен"
    }

    /**
     * Сколько уже работает running-ребёнок: от `started_at` до [nowEpochSeconds]
     * (рассинхрон часов схлопывается в 0), иначе серверная `duration_seconds`.
     */
    private fun runningSeconds(status: WorkerStatusInfo?, nowEpochSeconds: Double): Double? {
        val startedAt = status?.startedAtEpochSeconds
        if (startedAt != null && !startedAt.isNaN() && !startedAt.isInfinite()) {
            return (nowEpochSeconds - startedAt).coerceAtLeast(0.0)
        }
        return status?.durationSeconds
    }

    /** «14с» / «2м 14с»; null для неизмеренной (null, 0, отрицательной, NaN) длительности. */
    fun formatDuration(seconds: Double?): String? {
        if (seconds == null || seconds.isNaN() || seconds.isInfinite() || seconds <= 0.0) return null
        val total = seconds.roundToLong()
        if (total < 60) return "${total}с"
        return "${total / 60}м ${total % 60}с"
    }
}
