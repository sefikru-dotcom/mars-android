package ru.mars.mobile.localtools

import android.content.Context
import android.os.Build
import ru.mars.network.local.LocalToolResult
import ru.mars.network.local.LocalToolSchema

/**
 * `get_device_info` — что за телефон и какая на нём Android.
 *
 * Только публичные поля `Build`: они не требуют разрешений и не зависят от экрана.
 * `MANUFACTURER` и `MODEL` нужны модели, чтобы говорить «на вашем POCO» вместо
 * «на вашем устройстве», `VERSION.SDK_INT` — чтобы не предлагать функции новее
 * системы, `SUPPORTED_ABIS` — чтобы понимать, встанет ли нативный компонент.
 *
 * `Build.MODEL` у части прошивок содержит маркетинговое имя с кириллицей и пробелами —
 * отдаём как есть, без нормализации: любая правка сделает строку непохожей на то,
 * что человек видит в настройках.
 */
class GetDeviceInfoTool(private val context: Context) : LocalDeviceTool {

    private companion object {
        const val PARAMETERS = """{"type":"object","properties":{}}"""
    }

    override val schema: LocalToolSchema = LocalToolSchema(
        name = "get_device_info",
        description = "Узнать модель устройства и версию Android: производитель, модель, " +
            "версия системы, уровень API и архитектуры процессора. Аргументов нет.",
        parametersJson = PARAMETERS,
    )

    override val requiresConfirmation: Boolean = false

    override suspend fun execute(argumentsJson: String): LocalToolResult = guarded("get_device_info") {
        arguments(argumentsJson) ?: return@guarded badArguments()

        val abis = Build.SUPPORTED_ABIS?.joinToString(", ")?.takeIf { it.isNotBlank() } ?: "неизвестно"
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty().ifEmpty { "неизвестен" }
        val model = Build.MODEL?.trim().orEmpty().ifEmpty { "неизвестна" }
        val release = Build.VERSION.RELEASE?.trim().orEmpty().ifEmpty { "неизвестна" }

        toolOk(
            buildString {
                appendLine("Производитель: $manufacturer")
                appendLine("Модель: $model")
                appendLine("Android: $release (API ${Build.VERSION.SDK_INT})")
                appendLine("Архитектуры: $abis")
                append("Приложение: ${context.packageName}")
            },
        )
    }
}
