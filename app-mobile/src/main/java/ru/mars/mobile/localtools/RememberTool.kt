package ru.mars.mobile.localtools

import android.content.Context
import ru.mars.network.local.AddResult
import ru.mars.network.local.LocalToolResult
import ru.mars.network.local.LocalToolSchema
import ru.mars.network.local.MemoryStore
import java.io.File

/**
 * `remember` — модель сама записывает факт о владельце в долгую память.
 *
 * Память — файл `memory.md` рядом с сессиями (`filesDir`), записи разделены `§`. Тот же
 * файл читает `LocalChatPresenter` и вставляет в системный промпт, поэтому записанное
 * здесь модель увидит в следующем же ходе. Хранилище (`MemoryStore`) — чужая зона:
 * инструмент только зовёт `add` и переводит его ответ в текст для модели.
 *
 * Подтверждения нет: запись факта безобидна и обратима (память можно почистить в
 * настройках), а диалог на каждый «запомни, что меня зовут…» только мешает.
 *
 * Два правила нормализации, без которых ломается хранилище:
 *  - переводы строк и повторные пробелы сжимаются в один: запись памяти — одна строка,
 *    многострочный текст иначе поедет по вёрстке промпта;
 *  - символ `§` в тексте запрещён: это разделитель записей, факт с ним распадётся на
 *    две «записи» при следующем чтении файла.
 *
 * `MemoryStore` создаётся лениво: каталог инструментов собирается на чистой JVM
 * (см. `LocalToolCatalogTest`), и обращение к `filesDir` в конструкторе там упало бы.
 * Второй параметр — для тестов: подменить хранилище, не трогая файловую систему.
 */
class RememberTool(
    private val context: Context,
    store: MemoryStore? = null,
) : LocalDeviceTool {

    private companion object {
        /** Имя файла памяти: то же, что читает презентер чата. */
        const val MEMORY_FILE = "memory.md"

        /**
         * Каталог памяти — ровно тот же, что у экрана [LocalMemoryActivity] и у сборки
         * системного промпта в [LocalChatActivity]. Раньше инструмент писал в
         * `filesDir/memory.md`, а читали из `filesDir/memory/memory.md`: модель отвечала
         * «Запомнил», а в памяти оставалось пусто.
         */
        const val MEMORY_DIR = "memory"

        /** Разделитель записей памяти: в тексте факта его быть не должно. */
        const val SEPARATOR = "§"

        /** Потолок на один факт: память и так ограничена, а «факт» — это фраза. */
        const val MAX_FACT_CHARS = 2_000

        /** Любая последовательность пробельных символов, включая переводы строк. */
        val WHITESPACE = Regex("\\s+")

        const val PARAMETERS = """
{"type":"object","properties":{
"text":{"type":"string","description":"Факт одной фразой, от третьего лица"}},
"required":["text"]}
"""
    }

    /** Ленивое хранилище: файл открывается на первом вызове, а не при сборке каталога. */
    private val memory: MemoryStore by lazy {
        store ?: MemoryStore(File(File(context.filesDir, MEMORY_DIR), MEMORY_FILE))
    }

    override val schema: LocalToolSchema = LocalToolSchema(
        name = "remember",
        description = "Сохранить факт о владельце в долгую память. Используй, когда человек " +
            "сообщает что-то о себе надолго: имя, город, технику, привычки, проект. " +
            "Факт формулируй одной фразой от третьего лица: «Владельца зовут Игорь».",
        parametersJson = PARAMETERS,
    )

    override val requiresConfirmation: Boolean = false

    override suspend fun execute(argumentsJson: String): LocalToolResult = guarded("remember") {
        val args = arguments(argumentsJson) ?: return@guarded badArguments()

        val fact = args.optString("text").trim().replace(WHITESPACE, " ")
        if (fact.isEmpty()) {
            return@guarded toolError("Не указан обязательный аргумент text — нечего запоминать.")
        }
        if (fact.contains(SEPARATOR)) {
            return@guarded toolError(
                "В факте не должно быть символа $SEPARATOR — это разделитель записей памяти. " +
                    "Переформулируй факт без него.",
            )
        }
        if (fact.length > MAX_FACT_CHARS) {
            return@guarded toolError(
                "Факт слишком длинный: ${fact.length} символов при пределе $MAX_FACT_CHARS. " +
                    "Запоминай короткой фразой, детали — отдельными фактами.",
            )
        }

        // Переполнение памяти — не сбой инструмента, а причина, которую модель должна
        // увидеть дословно: чаще всего лечится тем, что человек чистит память.
        when (val added = memory.add(fact)) {
            is AddResult.Ok -> toolOk("Запомнил: $fact")
            is AddResult.Rejected -> toolError("В память не записано: ${added.reason}")
        }
    }
}
