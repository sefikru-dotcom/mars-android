package ru.mars.mobile.localtools

import android.content.Context
import android.provider.CalendarContract
import ru.mars.network.local.LocalToolResult
import ru.mars.network.local.LocalToolSchema
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * `list_events` — расписание на ближайшие дни.
 *
 * Читаем `CalendarContract.Instances`, а не `Events`: только Instances разворачивает
 * повторяющиеся события в конкретные вхождения, поэтому «что у меня в среду» получает
 * настоящий ответ, а не правило повтора. Запрос идёт по URI с парой begin/end —
 * провайдер сам отбрасывает всё вне окна.
 *
 * Колонка идентификатора — `Instances.EVENT_ID`, **не** `_ID`: у вхождения в Instances
 * `_ID` не событие, а собственный ключ строки, и по нему нельзя сопоставить ничего.
 * В тексте ответа отдаём именно EVENT_ID.
 *
 * Аргументы — `days_ahead` и `limit`, а не абсолютные `from`/`to` в миллисекундах:
 * модель, которой нужен epoch-таймстемп, ошибается в нём регулярно (путает секунды
 * и миллисекунды, берёт год за дату), а «сколько дней вперёд» она формулирует
 * безошибочно. Начало окна всегда «сейчас» — расписание в прошлом не нужно.
 */
class ListEventsTool(private val context: Context) : LocalDeviceTool {

    private companion object {
        const val DEFAULT_DAYS_AHEAD = 7
        const val MAX_DAYS_AHEAD = 60
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50
        const val MILLIS_PER_DAY = 86_400_000L

        const val PARAMETERS = """
{"type":"object","properties":{
"days_ahead":{"type":"integer","description":"На сколько дней вперёд от текущего момента смотреть, 1-60. По умолчанию 7","minimum":1,"maximum":60},
"limit":{"type":"integer","description":"Сколько событий вернуть, 1-50. По умолчанию 20","minimum":1,"maximum":50}}}
"""

        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }

    override val schema: LocalToolSchema = LocalToolSchema(
        name = "list_events",
        description = "Показать события календаря устройства на ближайшие дни, включая повторяющиеся. " +
            "Окно задаётся в днях вперёд (days_ahead, по умолчанию $DEFAULT_DAYS_AHEAD), " +
            "выдача ограничена limit событиями (по умолчанию $DEFAULT_LIMIT). " +
            "Возвращает время, название, календарь и место. Создать событие — create_event.",
        parametersJson = PARAMETERS,
    )

    override val requiresConfirmation: Boolean = false

    override suspend fun execute(argumentsJson: String): LocalToolResult = guarded("list_events") {
        val args = arguments(argumentsJson) ?: return@guarded badArguments()

        val days = args.optInt("days_ahead", DEFAULT_DAYS_AHEAD).coerceIn(1, MAX_DAYS_AHEAD)
        val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        if (!context.hasPermission(PERMISSION_READ_CALENDAR)) {
            return@guarded missingPermission(PERMISSION_READ_CALENDAR)
        }

        val fromMillis = System.currentTimeMillis()
        val toMillis = fromMillis + days * MILLIS_PER_DAY

        // Instances читаются только через URI с началом и концом окна в пути:
        // без них провайдер вернёт отказ, а не «всё подряд».
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(fromMillis.toString())
            .appendPath(toMillis.toString())
            .build()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.EVENT_LOCATION,
        )

        val lines = mutableListOf<String>()
        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(projection[0])
            val titleIdx = cursor.getColumnIndexOrThrow(projection[1])
            val beginIdx = cursor.getColumnIndexOrThrow(projection[2])
            val endIdx = cursor.getColumnIndexOrThrow(projection[3])
            val allDayIdx = cursor.getColumnIndexOrThrow(projection[4])
            val calendarIdx = cursor.getColumnIndexOrThrow(projection[5])
            val locationIdx = cursor.getColumnIndexOrThrow(projection[6])

            while (cursor.moveToNext() && lines.size < limit) {
                val eventId = cursor.getLong(idIdx)
                val title = cursor.getString(titleIdx)?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: "(без названия)"
                val begin = cursor.getLong(beginIdx)
                val end = cursor.getLong(endIdx)
                val allDay = cursor.getInt(allDayIdx) != 0
                val calendar = cursor.getString(calendarIdx)?.trim().takeUnless { it.isNullOrEmpty() }
                val location = cursor.getString(locationIdx)?.trim().takeUnless { it.isNullOrEmpty() }

                val details = mutableListOf<String>()
                calendar?.let { details += it }
                location?.let { details += "место: $it" }
                details += "id $eventId"

                lines += "${lines.size + 1}. ${formatRange(begin, end, allDay)} «$title» — " +
                    details.joinToString(" · ")
            }
        }

        if (lines.isEmpty()) {
            return@guarded toolOk("На ближайшие $days дн. событий в календаре нет.")
        }

        val tail = if (lines.size >= limit) "\nПоказаны первые ${lines.size} — сузьте окно, если нужно больше." else ""
        toolOk("События на ближайшие $days дн. (${lines.size}):\n" + lines.joinToString("\n") + tail)
    }

    /**
     * Строка времени.
     *
     * У событий «на весь день» BEGIN/END лежат в UTC-полуночи, поэтому форматировать их
     * в локальной зоне нельзя: в зоне западнее UTC дата уехала бы на день назад.
     */
    private fun formatRange(beginMillis: Long, endMillis: Long, allDay: Boolean): String {
        if (allDay) {
            val date = Instant.ofEpochMilli(beginMillis).atZone(ZoneOffset.UTC).toLocalDate()
            return "${DATE.format(date)} (весь день)"
        }
        val zone = ZoneId.systemDefault()
        val begin = Instant.ofEpochMilli(beginMillis).atZone(zone)
        val end = Instant.ofEpochMilli(endMillis).atZone(zone)
        val endText = if (end.toLocalDate() == begin.toLocalDate()) {
            TIME.format(end)
        } else {
            "${DATE.format(end.toLocalDate())} ${TIME.format(end)}"
        }
        return "${DATE.format(begin.toLocalDate())} ${TIME.format(begin)}–$endText"
    }
}
