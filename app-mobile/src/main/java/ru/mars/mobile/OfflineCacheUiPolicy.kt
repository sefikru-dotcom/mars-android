package ru.mars.mobile

/**
 * Экран, для которого решается вопрос офлайн-фолбэка (задачи vs. метрики проектов).
 * Окна устаревания разные: список задач живёт активнее, метрики проектов — агрегаты,
 * они остаются осмысленными дольше (паритет с app-tv TvOfflineDashboardCachePolicy).
 */
enum class MobileOfflineSurface { TODOS, PROJECTS }

/**
 * Статус данных на экране:
 * - [LIVE]: сеть отработала, показаны свежие данные — без бейджа и баннера;
 * - [CACHED]: на экране снимок из кэша (мгновенный старт или сбой сети) с бейджем
 *   возраста «Кэш (N мин назад)»;
 * - [EMPTY]: сеть отработала, но данных объективно нет (пустой снимок задач);
 * - [ERROR]: сеть упала, а показывать нечего (кэша нет или он просрочен) — экран ошибки;
 * - [LOADING]: первый запрос ещё в полёте, кэша нет — хост показывает прогресс.
 */
enum class OfflineDataStatus { LIVE, CACHED, EMPTY, ERROR, LOADING }

/** Фаза фонового запроса к сети, как её знает экран-хост. */
enum class OfflineFetchState { PENDING, SUCCESS, FAILURE }

/**
 * Готовое к рендеру решение [OfflineCacheUiPolicy.evaluate]:
 * [status], показывать ли содержимое кэша ([showCachedContent]), бейдж возраста
 * [badgeText] («Кэш (5 мин назад)», null когда бейдж не положен), ненавязчивый
 * [bannerText] о сбое обновления (только когда кэш показан ПОСЛЕ неудачи сети —
 * при мгновенном старте из кэша баннера нет, запрос ещё в полёте) и стабильный
 * машиночитаемый [reason] для тестов и логов.
 */
data class OfflineUiDecision(
    val status: OfflineDataStatus,
    val showCachedContent: Boolean,
    val badgeText: String?,
    val bannerText: String?,
    val reason: String,
)

/**
 * Чистая презентационная политика офлайн-кэша для TodosActivity и ProjectsActivity
 * (без Android, без I/O — полностью покрыта JVM unit-тестами; паритет с app-tv
 * TvOfflineDashboardCachePolicy). Хост скармливает наблюдаемые факты (фаза запроса,
 * пуст ли живой payload, когда сохранён снимок кэша) и получает [OfflineUiDecision]:
 * статус данных, бейдж возраста и текст баннера о сбое обновления.
 *
 * Формат бейджа возраста (спецификация мобильного клиента):
 * - < 1 мин -> «только что»;
 * - < 60 мин -> «N мин назад» (целые минуты, округление вниз);
 * - < 24 ч -> «N ч назад»;
 * - >= 24 ч -> «N дн назад».
 *
 * Ключевое правило: неудача сети НИКОГДА не затирает уже показанный кэш — снимок
 * остаётся на экране с бейджем и ненавязчивым баннером «обновление не удалось».
 * Просроченный снимок, наоборот, никогда не показывается как живой: честная ошибка
 * лучше несвежих данных.
 */
object OfflineCacheUiPolicy {

    /** Окно устаревания снимка задач. */
    const val TODOS_MAX_AGE_MS = 12L * 60 * 60 * 1000 // 12 часов

    /** Окно устаревания снимка метрик проектов — агрегаты живут дольше. */
    const val PROJECTS_MAX_AGE_MS = 24L * 60 * 60 * 1000 // 24 часа

    /** Префикс любого кэш-бейджа — хосты и тесты могут матчить по нему. */
    const val BADGE_PREFIX = "Кэш"

    /** Баннер о сбое обновления при показанном кэше (ненавязчивый, без диалогов). */
    const val FAILURE_BANNER = "Обновление не удалось — показаны данные из кэша"

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS

    /** Окно устаревания для [surface]. */
    fun maxSnapshotAgeMs(surface: MobileOfflineSurface): Long = when (surface) {
        MobileOfflineSurface.TODOS -> TODOS_MAX_AGE_MS
        MobileOfflineSurface.PROJECTS -> PROJECTS_MAX_AGE_MS
    }

    /**
     * Возраст снимка, сохранённого в [snapshotTakenAtMs], на момент [nowMs];
     * отрицательный возраст (часы сдвинулись назад, NTP) клампится в ноль.
     */
    fun snapshotAgeMs(snapshotTakenAtMs: Long, nowMs: Long): Long =
        (nowMs - snapshotTakenAtMs).coerceAtLeast(0L)

    /** Жив ли снимок [surface] в пределах своего окна устаревания. */
    fun isSnapshotUsable(surface: MobileOfflineSurface, snapshotTakenAtMs: Long, nowMs: Long): Boolean =
        snapshotAgeMs(snapshotTakenAtMs, nowMs) <= maxSnapshotAgeMs(surface)

    /**
     * Человекочитаемый возраст: «только что» / «N мин назад» / «N ч назад» / «N дн назад».
     * Отрицательный [ageMs] клампится в ноль («только что»), доли единиц округляются вниз.
     */
    fun ageText(ageMs: Long): String {
        val age = ageMs.coerceAtLeast(0L)
        return when {
            age < MINUTE_MS -> "только что"
            age < HOUR_MS -> "${age / MINUTE_MS} мин назад"
            age < DAY_MS -> "${age / HOUR_MS} ч назад"
            else -> "${age / DAY_MS} дн назад"
        }
    }

    /** Бейдж возраста для снимка (абсолютные таймстемпы, см. [snapshotAgeMs]). */
    fun ageText(snapshotTakenAtMs: Long, nowMs: Long): String =
        ageText(snapshotAgeMs(snapshotTakenAtMs, nowMs))

    /** Полный кэш-бейдж: «Кэш (5 мин назад)», «Кэш (только что)». */
    fun cacheBadgeText(ageMs: Long): String = "$BADGE_PREFIX (${ageText(ageMs)})"

    /** Полный кэш-бейдж по абсолютным таймстемпам. */
    fun cacheBadgeText(snapshotTakenAtMs: Long, nowMs: Long): String =
        cacheBadgeText(snapshotAgeMs(snapshotTakenAtMs, nowMs))

    /**
     * Итоговое решение из фактов хоста:
     * - сеть успешна: живой payload -> LIVE (кэш на экране сменяется свежими данными,
     *   бейдж и баннер исчезают); пустой payload -> EMPTY (пустой снимок валиден —
     *   «задач нет» как норма, а не ошибка);
     * - сеть упала: годный снимок -> CACHED с бейджем возраста и баннером о сбое
     *   (содержимое кэша НЕ затирается); снимка нет или он просрочен -> ERROR;
     * - запрос ещё в полёте: годный снимок -> CACHED с бейджем, но БЕЗ баннера ошибки
     *   (обновление ещё может удаться); снимка нет -> LOADING.
     *
     * [snapshotTakenAtMs] == null означает «кэша нет». Просроченный снимок не
     * показывается ни в одной ветке — сначала честный ERROR/плейсхолдер хоста.
     */
    fun evaluate(
        surface: MobileOfflineSurface,
        fetchState: OfflineFetchState,
        livePayloadEmpty: Boolean,
        snapshotTakenAtMs: Long?,
        nowMs: Long,
    ): OfflineUiDecision {
        val hasSnapshot = snapshotTakenAtMs != null
        val usable = hasSnapshot && isSnapshotUsable(surface, snapshotTakenAtMs!!, nowMs)
        val badge = if (usable) cacheBadgeText(snapshotTakenAtMs!!, nowMs) else null

        return when (fetchState) {
            OfflineFetchState.SUCCESS ->
                if (livePayloadEmpty) {
                    OfflineUiDecision(OfflineDataStatus.EMPTY, false, null, null, "live_empty")
                } else {
                    OfflineUiDecision(OfflineDataStatus.LIVE, false, null, null, "live_data")
                }

            OfflineFetchState.FAILURE -> when {
                !hasSnapshot -> error("no_snapshot")
                !usable -> error("snapshot_expired")
                else -> OfflineUiDecision(
                    status = OfflineDataStatus.CACHED,
                    showCachedContent = true,
                    badgeText = badge,
                    bannerText = FAILURE_BANNER,
                    reason = "snapshot_after_failure",
                )
            }

            OfflineFetchState.PENDING -> when {
                !hasSnapshot -> OfflineUiDecision(OfflineDataStatus.LOADING, false, null, null, "loading_no_snapshot")
                !usable -> OfflineUiDecision(OfflineDataStatus.LOADING, false, null, null, "loading_expired_snapshot")
                else -> OfflineUiDecision(
                    status = OfflineDataStatus.CACHED,
                    showCachedContent = true,
                    badgeText = badge,
                    bannerText = null,
                    reason = "snapshot_prefetch",
                )
            }
        }
    }

    private fun error(reason: String) =
        OfflineUiDecision(OfflineDataStatus.ERROR, false, null, null, reason)
}
