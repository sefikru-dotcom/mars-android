package ru.mars.mobile.localtools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import ru.mars.network.local.LocalToolResult
import ru.mars.network.local.LocalToolSchema

/**
 * `set_timer` — таймер обратного отсчёта в системных Часах.
 *
 * `AlarmClock.ACTION_SET_TIMER` + `EXTRA_LENGTH` — длительность в **секундах** и
 * строго Int: системные Часы читают его через `getIntExtra`, а строка дала бы
 * таймер на 0 секунд. `EXTRA_SKIP_UI = true` — без экрана Часов поверх чата.
 *
 * Подтверждение не требуется: таймер ничего не меняет на устройстве, кроме
 * собственного списка таймеров, и отменяется одним нажатием.
 */
class SetTimerTool(private val context: Context) : LocalDeviceTool {

    private companion object {
        const val PARAMETERS = """
{"type":"object","properties":{
"minutes":{"type":"integer","description":"Длительность в минутах, 1-1440","minimum":1,"maximum":1440},
"seconds":{"type":"integer","description":"Длительность в секундах, 1-86400. Альтернатива minutes","minimum":1,"maximum":86400},
"label":{"type":"string","description":"Название таймера, необязательно. Например: «Паста»"}}}
"""
        /** Значение, которого не бывает у валидного аргумента: отличает «не передали» от 0. */
        const val ABSENT = Int.MIN_VALUE
    }

    override val schema: LocalToolSchema = LocalToolSchema(
        name = "set_timer",
        description = "Запустить таймер обратного отсчёта в системных Часах. Укажите minutes ИЛИ seconds " +
            "(если заданы оба, побеждают минуты). Когда время выйдет, Часы дадут сигнал.",
        parametersJson = PARAMETERS,
    )

    override val requiresConfirmation: Boolean = false

    override suspend fun execute(argumentsJson: String): LocalToolResult = guarded("set_timer") {
        val args = arguments(argumentsJson) ?: return@guarded badArguments()

        val minutes = args.optInt("minutes", ABSENT)
        val seconds = args.optInt("seconds", ABSENT)

        val totalSeconds: Int
        val human: String
        when {
            minutes != ABSENT -> {
                if (minutes !in 1..1440) {
                    return@guarded toolError("minutes должно быть в диапазоне 1-1440, получено $minutes")
                }
                totalSeconds = minutes * 60
                human = "$minutes мин"
            }

            seconds != ABSENT -> {
                if (seconds !in 1..86400) {
                    return@guarded toolError("seconds должно быть в диапазоне 1-86400, получено $seconds")
                }
                totalSeconds = seconds
                human = "$seconds с"
            }

            else -> return@guarded toolError("Нужен аргумент minutes или seconds — ни один не задан")
        }

        val label = args.optString("label").trim().takeIf { it.isNotEmpty() } ?: "Таймер $human"

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, totalSeconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            return@guarded toolError(
                "Не удалось запустить таймер: нет приложения, обрабатывающего ACTION_SET_TIMER.",
            )
        }

        toolOk("Таймер на $human запущен в системных Часах.")
    }
}
