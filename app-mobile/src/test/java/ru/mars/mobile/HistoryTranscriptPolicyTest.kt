package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SessionMessage
import ru.mars.network.repository.StreamEventMapper

/**
 * P0-регрессия транскрипта истории: persisted-строки `GET /api/session` (~60% —
 * служебные `role:"tool"` с сырым JSON вывода) не должны становиться пузырями.
 * Форма строк — реальная wire-форма sidecar/state.db (см. hermes-webui
 * api/routes.py `_message_window_for_display`: tool-строки рендерятся только
 * через assistant-якорь или скрываются).
 */
class HistoryTranscriptPolicyTest {

    private fun row(
        role: String,
        content: String,
        toolName: String? = null,
        toolCallId: String? = null,
        timestamp: String? = null,
    ) = SessionMessage(
        id = null,
        role = role,
        content = content,
        createdAt = timestamp,
        eventId = null,
        toolName = toolName,
        toolCallId = toolCallId,
    )

    private val toolJson = """{"success": false, "error": "Skill 'x' not found."}"""

    @Test
    fun `service tool rows never become assistant bubbles`() {
        val bubbles = HistoryTranscriptPolicy.of(
            listOf(
                row("user", "проверь скилл"),
                row("assistant", "Смотрю."),
                row("tool", toolJson, toolName = "skill_view", toolCallId = "call_1"),
                row("tool", """{"files": ["a.kt"]}""", toolName = "search_files", toolCallId = "call_2"),
            )
        )
        assertEquals(2, bubbles.size)
        assertEquals(listOf("user", "assistant"), bubbles.map { it.role })
        // Сырой tool-вывод не попадает ни в один текст пузыря.
        assertTrue(bubbles.none { it.text.contains("success") || it.text.contains("files") })
        val anchor = bubbles[1]
        assertEquals(2, anchor.tools.size)
        assertEquals(listOf("skill_view", "search_files"), anchor.tools.map { it.name })
        assertEquals(listOf("call_1", "call_2"), anchor.tools.map { it.id })
        assertEquals(toolJson, anchor.tools[0].result)
        // Карточки свёрнуты — tool output только collapsible-карточка.
        assertTrue(anchor.tools.all { !it.expanded })
        assertEquals(MessageStatus.COMPLETE, anchor.status)
    }

    @Test
    fun `history with multiple assistants keeps one bubble per visible row in order`() {
        val bubbles = HistoryTranscriptPolicy.of(
            listOf(
                row("user", "задача"),
                row("assistant", "Шаг 1."),
                row("tool", "{}", toolName = "terminal", toolCallId = "t1"),
                row("assistant", "Шаг 2."),
                row("tool", "{}", toolName = "read_file", toolCallId = "t2"),
                row("tool", "{}", toolName = "patch", toolCallId = "t3"),
                row("assistant", "Готово."),
            )
        )
        assertEquals(4, bubbles.size)
        assertEquals(listOf("user", "assistant", "assistant", "assistant"), bubbles.map { it.role })
        assertEquals(listOf("Шаг 1.", "Шаг 2.", "Готово."), bubbles.drop(1).map { it.text })
        assertEquals(listOf(1, 2, 0), bubbles.drop(1).map { it.tools.size })
        assertEquals("t1", bubbles[1].tools.single().id)
        assertEquals(listOf("t2", "t3"), bubbles[2].tools.map { it.id })
    }

    @Test
    fun `empty assistant activity row renders only when it anchors tool cards`() {
        // Пустая activity-строка без tool-хвоста не плодит пустой пузырь.
        val withoutTools = HistoryTranscriptPolicy.of(
            listOf(row("user", "привет"), row("assistant", ""), row("assistant", "Ответ."))
        )
        assertEquals(2, withoutTools.size)
        assertEquals(listOf("user", "assistant"), withoutTools.map { it.role })
        assertEquals("Ответ.", withoutTools[1].text)

        // Пустая activity-строка С tool-хвостом материализуется якорем карточек.
        val withTools = HistoryTranscriptPolicy.of(
            listOf(
                row("user", "привет"),
                row("assistant", ""),
                row("tool", "{}", toolName = "terminal", toolCallId = "t1"),
                row("assistant", "Ответ."),
            )
        )
        assertEquals(3, withTools.size)
        assertEquals("", withTools[1].text)
        assertEquals(listOf("terminal"), withTools[1].tools.map { it.name })
    }

    @Test
    fun `trailing empty assistant row without tools is dropped`() {
        val bubbles = HistoryTranscriptPolicy.of(
            listOf(row("user", "привет"), row("assistant", "Ответ."), row("assistant", "  "))
        )
        assertEquals(2, bubbles.size)
    }

    @Test
    fun `orphan tool rows and non-chat roles are dropped`() {
        val bubbles = HistoryTranscriptPolicy.of(
            listOf(
                row("tool", """{"orphan":true}""", toolName = "terminal", toolCallId = "t0"),
                row("system", "служебный системный промпт"),
                row("user", "привет"),
                row("tool", """{"after_user":true}""", toolName = "terminal", toolCallId = "t1"),
                row("assistant", "Ответ."),
            )
        )
        assertEquals(2, bubbles.size)
        assertEquals(listOf("user", "assistant"), bubbles.map { it.role })
        assertTrue(bubbles.all { it.tools.isEmpty() })
        assertTrue(bubbles.none { it.text.contains("orphan") || it.text.contains("системный") || it.text.contains("after_user") })
    }

    @Test
    fun `blank user rows are dropped`() {
        val bubbles = HistoryTranscriptPolicy.of(listOf(row("user", "  "), row("assistant", "Ответ.")))
        assertEquals(1, bubbles.size)
        assertEquals("assistant", bubbles.single().role)
    }

    @Test
    fun `oversized tool result is capped with the shared live-path marker`() {
        val huge = "x".repeat(StreamEventMapper.MAX_TOOL_PAYLOAD_CHARS + 5_000)
        val bubbles = HistoryTranscriptPolicy.of(
            listOf(row("assistant", "Смотрю."), row("tool", huge, toolName = "terminal", toolCallId = "t1"))
        )
        val result = bubbles.single().tools.single().result.orEmpty()
        assertTrue(result.length <= StreamEventMapper.MAX_TOOL_PAYLOAD_CHARS + 64)
        assertTrue(result.contains("обрезано"))
        assertTrue(result.contains(huge.length.toString()))
    }

    @Test
    fun `tool row without name falls back to the generic card title`() {
        val bubbles = HistoryTranscriptPolicy.of(
            listOf(row("assistant", "Ок."), row("tool", "{}"))
        )
        val tool = bubbles.single().tools.single()
        assertEquals(HistoryTranscriptPolicy.GENERIC_TOOL_NAME, tool.name)
        assertNull(tool.id)
    }

    @Test
    fun `epoch second timestamps from the wire feed clock labels and day separators`() {
        val bubbles = HistoryTranscriptPolicy.of(
            listOf(
                row("user", "утро", timestamp = "1791921270.5"),
                row("assistant", "вечер", timestamp = "1791960000"),
            )
        )
        assertEquals(1_791_921_270_500L, bubbles[0].timestamp)
        assertEquals(1_791_960_000_000L, bubbles[1].timestamp)
        // Час на карточке больше не UNKNOWN.
        assertTrue(bubbles.all { it.timestamp > MessageTimePolicy.UNKNOWN_TIMESTAMP })
    }

    @Test
    fun `rows without timestamps stay UNKNOWN without breaking the mapping`() {
        val bubbles = HistoryTranscriptPolicy.of(listOf(row("user", "привет"), row("assistant", "Ответ.")))
        assertTrue(bubbles.all { it.timestamp == MessageTimePolicy.UNKNOWN_TIMESTAMP })
    }

    @Test
    fun `real sidecar session shape maps to the visible transcript`() {
        // Форма реальной сессии (sidecar 04ffcfa1f227): 1 user + 5 assistant + 8 tool.
        val rows = buildList {
            add(row("user", "загрузи скилл", timestamp = "1791921200.0"))
            add(row("assistant", "", timestamp = "1791921201.0"))
            add(row("tool", toolJson, toolName = "skill_view", toolCallId = "call_9", timestamp = "1791921277.8392885"))
            add(row("tool", """{"output":"ok"}""", toolName = "terminal", toolCallId = "call_10", timestamp = "1791921280.1"))
            add(row("assistant", "Скилл не найден, чиню.", timestamp = "1791921300.0"))
            add(row("tool", """{"files":[]}""", toolName = "search_files", toolCallId = "call_11", timestamp = "1791921310.0"))
            add(row("assistant", "Нашёл причину.", timestamp = "1791921320.0"))
            add(row("tool", """{"diff":"..."}""", toolName = "patch", toolCallId = "call_12", timestamp = "1791921330.0"))
            add(row("tool", """{"tests":"green"}""", toolName = "terminal", toolCallId = "call_13", timestamp = "1791921340.0"))
            add(row("assistant", "", timestamp = "1791921350.0"))
            add(row("tool", """{"released":true}""", toolName = "terminal", toolCallId = "call_14", timestamp = "1791921360.0"))
            add(row("assistant", "Готово.", timestamp = "1791921370.0"))
            add(row("tool", """{"final":"check"}""", toolName = "read_file", toolCallId = "call_15", timestamp = "1791921380.0"))
            add(row("tool", """{"final":"check2"}""", toolName = "read_file", toolCallId = "call_16", timestamp = "1791921390.0"))
        }
        val bubbles = HistoryTranscriptPolicy.of(rows)
        // 1 user + 4 assistant-текста + 1 пустой якорь с карточкой = 6 пузырей, а не 14.
        assertEquals(6, bubbles.size)
        assertEquals("user", bubbles[0].role)
        assertTrue(bubbles.none { it.text.contains("\"success\"") || it.text.contains("\"released\"") })
        assertEquals(8, bubbles.sumOf { it.tools.size })
        // Пустой якорь materialized только благодаря tool-карточке.
        assertTrue(bubbles.any { it.text.isEmpty() && it.tools.size == 1 })
        assertFalse(bubbles.last().text.isEmpty())
    }
}
