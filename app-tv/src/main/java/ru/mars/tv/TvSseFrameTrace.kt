package ru.mars.tv

import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ContextStatusUpdate
import ru.mars.network.model.GoalContinuation
import ru.mars.network.model.GoalUpdate
import ru.mars.network.model.SessionTitleUpdate
import ru.mars.network.model.StreamSideEvent
import ru.mars.network.model.SubagentStatusUpdate
import ru.mars.tv.chat.TvActivityDisplayPolicy

internal object TvSseFrameTrace {

    /** Тег logcat живого хода ТВ-чата. */
    const val TAG = "MarsTvChat"

    /**
     * Имена событий sealed-потока, которые ТВ-чат действительно обрабатывает: явными
     * ветками диспетчера, слоем сессии ([TvChatSessionFlow]), интерактивными карточками
     * или режимом показа хода ([TvActivityDisplayPolicy]). Список — не «красивый», а
     * проверяемый: тест сверяет его с фактическим набором [ChatEvent], чтобы новый
     * вариант не оказался «обработанным» по недоразумению.
     */
    val HANDLED_CHAT_EVENTS: Set<String> = setOf(
        "session_bound",
        "stream_started",
        "chunk/interim_assistant",
        "thought",
        "tool_call",
        "tool_result",
        "phase",
        "approval",
        "clarify",
        "metering",
        "pending_steer_leftover",
        "done",
        "error",
    )

    /**
     * Имя кадра для лога. Для `interim_assistant` имя двойное намеренно: на уровне
     * транспорта это [ChatEvent.Chunk], и по логу должно быть видно, что зона ответа
     * показывает именно промежуточную реплику, а не токены финального ответа.
     */
    fun chatEventName(event: ChatEvent): String = when (event) {
        is ChatEvent.SessionBound -> "session_bound"
        is ChatEvent.StreamStarted -> "stream_started"
        is ChatEvent.Chunk -> "chunk/interim_assistant"
        is ChatEvent.Thought -> "thought"
        is ChatEvent.ToolCall -> "tool_call"
        is ChatEvent.ToolResult -> "tool_result"
        is ChatEvent.Phase -> "phase"
        is ChatEvent.ApprovalRequired -> "approval"
        is ChatEvent.ClarifyRequired -> "clarify"
        is ChatEvent.ContextUsage -> "metering"
        is ChatEvent.SteerLeftover -> "pending_steer_leftover"
        ChatEvent.Done -> "done"
        is ChatEvent.Error -> "error"
        // sealed-интерфейс расширяется только правкой core-network: сюда попадёт
        // новый вариант, добавленный без ведома ТВ-чата.
        else -> "unknown:${event.javaClass.simpleName}"
    }

    /**
     * Строка для события sealed-потока, которое ТВ-чат не отрисовал; null — событие
     * обработано, шуметь не о чем.
     */
    fun unhandledChatEvent(event: ChatEvent): String? {
        val name = chatEventName(event)
        if (name in HANDLED_CHAT_EVENTS) return null
        return "SSE-событие без реакции ТВ-чата: $name — тип доставлен транспортом, но не разобран"
    }

    /** Имя кадра side-канала; для незнакомого носителя — его класс (тип важнее содержимого). */
    fun sideEventName(event: StreamSideEvent): String = when (event) {
        is ContextStatusUpdate -> "context_status"
        is SubagentStatusUpdate -> "subagent"
        is SessionTitleUpdate -> "title"
        is GoalUpdate -> "goal"
        is GoalContinuation -> "goal_continue"
        else -> "unknown:${event.javaClass.simpleName}"
    }

    /**
     * Строка для кадра side-канала, на который ТВ-чат не реагирует; null — кадр обработан
     * (`context_status` -> бейдж, `subagent` -> доска). Известные-но-без-реакции кадры
     * (`title`/`goal`/`goal_continue`) логируются отдельной формулировкой: это осознанный
     * паритет с мобильным чатом, а не пропущенная фича.
     */
    fun unhandledSideEvent(event: StreamSideEvent): String? = when (event) {
        is ContextStatusUpdate, is SubagentStatusUpdate -> null
        is SessionTitleUpdate, is GoalUpdate, is GoalContinuation ->
            "side-кадр доставлен без реакции ТВ-чата: ${sideEventName(event)} (паритет с мобильным)"
        else ->
            "side-кадр неизвестного типа: ${sideEventName(event)} — сервер прислал кадр, " +
                "которого ТВ-чат не знает"
    }

    /**
     * Чанк, который некуда положить: живого пузыря нет (лента перезагружена) или он уже
     * терминален (Done/Error). Текст такого кадра не показывается — раньше это
     * происходило без следа, теперь видно в logcat.
     */
    fun droppedChunk(hasAccumulator: Boolean, chars: Int): String =
        "чанк $chars симв. не показан: " +
            if (hasAccumulator) "ответ уже терминален (Done/Error)" else "живого пузыря нет"
}

