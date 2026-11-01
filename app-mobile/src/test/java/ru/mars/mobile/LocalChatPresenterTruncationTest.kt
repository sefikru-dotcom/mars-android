package ru.mars.mobile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.local.LocalChatClient
import ru.mars.network.local.LocalChatEvent
import ru.mars.network.local.LocalMessage
import ru.mars.network.local.LocalProviderProfile
import ru.mars.network.local.LocalRole
import ru.mars.network.local.LocalSession
import ru.mars.network.local.LocalToolExecutor
import ru.mars.network.local.LocalToolResult
import ru.mars.network.local.LocalToolSchema
import ru.mars.network.local.LocalTurnMetrics
import ru.mars.network.local.LocalUsage

/**
 * Связка `finish_reason` → «обрезан» → история (A1 + A3 контракта волны 12).
 *
 * Проверяется презентер, а не экран: транспорт подменён [ScriptedClient], поэтому
 * событие `AssistantFinished` с настоящим `finish_reason` доходит до ленты и до истории
 * без сети и без устройства. Второй шаг — тот же диалог через файл
 * ([LocalSessionJson]): после «перезапуска» футер обязан быть прежним.
 */
class LocalChatPresenterTruncationTest {

    @Test
    fun `length finish reason marks the answer truncated`() = runBlocking {
        val presenter = presenter(
            LocalChatEvent.Delta("ответ"),
            LocalChatEvent.UsageReported(LocalUsage(1_200, 300, 1_500)),
            LocalChatEvent.AssistantFinished("ответ", emptyList(), LocalTurnMetrics.REASON_LENGTH),
        )

        presenter.send(profile(), KEY, emptyList(), "вопрос")

        val answer = presenter.state.value.items.last { it.role == LocalRole.ASSISTANT }
        assertTrue("finish_reason=length обязан выставить обрезку", answer.truncated)

        val line = LocalTurnFooter.line(answer)
        assertNotNull("футер с обрезкой не должен быть пустым", line)
        assertTrue("в футере нет слова обрезки: $line", line!!.contains(LocalTurnFooter.TRUNCATED))
        assertFalse("обрыв не должен подмешиваться: $line", line.contains(LocalTurnFooter.INTERRUPTED))
    }

    @Test
    fun `stop is not truncation`() = runBlocking {
        val presenter = presenter(
            LocalChatEvent.Delta("ответ"),
            LocalChatEvent.UsageReported(LocalUsage(100, 20, 120)),
            LocalChatEvent.AssistantFinished("ответ", emptyList(), "stop"),
        )

        presenter.send(profile(), KEY, emptyList(), "вопрос")

        val answer = presenter.state.value.items.last { it.role == LocalRole.ASSISTANT }
        assertFalse("stop — модель закончила сама, обрезки нет", answer.truncated)
        val line = LocalTurnFooter.line(answer)!!
        assertFalse("слова обрезки быть не должно: $line", line.contains(LocalTurnFooter.TRUNCATED))

        val metrics = presenter.historySnapshot().last { it.role == LocalRole.ASSISTANT }.metrics
        assertEquals("stop", metrics?.finishReason)
        assertFalse(metrics?.truncated == true)
    }

    @Test
    fun `missing finish reason is not truncation`() = runBlocking {
        val presenter = presenter(
            LocalChatEvent.Delta("ответ"),
            LocalChatEvent.UsageReported(LocalUsage(100, 20, 120)),
            // Провайдер причину не прислал — это норма, а не обрезка.
            LocalChatEvent.AssistantFinished("ответ", emptyList()),
        )

        presenter.send(profile(), KEY, emptyList(), "вопрос")

        val answer = presenter.state.value.items.last { it.role == LocalRole.ASSISTANT }
        assertFalse(answer.truncated)
        val metrics = presenter.historySnapshot().last { it.role == LocalRole.ASSISTANT }.metrics
        assertNull(metrics?.finishReason)
    }

    @Test
    fun `turn metrics land in history and survive a restart`() = runBlocking {
        val saved = mutableListOf<List<LocalMessage>>()
        val presenter = presenter(
            events = listOf(
                LocalChatEvent.Delta("ответ"),
                LocalChatEvent.UsageReported(LocalUsage(1_200, 300, 1_500)),
                LocalChatEvent.AssistantFinished("ответ", emptyList(), LocalTurnMetrics.REASON_LENGTH),
            ),
            saved = saved,
        )

        presenter.send(profile(), KEY, emptyList(), "вопрос")

        // onPersist зовётся в конце хода: именно эти сообщения лягут на диск.
        val persisted = saved.last()
        val metrics = persisted.last { it.role == LocalRole.ASSISTANT }.metrics
        assertNotNull("метрики хода не попали в историю", metrics)
        assertEquals(1_200, metrics!!.promptTokens)
        assertEquals(300, metrics.completionTokens)
        assertEquals(1_500, metrics.totalTokens)
        assertTrue("обрезка обязана сохраниться", metrics.truncated)
        assertEquals("length", metrics.finishReason)

        // Перезапуск: файл на диске → чтение → restore. Футер обязан совпасть.
        val liveItem = presenter.state.value.items.last { it.role == LocalRole.ASSISTANT }
        val fromDisk = LocalSessionJson
            .decode(LocalSessionJson.encode(LocalSession("s1", "Диалог", 1L), persisted))
            ?.messages
            .orEmpty()
        // На диск ложатся только реплики диалога: сообщение человека и ответ.
        // SYSTEM-блок памяти в историю не пишется — он собирается заново при каждом ходе,
        // и его наличие в файле означало бы устаревшую копию профиля владельца.
        assertEquals(2, fromDisk.size)
        assertTrue(
            "SYSTEM-блок не должен попадать на диск",
            fromDisk.none { it.role == LocalRole.SYSTEM },
        )

        val restarted = presenter()
        restarted.restore(fromDisk)
        val restoredItem = restarted.state.value.items.last { it.role == LocalRole.ASSISTANT }

        assertTrue("обрезка потерялась после перезапуска", restoredItem.truncated)
        assertEquals(
            "футер после перезапуска обязан быть прежним",
            LocalTurnFooter.line(liveItem),
            LocalTurnFooter.line(restoredItem),
        )
    }

    @Test
    fun `ttft is measured and restored`() = runBlocking {
        // Часы идут: ttft считается как «первый кадр минус старт хода», и с постоянными
        // часами он был бы нулём (то есть «не измеряли»).
        var tick = 0L
        val presenter = presenter(
            events = listOf(
                LocalChatEvent.Delta("ответ"),
                LocalChatEvent.UsageReported(LocalUsage(100, 20, 120)),
                LocalChatEvent.AssistantFinished("ответ", emptyList(), "stop"),
            ),
            clock = { tick++ },
        )

        presenter.send(profile(), KEY, emptyList(), "вопрос")

        val liveItem = presenter.state.value.items.last { it.role == LocalRole.ASSISTANT }
        assertTrue("ttft не измерен: ${liveItem.ttftMs}", liveItem.ttftMs > 0L)

        val metrics = presenter.historySnapshot().last { it.role == LocalRole.ASSISTANT }.metrics
        assertEquals(liveItem.ttftMs, metrics?.ttftMs)

        val restarted = presenter()
        restarted.restore(presenter.historySnapshot())
        val restoredItem = restarted.state.value.items.last { it.role == LocalRole.ASSISTANT }
        assertEquals("ttft потерялся при восстановлении", liveItem.ttftMs, restoredItem.ttftMs)
    }

    @Test
    fun `turn without usage leaves no metrics`() = runBlocking {
        // Провайдер не прислал `usage`, а часы стоят — измерять нечего: метрики нет,
        // футера нет. Нули в истории означали бы «шаг ничего не стоил».
        val presenter = presenter(
            LocalChatEvent.Delta("ответ"),
            LocalChatEvent.AssistantFinished("ответ", emptyList(), "stop"),
        )

        presenter.send(profile(), KEY, emptyList(), "вопрос")

        val message = presenter.historySnapshot().last { it.role == LocalRole.ASSISTANT }
        assertNull("нули не должны превращаться в метрику", message.metrics)
        val item = presenter.state.value.items.last { it.role == LocalRole.ASSISTANT }
        assertNull(LocalTurnFooter.line(item))
    }

    // --- фейки и фикстуры ---------------------------------------------------

    private fun presenter(
        vararg events: LocalChatEvent,
        saved: MutableList<List<LocalMessage>> = mutableListOf(),
        clock: () -> Long = { 1_000L },
    ): LocalChatPresenter = presenter(events.toList(), saved, clock)

    private fun presenter(
        events: List<LocalChatEvent> = emptyList(),
        saved: MutableList<List<LocalMessage>> = mutableListOf(),
        clock: () -> Long = { 1_000L },
    ): LocalChatPresenter = LocalChatPresenter(
        client = ScriptedClient(events),
        baseExecutor = LocalToolExecutor { LocalToolResult(ok = true, content = "ok") },
        onPersist = { messages -> saved.add(messages) },
        now = clock,
    )

    private fun profile(): LocalProviderProfile = LocalProviderProfile(
        id = "p1",
        title = "PlusVibe",
        baseUrl = "https://example.invalid/v1",
        model = "test-model",
        contextWindow = 200_000,
    )

    /** Клиент, отдающий записанный поток: сеть в этих тестах не участвует вовсе. */
    private class ScriptedClient(private val events: List<LocalChatEvent>) : LocalChatClient {

        override fun stream(
            profile: LocalProviderProfile,
            apiKey: String,
            messages: List<LocalMessage>,
            tools: List<LocalToolSchema>,
        ): Flow<LocalChatEvent> = flow {
            events.forEach { event -> emit(event) }
        }

        override suspend fun models(
            profile: LocalProviderProfile,
            apiKey: String,
        ): Result<List<String>> = Result.success(emptyList())
    }

    private companion object {
        /** Ключ-заглушка: транспорт подменён фейком, настоящий ключ в тест не попадает. */
        const val KEY = "test-key"
    }
}
