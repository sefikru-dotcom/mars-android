package ru.mars.mobile.localtools

import android.content.Context
import android.provider.ContactsContract
import ru.mars.network.local.LocalToolResult
import ru.mars.network.local.LocalToolSchema

/**
 * `find_contact` — поиск по телефонной книге.
 *
 * `CommonDataKinds.Phone`, а не `Contacts`: номер уже нормализован провайдером,
 * и одна строка ответа = один телефон, а не контакт без номера.
 *
 * `%` и `_` в LIKE служебные: без экранирования запрос «%» вытягивал бы первые
 * попавшиеся контакты вместо совпадения по имени. Поэтому и `ESCAPE`, и
 * `escapeLike` — оба обязательны и согласованы между собой.
 *
 * Выдача ограничена пятью совпадениями: модель, получившая сотню строк, теряет
 * нить диалога, а человеку нужен один номер.
 */
class FindContactTool(private val context: Context) : LocalDeviceTool {

    private companion object {
        const val MAX_RESULTS = 5
        const val MAX_QUERY_LEN = 64

        const val PARAMETERS = """
{"type":"object","properties":{
"query":{"type":"string","description":"Часть имени или фамилии для поиска"},
"limit":{"type":"integer","description":"Сколько совпадений вернуть, 1-5. По умолчанию 5","minimum":1,"maximum":5}},
"required":["query"]}
"""
    }

    override val schema: LocalToolSchema = LocalToolSchema(
        name = "find_contact",
        description = "Найти контакт в телефонной книге по части имени (например: «мама», «Иванов»). " +
            "Возвращает не больше $MAX_RESULTS совпадений с именами и номерами. " +
            "Если контактов нет или нет разрешения — вернёт ошибку с причиной.",
        parametersJson = PARAMETERS,
    )

    override val requiresConfirmation: Boolean = false

    override suspend fun execute(argumentsJson: String): LocalToolResult = guarded("find_contact") {
        val args = arguments(argumentsJson) ?: return@guarded badArguments()

        val query = args.optString("query").trim()
        if (query.isEmpty()) {
            return@guarded toolError("Не указан обязательный аргумент query — часть имени контакта")
        }
        if (query.length > MAX_QUERY_LEN) {
            return@guarded toolError("query длиннее $MAX_QUERY_LEN символов — уточните имя")
        }

        val limit = args.optInt("limit", MAX_RESULTS).coerceIn(1, MAX_RESULTS)

        if (!context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return@guarded missingPermission(PERMISSION_READ_CONTACTS)
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\'"
        val rows = mutableListOf<Pair<String, String?>>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            arrayOf("%${escapeLike(query)}%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow(projection[0])
            val phoneIdx = cursor.getColumnIndexOrThrow(projection[1])
            while (cursor.moveToNext() && rows.size < limit) {
                rows += (cursor.getString(nameIdx) ?: "") to cursor.getString(phoneIdx)
            }
        }

        if (rows.isEmpty()) {
            return@guarded toolError("Контакты не найдены по запросу «$query» — имя не совпало или книга пуста.")
        }

        val lines = rows.mapIndexed { index, row ->
            val name = row.first.ifBlank { "(без имени)" }
            val phone = row.second?.trim()?.takeIf { it.isNotEmpty() } ?: "номер не указан"
            "${index + 1}. $name — $phone"
        }
        toolOk("Найдено ${lines.size} из запрошенных $limit по «$query»:\n" + lines.joinToString("\n"))
    }

    /** Гасит служебные символы LIKE: `\` → `\\`, `%` → `\%`, `_` → `\_`. Порядок важен. */
    private fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
