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
 * служебные `role:"tool"` с сырым JSON вывода) не должны становиться пузырями,
 * а ОДИН ход агента (все assistant-сегменты + tool-строки между user-сообщениями)
 * рендерится ОДНОЙ карточкой — как живой стрим (GAP-1).
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

    // ---- GAP-1: один ход = одна карточка --------------------------------------------------

    @Test
    fun `multiple assistant rows of one turn merge into a single grouped card`() {
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
        // 3 assistant-строки хода — НЕ 3 пузыря, а одна карточка.
        assertEquals(2, bubbles.size)
        assertEquals(listOf("user", "assistant"), bubbles.map { it.role })
        val card = bubbles[1]
        assertEquals(
            "Шаг 1.${HistoryTranscriptPolicy.SEGMENT_SEPARATOR}Шаг 2.${HistoryTranscriptPolicy.SEGMENT_SEPARATOR}Готово.",
            card.text,
        )
        // Tool-карточки всех сегментов — внутри этой карточки, в порядке wire-строк.
        assertEquals(listOf("t1", "t2", "t3"), card.tools.map { it.id })
        assertEquals(listOf("terminal", "read_file", "patch"), card.tools.map { it.name })
        assertTrue(card.tools.all { !it.expanded })
    }

    @Test
    fun `commentary tool and final segments of one turn render as a single card`() {
        val bubbles = HistoryTranscriptPolicy.of(
            listOf(
                row("user", "почини"),
                row("assistant", "Смотрю логи."),
                row("tool", """{"output":"err"}""", toolName = "terminal", toolCallId = "t1"),
                row("assistant", "Вижу причину, правлю."),
                row("tool", """{"diff":"ok"}""", toolName = "patch", toolCallId = "t2"),
                row("assistant", "Готово, тесты зелёные."),
            )
        )
        assertEquals(2, bubbles.size)
        val card = bubbles[1]
        assertEquals(
            "Смотрю логи.\n\nВижу причину, правлю.\n\nГотово, тесты зелёные.",
            card.text,
        )
        assertEquals(listOf("t1", "t2"), card.tools.map { it.id })
        assertTrue(card.tools.all { !it.expanded })
    }

    @Test
    fun `empty assistant rows plus tools render one card with tools and no text`() {
        // Реальная форма sidecar-хода: пустые activity-строки вокруг tool-результатов.
        val bubbles = HistoryTranscriptPolicy.of(
            listOf(
                row("user", "привет"),
                row("assistant", ""),
                row("tool", "{}", toolName = "terminal", toolCallId = "t1"),
                row("assistant", "   "),
                row("tool", "{}", toolName = "read_file", toolCallId = "t2"),
            )
        )
        assertEquals(2, bubbles.size)
        val card = bubbles[1]
        assertEquals("", card.text)
        assertEquals(listOf("t1", "t2"), card.tools.map { it.id })
        assertTrue(card.tools.all { !it.expanded })
    }

    @Test
    fun `empty assistant rows without tools never render a bubble`() {
        // Ход только из пустых activity-строк — карточки нет вовсе.
        val bubbles = HistoryTranscriptPolicy.of(
            listOf(row("user", "привет"), row("assistant", ""), row("assistant", "  "))
        )
        assertEquals(1, bubbles.size)
        assertEquals("user", bubbles.single().role)
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
        assertTrue(withoutTools[1].tools.isEmpty())

        // Пустая activity-строка С tool-хвостом — часть той же карточки хода.
        val withTools = HistoryTranscriptPolicy.of(
            listOf(
                row("user", "привет"),
                row("assistant", ""),
                row("tool", "{}", toolName = "terminal", toolCallId = "t1"),
                row("assistant", "Ответ."),
            )
        )
        assertEquals(2, withTools.size)
        assertEquals("Ответ.", withTools[1].text)
        assertEquals(listOf("terminal"), withTools[1].tools.map { it.name })
    }

    @Test
    fun `trailing empty assistant row without tools is dropped`() {
        val bubbles = HistoryTranscriptPolicy.of(
            listOf(row("user", "привет"), row("assistant", "Ответ."), row("assistant", "  "))
        )
        assertEquals(2, bubbles.size)
        assertEquals("Ответ.", bubbles[1].text)
    }

    @Test
    fun `user row closes the turn so cards never mix across turns`() {
        val bubbles = HistoryTranscriptPolicy.of(
            listOf(
                row("user", "первый вопрос"),
                row("assistant", "Первый ответ."),
                row("tool", "{}", toolName = "terminal", toolCallId = "t1"),
                row("user", "второй вопрос"),
                row("tool", "{}", toolName = "terminal", toolCallId = "orphan"),
                row("assistant", "Второй ответ."),
                row("tool", "{}", toolName = "patch", toolCallId = "t2"),
            )
        )
        assertEquals(4, bubbles.size)
        assertEquals(listOf("user", "assistant", "user", "assistant"), bubbles.map { it.role })
        // Каждый ход несёт только СВОИ инструменты.
        assertEquals(listOf("t1"), bubbles[1].tools.map { it.id })
        assertEquals(listOf("t2"), bubbles[3].tools.map { it.id })
        // Tool-строка сразу после user-границы (до assistant-якоря хода) — сирота.
        assertTrue(bubbles.none { card -> card.tools.any { it.id == "orphan" } })
        assertEquals("Первый ответ.", bubbles[1].text)
        assertEquals("Второй ответ.", bubbles[3].text)
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

    // ---- Метки времени ----------------------------------------------------------------------

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
    fun `multi-day history renders a Moscow clock on every bubble and day separators`() {
        val msk = java.time.ZoneId.of("Europe/Moscow")
        val bubbles = HistoryTranscriptPolicy.of(
            listOf(
                // 2026-10-13 19:54:37 UTC = 22:54 MSK
                row("user", "вчерашний вопрос", timestamp = "1791921277.8392885"),
                row("assistant", "вчерашний ответ", timestamp = "1791921300"),
                // 2026-10-14 06:40:00 UTC = 09:40 MSK — другие сутки
                row("user", "сегодняшний вопрос", timestamp = "1791960000"),
                row("assistant", "сегодняшний ответ", timestamp = "1791960030"),
            )
        )
        assertEquals(4, bubbles.size)
        // Час есть у КАЖДОГО пузыря истории (ни один не остался UNKNOWN).
        val clocks = bubbles.map { MessageTimePolicy.clockLabel(it.timestamp, msk) }
        assertEquals(listOf("22:54", "22:55", "09:40", "09:40"), clocks)
        // Разделитель суток: первый пузырь и смена дня, между сообщениями одного дня — нет.
        val snapshots = bubbles.mapIndexed { index, bubble ->
            CardSnapshotPolicy.of(
                bubble,
                previousTimestamp = bubbles.getOrNull(index - 1)?.timestamp ?: MessageTimePolicy.UNKNOWN_TIMESTAMP,
                chromeEpoch = 0L,
                streamingFlag = false,
            )
        }
        assertEquals(listOf(true, false, true, false), snapshots.map { it.showDaySeparator })
        // Подписи разделителей считаются от тех же меток (в зоне владельца).
        assertEquals("13 октября", MessageTimePolicy.dayLabel(snapshots[0].separatorTimestamp, millisNow(), msk))
        assertEquals("14 октября", MessageTimePolicy.dayLabel(snapshots[2].separatorTimestamp, millisNow(), msk))
    }

    private fun millisNow(): Long = java.time.Instant.parse("2026-10-20T09:00:00Z").toEpochMilli()

    @Test
    fun `grouped card keeps the first assistant timestamp of the turn`() {
        val bubbles = HistoryTranscriptPolicy.of(
            listOf(
                row("user", "задача", timestamp = "1791921200.0"),
                row("assistant", "", timestamp = "1791921201.0"),
                row("tool", "{}", toolName = "terminal", toolCallId = "t1", timestamp = "1791921220.0"),
                row("assistant", "Готово.", timestamp = "1791921300.0"),
            )
        )
        assertEquals(2, bubbles.size)
        assertEquals(1_791_921_200_000L, bubbles[0].timestamp)
        // Метка карточки хода — начало хода (первая assistant-строка), не финал и не tool.
        assertEquals(1_791_921_201_000L, bubbles[1].timestamp)
    }

    @Test
    fun `turn without a timestamp on the first assistant row falls back to the next known one`() {
        val bubbles = HistoryTranscriptPolicy.of(
            listOf(
                row("user", "задача"),
                row("assistant", ""),
                row("tool", "{}", toolName = "terminal", toolCallId = "t1", timestamp = "1791921220.0"),
                row("assistant", "Готово.", timestamp = "1791921300.0"),
            )
        )
        assertEquals(2, bubbles.size)
        // Tool-метки не используются: первая распознанная метка assistant-строк хода.
        assertEquals(1_791_921_300_000L, bubbles[1].timestamp)
    }

    @Test
    fun `rows without timestamps stay UNKNOWN without breaking the mapping`() {
        val bubbles = HistoryTranscriptPolicy.of(listOf(row("user", "привет"), row("assistant", "Ответ.")))
        assertTrue(bubbles.all { it.timestamp == MessageTimePolicy.UNKNOWN_TIMESTAMP })
    }

    // ---- Реальная форма sidecar-сессии --------------------------------------------------------

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
        // Весь ход — ОДНА assistant-карточка: 2 пузыря, а не 14 строк и не 6 построчных.
        assertEquals(2, bubbles.size)
        assertEquals("user", bubbles[0].role)
        val card = bubbles[1]
        assertEquals("assistant", card.role)
        assertTrue(card.text.contains("Скилл не найден, чиню."))
        assertTrue(card.text.contains("Нашёл причину."))
        assertTrue(card.text.endsWith("Готово."))
        // Сырой tool-вывод никогда не становится текстом пузыря.
        assertFalse(card.text.contains("\"success\"") || card.text.contains("\"released\""))
        // Все 8 инструментов хода — collapsed-карточки внутри этой карточки, по порядку.
        assertEquals(
            listOf("call_9", "call_10", "call_11", "call_12", "call_13", "call_14", "call_15", "call_16"),
            card.tools.map { it.id },
        )
        assertTrue(card.tools.all { !it.expanded })
        // Пустых карточек без текста и инструментов не остаётся.
        assertTrue(bubbles.none { it.text.isBlank() && it.tools.isEmpty() })
        // Метка карточки — начало хода.
        assertEquals(1_791_921_201_000L, card.timestamp)
    }
}
