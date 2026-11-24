package ru.mars.network.local

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * SSE-парсер локального провайдера на живых ловушках PlusVibe (контракт §4, п. 3).
 *
 * Тест идёт через единственную зафиксированную контрактом точку — `LocalChatClient.stream`:
 * сам парсер W1 держит внутренним, и привязываться к его имени/API значило бы зафиксировать
 * чужую приватную деталь. Наблюдаемый контракт от этого не страдает: все четыре ловушки
 * видны именно в потоке событий.
 *
 * Если реализации транспорта ещё нет, тесты помечаются пропущенными
 * ([LocalClientFixture.MISSING_MESSAGE]), а не валят сборку модуля.
 */
class LocalSseParserTest {

    private val profile = LocalProviderProfile(
        id = "p1",
        title = "PlusVibe",
        baseUrl = "https://example.test/v1",
        model = "test-model",
    )

    // --- №1: куски tool_calls склеиваются по index --------------------------

    @Test
    fun toolCallChunksAreConcatenatedByIndex() {
        val body = frames(
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"set_alarm","arguments":""}}]}}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"time\":"}}]}}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"07:00\"}"}}]}}]}""",
            finish("tool_calls"),
            "[DONE]",
        )

        val events = collect(body, attempts = 1)

        val calls = events.filterIsInstance<LocalChatEvent.AssistantFinished>()
            .flatMap { it.toolCalls }
            .filter { it.name == "set_alarm" }
        assertEquals("должен собраться ровно один вызов инструмента, события: $events", 1, calls.size)
        val call = calls.single()
        assertEquals("id приходит только в первом чанке", "call_1", call.id)
        assertEquals("name приходит только в первом чанке", "set_alarm", call.name)
        assertEquals(
            "аргументы обязаны КОНКАТЕНИРОВАТЬСЯ по index, а не перезаписываться",
            """{"time":"07:00"}""",
            call.argumentsJson,
        )
    }

    // --- №2: EOF без data: [DONE] — не ошибка --------------------------------

    @Test
    fun eofWithoutDoneMarkerIsNotAnError() {
        val body = frames(
            delta("привет"),
            finish("stop"),
        ) // намеренно без "[DONE]"

        val events = collect(body, attempts = 1)

        assertTrue(
            "обрыв после finish_reason — норма, а не ошибка; события: $events",
            events.none { it is LocalChatEvent.Error },
        )
        val finished = events.filterIsInstance<LocalChatEvent.AssistantFinished>().single()
        assertEquals("привет", finished.fullText)
    }

    // --- №3: finish_reason в чанке с пустой delta ----------------------------

    @Test
    fun finishReasonArrivesInChunkWithEmptyDelta() {
        val body = frames(
            delta("раз"),
            delta("два"),
            finish("stop"),
            "[DONE]",
        )

        val events = collect(body, attempts = 1)

        val finished = events.filterIsInstance<LocalChatEvent.AssistantFinished>().single()
        assertEquals("текст собирается из дельт до чанка с finish_reason", "раздва", finished.fullText)
        assertTrue("вызовов инструментов нет", finished.toolCalls.isEmpty())
        assertTrue(events.none { it is LocalChatEvent.Error })
    }

    // --- №4: битый JSON не роняет поток --------------------------------------

    @Test
    fun brokenJsonDoesNotKillTheStream() {
        val body = frames(
            delta("раз"),
            """{"choices":[{"index":0,"delta":{"content":"обрыв""", // обрезанный JSON
            delta("два"),
            finish("stop"),
            "[DONE]",
        )

        val events = collect(body, attempts = 1)

        val text = events.filterIsInstance<LocalChatEvent.Delta>().joinToString("") { it.text }
        assertEquals("валидные чанки после битого обязаны дойти", "раздва", text)
    }

    // --- helpers ------------------------------------------------------------

    private fun collect(body: String, attempts: Int): List<LocalChatEvent> {
        val client = LocalClientFixture.clientOrNull()
        assumeTrue(LocalClientFixture.MISSING_MESSAGE, client != null)

        return MockWebServer().use { server ->
            repeat(attempts) {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(body),
                )
            }
            val base = server.url("/v1").toString().trimEnd('/')
            runBlocking {
                withTimeout(30_000) {
                    client!!.stream(
                        profile = profile.copy(baseUrl = base),
                        apiKey = "test-key",
                        messages = listOf(LocalMessage(id = "u1", role = LocalRole.USER, text = "привет")),
                        tools = emptyList(),
                    ).toList()
                }
            }
        }
    }

    /** SSE-поток: каждый payload — отдельный `data:`-фрейм, кадры разделены пустой строкой. */
    private fun frames(vararg payloads: String): String =
        payloads.joinToString(separator = "\n\n", postfix = "\n\n") { "data: $it" }

    private fun delta(text: String): String =
        """{"choices":[{"index":0,"delta":{"content":"$text"},"finish_reason":null}]}"""

    private fun finish(reason: String): String =
        """{"choices":[{"index":0,"delta":{},"finish_reason":"$reason"}]}"""
}
