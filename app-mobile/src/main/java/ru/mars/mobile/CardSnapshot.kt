package ru.mars.mobile

import ru.mars.network.model.SubagentStatus

/**
 * Снимок ВСЕХ входных данных рендера ОДНОЙ карточки транскрипта — чистая JVM-модель
 * (data class, структурное равенство). Инкрементальный рендер сравнивает снимки
 * «что нарисовано» vs «что в состоянии»: совпали — карточка не трогается вообще,
 * differs — пересобирается ровно она одна, а не весь контейнер.
 *
 * Снимок обязан покрывать каждое поле [ChatMessage] и каждый глобальный вход,
 * которые читает построение карточки (renderMessages/buildMessageCard): пропуск
 * поля = залипший UI (политика скажет NoChange, а вид должен был измениться).
 * Тяжёлые строки (текст, мысли, результаты инструментов) сворачиваются в отпечатки:
 * String.hashCode кэшируется внутри экземпляра JVM-строкой, поэтому пересчёт
 * снимка на кадр стрима стоит O(1) по числу сообщений.
 *
 * [chromeEpoch] — счётчик «эпохи окружения»: переключатель режима активности
 * (stream_full/window/quiet) и фолды code-блоков хранятся ВНЕ сообщений, но влияют
 * на ВСЕ карточки сразу; хост инкрементирует эпоху при их смене → все снимки
 * различаются → [IncrementalTranscriptPolicy] выбирает полный пересбор (как раньше,
 * но только на эти редкие пользовательские действия).
 */
data class CardSnapshot(
    val chromeEpoch: Long,
    val showDaySeparator: Boolean,
    val separatorTimestamp: Long,
    val isTrimAnchor: Boolean,
    val trimmedCount: Int,
    val isUser: Boolean,
    val attachments: List<String>,
    val textLength: Int,
    val textHash: Int,
    val status: MessageStatus,
    val streamingFlag: Boolean,
    val thoughtsExpanded: Boolean,
    val thoughtsFingerprint: Long,
    val toolsFingerprint: Long,
    val timestamp: Long,
    /** Фактическая модель хода ([ChatMessage.model], волна C) — подпись карточки ответа. */
    val model: String? = null,
    /** Провайдер фактической модели хода ([ChatMessage.modelProvider]). */
    val modelProvider: String? = null,
    /** Отпечаток доски субагентов хода ([ChatMessage.subagents], волна C). */
    val subagentsFingerprint: Long = CardSnapshotPolicy.EMPTY_SUBAGENTS_FINGERPRINT,
) {
    /** Всё, кроме текста пузыря: «окружение» карточки идентично — менять нужно только markdown-секцию. */
    fun sameChrome(other: CardSnapshot): Boolean =
        copy(textLength = -1, textHash = 0) == other.copy(textLength = -1, textHash = 0)
}

/** Построение снимков карточек из [ChatMessage] — без Android, тестируется на JVM. */
object CardSnapshotPolicy {

    /** Затравка Fold (простое) — чтобы пустой список не давал 0 и не сливался с «одним пустым элементом». */
    private const val SEEDED_PRIME = 1125899906842597L

    fun of(
        message: ChatMessage,
        previousTimestamp: Long,
        chromeEpoch: Long,
        streamingFlag: Boolean,
    ): CardSnapshot = CardSnapshot(
        chromeEpoch = chromeEpoch,
        // Ровно та же формула, что и в цикле прежнего renderMessages: разделитель суток
        // на первой известной метке и на смене локального дня.
        showDaySeparator = MessageTimePolicy.showDaySeparator(previousTimestamp, message.timestamp),
        separatorTimestamp = message.timestamp,
        isTrimAnchor = message.isTrimAnchor,
        trimmedCount = message.trimmedCount,
        isUser = message.role == "user",
        attachments = message.attachments,
        textLength = message.text.length,
        textHash = message.text.hashCode(),
        status = message.status,
        streamingFlag = streamingFlag,
        thoughtsExpanded = message.thoughtsExpanded,
        thoughtsFingerprint = thoughtsFingerprint(message.thoughts),
        toolsFingerprint = toolsFingerprint(message.tools),
        timestamp = message.timestamp,
        model = message.model,
        modelProvider = message.modelProvider,
        subagentsFingerprint = subagentsFingerprint(message.subagents),
    )

    /** Отпечаток списка мыслей: размер + содержимое (мысли дописываются во время стрима). */
    fun thoughtsFingerprint(thoughts: List<String>): Long =
        thoughts.fold(SEEDED_PRIME) { acc, thought -> acc * 31 + thought.hashCode() }

    /** Отпечаток инструментов: прибытие результата/аргументов/фолда меняет карточку. */
    fun toolsFingerprint(tools: List<ToolExecution>): Long =
        tools.fold(SEEDED_PRIME) { acc, tool ->
            var hash = acc * 31 + (tool.id?.hashCode() ?: 0)
            hash = hash * 31 + tool.name.hashCode()
            hash = hash * 31 + tool.arguments.hashCode()
            hash = hash * 31 + (tool.result?.hashCode() ?: 0)
            hash = hash * 31 + (if (tool.expanded) 1 else 0)
            hash
        }

    /** Пустая доска субагентов: отпечаток «детей нет» ([ChatMessage.subagents] пуст). */
    const val EMPTY_SUBAGENTS_FINGERPRINT: Long = SEEDED_PRIME

    /**
     * Отпечаток доски субагентов хода (волна C, фича B): любое изменение ребёнка —
     * старт, шаг, переход статуса, причина провала — делает снимок «грязным» и
     * пересобирает ровно эту карточку. Порядок обхода карты детерминирован
     * (LinkedHashMap-вставка [SubagentStatusPolicy]), поэтому равные доски дают
     * равные отпечатки.
     */
    fun subagentsFingerprint(subagents: Map<String, SubagentStatus>): Long =
        subagents.entries.fold(SEEDED_PRIME) { acc, entry ->
            var hash = acc * 31 + entry.key.hashCode()
            hash = hash * 31 + entry.value.state.ordinal
            hash = hash * 31 + (entry.value.displayName?.hashCode() ?: 0)
            hash = hash * 31 + (entry.value.model?.hashCode() ?: 0)
            hash = hash * 31 + (entry.value.phase?.hashCode() ?: 0)
            hash = hash * 31 + (entry.value.failureReason?.hashCode() ?: 0)
            hash
        }
}
