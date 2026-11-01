package ru.mars.network.local

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Разбор SSE-потока [OI]-совместимого провайдера.
 *
 * Чистый JVM-класс: на вход — строки (`onLine`), на выходе — события ([Event]); ни okhttp,
 * ни `android.*` здесь нет, поэтому ловушки стрима проверяются без сети.
 *
 * Ловушки, снятые с живого ответа PlusVibe (контракт §4, п. 3):
 * 1. `tool_calls` приходят чанками: `id` и `name` только в первом, дальше — `index` и куски
 *    `arguments`, которые **конкатенируются по index**, а не перезаписываются;
 * 2. поток может кончиться **без `data: [DONE]`** — [onEndOfStream] закрывает шаг сам;
 * 3. `finish_reason` приходит в чанке с пустым `delta: {}` — поэтому кадр разбирается целиком,
 *    а не по наличию текста;
 * 4. битый (обрезанный) JSON пропускается молча: остальные чанки обязаны дойти;
 * 5. финальный кадр расхода (`choices: []` + `usage`) приходит **после** `finish_reason` и
 *    **перед** `[DONE]`, поэтому шаг закрывается не на `finish_reason`, а на кадре `usage`
 *    (или на `[DONE]`/EOF, если провайдер расход не прислал): иначе [Event.Finished] ушёл бы
 *    раньше [Event.Usage], и экран не успел бы обновить пилюлю тем же шагом.
 *
 * Состояние накопительное и однопроходное: один экземпляр — один шаг диалога.
 */
class LocalSseParser {

    /** Событие одного кадра. Отображение в [LocalChatEvent] делает транспорт. */
    sealed interface Event {

        /** Кусок текста ответа. */
        data class Text(val text: String) : Event

        /** Кусок размышлений (`reasoning_content`/`reasoning`). */
        data class Reasoning(val text: String) : Event

        /**
         * Расход шага из финального кадра `usage` (`stream_options.include_usage`).
         *
         * Всегда идёт ДО [Finished]. Значения уже приведены к безопасным неотрицательным
         * `Int`: мусор в поле превращается в `0`, а не в исключение.
         */
        data class Usage(val usage: LocalUsage) : Event

        /**
         * Шаг модели закончен: [text] — весь собранный текст, [toolCalls] — склеенные вызовы,
         * [finishReason] — `stop`/`tool_calls`/`length`/... либо `null` при обрыве по EOF.
         */
        data class Finished(
            val text: String,
            val toolCalls: List<LocalToolCall>,
            val finishReason: String?,
        ) : Event

        /** Пришёл `data: [DONE]`; после него кадров не будет. */
        object Done : Event
    }

    /** Накопитель одного вызова инструмента: `arguments` доклеиваются по index. */
    private class CallBuilder(val index: Int) {
        var id: String = ""
        var name: String = ""
        val arguments: StringBuilder = StringBuilder()
    }

    private val jsonAdapter = Moshi.Builder().build()
        .adapter<Map<String, Any?>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
        )
        .lenient()

    /** Накопленный `data:`-payload текущего кадра (без пустой строки-разделителя). */
    private val payload = StringBuilder()

    private val text = StringBuilder()
    private val calls = LinkedHashMap<Int, CallBuilder>()

    private var finished = false
    private var finishReason: String? = null

    /**
     * `finish_reason` увиден, но [Event.Finished] отложен: кадр `usage` идёт следующим,
     * и закрывать шаг раньше него значит потерять расход (контракт §3, W1 п. 1).
     */
    private var finishDeferred = false

    /** Первый кадр `usage` уже отдан: повторный кадр не должен удвоить расход шага. */
    private var usageReported = false

    /** Шаг уже закрыт (`finish_reason` или `[DONE]`): повторного `Finished` не будет. */
    val isFinished: Boolean get() = finished

    /**
     * Одна строка потока. Возвращает события, которые эта строка закрыла (обычно пусто:
     * кадр закрывается пустой строкой).
     */
    fun onLine(line: String): List<Event> {
        val raw = line.trimEnd('\r')
        if (raw.isEmpty()) return flush()
        // `:` — комментарий/keep-alive, `event:`/`id:`/`retry:` для чата не нужны.
        if (raw.startsWith(":")) return emptyList()
        if (!raw.startsWith(DATA_PREFIX)) return emptyList()

        val chunk = raw.substring(DATA_PREFIX.length).let { if (it.startsWith(" ")) it.substring(1) else it }
        val events = ArrayList<Event>(2)
        // Толерантность к потоку без пустых строк между кадрами: если накопленное уже
        // разбирается целиком — закрываем кадр, не дожидаясь разделителя.
        if (payload.isNotEmpty() && isComplete(payload.toString())) events.addAll(flush())
        if (payload.isNotEmpty()) payload.append('\n')
        payload.append(chunk)
        return events
    }

    /**
     * Конец потока. Обрыв без `[DONE]` — норма: шаг закрывается тем, что успели собрать.
     * Отложенный `finish_reason` тоже закрывается здесь — иначе шаг остался бы без
     * [Event.Finished] из-за отсутствующего кадра `usage`.
     */
    fun onEndOfStream(): List<Event> {
        val events = ArrayList<Event>(2)
        events.addAll(flush())
        if (!finished) events.add(finish(null))
        return events
    }

    // --- кадры ---------------------------------------------------------------

    private fun flush(): List<Event> {
        if (payload.isEmpty()) return emptyList()
        val raw = payload.toString()
        payload.setLength(0)
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()

        if (trimmed == DONE_MARKER) {
            val events = ArrayList<Event>(3)
            // `[DONE]` — последняя точка, где закрывается отложенный шаг: провайдер мог
            // не прислать `usage` вовсе, и ждать больше нечего.
            if (!finished) events.add(finish(null))
            events.add(Event.Done)
            return events
        }

        // Битый/обрезанный JSON не роняет поток: кадр просто выпадает.
        val frame = parse(trimmed) ?: return emptyList()
        return apply(frame)
    }

    private fun apply(frame: Map<String, Any?>): List<Event> {
        val events = ArrayList<Event>(3)

        // `choices` может отсутствовать вовсе (кадр одного `usage`) — это не повод бросать кадр.
        val choices = frame["choices"] as? List<*>
        if (choices != null) {
            for (rawChoice in choices) {
                val choice = rawChoice as? Map<*, *> ?: continue

                val delta = choice["delta"] as? Map<*, *>
                if (delta != null) {
                    val content = delta["content"] as? String
                    if (!content.isNullOrEmpty()) {
                        text.append(content)
                        events.add(Event.Text(content))
                    }
                    val reasoning = (delta["reasoning_content"] ?: delta["reasoning"]) as? String
                    if (!reasoning.isNullOrEmpty()) events.add(Event.Reasoning(reasoning))
                    (delta["tool_calls"] as? List<*>)?.forEach { accumulate(it) }
                }

                // `finish_reason` приходит в чанке с пустым `delta`, поэтому проверяется
                // независимо от того, был ли в кадре текст.
                val reason = choice["finish_reason"] as? String
                if (!reason.isNullOrBlank()) {
                    finishReason = reason
                    // Кадр `usage` идёт СЛЕДОМ за `finish_reason`: шаг закрывается там,
                    // чтобы [Event.Usage] гарантированно ушёл раньше [Event.Finished].
                    if (!finished) finishDeferred = true
                }
            }
        }

        val usage = readUsage(frame["usage"])
        if (usage != null && !usageReported) {
            usageReported = true
            events.add(Event.Usage(usage))
        }

        // Единственный кадр, где шаг можно закрыть, не потеряв расход, — тот, что принёс
        // `usage`. Без него закрытие происходит на `[DONE]` или на EOF.
        if (finishDeferred && usage != null && !finished) {
            events.add(finish(finishReason))
        }
        return events
    }

    private fun accumulate(rawCall: Any?) {
        val entry = rawCall as? Map<*, *> ?: return
        val index = (entry["index"] as? Number)?.toInt() ?: 0
        val builder = calls.getOrPut(index) { CallBuilder(index) }

        (entry["id"] as? String)?.takeIf { it.isNotBlank() }?.let { builder.id = it }

        val function = entry["function"] as? Map<*, *> ?: return
        val name = function["name"] as? String
        if (!name.isNullOrEmpty()) {
            // Обычно имя целиком в первом чанке; если провайдер режет его на куски — клеим,
            // а повтор того же имени (некоторые шлют его в каждом чанке) не дублируем.
            builder.name = if (builder.name.isEmpty() || builder.name == name) name else builder.name + name
        }
        (function["arguments"] as? String)?.let { builder.arguments.append(it) }
    }

    private fun finish(reason: String?): Event.Finished {
        finished = true
        finishDeferred = false
        if (reason != null) finishReason = reason
        return Event.Finished(text.toString(), buildCalls(), finishReason)
    }

    private fun buildCalls(): List<LocalToolCall> =
        calls.toSortedMap().values.mapNotNull { builder ->
            val name = builder.name.trim()
            // Без имени вызов исполнить нельзя — такой обрывок не отдаём наружу.
            if (name.isEmpty()) return@mapNotNull null
            LocalToolCall(
                id = builder.id.ifBlank { "call_${builder.index}" },
                name = name,
                // Пустые аргументы — валидный `{}`, а не пустая строка (контракт §4, п. 7).
                argumentsJson = builder.arguments.toString().trim().ifBlank { EMPTY_ARGUMENTS },
            )
        }

    // --- расход ---------------------------------------------------------------

    /**
     * Объект `usage` кадра → [LocalUsage]; `null` — поля нет или это не объект.
     *
     * Терпимость намеренная (контракт §3, W1 п. 2): кадр приходит от чужого сервера, и
     * `"usage": null`, строка вместо числа или отрицательное значение не должны ронять
     * поток. Всё непонятное становится `0`, а не исключением.
     *
     * `cache_read_input_tokens` (есть у PlusVibe) отдельно не выносится: в [OI]-совместимом
     * учёте это часть `prompt_tokens`, а своего поля в [LocalUsage] нет и заводить дубль
     * типа здесь запрещено.
     */
    private fun readUsage(raw: Any?): LocalUsage? {
        val map = raw as? Map<*, *> ?: return null
        return LocalUsage(
            promptTokens = intValue(map["prompt_tokens"]),
            completionTokens = intValue(map["completion_tokens"]),
            totalTokens = intValue(map["total_tokens"]),
        )
    }

    /** Число из JSON-значения: строка `"12"` читается, дробное усекается, мусор → 0. */
    private fun intValue(raw: Any?): Int = when (raw) {
        is Number -> clampTokens(raw.toDouble())
        is String -> clampTokens(raw.trim().toDoubleOrNull() ?: 0.0)
        else -> 0
    }

    /** Отрицательные/нулевые/NaN/переполнение → безопасный неотрицательный `Int`. */
    private fun clampTokens(value: Double): Int = when {
        value.isNaN() || value <= 0.0 -> 0
        value >= Int.MAX_VALUE.toDouble() -> Int.MAX_VALUE
        else -> value.toInt()
    }

    // --- JSON ----------------------------------------------------------------

    private fun parse(raw: String): Map<String, Any?>? =
        try {
            jsonAdapter.fromJson(raw)
        } catch (_: Exception) {
            null
        } catch (_: StackOverflowError) {
            // Патологически вложенный мусор не должен убивать поток.
            null
        }

    private fun isComplete(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed == DONE_MARKER) return true
        return parse(trimmed) != null
    }

    private companion object {
        const val DATA_PREFIX = "data:"
        const val DONE_MARKER = "[DONE]"
        const val EMPTY_ARGUMENTS = "{}"
    }
}
