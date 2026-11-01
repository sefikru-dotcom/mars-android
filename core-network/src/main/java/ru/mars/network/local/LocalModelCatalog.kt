package ru.mars.network.local

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Метаданные модели из `GET /models`.
 *
 * Зачем модель, а не только имя: пилюля контекста без размера окна бесполезна — процент
 * не из чего считать. Провайдер (проверено на PlusVibe) отдаёт `context_window` по каждой
 * модели, и это единственный честный источник. Выдумывать окно «по семейству» нельзя:
 * у Anthropic в одном каталоге и 200k, и 1M.
 */
data class LocalModelInfo(
    val id: String,
    /** Размер окна контекста в токенах; null — провайдер не сообщил. */
    val contextWindow: Int? = null,
    /** Максимум токенов ответа; null — не сообщено. */
    val maxOutputTokens: Int? = null,
    /**
     * Модель принимает `reasoning_effort`.
     *
     * Признак — непустой `reasoning_controls`, а не догадка по имени: у 14 моделей из 85
     * он есть, у остальных поле отсутствует. Показывать переключатель reasoning на модели
     * без поддержки значит рисовать мёртвую кнопку, которая молча ничего не делает.
     */
    val supportsReasoningEffort: Boolean = false,
    val inputModalities: List<String> = emptyList(),
)

/**
 * Разбор каталога моделей провайдера.
 *
 * На Moshi, а не на `org.json`. Причина не стилистическая: `org.json` в JVM-юнит-тестах
 * Android — заглушка, все методы которой возвращают `null`/0. В `app-mobile` это скрыто
 * `unitTests.isReturnDefaultValues = true`, а в `core-network` такой настройки нет вовсе —
 * разбор на `org.json` проверялся бы на заглушке, то есть не проверялся (поймано живьём:
 * все тесты каталога упали с `AssertionError` на пустом списке при верном JSON).
 * Moshi — та же библиотека, что уже использует [LocalSseParser], и в JVM она работает.
 *
 * Терпимость к мусору намеренная: каталог приходит от чужого сервера, и один битый
 * элемент не должен лишать пользователя выбора модели целиком. Поэтому разбор идёт
 * по каждому элементу отдельно, а не «всё или ничего».
 */
object LocalModelCatalog {

    private val moshi: Moshi = Moshi.Builder().build()

    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val listOfMapsType = Types.newParameterizedType(List::class.java, mapType)

    private val anyAdapter = moshi.adapter<Any>(Any::class.java)
    private val listAdapter = moshi.adapter<List<Map<String, Any?>>>(listOfMapsType)

    /**
     * Разобрать ответ `/models`.
     *
     * Поддерживаются оба вида: объект с `data: [...]` (OpenAI-совместимый, так отвечает
     * PlusVibe) и голый массив. Полностью битый JSON даёт пустой список — вызывающий
     * покажет «моделей нет», а не упадёт.
     */
    fun parse(json: String): List<LocalModelInfo> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyList()
        return runCatching {
            if (trimmed.startsWith("[")) {
                readAll(listAdapter.fromJson(trimmed).orEmpty())
            } else {
                val root = anyAdapter.fromJson(trimmed) as? Map<*, *> ?: return emptyList()
                readAll(asMapList(root["data"]))
            }
        }.getOrElse { emptyList() }
    }

    /** Прочитать элементы массива, пропуская непригодные. */
    private fun readAll(entries: List<Map<String, Any?>>): List<LocalModelInfo> {
        val out = ArrayList<LocalModelInfo>(entries.size)
        for (entry in entries) {
            parseOne(entry)?.let(out::add)
        }
        return out
    }

    /** Один элемент каталога; null — нет даже имени, показывать нечего. */
    private fun parseOne(entry: Map<String, Any?>): LocalModelInfo? {
        val id = (entry["id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val topProvider = entry["top_provider"] as? Map<*, *>
        // context_window и context_length у PlusVibe совпадают; top_provider.context_length
        // уточняет фактический лимит посредника и берётся, если первые два пусты.
        val contextWindow = firstPositive(
            intOf(entry["context_window"]),
            intOf(entry["context_length"]),
            intOf(topProvider?.get("context_length")),
        )
        val maxOutput = firstPositive(
            intOf(entry["max_output_tokens"]),
            intOf(topProvider?.get("max_completion_tokens")),
        )
        val architecture = entry["architecture"] as? Map<*, *>
        val modalities = (architecture?.get("input_modalities") as? List<*>)
            .orEmpty()
            .mapNotNull { (it as? String)?.takeIf(String::isNotBlank) }
        return LocalModelInfo(
            id = id,
            contextWindow = contextWindow,
            maxOutputTokens = maxOutput,
            supportsReasoningEffort = supportsReasoning(entry),
            inputModalities = modalities,
        )
    }

    /**
     * Поддерживает ли модель `reasoning_effort`.
     *
     * Смотрим `reasoning_controls` (массив строк): PlusVibe кладёт туда `"reasoning_effort"`.
     * Пустой массив означает «поля нет» — именно так отвечает `claude-haiku-5.5`.
     */
    private fun supportsReasoning(entry: Map<String, Any?>): Boolean {
        val controls = entry["reasoning_controls"] as? List<*> ?: return false
        return controls.any { (it as? String)?.equals("reasoning_effort", ignoreCase = true) == true }
    }

    /** Первое положительное значение из списка (не `maxOf`, чтобы сохранить приоритет). */
    private fun firstPositive(vararg values: Int?): Int? =
        values.firstOrNull { it != null && it > 0 }

    /**
     * Привести значение к Int.
     *
     * Moshi отдаёт числа как `Double` — целые в JSON он не различает. Строки и мусор
     * дают null: провайдер, приехавший со строкой вместо числа, не должен ронять разбор.
     */
    private fun intOf(value: Any?): Int? = when (value) {
        is Number -> value.toInt().takeIf { it > 0 }
        is String -> value.trim().toIntOrNull()?.takeIf { it > 0 }
        else -> null
    }

    /** Привести `data` к списку карт, отбросив всё непригодное. */
    private fun asMapList(value: Any?): List<Map<String, Any?>> =
        (value as? List<*>).orEmpty().mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            (item as? Map<String, Any?>)
        }
}
