package ru.mars.mobile.localtools

import android.content.ClipboardManager
import android.content.Context
import ru.mars.network.local.LocalToolResult
import ru.mars.network.local.LocalToolSchema

/**
 * `read_clipboard` — текст из буфера обмена.
 *
 * `coerceToText`, а не `getText`: в буфере может лежать URI или Intent, и провайдер
 * отдаёт их текстовым представлением — модели полезнее «https://…», чем пустота.
 *
 * Разрешений нет, но на Android 10+ система отдаёт буфер только приложению, которое
 * сейчас на экране: если чат в фоне, придёт пустой клип, и это честная ошибка с
 * причиной, а не сбой инструмента.
 *
 * Подтверждение не требуется: чтение ничего не меняет. Запись — отдельный
 * write_clipboard, и там подтверждение есть.
 */
class ReadClipboardTool(private val context: Context) : LocalDeviceTool {

    private companion object {
        /** Длинный текст в ответ модели не влезает: обрезаем и говорим, сколько было. */
        const val MAX_CHARS = 4000

        const val PARAMETERS = """{"type":"object","properties":{}}"""
    }

    override val schema: LocalToolSchema = LocalToolSchema(
        name = "read_clipboard",
        description = "Прочитать текст из буфера обмена устройства. Аргументов нет. " +
            "Если в буфере картинка, файл или он пуст — вернёт ошибку с причиной. " +
            "Длинный текст обрезается до $MAX_CHARS символов.",
        parametersJson = PARAMETERS,
    )

    override val requiresConfirmation: Boolean = false

    override suspend fun execute(argumentsJson: String): LocalToolResult = guarded("read_clipboard") {
        // Аргументов нет, но битая строка всё равно должна дать понятный отказ.
        arguments(argumentsJson) ?: return@guarded badArguments()

        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@guarded toolError("Буфер обмена недоступен на этом устройстве.")

        val clip = manager.primaryClip
        if (clip == null || clip.itemCount == 0) {
            return@guarded toolError(
                "Буфер обмена пуст или недоступен: система отдаёт его только приложению на экране.",
            )
        }

        val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        if (text.isBlank()) {
            return@guarded toolError("В буфере обмена нет текста — там картинка, файл или пусто.")
        }

        val total = text.length
        val shown = if (total > MAX_CHARS) text.take(MAX_CHARS) + "\n…(обрезано)" else text
        toolOk("Текст из буфера обмена ($total симв.):\n$shown")
    }
}
