package ru.mars.mobile.localtools

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import ru.mars.network.local.LocalToolResult
import ru.mars.network.local.LocalToolSchema
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.TimeZone

/**
 * `create_event` — событие в календаре устройства.
 *
 * Пишем напрямую `ContentResolver.insert` в `CalendarContract.Events`, без экрана
 * приложения-календаря: диалог подтверждения показывает сам локальный чат, потому
 * что вставка мимо UI деструктивна — событие появляется в чужом календаре сразу.
 *
 * Календарь выбирается по `VISIBLE = 1`, порядок `IS_PRIMARY DESC`: основной
 * календарь аккаунта первым, иначе первый видимый. `CALENDAR_ID` обязателен —
 * без него провайдер молча отклонит вставку.
 *
 * Время начала принимается одной строкой: ISO 8601 (`2026-08-11T15:00`, со
 * смещением или `Z`), локальные форматы вида `2026-08-11 15:00`, голая дата
 * (полночь) или epoch-миллисекунды. Модели проще отдать то, что она уже знает,
 * чем угадывать формат; разбор — только `java.time` (minSdk 28 его имеет).
 */
class CreateEventTool(private val context: Context) : LocalDeviceTool {

    private companion object {
        const val DEFAULT_DURATION = 60
        const val MAX_DURATION = 60 * 24 * 30
        const val MAX_NOTES = 4000

        /**
         * Допустимый диапазон epoch-миллисекунд для аргумента-числа: 2001-01-01 — 2100-01-01.
         * «2026» — это год, а не 1 января 1970; такая ошибка должна стать понятным отказом,
         * а не событием в прошлом веке.
         */
        const val MIN_EVENT_MILLIS = 978_307_200_000L
        const val MAX_EVENT_MILLIS = 4_102_444_800_000L

        const val PARAMETERS = """
{"type":"object","properties":{
"title":{"type":"string","description":"Название события"},
"start":{"type":"string","description":"Начало: ISO 8601 («2026-08-11T15:00», «2026-08-11T15:00:00+03:00») или epoch-миллисекунды. Локальное время без зоны — в часовом поясе устройства"},
"duration_minutes":{"type":"integer","description":"Длительность в минутах, 1-43200. По умолчанию 60","minimum":1,"maximum":43200},
"notes":{"type":"string","description":"Описание или место, необязательно"}},
"required":["title","start"]}
"""

        /** Порядок важен: сначала полные формы с зоной, потом локальные. */
        val FORMATS: List<DateTimeFormatter> = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm[:ss]"),
        )
    }

    override val schema: LocalToolSchema = LocalToolSchema(
        name = "create_event",
        description = "Создать событие в календаре устройства. Время начала — строка: либо ISO 8601 " +
            "(«2026-08-11T15:00», «2026-08-11T15:00:00+03:00», «2026-08-11T15:00:00Z»), либо epoch-миллисекунды. " +
            "Длительность по умолчанию $DEFAULT_DURATION минут. Событие пишется сразу — " +
            "пользователь подтверждает его до создания. Посмотреть расписание — list_events.",
        parametersJson = PARAMETERS,
    )

    override val requiresConfirmation: Boolean = true

    override suspend fun execute(argumentsJson: String): LocalToolResult = guarded("create_event") {
        val args = arguments(argumentsJson) ?: return@guarded badArguments()

        val title = args.optString("title").trim()
        if (title.isEmpty()) {
            return@guarded toolError("Не указан обязательный аргумент title — название события")
        }
        val rawStart = args.optString("start").trim()
        if (rawStart.isEmpty()) {
            return@guarded toolError(
                "Не указан обязательный аргумент start — время начала в ISO 8601 или epoch-миллисекундах",
            )
        }

        val startMillis = parseStart(rawStart) ?: return@guarded toolError(
            "Не удалось разобрать start «$rawStart». Нужен ISO 8601 " +
                "(2026-08-11T15:00, 2026-08-11T15:00:00+03:00) или число epoch-миллисекунд " +
                "в диапазоне 2001–2100 годов (одно число вроде «2026» — это год, а не дата).",
        )

        val duration = args.optInt("duration_minutes", DEFAULT_DURATION).coerceIn(1, MAX_DURATION)
        val notes = args.optString("notes").trim().takeIf { it.isNotEmpty() }?.take(MAX_NOTES)
        val endMillis = startMillis + duration * 60_000L

        if (!context.hasPermission(PERMISSION_WRITE_CALENDAR)) {
            return@guarded missingPermission(PERMISSION_WRITE_CALENDAR)
        }

        val calendarId = writableCalendarId()
            ?: return@guarded toolError(
                "Не удалось создать событие: на устройстве нет видимого календаря для записи.",
            )

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            notes?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return@guarded toolError(
                "Не удалось записать событие «$title»: вставка отклонена провайдером календаря.",
            )

        toolOk(
            "Событие «$title» на ${formatLocal(startMillis)} ($duration мин) записано в календарь устройства " +
                "(${uri.lastPathSegment}).",
        )
    }

    /** Первый видимый календарь, основной предпочтителен. */
    private fun writableCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return null
    }

    /** Разбор начала события: epoch-число, ISO с зоной или локальное время устройства. */
    private fun parseStart(raw: String): Long? {
        // Число — epoch-миллисекунды, но «2026» модель легко примет за год и получит
        // событие в 1970-м. Принимаем только диапазон, в котором живёт человек.
        raw.toLongOrNull()?.let { return it.takeIf { millis -> millis in MIN_EVENT_MILLIS..MAX_EVENT_MILLIS } }

        // ISO с зоной: Instant (…Z) или OffsetDateTime (…+03:00).
        runCatching { Instant.parse(raw) }.getOrNull()?.let { return it.toEpochMilli() }
        runCatching { OffsetDateTime.parse(raw) }.getOrNull()?.let { return it.toInstant().toEpochMilli() }

        val zone = ZoneId.systemDefault()
        for (format in FORMATS) {
            try {
                return LocalDateTime.parse(raw, format).atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                // пробуем следующий шаблон
            }
        }
        // Дата без времени — считаем полночь: «запиши на 12 августа».
        return runCatching { LocalDate.parse(raw).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun formatLocal(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
}
