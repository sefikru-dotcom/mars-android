package ru.mars.mobile

import java.time.Instant
import java.time.ZoneId

/**
 * Чистая политика мета-строки карточки сессии в шторке: относительное время
 * последней активности («5 мин назад», «вчера») и счётчик сообщений с русским
 * склонением; результат — одна строка «5 мин назад · 12 сообщений» или null,
 * когда показывать нечего. Без Android-импортов — тестируется на JVM.
 */
object SessionMetaPolicy {
    /** Относительная подпись времени: «только что» / «N мин назад» / «N ч назад» / «вчера» / «N дн назад» / дата. */
    fun relativeLabel(timestampMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (timestampMillis <= MessageTimePolicy.UNKNOWN_TIMESTAMP) return ""
        val diff = nowMillis - timestampMillis
        if (diff < 0) return "только что"
        val minutes = diff / 60_000
        if (minutes < 1) return "только что"
        if (minutes < 60) return "$minutes мин назад"
        val nowDay = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val thenDay = Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()
        if (thenDay == nowDay) return "${diff / 3_600_000} ч назад"
        if (thenDay == nowDay.minusDays(1)) return "вчера"
        val days = java.time.temporal.ChronoUnit.DAYS.between(thenDay, nowDay)
        if (days < 7) return "$days дн назад"
        return MessageTimePolicy.dayLabel(timestampMillis, nowMillis, zone)
    }

    /** Счётчик сообщений со склонением; null/неположительный -> null (нечего показывать). */
    fun messagesLabel(messageCount: Int?): String? {
        val count = messageCount ?: return null
        if (count <= 0) return null
        val noun = when {
            count % 10 == 1 && count % 100 != 11 -> "сообщение"
            count % 10 in 2..4 && count % 100 !in 12..14 -> "сообщения"
            else -> "сообщений"
        }
        return "$count $noun"
    }

    /**
     * Итоговая мета-строка карточки: непустые части через « · »; обе пустые -> null.
     * [updatedAtMillis] — уже разобранная метка (0/отрицательная = неизвестна).
     */
    fun metaLine(
        updatedAtMillis: Long?,
        messageCount: Int?,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        val time = updatedAtMillis
            ?.takeIf { it > MessageTimePolicy.UNKNOWN_TIMESTAMP }
            ?.let { relativeLabel(it, nowMillis, zone) }
            ?.takeIf { it.isNotBlank() }
        val count = messagesLabel(messageCount)
        return listOfNotNull(time, count).joinToString(" · ").ifBlank { null }
    }
}
