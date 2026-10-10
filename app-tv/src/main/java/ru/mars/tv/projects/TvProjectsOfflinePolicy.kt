package ru.mars.tv.projects

import ru.mars.tv.TvOfflineAgeBadge
import ru.mars.tv.TvOfflineCacheStatus

/**
 * Исход оценки [TvProjectsOfflinePolicy]: [status] источника данных, сырой JSON снимка
 * [cachedJson] (только для [TvOfflineCacheStatus.CACHED] — парсит его контроллер),
 * плашка [badgeText] для live-региона и стабильный машиночитаемый тег [reason] для
 * тестов и логов (`live_data`, `snapshot_shown`, `no_snapshot`, `snapshot_expired`,
 * `snapshot_unverified`).
 */
data class TvProjectsOfflineDecision(
    val status: TvOfflineCacheStatus,
    val cachedJson: String?,
    val badgeText: String?,
    val reason: String,
)

/**
 * Чистая политика офлайн-кэша экрана «Мониторинг» (без Android, без I/O —
 * JVM-тестируемая, паттерн [ru.mars.tv.TvOfflineDashboardCachePolicy]). Контроллер
 * кормит её наблюдаемыми фактами (результат сети, снимок
 * [ru.mars.network.cache.ProjectsOfflineCache] и момент его сохранения) и получает
 * решение: LIVE, CACHED с сырым JSON снимка и возрастной плашкой, или ERROR.
 *
 * Правила:
 * - успешный сетевой ответ — LIVE с плашкой [TvOfflineAgeBadge.LIVE_BADGE]; в кэш
 *   контроллер сохраняет только успешно разобранный ответ — снимок, который нечем
 *   отрисовать, кэша не стоит;
 * - при сетевом сбое/таймауте пригодный снимок показывается как CACHED с плашкой
 *   «Снимок из кэша (N … назад)»: карточки метрик остаются на экране, фокус пульта
 *   не переставляется на «Повторить»;
 * - просроченный (старше [MAX_AGE_MS]) или не имеющий метки времени снимок НЕ
 *   показывается никогда: устаревшие метрики хуже честной ошибки — окно в два раза
 *   шире задачного, паритет [ru.mars.tv.TvOfflineDashboardCachePolicy.PROJECTS_MAX_AGE_MS]
 *   (агрегатные числа остаются осмысленными дольше).
 */
object TvProjectsOfflinePolicy {

    /** Максимальный возраст снимка метрик — паритет окна проектов дашборда (24 часа). */
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** Снимок, сохранённый в [savedAtMs], ещё показываем; `null`-метка — уже нет. */
    fun isSnapshotUsable(savedAtMs: Long?, nowMs: Long): Boolean =
        savedAtMs != null && TvOfflineAgeBadge.ageMs(savedAtMs, nowMs) <= MAX_AGE_MS

    /** Успешный сетевой ответ: LIVE + плашка «Обновлено только что». */
    fun onLiveSuccess(): TvProjectsOfflineDecision = TvProjectsOfflineDecision(
        status = TvOfflineCacheStatus.LIVE,
        cachedJson = null,
        badgeText = TvOfflineAgeBadge.LIVE_BADGE,
        reason = "live_data",
    )

    /**
     * Сетевой сбой: показать кэшированный снимок ([cachedJson] + момент сохранения
     * [savedAtMs]) как CACHED, а при отсутствии/непригодности снимка — ERROR, чтобы хост
     * нарисовал явную ошибку (текст сообщения — из причины сетевого отказа, его
     * формирует контроллер).
     */
    fun onFetchFailure(
        cachedJson: String?,
        savedAtMs: Long?,
        nowMs: Long,
    ): TvProjectsOfflineDecision {
        if (cachedJson.isNullOrBlank()) return reject("no_snapshot")
        if (savedAtMs == null) return reject("snapshot_unverified")
        if (!isSnapshotUsable(savedAtMs, nowMs)) return reject("snapshot_expired")
        return TvProjectsOfflineDecision(
            status = TvOfflineCacheStatus.CACHED,
            cachedJson = cachedJson,
            badgeText = TvOfflineAgeBadge.cachedBadge(savedAtMs, nowMs),
            reason = "snapshot_shown",
        )
    }

    private fun reject(reason: String) = TvProjectsOfflineDecision(
        status = TvOfflineCacheStatus.ERROR,
        cachedJson = null,
        badgeText = null,
        reason = reason,
    )
}
