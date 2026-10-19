package ru.mars.mobile

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Результат решения «показать снимок или ошибку» для вкладки музыки.
 *
 * [items] — данные, которые надо отрисовать (null → экран ошибки с retry);
 * [label] — подпись возраста для UI («из кэша, снимок на 18.10 23:41»), есть ровно
 * тогда, когда есть [items]; [reason] — машинная причина для логов/тестов.
 */
data class MusicOfflineFallback<T>(
    val items: List<T>?,
    val label: String?,
    val reason: String,
) {
    /** true — на экран идёт снимок; false — прежнее поведение (ошибка + retry). */
    val hasSnapshot: Boolean get() = items != null
}

/**
 * Чистая политика офлайн-фолбэка вкладок музыки ([MusicActivity] «Обзор» и «Плейлисты»).
 *
 * Зачем отдельный класс: ветка «сеть упала → есть снимок → рисуем его» иначе живёт
 * внутри Activity и проверяется только на устройстве, которого в момент правки может
 * не быть. Здесь она — функция от данных кэша и часов, покрытая JVM-тестами
 * (`MusicOfflineFallbackPolicyTest`), а Activity остаётся тонким хостом.
 *
 * Правила (почему именно так):
 * - снимка нет или он битый (нет метки времени) → ошибка: показывать нечего;
 * - снимок пуст → ошибка: пустой список под видом «кэша» неотличим от поломки;
 * - снимок старше [MAX_AGE_MS] → ошибка: чарт за неделю вводит в заблуждение сильнее,
 *   чем честное «нет сети». Трое суток выбраны сознательно: задача прямо про «вчера
 *   чарт ещё был загружен», а подпись показывает конкретную дату, так что пользователь
 *   видит возраст, а не догадывается о нём.
 */
object MusicOfflineFallbackPolicy {

    /** Предельный возраст снимка, после которого он считается неактуальным. */
    const val MAX_AGE_MS: Long = 72L * 60 * 60 * 1000

    /** Префикс подписи: «из кэша, снимок на …». */
    const val LABEL_PREFIX = "из кэша, снимок на "

    /** Причины решения — стабильные строки для тестов и логов. */
    const val REASON_NO_SNAPSHOT = "no_snapshot"
    const val REASON_NO_TIMESTAMP = "no_timestamp"
    const val REASON_EMPTY_SNAPSHOT = "empty_snapshot"
    const val REASON_EXPIRED = "snapshot_expired"
    const val REASON_SNAPSHOT = "snapshot_after_failure"

    /** «18.10 23:41»: день/месяц и время — без года, экран музыки не про архивы. */
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    /**
     * Решение для упавшей загрузки: [items]/[snapshotTakenAtMs] — то, что вернул
     * `MusicOfflineCache`, [nowMs] — текущее время (передаётся, чтобы тест был
     * детерминированным).
     */
    fun <T> decide(
        items: List<T>?,
        snapshotTakenAtMs: Long?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): MusicOfflineFallback<T> {
        if (items == null) return MusicOfflineFallback(null, null, REASON_NO_SNAPSHOT)
        if (snapshotTakenAtMs == null) return MusicOfflineFallback(null, null, REASON_NO_TIMESTAMP)
        if (items.isEmpty()) return MusicOfflineFallback(null, null, REASON_EMPTY_SNAPSHOT)
        // Часы устройства могут уйти назад (перевод времени, ручная правка): отрицательный
        // возраст — это «только что», а не «из будущего».
        val ageMs = (nowMs - snapshotTakenAtMs).coerceAtLeast(0L)
        if (ageMs > MAX_AGE_MS) return MusicOfflineFallback(null, null, REASON_EXPIRED)
        return MusicOfflineFallback(items, label(snapshotTakenAtMs, zone), REASON_SNAPSHOT)
    }

    /** Подпись снимка для UI: «из кэша, снимок на 18.10 23:41». */
    fun label(takenAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        LABEL_PREFIX + stamp(takenAtMs, zone)

    /** Только метка времени: «18.10 23:41». */
    fun stamp(takenAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        STAMP.format(Instant.ofEpochMilli(takenAtMs).atZone(zone))
}
