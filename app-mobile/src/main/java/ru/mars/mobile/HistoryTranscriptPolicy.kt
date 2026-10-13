package ru.mars.mobile

import ru.mars.network.model.SessionMessage
import ru.mars.network.repository.StreamEventMapper

/**
 * Преобразование сохранённого транскрипта сессии (wire-строки `GET /api/session`)
 * в пузыри [ChatMessage] — чистая JVM-политика, без Android.
 *
 * Почему не «каждая строка = пузырь»: persisted-история примерно на 60% состоит из
 * служебных строк `role:"tool"` — это СЫРОЙ вывод инструментов (JSON/логи), который
 * WebUI никогда не рендерит текстом (`_message_window_for_display`: «Tool result rows
 * are rendered through their assistant anchor or hidden as raw tool output»). Прямой
 * маппинг «всё не-user → assistant» превращал историю в простыню мегапузырей с
 * машинным JSON — P0-загрязнение пользовательского транскрипта.
 *
 * Почему не «каждая assistant-строка = пузырь» (GAP-1): один ОТВЕТ агента
 * persistится многими assistant-строками — пустые activity-строки, промежуточный
 * commentary между инструментами и финальный текст. Построчный маппинг дробил один
 * ход на стопку карточек (commentary-карточка, пустая якорная карточка, финальная
 * карточка), хотя живой стрим рисует ход одним пузырём: chunks копятся в одной
 * карточке, инструменты — collapsible-карточки внутри неё.
 *
 * Правила (WebUI parity, один ход = одна карточка):
 * - ХОД — все строки между user-сообщениями. Все assistant-сегменты и tool-строки
 *   хода сливаются в ОДИН assistant-пузырь:
 *   - непустые тексты сегментов склеиваются в порядке появления через
 *     [SEGMENT_SEPARATOR] (параграф markdown, как между отдельными сообщениями);
 *   - все tool-строки хода сворачиваются в карточки [ToolExecution] (collapsed,
 *     результат ограничен [StreamEventMapper.MAX_TOOL_PAYLOAD_CHARS]) внутри этой
 *     карточки, в порядке появления;
 *   - ход без текста И без tool-карточек (только пустые activity-строки) не
 *     материализуется — пустой пузырь не оставляем;
 * - `user` → пользовательский пузырь (пустые строки отбрасываются) и граница хода;
 * - `tool` до первой assistant-строки хода (включая строки до первого user) —
 *   сирота без якоря, скрывается;
 * - метка времени карточки хода — метка его ПЕРВОЙ assistant-строки (начало хода;
 *   wire несёт epoch-секунды в `timestamp`, [MessageTimePolicy.parseServerTimestamp]
 *   разбирает их после нормализации в core-network); если у первой строки метки нет,
 *   берётся первая распознанная метка среди остальных assistant-строк хода — без
 *   них история теряет часы и день-разделители;
 * - любые прочие роли (system и т.п.) — служебные, не рендерятся и ход не разрывают.
 */
object HistoryTranscriptPolicy {

    /** Разделитель склейки assistant-сегментов одного хода (параграф markdown). */
    const val SEGMENT_SEPARATOR = "\n\n"

    fun of(rows: List<SessionMessage>): List<ChatMessage> {
        val bubbles = mutableListOf<ChatMessage>()

        // Аккумуляторы текущего хода (сбрасываются на user-границе и в конце).
        var segments = mutableListOf<String>()      // непустые assistant-тексты, по порядку
        var tools = mutableListOf<ToolExecution>()  // tool-карточки хода, по порядку
        var turnTimestamp = MessageTimePolicy.UNKNOWN_TIMESTAMP
        // Была ли в ходе assistant-строка: она открывает ход и становится якорем
        // для tool-карточек; tool-строки до якоря — сироты и скрываются.
        var hasAnchor = false

        fun flushTurn() {
            if (hasAnchor) {
                val text = segments.joinToString(SEGMENT_SEPARATOR)
                // Ход без текста и без инструментов (пустые activity-строки) не рендерится.
                if (text.isNotBlank() || tools.isNotEmpty()) {
                    bubbles += ChatMessage(
                        role = "assistant",
                        text = text,
                        tools = tools.toList(),
                        timestamp = turnTimestamp,
                    )
                }
            }
            segments = mutableListOf()
            tools = mutableListOf()
            turnTimestamp = MessageTimePolicy.UNKNOWN_TIMESTAMP
            hasAnchor = false
        }

        for (row in rows) {
            val timestamp = MessageTimePolicy.parseServerTimestamp(row.createdAt)
            when (row.role.trim().lowercase()) {
                "user" -> {
                    if (row.content.isBlank()) continue
                    flushTurn()
                    bubbles += ChatMessage("user", row.content, timestamp = timestamp)
                }
                "assistant" -> {
                    if (!hasAnchor) {
                        hasAnchor = true
                        turnTimestamp = timestamp
                    } else if (turnTimestamp == MessageTimePolicy.UNKNOWN_TIMESTAMP) {
                        turnTimestamp = timestamp
                    }
                    if (row.content.isNotBlank()) segments += row.content
                }
                "tool" -> {
                    if (!hasAnchor) continue // сирота без якоря — скрываем
                    tools += ToolExecution(
                        id = row.toolCallId,
                        name = row.toolName?.takeIf { it.isNotBlank() } ?: GENERIC_TOOL_NAME,
                        arguments = "",
                        result = StreamEventMapper.capToolPayload(row.content),
                        expanded = false,
                    )
                }
                // system/developer/иные служебные роли в транскрипт не попадают.
            }
        }
        flushTurn()
        return bubbles
    }

    /** Имя карточки, когда wire-строка не донесла `tool_name`/`name`. */
    const val GENERIC_TOOL_NAME = "tool"
}
