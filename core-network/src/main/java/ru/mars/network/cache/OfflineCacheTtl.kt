package ru.mars.network.cache

/**
 * Срок годности офлайн-снимка (TTL) — чистая политика без Android и без I/O.
 *
 * Зачем TTL вообще: кэш диалогов переживает и перезапуск приложения, и долгий офлайн.
 * Без срока годности экран через месяц без сети показывал бы прошлогоднюю переписку как
 * «текущий диалог» — это хуже честного «копии нет»: пользователь отвечает в диалог,
 * которого уже нет, и не понимает, почему ответы не приходят. TTL ограничивает окно,
 * в котором копия считается пригодной.
 *
 * Правила:
 * - `maxAgeMillis <= 0` — TTL выключен (прежнее поведение; нужно экранам без политики);
 * - неизвестный момент сохранения (`savedAt <= 0`, записи старых версий) считается
 *   свежим: отсутствие метки — не доказательство устаревания, а выкидывать валидный
 *   кэш из-за миграции нельзя;
 * - возраст ровно `maxAgeMillis` ещё годен (граница включительно);
 * - отрицательный возраст (часы устройства сдвинулись назад, NTP) — свежий снимок.
 */
object OfflineCacheTtl {

    /** Срок годности по умолчанию: трое суток. Дольше — уже не «последняя переписка». */
    const val DEFAULT_HOURS: Int = 72

    /** [DEFAULT_HOURS] в миллисекундах (готовый аргумент `maxAgeMillis`). */
    const val DEFAULT_MILLIS: Long = DEFAULT_HOURS * 60L * 60L * 1000L

    /**
     * Годен ли снимок, сохранённый в [savedAtEpochMillis], на момент [nowEpochMillis]
     * при окне [maxAgeMillis].
     */
    fun isFresh(savedAtEpochMillis: Long, nowEpochMillis: Long, maxAgeMillis: Long): Boolean {
        if (maxAgeMillis <= 0L) return true
        if (savedAtEpochMillis <= 0L) return true
        return nowEpochMillis - savedAtEpochMillis <= maxAgeMillis
    }
}
