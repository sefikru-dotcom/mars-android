package ru.mars.tv.todos

import ru.mars.network.model.TodoSnapshot
import ru.mars.tv.TvOfflineAgeBadge
import ru.mars.tv.TvOfflineCacheStatus

/**
 * Исход оценки [TvTodosOfflinePolicy]: [status] источника данных, готовые к отрисовке
 * [data] (`null` при [TvOfflineCacheStatus.ERROR]), плашка [badgeText] для live-региона
 * (`null` когда показывать нечего) и стабильный машиночитаемый тег [reason] для тестов
 * и логов (`live_data`, `snapshot_shown`, `no_snapshot`, `snapshot_expired`,
 * `snapshot_unverified`).
 */
data class TvTodosOfflineDecision(
    val status: TvOfflineCacheStatus,
    val data: TvTodosData?,
    val badgeText: String?,
    val reason: String,
)

/**
 * Чистая политика офлайн-кэша экрана «Задачи» (без Android, без I/O — JVM-тестируемая,
 * паттерн [ru.mars.tv.TvOfflineDashboardCachePolicy]). Контроллер кормит её наблюдаемыми
 * фактами (результат сети, снимок [ru.mars.network.cache.TodosOfflineCache] и момент его
 * сохранения) и получает решение: свежие данные (LIVE), кэшированный снимок с возрастной
 * плашкой (CACHED) или явная ошибка (ERROR).
 *
 * Правила:
 * - успешный сетевой ответ — всегда LIVE с плашкой [TvOfflineAgeBadge.LIVE_BADGE];
 *   сохранение снимка в кэш делает контроллер, политика решает только что показать;
 * - при сетевом сбое/таймауте пригодный снимок показывается как CACHED с плашкой
 *   «Снимок из кэша (N … назад)»: экран не пустеет, фокус пульта не переставляется
 *   на «Повторить» — мягкое восстановление паритетом с дашбордом;
 * - просроченный (старше [MAX_AGE_MS]) или не имеющий метки времени снимок НЕ
 *   показывается никогда: устаревшие задачи хуже честной ошибки;
 * - заголовок сессии не кэшируется ([ru.mars.network.cache.TodosOfflineCache] хранит
 *   только `todo_state`), поэтому CACHED-снимок возвращается без заголовка — честную
 *   копию из самого снимка соберёт [TvTodosPolicy.summaryLine].
 */
object TvTodosOfflinePolicy {

    /** Максимальный возраст снимка задач — паритет окна дашборда (12 часов). */
    const val MAX_AGE_MS = 12L * 60 * 60 * 1000

    /** Снимок, сохранённый в [savedAtMs], ещё показываем; `null`-метка — уже нет. */
    fun isSnapshotUsable(savedAtMs: Long?, nowMs: Long): Boolean =
        savedAtMs != null && TvOfflineAgeBadge.ageMs(savedAtMs, nowMs) <= MAX_AGE_MS

    /** Успешный сетевой ответ: LIVE + плашка «Обновлено только что». */
    fun onLiveSuccess(data: TvTodosData): TvTodosOfflineDecision = TvTodosOfflineDecision(
        status = TvOfflineCacheStatus.LIVE,
        data = data,
        badgeText = TvOfflineAgeBadge.LIVE_BADGE,
        reason = "live_data",
    )

    /**
     * Сетевой сбой: показать кэшированный снимок ([cachedSnapshot] + момент сохранения
     * [savedAtMs]) как CACHED, а при отсутствии/непригодности снимка — ERROR, чтобы хост
     * нарисовал явную ошибку (текст сообщения — из причины сетевого отказа, его
     * формирует контроллер).
     */
    fun onFetchFailure(
        cachedSnapshot: TodoSnapshot?,
        savedAtMs: Long?,
        nowMs: Long,
    ): TvTodosOfflineDecision {
        if (cachedSnapshot == null) return reject("no_snapshot")
        if (savedAtMs == null) return reject("snapshot_unverified")
        if (!isSnapshotUsable(savedAtMs, nowMs)) return reject("snapshot_expired")
        return TvTodosOfflineDecision(
            status = TvOfflineCacheStatus.CACHED,
            data = TvTodosData(sessionTitle = null, snapshot = cachedSnapshot),
            badgeText = TvOfflineAgeBadge.cachedBadge(savedAtMs, nowMs),
            reason = "snapshot_shown",
        )
    }

    private fun reject(reason: String) = TvTodosOfflineDecision(
        status = TvOfflineCacheStatus.ERROR,
        data = null,
        badgeText = null,
        reason = reason,
    )
}
