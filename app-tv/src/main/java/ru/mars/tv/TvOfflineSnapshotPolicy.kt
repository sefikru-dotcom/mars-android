package ru.mars.tv

/**
 * Исход оценки [TvOfflineSnapshotPolicy]: [status] источника данных, флаг
 * [showCached] (показывать ли кэшированный снимок — сами данные хранит
 * контроллер экрана), плашка [badgeText] для live-региона (`null` когда
 * показывать нечего) и стабильный машиночитаемый тег [reason] для тестов и
 * логов (`live_data`, `snapshot_shown`, `no_snapshot`, `snapshot_expired`,
 * `snapshot_unverified`).
 */
data class TvSnapshotDecision(
    val status: TvOfflineCacheStatus,
    val showCached: Boolean,
    val badgeText: String?,
    val reason: String,
)

/**
 * Чистая политика офлайн-кэша «последний удачный ответ» для read-only экранов
 * (без Android, без I/O — JVM-тестируемая; правила те же, что у экранных
 * [ru.mars.tv.todos.TvTodosOfflinePolicy]/[ru.mars.tv.projects.TvProjectsOfflinePolicy],
 * но без привязки к конкретному типу снимка: контроллер хранит данные сам и
 * спрашивает здесь только решение).
 *
 * Правила:
 * - успешный сетевой ответ — всегда LIVE с плашкой [TvOfflineAgeBadge.LIVE_BADGE];
 *   сохранение снимка в кэш делает контроллер, политика решает только что показать;
 * - при сетевом сбое/таймауте снимок с валидной меткой времени моложе [maxAgeMs]
 *   показывается как CACHED с плашкой «Снимок из кэша (N … назад)»: экран не
 *   пустеет, фокус пульта не переставляется на «Повторить»;
 * - снимок без метки времени (`snapshot_unverified`) или старше [maxAgeMs]
 *   (`snapshot_expired`) НЕ показывается никогда: устаревшие данные хуже
 *   честной ошибки; откат часов (NTP) клампится в «только что»
 *   ([TvOfflineAgeBadge.ageMs]).
 *
 * Окно свежести задаёт экран-владелец (операционные данные короче агрегатов) —
 * константы `CACHE_MAX_AGE_MS` в policy-объектах экранов.
 */
class TvOfflineSnapshotPolicy(private val maxAgeMs: Long) {

    /** Снимок, сохранённый в [savedAtMs], ещё показываем; `null`-метка — уже нет. */
    fun isSnapshotUsable(savedAtMs: Long?, nowMs: Long): Boolean =
        savedAtMs != null && TvOfflineAgeBadge.ageMs(savedAtMs, nowMs) <= maxAgeMs

    /** Успешный сетевой ответ: LIVE + плашка «Обновлено только что». */
    fun onLiveSuccess(): TvSnapshotDecision = TvSnapshotDecision(
        status = TvOfflineCacheStatus.LIVE,
        showCached = false,
        badgeText = TvOfflineAgeBadge.LIVE_BADGE,
        reason = "live_data",
    )

    /**
     * Сетевой сбой: показать кэшированный снимок как CACHED, а при отсутствии/
     * непригодности снимка — ERROR, чтобы хост нарисовал явную ошибку (текст
     * сообщения — из причины сетевого отказа, его формирует контроллер).
     * [hasSnapshot] — есть ли у контроллера последний удачный ответ; снимок без
     * метки времени ([savedAtMs] == null) не верифицируем и не показываем
     * (`snapshot_unverified`, паритет [ru.mars.tv.todos.TvTodosOfflinePolicy]).
     */
    fun onFetchFailure(hasSnapshot: Boolean, savedAtMs: Long?, nowMs: Long): TvSnapshotDecision {
        if (!hasSnapshot) return reject("no_snapshot")
        if (savedAtMs == null) return reject("snapshot_unverified")
        if (!isSnapshotUsable(savedAtMs, nowMs)) return reject("snapshot_expired")
        return TvSnapshotDecision(
            status = TvOfflineCacheStatus.CACHED,
            showCached = true,
            badgeText = TvOfflineAgeBadge.cachedBadge(savedAtMs, nowMs),
            reason = "snapshot_shown",
        )
    }

    private fun reject(reason: String) = TvSnapshotDecision(
        status = TvOfflineCacheStatus.ERROR,
        showCached = false,
        badgeText = null,
        reason = reason,
    )
}
