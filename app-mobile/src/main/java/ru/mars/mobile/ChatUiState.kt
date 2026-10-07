package ru.mars.mobile

import ru.mars.network.model.ApprovalPayload
import ru.mars.network.model.ChatEvent
import ru.mars.network.model.ClarifyPayload
import ru.mars.network.model.SessionItem

/** UI model for the rich chat transcript. Kept platform independent for unit tests. */
enum class ChatStatus { IDLE, TYPING, RECEIVED, ERROR }
enum class MessageStatus { PENDING, STREAMING, COMPLETE, ERROR }

data class ToolExecution(
    val id: String?,
    val name: String,
    val arguments: String,
    val result: String? = null,
    val expanded: Boolean = false,
)

data class ChatMessage(
    val role: String,
    val text: String = "",
    val attachments: List<String> = emptyList(),
    val thoughts: List<String> = emptyList(),
    val thoughtsExpanded: Boolean = false,
    val tools: List<ToolExecution> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.COMPLETE,
)

class ChatUiState {
    var status: ChatStatus = ChatStatus.IDLE
        private set
    val messages: MutableList<ChatMessage> = mutableListOf()
    var activeSessionId: String? = null
        private set
    var sessionTitle: String? = null
        private set
    var sessions: List<SessionItem> = emptyList()
        private set
    var phase: String? = null
        private set
    /** Approval prompt awaiting the user's decision (dialog is driven by InteractiveRequestController). */
    var pendingApproval: ApprovalPayload? = null
        private set
    /** Clarify prompt awaiting the user's answer (dialog is driven by InteractiveRequestController). */
    var pendingClarify: ClarifyPayload? = null
        private set

    fun begin(message: String, attachmentNames: List<String> = emptyList()): Boolean {
        if (message.isBlank() && attachmentNames.isEmpty() || status == ChatStatus.TYPING) return false
        status = ChatStatus.TYPING
        messages += ChatMessage("user", message, attachments = attachmentNames, status = MessageStatus.COMPLETE)
        messages += ChatMessage("assistant", status = MessageStatus.STREAMING)
        return true
    }

    fun onEvent(event: ChatEvent) {
        // Attention requests are recorded even without a live assistant bubble (e.g. history
        // replay races) so the controller hook never misses a prompt.
        when (event) {
            is ChatEvent.ApprovalRequired -> pendingApproval = event.payload
            is ChatEvent.ClarifyRequired -> pendingClarify = event.payload
            else -> Unit
        }
        val index = messages.indexOfLast { it.role == "assistant" }
        if (index < 0) return
        val current = messages[index]
        messages[index] = when (event) {
            is ChatEvent.SessionBound -> { activeSessionId = event.sessionId; current }
            is ChatEvent.StreamStarted -> current
            is ChatEvent.Chunk -> current.copy(text = current.text + event.text)
            is ChatEvent.Thought -> current.copy(thoughts = current.thoughts + event.text)
            is ChatEvent.ToolCall -> current.copy(tools = current.tools + ToolExecution(event.id, event.name, event.arguments))
            is ChatEvent.ToolResult -> current.copy(tools = current.tools.map { tool ->
                if ((event.id != null && tool.id == event.id) || (event.id == null && tool.name == event.name && tool.result == null)) tool.copy(result = event.result) else tool
            })
            is ChatEvent.Phase -> { phase = event.name; current }
            // Transcript parity with the pre-typed mapper: keep a readable trace of the prompt
            // while the structured payload drives the interactive dialog.
            is ChatEvent.ApprovalRequired -> current.copy(thoughts = current.thoughts + approvalThought(event.payload))
            is ChatEvent.ClarifyRequired -> current.copy(thoughts = current.thoughts + clarifyThought(event.payload))
            ChatEvent.Done -> current.copy(status = MessageStatus.COMPLETE)
            is ChatEvent.Error -> current.copy(text = current.text + "\nОшибка: " + event.message, status = MessageStatus.ERROR)
        }
        when (event) {
            ChatEvent.Done -> complete()
            is ChatEvent.Error -> fail()
            else -> Unit
        }
    }

    fun complete() { status = ChatStatus.RECEIVED; phase = null; clearPendingInteractive(); markAssistant(MessageStatus.COMPLETE) }
    fun fail() { status = ChatStatus.ERROR; phase = null; clearPendingInteractive(); markAssistant(MessageStatus.ERROR) }
    fun cancel() { status = ChatStatus.IDLE; phase = null; clearPendingInteractive(); markAssistant(MessageStatus.COMPLETE) }
    fun startNewSession() { activeSessionId = null; sessionTitle = null; messages.clear(); status = ChatStatus.IDLE; phase = null; clearPendingInteractive() }
    fun setSessions(value: List<SessionItem>) { sessions = value }
    fun bindSession(id: String?, title: String?, history: List<ChatMessage>) { activeSessionId = id; sessionTitle = title; messages.clear(); messages.addAll(history); status = ChatStatus.IDLE; phase = null; clearPendingInteractive() }
    fun toggleThoughts(index: Int) { messages.getOrNull(index)?.let { messages[index] = it.copy(thoughtsExpanded = !it.thoughtsExpanded) } }
    fun toggleTool(messageIndex: Int, toolIndex: Int) { messages.getOrNull(messageIndex)?.let { m -> if (toolIndex in m.tools.indices) messages[messageIndex] = m.copy(tools = m.tools.mapIndexed { i, t -> if (i == toolIndex) t.copy(expanded = !t.expanded) else t }) } }

    /** Called by the interactive controller once an approval was answered or dismissed. */
    fun resolveApproval() { pendingApproval = null }

    /** Called by the interactive controller once a clarify was answered or dismissed. */
    fun resolveClarify() { pendingClarify = null }

    private fun clearPendingInteractive() { pendingApproval = null; pendingClarify = null }

    private fun approvalThought(payload: ApprovalPayload): String {
        val detail = payload.description.ifBlank { payload.command.ifBlank { payload.tool.orEmpty() } }
        return if (detail.isBlank()) "Требуется подтверждение" else "Требуется подтверждение: $detail"
    }

    private fun clarifyThought(payload: ClarifyPayload): String {
        val suffix = if (payload.choices.isEmpty()) "" else " (варианты: ${payload.choices.joinToString(" / ")})"
        return "Требуется уточнение: ${payload.question}$suffix"
    }

    private fun markAssistant(value: MessageStatus) {
        val i = messages.indexOfLast { it.role == "assistant" }
        if (i >= 0) messages[i] = messages[i].copy(status = value)
    }
}
