package ru.mars.mobile.perf

import ru.mars.mobile.ChatMessage
import ru.mars.mobile.MessageStatus
import ru.mars.mobile.ToolExecution
import ru.mars.mobile.TranscriptCapPolicy
import java.util.Random

/**
 * Детерминированные фикстуры транскрипта для perf-харнесса (чистая JVM, без Android).
 *
 * Контракт фикстуры:
 * - один и тот же (wireSize, seed) ВСЕГДА даёт побайтово идентичный [WireTranscript]
 *   (java.util.Random с фиксированным seed, никаких System.currentTimeMillis внутри);
 * - состав реалистичный: чередование user/assistant, markdown (заголовки, абзацы,
 *   буллиты, код-блоки, цитаты, таблицы, ссылки), мысли и инструменты на части
 *   assistant-сообщений, вложения;
 * - timestamps монотонны с фиксированным шагом от фиксированной базы — история
 *   пересекает границы локальных суток (день-разделители [ru.mars.mobile.MessageTimePolicy]
 *   участвуют в снимках карточек);
 * - ровно ОДНО заведомо большепузырное сообщение (текст > [TranscriptCapPolicy.MAX_BUBBLE_CHARS])
 *   стоит на позиции wireSize-2 — оно гарантированно попадает в сохраняемый хвост
 *   после cap-подрезки и упражняет пузырьковый лимит на всех wire-размерах;
 * - последнее сообщение — непустой assistant в статусе STREAMING (передний фронт
 *   стрима: на нём проверяется TEXT_ONLY-заплатка [ru.mars.mobile.IncrementalTranscriptPolicy]).
 *
 * «wire-размер» (100/500/1000) — это размер ИСТОРИИ С СЕРВЕРА до применения
 * [TranscriptCapPolicy.capMessages]. Продуктовый cap = 300 сообщений, поэтому
 * wire=500 и wire=1000 упражняют ПОГЛОЩЕНИЕ избытка якорем, а не рендер 500/1000
 * карточек: харнесс всегда измеряет UI-размер ПОСЛЕ cap (≤ 301).
 */
object TranscriptPerfFixtures {

    /** База меток времени: 2025-10-01T00:00:00Z. Фиксирована — детерминизм между прогонами. */
    const val BASE_TIMESTAMP_MS = 1_759_276_800_000L

    /** Шаг меток: 90 секунд. 1000 сообщений ≈ 25 часов — гарантия пересечения суток. */
    const val TIMESTAMP_STEP_MS = 90_000L

    const val DEFAULT_SEED = 20_251_001L

    /** Позиция гарантированно oversized-пузыря относительно конца списка. */
    private const val OVERSIZED_TAIL_OFFSET = 2

    private val WORDS = arrayOf(
        "транскрипт", "рендер", "кэш", "политика", "сообщение", "поток", "карточка",
        "маркер", "блок", "снимок", "контейнер", "якорь", "пузырь", "стрим", "состояние",
        "throttle", "snapshot", "markdown", "anchor", "fixture",
    )

    /** История «с провода» заданного wire-размера: то, что присылает сервер ДО cap. */
    data class WireTranscript(
        val wireSize: Int,
        val seed: Long,
        val messages: List<ChatMessage>,
    ) {
        init {
            require(messages.size == wireSize) { "fixture size mismatch: ${messages.size} != $wireSize" }
        }

        val bubbleChars: Long get() = messages.sumOf { it.text.length.toLong() }
        val largestBubbleChars: Int get() = messages.maxOf { it.text.length }
        val oversizedBubbles: Int get() = messages.count { it.text.length > TranscriptCapPolicy.MAX_BUBBLE_CHARS }
        val messagesWithThoughts: Int get() = messages.count { it.thoughts.isNotEmpty() }
        val messagesWithTools: Int get() = messages.count { it.tools.isNotEmpty() }
        val messagesWithAttachments: Int get() = messages.count { it.attachments.isNotEmpty() }

        fun timestampFor(index1Based: Int): Long = BASE_TIMESTAMP_MS + (index1Based - 1) * TIMESTAMP_STEP_MS
    }

    fun wireTranscript(wireSize: Int, seed: Long = DEFAULT_SEED): WireTranscript {
        require(wireSize >= 4) { "wireSize слишком мал: $wireSize" }
        val rnd = Random(seed)
        val oversizedIndex = wireSize - OVERSIZED_TAIL_OFFSET
        val messages = (1..wireSize).map { index ->
            val isUser = index % 2 == 1
            val timestamp = BASE_TIMESTAMP_MS + (index - 1) * TIMESTAMP_STEP_MS
            val isLast = index == wireSize
            if (isUser) {
                ChatMessage(
                    role = "user",
                    text = userText(rnd, index),
                    attachments = if (index % 23 == 0) listOf("attach_$index.png") else emptyList(),
                    timestamp = timestamp,
                    status = MessageStatus.COMPLETE,
                )
            } else {
                val oversized = index == oversizedIndex || rnd.nextInt(120) == 0
                ChatMessage(
                    role = "assistant",
                    text = assistantText(rnd, index, oversized),
                    thoughts = if (index % 5 == 0) listOf("План шага $index", "Проверка результата ${index + 1}") else emptyList(),
                    tools = if (index % 7 == 0) {
                        listOf(
                            ToolExecution(
                                id = "call_$index",
                                name = "terminal",
                                arguments = "{\"cmd\":\"ls -la /srv\"}",
                                result = "ok#$index",
                            )
                        )
                    } else emptyList(),
                    timestamp = timestamp,
                    status = if (isLast) MessageStatus.STREAMING else MessageStatus.COMPLETE,
                )
            }
        }
        return WireTranscript(wireSize, seed, messages)
    }

    /** Короткое пользовательское сообщение. */
    fun userMessage(index1Based: Int, seed: Long = DEFAULT_SEED): ChatMessage = ChatMessage(
        role = "user",
        text = userText(Random(seed), index1Based),
        timestamp = BASE_TIMESTAMP_MS + (index1Based - 1) * TIMESTAMP_STEP_MS,
        status = MessageStatus.COMPLETE,
    )

    /** Детерминированный стрим-токен: дописывается к хвостовому пузырю на «кадре стрима». */
    fun streamToken(seed: Long, frame: Int): String {
        val rnd = Random(seed * 31 + frame)
        return " токен-$frame-${rnd.nextInt(1_000)}"
    }

    private fun userText(rnd: Random, index: Int): String =
        "Вопрос $index: ${sentence(rnd)}"

    /**
     * Assistant-сообщение с markdown-разметкой (упражняет полный путь разбора
     * [ru.mars.mobile.MarkdownFormatter]: код-блоки, списки, таблицы, ссылки).
     * [oversized] = true даёт пузырь заведомо длиннее [TranscriptCapPolicy.MAX_BUBBLE_CHARS].
     */
    private fun assistantText(rnd: Random, index: Int, oversized: Boolean): String {
        val sb = StringBuilder()
        sb.append("## Отчёт ").append(index).append("\n\n")
        val paragraphs = 2 + rnd.nextInt(4)
        repeat(paragraphs) {
            sb.append(sentence(rnd)).append(' ').append(sentence(rnd)).append("\n\n")
        }
        if (rnd.nextInt(3) == 0) {
            sb.append("```kotlin\nfun measure$index(): Long = System.nanoTime() // шаг $index\n```\n\n")
        }
        if (rnd.nextInt(4) == 0) {
            sb.append("- пункт ").append(rnd.nextInt(100)).append('\n')
            sb.append("- пункт ").append(rnd.nextInt(100)).append("\n\n")
        }
        if (rnd.nextInt(6) == 0) {
            sb.append("> цитата из отчёта ").append(index).append("\n\n")
        }
        if (rnd.nextInt(8) == 0) {
            sb.append("| метрика | до | после |\n| --- | --- | --- |\n| cap | 0 | ").append(index).append(" |\n\n")
        }
        if (rnd.nextInt(5) == 0) {
            sb.append("[документация](https://marscore.ru/docs/$index)\n\n")
        }
        if (oversized) {
            // Повтор абзацев с маркерами: разбор идёт ПОЛНЫМ регекс-путём (не fast path),
            // как на реальном мегабайтном ответе.
            val filler = sb.toString()
            while (sb.length <= TranscriptCapPolicy.MAX_BUBBLE_CHARS + 40_000 + rnd.nextInt(20_000)) {
                sb.append(filler)
            }
        }
        return sb.toString()
    }

    private fun sentence(rnd: Random): String {
        val words = 6 + rnd.nextInt(9)
        val capitalized = StringBuilder()
        repeat(words) { i ->
            val w = WORDS[rnd.nextInt(WORDS.size)]
            capitalized.append(if (i == 0) w.replaceFirstChar { it.uppercase() } else w)
            capitalized.append(if (i == words - 1) "." else " ")
        }
        return capitalized.toString()
    }
}
