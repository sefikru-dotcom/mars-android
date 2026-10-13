package ru.mars.mobile

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Чистая политика времени сообщений: разбор серверных ISO-меток в epoch-millis,
 * подпись часа на карточке («14:05»), подписи разделителей дней («Сегодня»,
 * «Вчера», «9 октября») и решение, нужен ли разделитель между двумя сообщениями.
 * Без Android-импортов — тестируется на JVM с фиксированной зоной.
 */
object MessageTimePolicy {
    /** Маркер «время неизвестно»: сервер не отдал createdAt или метка не распознана. */
    const val UNKNOWN_TIMESTAMP = 0L

    private val MONTHS_GENITIVE = arrayOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    /** «2026-10-11 14:05:03» (naive-метки сервера считаем UTC). */
    private val SPACE_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss[.SSSSSS][.SSS]]")

    /**
     * Epoch-метка сохранённой истории: state.db/sidecar несут `timestamp`
     * epoch-СЕКУНДАМИ (целыми или дробными). Граница [EPOCH_MILLIS_FLOOR]:
     * значения ниже неё — секунды (эпоха до 2286 года), выше — миллисекунды.
     */
    private val EPOCH_NUMBER = Regex("^\\d{9,15}(\\.\\d+)?$")
    private const val EPOCH_MILLIS_FLOOR = 1e12

    /**
     * Разбор серверной метки: ISO со смещением, ISO-instant («…Z»), «YYYY-MM-DD HH:MM:SS»
     * (трактуется как UTC) и epoch-число истории (секунды, порог [EPOCH_MILLIS_FLOOR] —
     * миллисекунды). null/пусто/нераспознанное -> [fallback].
     */
    fun parseServerTimestamp(raw: String?, fallback: Long = UNKNOWN_TIMESTAMP): Long {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return fallback
        if (EPOCH_NUMBER.matches(value)) {
            value.toDoubleOrNull()?.takeIf { it > 0 }?.let { epoch ->
                return (if (epoch >= EPOCH_MILLIS_FLOOR) epoch else epoch * 1000.0).toLong()
            }
        }
        return runCatching { DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(value, Instant::from).toEpochMilli() }
            .recoverCatching { DateTimeFormatter.ISO_INSTANT.parse(value, Instant::from).toEpochMilli() }
            .recoverCatching { LocalDateTime.parse(value, SPACE_FORMATTER).toInstant(ZoneOffset.UTC).toEpochMilli() }
            .getOrDefault(fallback)
    }

    /** Часовая подпись карточки («14:05»); [UNKNOWN_TIMESTAMP] -> null (время не показываем). */
    fun clockLabel(timestampMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (timestampMillis <= UNKNOWN_TIMESTAMP) return null
        val time = Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalTime()
        return "%02d:%02d".format(time.hour, time.minute)
    }

    /** Подпись разделителя дней: «Сегодня» / «Вчера» / «9 октября» / «9 октября 2025». */
    fun dayLabel(timestampMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val day = Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return when (day) {
            today -> "Сегодня"
            today.minusDays(1) -> "Вчера"
            else -> buildString {
                append(day.dayOfMonth).append(' ').append(MONTHS_GENITIVE[day.monthValue - 1])
                if (day.year != today.year) append(' ').append(day.year)
            }
        }
    }

    /**
     * Нужен ли разделитель дней между двумя сообщениями: показываем на первом
     * сообщении транскрипта и при смене локальных суток. Неизвестные метки (0)
     * разделитель не порождают.
     */
    fun showDaySeparator(
        previousTimestampMillis: Long,
        nextTimestampMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (nextTimestampMillis <= UNKNOWN_TIMESTAMP) return false
        val nextDay = Instant.ofEpochMilli(nextTimestampMillis).atZone(zone).toLocalDate()
        if (previousTimestampMillis <= UNKNOWN_TIMESTAMP) return true
        val prevDay = Instant.ofEpochMilli(previousTimestampMillis).atZone(zone).toLocalDate()
        return prevDay != nextDay
    }
}
