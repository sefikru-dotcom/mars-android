package ru.mars.tv

/**
 * Статус источника данных TV-экрана с офлайн-кэшем («Задачи», «Мониторинг»):
 * - [LIVE]: свежий сетевой ответ отрисован, офлайн-кэш обновлён;
 * - [CACHED]: сетевой сбой/таймаут, на экране валидный снимок из кэша с возрастной
 *   плашкой — данные не теряются, фокус пульта остаётся на месте (прыжка на
 *   «Повторить» и пустого экрана нет);
 * - [ERROR]: сетевой сбой И пригодного снимка нет (кэш пуст, бит или просрочен) —
 *   хост рисует явную ошибку, фокус переводится на «Повторить».
 *
 * Смежный с [TvOfflineRecoveryState] (фазы восстановления дашборда), но уже: описывает
 * источник фактически показанных данных, а не фазу сети.
 */
enum class TvOfflineCacheStatus { LIVE, CACHED, ERROR }

/**
 * Чистый форматтер возрастных плашек офлайн-кэша (без Android и I/O — JVM-тестируемый,
 * паттерн [TvOfflineDashboardCachePolicy]). Обе экранные политики ([ru.mars.tv.todos.TvTodosOfflinePolicy]
 * и [ru.mars.tv.projects.TvProjectsOfflinePolicy]) строят свои плашки поверх него, поэтому
 * кэш на всех экранах читается одинаково: «Снимок из кэша (5 мин. назад)»,
 * «Снимок из кэша (2 ч. назад)», «Снимок из кэша (3 дн. назад)».
 *
 * Возраст округляется вниз до целых единиц и клампится нулём: откат часов (NTP-коррекция)
 * даёт «только что», а не отрицательные счётчики.
 */
object TvOfflineAgeBadge {

    /** Плашка свежего сетевого ответа (LIVE). */
    const val LIVE_BADGE = "Обновлено только что"

    /** Префикс плашки CACHED — хосты и тесты матчатся на него. */
    const val CACHED_BADGE_PREFIX = "Снимок из кэша"

    /** Возрастной текст снимка моложе минуты. */
    const val AGE_JUST_NOW = "только что"

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS

    /** Возраст снимка, сохранённого в [savedAtMs], на момент [nowMs]; никогда не отрицательный. */
    fun ageMs(savedAtMs: Long, nowMs: Long): Long = (nowMs - savedAtMs).coerceAtLeast(0L)

    /**
     * Короткий возрастной текст:
     * - меньше минуты -> «только что» (включая отрицательный возраст — clamp);
     * - меньше часа -> «N мин. назад»;
     * - меньше суток -> «N ч. назад»;
     * - иначе -> «N дн. назад».
     */
    fun ageText(ageMs: Long): String {
        val age = ageMs.coerceAtLeast(0L)
        return when {
            age < MINUTE_MS -> AGE_JUST_NOW
            age < HOUR_MS -> "${age / MINUTE_MS} мин. назад"
            age < DAY_MS -> "${age / HOUR_MS} ч. назад"
            else -> "${age / DAY_MS} дн. назад"
        }
    }

    /** Готовая к отрисовке плашка CACHED для снимка данного возраста. */
    fun cachedBadge(ageMs: Long): String = "$CACHED_BADGE_PREFIX (${ageText(ageMs)})"

    /** Плашка CACHED по абсолютным меткам времени (см. [ageMs]). */
    fun cachedBadge(savedAtMs: Long, nowMs: Long): String = cachedBadge(ageMs(savedAtMs, nowMs))
}
