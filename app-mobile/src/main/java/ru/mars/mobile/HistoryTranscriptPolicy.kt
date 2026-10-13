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
 * Правила (WebUI parity):
 * - `user` → пользовательский пузырь (пустые строки отбрасываются);
 * - `assistant` → пузырь Марса; ПУСТАЯ assistant-строка (activity-строка без текста)
 *   сама по себе не рендерится, но становится якорем, если на неё приземлился
 *   хотя бы один tool-результат (иначе детали инструмента потерялись бы);
 * - `tool` → сворачивается в карточку [ToolExecution] (collapsed, результат
 *   ограничен [StreamEventMapper.MAX_TOOL_PAYLOAD_CHARS]) на ближайшем preceding
 *   assistant-якоре; сироты без якоря отбрасываются;
 * - любые прочие роли (system и т.п.) — служебные, не рендерятся.
 *
 * Метки времени: wire-строки несут epoch-секунды в `timestamp`
 * ([MessageTimePolicy.parseServerTimestamp] разбирает их после нормализации в
 * core-network) — без них история теряет часы и день-разделители.
 */
object HistoryTranscriptPolicy {

    fun of(rows: List<SessionMessage>): List<ChatMessage> {
        val bubbles = mutableListOf<ChatMessage>()
        // Индекс последнего assistant-якоря в [bubbles]; -1 — якоря нет.
        var anchorIndex = -1
        // Пустая assistant-строка: материализуется в пузырь только если на неё
        // приземлится tool-карточка (иначе в конце прохода отбрасывается).
        var pendingEmptyAnchor: ChatMessage? = null

        fun anchorToToolCard(row: SessionMessage) {
            if (anchorIndex < 0) {
                val pending = pendingEmptyAnchor ?: return // сирота без якоря — скрываем
                bubbles += pending
                anchorIndex = bubbles.size - 1
                pendingEmptyAnchor = null
            }
            val anchor = bubbles[anchorIndex]
            bubbles[anchorIndex] = anchor.copy(
                tools = anchor.tools + ToolExecution(
                    id = row.toolCallId,
                    name = row.toolName?.takeIf { it.isNotBlank() } ?: GENERIC_TOOL_NAME,
                    arguments = "",
                    result = StreamEventMapper.capToolPayload(row.content),
                    expanded = false,
                )
            )
        }

        for (row in rows) {
            val timestamp = MessageTimePolicy.parseServerTimestamp(row.createdAt)
            when (row.role.trim().lowercase()) {
                "user" -> {
                    if (row.content.isBlank()) continue
                    bubbles += ChatMessage("user", row.content, timestamp = timestamp)
                    anchorIndex = -1
                    pendingEmptyAnchor = null
                }
                "assistant" -> {
                    pendingEmptyAnchor = null
                    anchorIndex = -1
                    if (row.content.isBlank()) {
                        pendingEmptyAnchor = ChatMessage("assistant", timestamp = timestamp)
                    } else {
                        bubbles += ChatMessage("assistant", row.content, timestamp = timestamp)
                        anchorIndex = bubbles.size - 1
                    }
                }
                "tool" -> anchorToToolCard(row)
                // system/developer/иные служебные роли в транскрипт не попадают.
            }
        }
        return bubbles
    }

    /** Имя карточки, когда wire-строка не донесла `tool_name`/`name`. */
    const val GENERIC_TOOL_NAME = "tool"
}
