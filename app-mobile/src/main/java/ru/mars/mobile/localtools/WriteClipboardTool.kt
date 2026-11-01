package ru.mars.mobile.localtools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import ru.mars.network.local.LocalToolResult
import ru.mars.network.local.LocalToolSchema

/**
 * `write_clipboard` — положить текст в буфер обмена.
 *
 * `ClipData.newPlainText` — единственный безопасный вариант: MIME-тип `text/plain`
 * понимают все, а чужие типы (URI, HTML) требуют прав на чтение у получателя.
 *
 * Подтверждение обязательно: запись **затирает** прежнее содержимое буфера, и вернуть
 * его нельзя. Это потеря данных пользователя, пусть и небольшая, поэтому диалог
 * показывает экран, а инструмент только объявляет флаг.
 *
 * На Android 13+ система сама показывает тост «скопировано» — свой UI не рисуем.
 */
class WriteClipboardTool(private val context: Context) : LocalDeviceTool {

    private companion object {
        /** Предел биндера на размер клипа — примерно 1 МБ; берём с запасом по символам. */
        const val MAX_CHARS = 100_000

        /** Метка клипа: видна в некоторых файловых менеджерах, на смысл не влияет. */
        const val CLIP_LABEL = "Марс"

        const val PARAMETERS = """
{"type":"object","properties":{
"text":{"type":"string","description":"Текст, который нужно положить в буфер обмена. Прежнее содержимое буфера будет затёрто"}},
"required":["text"]}
"""
    }

    override val schema: LocalToolSchema = LocalToolSchema(
        name = "write_clipboard",
        description = "Скопировать текст в буфер обмена устройства, затерев прежнее содержимое. " +
            "Пользователь подтверждает действие до записи. Прочитать буфер — read_clipboard.",
        parametersJson = PARAMETERS,
    )

    override val requiresConfirmation: Boolean = true

    override suspend fun execute(argumentsJson: String): LocalToolResult = guarded("write_clipboard") {
        val args = arguments(argumentsJson) ?: return@guarded badArguments()

        val text = args.optString("text")
        if (text.isBlank()) {
            return@guarded toolError("Не указан обязательный аргумент text — нечего копировать.")
        }
        if (text.length > MAX_CHARS) {
            return@guarded toolError(
                "Текст длиннее $MAX_CHARS символов (${text.length}) — буфер обмена столько не примет.",
            )
        }

        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return@guarded toolError("Буфер обмена недоступен на этом устройстве.")

        manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))

        toolOk("Текст (${text.length} симв.) скопирован в буфер обмена, прежнее содержимое затёрто.")
    }
}
