package ru.mars.network.local

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Агентный цикл: сколько шагов делает, что отдаёт наружу, как умирает.
 *
 * Тест держится только на контракте §3.4 (`LocalAgentLoop(client, executor, maxIterations)`
 * + `LocalChatClient`/`LocalToolExecutor`), поэтому он компилируется и до, и после
 * реализации W1 — а падает ровно там, где цикл нарушает договорённость.
 *
 * Корутины: `kotlinx-coroutines-test` в проекте не подключён (нет ни в одном модуле),
 * поэтому отмена проверяется настоящим `runBlocking`/`launch`/`delay` — не через
 * виртуальное время. Тест от этого не «умный», зато не тянет новую зависимость.
 */
class LocalAgentLoopTest {

    private val profile = LocalProviderProfile(
        id = "p1",
        title = "PlusVibe",
        baseUrl = "https://example.test/v1",
        model = "test-model",
    )

    private val executorCalls = mutableListOf<LocalToolCall>()

    private val executor = LocalToolExecutor { call ->
        executorCalls += call
        LocalToolResult(ok = true, content = "будильник поставлен на 07:00")
    }

    private fun userSays(text: String) = LocalMessage(id = "u1", role = LocalRole.USER, text = text)

    private fun loop(
        client: LocalChatClient,
        maxIterations: Int = LocalAgentLoop.MAX_ITERATIONS,
        tools: List<LocalToolSchema> = emptyList(),
    ): List<LocalChatEvent> = runBlocking {
        LocalAgentLoop(client, executor, maxIterations)
            .run(profile, API_KEY, listOf(userSays("привет")), tools)
            .toList()
    }

    @Test
    fun withoutToolsOneStepEndsWithAssistantFinished() {
        val client = ScriptedClient(
            listOf(
                listOf(
                    LocalChatEvent.Delta("при"),
                    LocalChatEvent.Delta("вет"),
                    LocalChatEvent.AssistantFinished("привет", emptyList()),
                ),
            ),
        )

        val events = loop(client)

        assertEquals("без инструментов цикл обязан остановиться после первого шага", 1, client.calls)
        assertTrue("текстовый ход не должен звать инструменты", events.none { it is LocalChatEvent.ToolRequested })
        val finished = events.filterIsInstance<LocalChatEvent.AssistantFinished>().single()
        assertEquals("привет", finished.fullText)
        assertTrue(finished.toolCalls.isEmpty())
        assertEquals("исполнитель не должен вызываться", 0, executorCalls.size)
        assertTrue("ошибок быть не должно: $events", events.none { it is LocalChatEvent.Error })
    }

    @Test
    fun oneToolTakesTwoStepsAndReportsToolFinished() {
        val call = LocalToolCall("call_1", "set_alarm", """{"time":"07:00"}""")
        val client = ScriptedClient(
            listOf(
                listOf(
                    LocalChatEvent.ToolRequested(call),
                    LocalChatEvent.AssistantFinished("", listOf(call)),
                ),
                listOf(
                    LocalChatEvent.Delta("готово"),
                    LocalChatEvent.AssistantFinished("готово", emptyList()),
                ),
            ),
        )

        val events = loop(client, tools = listOf(LocalToolSchema("set_alarm", "будильник", """{"type":"object"}""")))

        assertEquals("шагов должно быть два: запрос инструмента и ответ модели", 2, client.calls)
        assertEquals("инструмент обязан исполниться ровно один раз", 1, executorCalls.size)
        assertEquals("set_alarm", executorCalls.single().name)

        val finished = events.filterIsInstance<LocalChatEvent.ToolFinished>().single()
        assertEquals("call_1", finished.callId)
        assertTrue(finished.ok)
        assertTrue("preview для карточки в ленте обязателен", finished.preview.isNotEmpty())

        // Второй шаг уходит провайдеру уже с ответом инструмента, привязанным к вызову.
        val secondHistory = client.histories[1]
        val toolMessage = secondHistory.last { it.role == LocalRole.TOOL }
        assertEquals("call_1", toolMessage.toolCallId)
        assertTrue("результат инструмента должен попасть в историю", toolMessage.text.isNotEmpty())

        assertEquals("готово", events.filterIsInstance<LocalChatEvent.AssistantFinished>().last().fullText)
    }

    @Test
    fun iterationLimitStopsTheLoopWithError() {
        val call = LocalToolCall("call_x", "get_battery", "{}")
        val endless = List(50) {
            listOf(
                LocalChatEvent.ToolRequested(call),
                LocalChatEvent.AssistantFinished("", listOf(call)),
            )
        }
        val client = ScriptedClient(endless)

        val events = loop(client, maxIterations = 2)

        assertEquals("лимит итераций обязан оборвать прогон, а не крутиться до конца скрипта", 2, client.calls)
        val error = events.last()
        assertTrue("последним событием должен быть Error, а было $error", error is LocalChatEvent.Error)
        val message = (error as LocalChatEvent.Error).message
        assertTrue("сообщение должно объяснять обрыв, было: $message", message.contains("слишком много шагов"))
    }

    @Test
    fun maxIterationsConstantIsEight() {
        assertEquals(8, LocalAgentLoop.MAX_ITERATIONS)
    }

    @Test
    fun clientExceptionBecomesErrorAndDoesNotEscape() {
        val client = ThrowingClient(IllegalStateException("boom"))

        val result = runCatching { loop(client) }

        assertTrue("цикл не имеет права бросать наружу: ${result.exceptionOrNull()}", result.isSuccess)
        val events = result.getOrThrow()
        val error = events.filterIsInstance<LocalChatEvent.Error>().firstOrNull()
        assertTrue("исключение клиента обязано стать LocalChatEvent.Error, события: $events", error != null)
        assertTrue(error!!.message.isNotBlank())
    }

    @Test
    fun toolExceptionBecomesErrorAndDoesNotEscape() {
        val explodingExecutor = LocalToolExecutor { throw IllegalStateException("инструмент упал") }
        val call = LocalToolCall("call_2", "set_timer", """{"seconds":60}""")
        val client = ScriptedClient(
            listOf(
                listOf(
                    LocalChatEvent.ToolRequested(call),
                    LocalChatEvent.AssistantFinished("", listOf(call)),
                ),
            ),
        )

        val result = runCatching {
            runBlocking {
                LocalAgentLoop(client, explodingExecutor)
                    .run(profile, API_KEY, listOf(userSays("таймер")), emptyList())
                    .toList()
            }
        }

        assertTrue("падение инструмента не должно вылетать наружу: ${result.exceptionOrNull()}", result.isSuccess)
        assertTrue(
            "падение инструмента обязано прийти событием Error",
            result.getOrThrow().any { it is LocalChatEvent.Error },
        )
    }

    @Test
    fun cancellationStopsTheRun() = runBlocking {
        val emissions = AtomicInteger()
        val endless = object : LocalChatClient {
            override fun stream(
                profile: LocalProviderProfile,
                apiKey: String,
                messages: List<LocalMessage>,
                tools: List<LocalToolSchema>,
            ): Flow<LocalChatEvent> = flow {
                while (true) {
                    emissions.incrementAndGet()
                    emit(LocalChatEvent.Delta("кусок"))
                    delay(5)
                }
            }

            override suspend fun models(profile: LocalProviderProfile, apiKey: String): Result<List<String>> =
                Result.success(emptyList())
        }

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            LocalAgentLoop(endless, executor).run(profile, API_KEY, listOf(userSays("стоп")), emptyList()).collect()
        }
        delay(60)
        job.cancelAndJoin()

        assertTrue("прогон обязан быть отменён", job.isCancelled)
        val afterCancel = emissions.get()
        delay(50)
        assertEquals("после отмены стрим обязан замолчать", afterCancel, emissions.get())
        assertFalse("прогон не должен был успеть выжечь весь скрипт", afterCancel > 1000)
    }

    // --- фейки -------------------------------------------------------------

    /** Клиент, который отдаёт заранее записанные шаги: шаг N — N-й вызов `stream`. */
    private class ScriptedClient(private val steps: List<List<LocalChatEvent>>) : LocalChatClient {
        var calls = 0
            private set

        val histories = mutableListOf<List<LocalMessage>>()
        val toolsSeen = mutableListOf<List<LocalToolSchema>>()

        override fun stream(
            profile: LocalProviderProfile,
            apiKey: String,
            messages: List<LocalMessage>,
            tools: List<LocalToolSchema>,
        ): Flow<LocalChatEvent> = flow {
            histories += messages
            toolsSeen += tools
            val step = steps.getOrNull(calls) ?: emptyList()
            calls += 1
            emitAll(step.asFlow())
        }

        override suspend fun models(profile: LocalProviderProfile, apiKey: String): Result<List<String>> =
            Result.success(emptyList())
    }

    private class ThrowingClient(private val failure: Throwable) : LocalChatClient {
        override fun stream(
            profile: LocalProviderProfile,
            apiKey: String,
            messages: List<LocalMessage>,
            tools: List<LocalToolSchema>,
        ): Flow<LocalChatEvent> = flow { throw failure }

        override suspend fun models(profile: LocalProviderProfile, apiKey: String): Result<List<String>> =
            Result.failure(failure)
    }

    private companion object {
        /** Секрет в тесте не нужен: транспорт подменён фейком. */
        const val API_KEY = "test-key"
    }
}
