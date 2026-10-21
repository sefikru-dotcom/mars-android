package ru.mars.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.AgentFinishedReason
import ru.mars.network.model.VoiceEvent
import ru.mars.network.repository.VoiceStreamParser

/**
 * Разбор кадров голосового шлюза без сокета: транспортную фрагментацию, keepalive и
 * дрейф серверного контракта проверяем на чистом JVM.
 *
 * Ключевые инварианты:
 * - кадр, разрезанный по чанкам ГДЕ УГОДНО (включая `\r`/`\n` и середину JSON),
 *   собирается ровно один раз и не теряет хвост;
 * - комментарии-keepalive и кадры без `data` не порождают событий;
 * - неизвестное имя события и битый JSON не бросают исключений;
 * - `id:` кадра становится курсором `Last-Event-ID`.
 */
class VoiceStreamParserTest {

    private fun frame(id: String, event: String, data: String) = "id: $id\nevent: $event\ndata: $data\n\n"

    private val sessionReady = frame(
        "v1_abc:1",
        "session.ready",
        """{"protocol":"hermes.voice.v1","voice_id":"v1_abc","session_id":"s-1",""" +
            """"server_events":["session.ready","agent.text.delta"],"keepalive_seconds":5,""" +
            """"resume_requested":false,"resume_status":"none"}""",
    )

    private fun fullStream(): String = buildString {
        append(sessionReady)
        append(frame("v1_abc:2", "transcript.partial", """{"text":"привет","session_id":"s-1"}"""))
        append(frame("v1_abc:3", "transcript.final", """{"text":"привет, Марс","session_id":"s-1","final":true}"""))
        append(frame("v1_abc:4", "agent.started", """{"session_id":"s-1","run_id":"r-1"}"""))
        append(frame("v1_abc:5", "agent.text.delta", """{"text":"Здрав"}"""))
        append(frame("v1_abc:6", "agent.text.delta", """{"text":"ствуй"}"""))
        append(frame("v1_abc:7", "tool.started", """{"name":"read_file","preview":"a.kt","tool_call_id":"c-1"}"""))
        append(
            frame(
                "v1_abc:8",
                "tool.finished",
                """{"name":"read_file","preview":"a.kt","tool_call_id":"c-1","is_error":false,"duration":0.42}""",
            )
        )
        append(frame("v1_abc:9", "speech.stop", """{"reason":"interrupt","at":1730000000,"run_id":"r-1","session_id":"s-1"}"""))
        append(
            frame(
                "v1_abc:10",
                "agent.finished",
                """{"reason":"done","session_id":"s-1","usage":{"tokens":12},"terminal_state":{"status":"ok"}}""",
            )
        )
    }

    private val expected: List<VoiceEvent> = listOf(
        VoiceEvent.SessionReady(
            voiceId = "v1_abc",
            sessionId = "s-1",
            protocol = "hermes.voice.v1",
            serverEvents = listOf("session.ready", "agent.text.delta"),
            keepaliveSeconds = 5,
            resumeRequested = false,
            resumeStatus = "none",
        ),
        VoiceEvent.TranscriptPartial(text = "привет", sessionId = "s-1"),
        VoiceEvent.TranscriptFinal(text = "привет, Марс", sessionId = "s-1", final = true),
        VoiceEvent.AgentStarted(sessionId = "s-1", message = null),
        VoiceEvent.AgentTextDelta(text = "Здрав"),
        VoiceEvent.AgentTextDelta(text = "ствуй"),
        VoiceEvent.ToolStarted(name = "read_file", preview = "a.kt", toolCallId = "c-1"),
        VoiceEvent.ToolFinished(
            name = "read_file", preview = "a.kt", toolCallId = "c-1", isError = false, duration = 0.42,
        ),
        VoiceEvent.SpeechStop(reason = "interrupt", at = 1730000000L, runId = "r-1", sessionId = "s-1"),
        VoiceEvent.AgentFinished(
            reason = AgentFinishedReason.DONE,
            sessionId = "s-1",
            message = null,
            usage = mapOf("tokens" to 12.0),
            terminalState = mapOf("status" to "ok"),
        ),
    )

    @Test
    fun `parses every known event of a whole stream`() {
        val parser = VoiceStreamParser()
        val events = parser.feed(fullStream())

        assertEquals(expected.size, events.size)
        assertEquals(expected[0], events[0])
        assertEquals(expected[1], events[1])
        assertEquals(expected[2], events[2])
        assertEquals(expected[3], events[3])
        assertEquals(expected[4], events[4])
        assertEquals(expected[5], events[5])
        assertEquals(expected[6], events[6])
        assertEquals(expected[7], events[7])
        assertEquals(expected[8], events[8])
        assertEquals(expected[9], events[9])
        // ответ = конкатенация дельт
        assertEquals(
            "Здравствуй",
            events.filterIsInstance<VoiceEvent.AgentTextDelta>().joinToString("") { it.text },
        )
    }

    @Test
    fun `reassembles frames split into single-character chunks`() {
        val parser = VoiceStreamParser()
        val collected = mutableListOf<VoiceEvent>()
        // Один символ за вызов: разрезаны и строки, и JSON, и пары CRLF.
        for (chunk in fullStream()) collected += parser.feed(chunk.toString())

        assertEquals(expected, collected)
    }

    @Test
    fun `reassembles CRLF stream split between CR and LF`() {
        val crlf = fullStream().replace("\n", "\r\n")
        val parser = VoiceStreamParser()
        val collected = mutableListOf<VoiceEvent>()
        // Разрез ровно между '\r' и '\n' — классический случай потери кадра.
        var index = 0
        while (index < crlf.length) {
            val step = if (crlf[index] == '\r' && index + 1 < crlf.length) 1 else 7
            collected += parser.feed(crlf.substring(index, minOf(index + step, crlf.length)))
            index += step
        }
        assertEquals(expected, collected)
    }

    @Test
    fun `ignores keepalive comments and data-less frames`() {
        val parser = VoiceStreamParser()
        val events = parser.feed(
            ": keepalive\n\n" +
                ": keepalive\n" +
                "id: v1_abc:0\n\n" +
                frame("v1_abc:1", "agent.text.delta", """{"text":"ok"}""") +
                "\n: ping\n\n",
        )
        assertEquals(listOf(VoiceEvent.AgentTextDelta("ok")), events)
        // Кадры без data не двигают курсор докачки.
        assertEquals("v1_abc:1", parser.lastEventId)
    }

    @Test
    fun `unknown event name surfaces as Unknown without throwing`() {
        val parser = VoiceStreamParser()
        val events = parser.feed(frame("v1_abc:1", "agent.thinking", """{"note":"planning"}"""))
        assertEquals(listOf(VoiceEvent.Unknown("agent.thinking", """{"note":"planning"}""")), events)
    }

    @Test
    fun `broken JSON becomes Unknown instead of an exception`() {
        val parser = VoiceStreamParser()
        val events = parser.feed(frame("v1_abc:1", "transcript.partial", """{"text":"""))
        assertEquals(1, events.size)
        val unknown = events.single() as VoiceEvent.Unknown
        assertEquals("transcript.partial", unknown.name)
        assertEquals("v1_abc:1", parser.lastEventId)
    }

    @Test
    fun `unknown fields are ignored`() {
        val parser = VoiceStreamParser()
        val events = parser.feed(
            frame("v1_abc:7", "agent.text.delta", """{"text":"x","brand_new_field":{"a":1}}"""),
        )
        assertEquals(listOf(VoiceEvent.AgentTextDelta("x")), events)
    }

    @Test
    fun `agent finished reason maps to typed enum`() {
        fun reason(wire: String): AgentFinishedReason {
            val events = VoiceStreamParser().feed(
                frame("v1_abc:1", "agent.finished", """{"reason":"$wire","session_id":"s-1"}"""),
            )
            return (events.single() as VoiceEvent.AgentFinished).reason
        }

        assertEquals(AgentFinishedReason.DONE, reason("done"))
        assertEquals(AgentFinishedReason.CANCELLED, reason("cancelled"))
        assertEquals(AgentFinishedReason.ERROR, reason("error"))
        assertEquals(AgentFinishedReason.STREAM_END, reason("stream_end"))
        assertEquals(AgentFinishedReason.UNKNOWN, reason("something_new"))
    }

    @Test
    fun `last event id tracks the newest frame id`() {
        val parser = VoiceStreamParser()
        assertNull(parser.lastEventId)
        parser.feed(frame("v1_abc:4", "agent.text.delta", """{"text":"a"}"""))
        assertEquals("v1_abc:4", parser.lastEventId)
        parser.feed(frame("v1_abc:5", "agent.text.delta", """{"text":"b"}"""))
        assertEquals("v1_abc:5", parser.lastEventId)
    }

    @Test
    fun `end flushes a frame that never saw its blank line`() {
        val parser = VoiceStreamParser()
        val partial = "id: v1_abc:2\nevent: agent.text.delta\ndata: {\"text\":\"tail\"}"
        assertTrue(parser.feed(partial).isEmpty())
        assertEquals(listOf(VoiceEvent.AgentTextDelta("tail")), parser.end())
    }

    // --- проективные поля voice_mode / capabilities ----------------------------------------

    @Test
    fun `voice mode and capabilities are read when the server sends them`() {
        val events = VoiceStreamParser().feed(
            frame(
                "v1_abc:1",
                "session.ready",
                """{"voice_id":"v1_abc","session_id":"s-1","voice_mode":true,""" +
                    """"capabilities":["normalizer","tts_voice_profile"]}""",
            ),
        )

        val ready = events.single() as VoiceEvent.SessionReady
        assertEquals(true, ready.voiceMode)
        assertTrue(ready.supportsVoiceMode)
        assertTrue(ready.supports("normalizer"))
        assertTrue("сравнение без регистра", ready.supports("TTS_VOICE_PROFILE"))
        assertFalse(ready.supports("speech_to_speech"))
        assertFalse(ready.supports("  "))
    }

    @Test
    fun `missing voice mode is unknown not false`() {
        val ready = VoiceStreamParser().feed(sessionReady).single() as VoiceEvent.SessionReady

        assertNull("отсутствие ключа ≠ выключенный режим", ready.voiceMode)
        assertFalse(ready.supportsVoiceMode)
        assertTrue(ready.capabilities.isEmpty())
        assertFalse(ready.supports("normalizer"))
    }

    @Test
    fun `explicit false voice mode is distinguishable from missing`() {
        val ready = VoiceStreamParser().feed(
            frame(
                "v1_abc:1",
                "session.ready",
                """{"voice_id":"v1_abc","session_id":"s-1","voice_mode":false}""",
            ),
        ).single() as VoiceEvent.SessionReady

        assertEquals(false, ready.voiceMode)
        assertFalse(ready.supportsVoiceMode)
    }

    @Test
    fun `unknown fields in session ready do not break the frame`() {
        val ready = VoiceStreamParser().feed(
            frame(
                "v1_abc:1",
                "session.ready",
                """{"voice_id":"v1_abc","session_id":"s-1","future_field":{"a":1},"voice_mode":true}""",
            ),
        ).single() as VoiceEvent.SessionReady

        assertEquals("v1_abc", ready.voiceId)
        assertEquals(true, ready.voiceMode)
    }
}
