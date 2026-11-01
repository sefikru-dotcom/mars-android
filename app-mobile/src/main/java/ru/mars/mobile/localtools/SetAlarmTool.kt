package ru.mars.mobile.localtools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import ru.mars.network.local.LocalToolResult
import ru.mars.network.local.LocalToolSchema

/**
 * `set_alarm` — будильник в системных Часах.
 *
 * Идём через `AlarmClock.ACTION_SET_ALARM`, а не через собственный AlarmManager:
 * будильник должен звонить в системных часах, переживать перезагрузку и попадать
 * в список, который человек видит. `EXTRA_SKIP_UI = true` — чтобы не открывать
 * экран Часов поверх чата: будильник ставится молча.
 *
 * Типы extras — часть контракта AlarmClock: HOUR и MINUTES читаются через
 * `getIntExtra`, SKIP_UI — через `getBooleanExtra`. Строка на их месте молча даёт
 * дефолт (часы показывали «-1:xx»), поэтому здесь строго Int / String / Boolean.
 *
 * Подтверждение обязательно: будильник — действие на устройстве, которое человек
 * не отменял вручную.
 */
class SetAlarmTool(private val context: Context) : LocalDeviceTool {

    private companion object {
        const val PARAMETERS = """
{"type":"object","properties":{
"hour":{"type":"integer","description":"Час по 24-часовой шкале, 0-23","minimum":0,"maximum":23},
"minute":{"type":"integer","description":"Минуты, 0-59","minimum":0,"maximum":59},
"label":{"type":"string","description":"Подпись будильника, необязательно. Например: «Подъём»"}},
"required":["hour","minute"]}
"""
        /** Значение, которого не бывает у валидного аргумента: отличает «не передали» от 0. */
        const val ABSENT = Int.MIN_VALUE
    }

    override val schema: LocalToolSchema = LocalToolSchema(
        name = "set_alarm",
        description = "Поставить будильник в системных Часах. Время в 24-часовом формате: " +
            "час 0-23 и минуты 0-59. Модель сама разбирает «на семь утра» в hour=7, minute=0.",
        parametersJson = PARAMETERS,
    )

    override val requiresConfirmation: Boolean = true

    override suspend fun execute(argumentsJson: String): LocalToolResult = guarded("set_alarm") {
        val args = arguments(argumentsJson) ?: return@guarded badArguments()

        val hour = args.optInt("hour", ABSENT)
        if (hour == ABSENT) return@guarded toolError("Не указан обязательный аргумент hour (0-23)")
        val minute = args.optInt("minute", ABSENT)
        if (minute == ABSENT) return@guarded toolError("Не указан обязательный аргумент minute (0-59)")
        if (hour !in 0..23) return@guarded toolError("hour должно быть в диапазоне 0-23, получено $hour")
        if (minute !in 0..59) return@guarded toolError("minute должно быть в диапазоне 0-59, получено $minute")

        val label = args.optString("label").trim().takeIf { it.isNotEmpty() }
            ?: "Будильник %02d:%02d".format(hour, minute)

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Проверяем именно запуск, а не resolveActivity: на Android 11+ фильтрация
        // видимости пакетов может вернуть null для живого обработчика, а исключение
        // при старте — честный признак «некому обработать».
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            return@guarded toolError(
                "Не удалось поставить будильник: на устройстве нет приложения, обрабатывающего " +
                    "ACTION_SET_ALARM (обычно это системные Часы).",
            )
        }

        val shown = if (label.startsWith("Будильник ")) "" else " («$label»)"
        toolOk("Будильник на %02d:%02d%s поставлен в системных Часах.".format(hour, minute, shown))
    }
}
