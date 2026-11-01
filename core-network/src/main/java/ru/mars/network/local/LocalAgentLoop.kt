package ru.mars.network.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Результат исполнения инструмента на устройстве. */
data class LocalToolResult(
    val ok: Boolean,
    val content: String,
    /** Короткая строка для карточки в ленте; по умолчанию — первые 200 символов. */
    val preview: String = content.take(200),
)

/**
 * Исполнение инструмента. Отдельный интерфейс, а не ссылка на каталог: ядро не знает
 * про Android, а экран — про конкретные инструменты.
 */
fun interface LocalToolExecutor {
    suspend fun execute(call: LocalToolCall): LocalToolResult
}

/**
 * Агентный цикл локального чата.
 *
 * Один прогон = сколько угодно шагов модели, пока она просит инструменты, и не больше
 * [MAX_ITERATIONS] шагов. Почему лимит именно здесь: модель, зацикленная на неудачном
 * инструменте, иначе жжёт токены и батарею бесконечно, а пользователь видит «думает»
 * без конца.
 *
 * Цикл не бросает исключений наружу: всё, включая падение инструмента, приходит
 * [LocalChatEvent.Error]. Так экран не обязан оборачивать вызов в try.
 */
class LocalAgentLoop(
    private val client: LocalChatClient,
    private val executor: LocalToolExecutor,
    private val maxIterations: Int = MAX_ITERATIONS,
) {

    /**
     * Прогон диалога.
     *
     * @param history вся история, включая новое сообщение пользователя последним.
     * @param tools схемы инструментов; пустой список — обычный текстовый чат.
     */
    fun run(
        profile: LocalProviderProfile,
        apiKey: String,
        history: List<LocalMessage>,
        tools: List<LocalToolSchema>,
    ): Flow<LocalChatEvent> = flow {
        // Рабочая копия: провайдеру уходит история вместе с шагами, которых ещё нет в
        // хранилище экрана (ответ модели с tool_calls и ответы инструментов).
        val conversation = ArrayList<LocalMessage>(history.size + 4)
        conversation.addAll(history)

        var iteration = 0
        while (iteration < maxIterations) {
            iteration += 1

            val streamed = StringBuilder()
            var stepText = ""
            var requested: List<LocalToolCall> = emptyList()
            var stepFailed = false

            try {
                client.stream(profile, apiKey, conversation.toList(), tools).collect { event ->
                    when (event) {
                        is LocalChatEvent.Delta -> {
                            streamed.append(event.text)
                            this@flow.emit(event)
                        }
                        // Размышления идут в ленту отдельным событием, текстом ответа не считаются.
                        is LocalChatEvent.ReasoningDelta -> this@flow.emit(event)
                        is LocalChatEvent.ToolRequested -> this@flow.emit(event)
                        is LocalChatEvent.ToolFinished -> this@flow.emit(event)
                        // Расход шага пробрасывается как есть: агрегирует его экран, цикл
                        // не хранит состояние между шагами и не должен.
                        is LocalChatEvent.UsageReported -> this@flow.emit(event)
                        is LocalChatEvent.AssistantFinished -> {
                            stepText = event.fullText.ifEmpty { streamed.toString() }
                            requested = event.toolCalls
                            this@flow.emit(event)
                        }
                        is LocalChatEvent.Error -> {
                            stepFailed = true
                            this@flow.emit(event)
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                // Отмену глотать нельзя: иначе «Стоп» не остановит ни сокет, ни инструмент.
                throw cancellation
            } catch (failure: Throwable) {
                // Клиент по контракту не бросает, но подменённый/чужой — может.
                this@flow.emit(LocalChatEvent.Error(describe(failure)))
                return@flow
            }

            // Ошибку транспорта уже отдали наружу — второй шаг не начинаем.
            if (stepFailed) return@flow

            // Текстовый ответ без вызовов — конец прогона.
            if (requested.isEmpty()) return@flow

            conversation.add(
                LocalMessage(
                    id = "assistant-$iteration-${System.currentTimeMillis()}",
                    role = LocalRole.ASSISTANT,
                    text = stepText,
                    toolCalls = requested,
                    createdAt = System.currentTimeMillis(),
                ),
            )

            for (call in requested) {
                // Пустые аргументы — валидный `{}`: инструменты ждут JSON-объект, а не "".
                val normalized =
                    if (call.argumentsJson.isBlank()) call.copy(argumentsJson = EMPTY_ARGUMENTS) else call

                val result = try {
                    executor.execute(normalized)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    this@flow.emit(
                        LocalChatEvent.Error("инструмент «${call.name}» упал: ${describe(failure)}"),
                    )
                    return@flow
                }

                this@flow.emit(
                    LocalChatEvent.ToolFinished(
                        callId = call.id,
                        ok = result.ok,
                        preview = result.preview.ifBlank { result.content.take(PREVIEW_LIMIT) },
                    ),
                )

                conversation.add(
                    LocalMessage(
                        id = "tool-$iteration-${call.id}",
                        role = LocalRole.TOOL,
                        // Пустой текст провайдеры принимают плохо, а связь с вызовом обязательна.
                        text = result.content.ifBlank { if (result.ok) "готово" else "инструмент вернул ошибку" },
                        toolCallId = call.id,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }

        this@flow.emit(
            LocalChatEvent.Error("слишком много шагов: модель просит инструменты дольше $maxIterations итераций"),
        )
    }.flowOn(Dispatchers.IO)

    /** Человекочитаемая причина падения для [LocalChatEvent.Error]. */
    private fun describe(failure: Throwable): String =
        failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName

    companion object {
        /** Предел шагов модели в одном прогоне. */
        const val MAX_ITERATIONS = 8

        private const val EMPTY_ARGUMENTS = "{}"
        private const val PREVIEW_LIMIT = 200
    }
}
