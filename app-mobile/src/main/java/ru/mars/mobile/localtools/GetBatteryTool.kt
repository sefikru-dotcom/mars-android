package ru.mars.mobile.localtools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import ru.mars.network.local.LocalToolResult
import ru.mars.network.local.LocalToolSchema

/**
 * `get_battery` — заряд, статус зарядки и температура батареи.
 *
 * Заряд берём у `BatteryManager` (`BATTERY_PROPERTY_CAPACITY`) — это процент, который
 * показывает сама система. Статус и температуру отдаёт только липкий броадкаст
 * `ACTION_BATTERY_CHANGED`, поэтому читаем его через `registerReceiver(null, filter)`:
 * подписка на один вызов, без получателя и без утечки. Если процент система не сообщает
 * (`-1`), считаем его из `EXTRA_LEVEL / EXTRA_SCALE` того же броадкаста.
 *
 * Разрешений нет: батарея — публичное состояние устройства.
 *
 * Регистрация без получателя на Android 14 может бросить SecurityException (требование
 * флагов экспорта), поэтому она в `runCatching`: без броадкаста инструмент всё равно
 * вернёт заряд, а не упадёт целиком.
 */
class GetBatteryTool(private val context: Context) : LocalDeviceTool {

    private companion object {
        /** Значение, которого не бывает у валидного extra: отличает «не передали» от 0. */
        const val ABSENT = Int.MIN_VALUE

        const val PARAMETERS = """{"type":"object","properties":{}}"""
    }

    override val schema: LocalToolSchema = LocalToolSchema(
        name = "get_battery",
        description = "Узнать состояние батареи телефона: процент заряда, заряжается ли сейчас " +
            "(и от чего) и температуру. Аргументов нет.",
        parametersJson = PARAMETERS,
    )

    override val requiresConfirmation: Boolean = false

    override suspend fun execute(argumentsJson: String): LocalToolResult = guarded("get_battery") {
        arguments(argumentsJson) ?: return@guarded badArguments()

        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val capacity = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: ABSENT

        val sticky = batteryBroadcast()
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, ABSENT) ?: ABSENT
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, ABSENT) ?: ABSENT
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val temperature = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, ABSENT) ?: ABSENT

        val percent = when {
            capacity in 0..100 -> capacity
            level != ABSENT && level >= 0 && scale != ABSENT && scale > 0 -> level * 100 / scale
            else -> ABSENT
        }

        val lines = mutableListOf<String>()
        lines += if (percent == ABSENT) {
            "Заряд: неизвестен — устройство не сообщает ёмкость батареи."
        } else {
            "Заряд: $percent%"
        }
        lines += "Статус: ${statusText(status)}${pluggedText(plugged)}"
        if (temperature != ABSENT) {
            lines += "Температура батареи: %.1f °C".format(temperature / 10.0)
        }

        toolOk(lines.joinToString("\n"))
    }

    /** Липкий `ACTION_BATTERY_CHANGED` без получателя: подписка на одно чтение. */
    private fun batteryBroadcast(): Intent? = runCatching {
        @Suppress("UnspecifiedRegisterReceiverFlag")
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()

    private fun statusText(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "заряжается"
        BatteryManager.BATTERY_STATUS_FULL -> "заряжен полностью"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "разряжается"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "подключён, но не заряжается"
        else -> "неизвестен"
    }

    private fun pluggedText(plugged: Int): String = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> " (от сети)"
        BatteryManager.BATTERY_PLUGGED_USB -> " (USB)"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> " (беспроводная зарядка)"
        else -> ""
    }
}
