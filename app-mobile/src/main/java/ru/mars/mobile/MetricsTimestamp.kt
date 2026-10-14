package ru.mars.mobile

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * «ts» ответа дашборда — момент, когда метрики действительно сняты (например,
 * «14.10.2026 в 16:30:28 (МСК)»). Чистая логика без Android: разбирает его в
 * epoch-миллис и даёт честную давность для статус-строки экрана проектов
 * (P0-4: пользователь должен видеть возраст ДАННЫХ, а не время запроса).
 *
 * Ничего не бросает: пустой/незнакомый формат (в т.ч. легаси ISO) даёт null —
 * тогда [ProjectsSummary.freshnessText] откатывается к времени запроса.
 */
object MetricsTimestamp {

    /** Формат даты/времени живого ответа дашборда; хвост «(МСК)» отбрасывается. */
    private const val PATTERN = "dd.MM.yyyy 'в' HH:mm:ss"
    private const val MSK_ZONE = "Europe/Moscow"

    /** Epoch-миллис момента снятия метрик; null — ts пуст или в незнакомом формате. */
    fun parseMs(ts: String): Long? {
        val core = ts.substringBefore('(').trim()
        if (core.isBlank()) return null
        // SimpleDateFormat не потокобезопасен — создаём на каждый вызов.
        val format = SimpleDateFormat(PATTERN, Locale.forLanguageTag("ru")).apply {
            timeZone = TimeZone.getTimeZone(MSK_ZONE)
            isLenient = false
        }
        return runCatching { format.parse(core)?.time }.getOrNull()
    }

    /** «5 мин назад» с момента снятия метрик; null — ts не удалось разобрать. */
    fun ageText(ts: String, nowMs: Long): String? = parseMs(ts)?.let {
        OfflineCacheUiPolicy.ageText(OfflineCacheUiPolicy.snapshotAgeMs(it, nowMs))
    }
}
