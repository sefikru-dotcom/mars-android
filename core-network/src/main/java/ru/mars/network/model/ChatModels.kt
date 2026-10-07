package ru.mars.network.model

data class ChatStartResponse(val streamId: String, val sessionId: String? = null)

sealed interface ChatEvent {
    data class SessionBound(val sessionId: String) : ChatEvent
    data class Chunk(val text: String, val eventId: String? = null) : ChatEvent
    data class Thought(val text: String) : ChatEvent
    data class ToolCall(val id: String?, val name: String, val arguments: String) : ChatEvent
    data class ToolResult(val id: String?, val name: String, val result: String) : ChatEvent
    data class Phase(val name: String) : ChatEvent
    data object Done : ChatEvent
    data class Error(val message: String) : ChatEvent
}
