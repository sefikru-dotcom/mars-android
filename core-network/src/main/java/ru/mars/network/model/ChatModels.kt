package ru.mars.network.model

data class ChatStartResponse(val streamId: String)

sealed interface ChatEvent {
    data class Chunk(val text: String, val eventId: String? = null) : ChatEvent
    data object Done : ChatEvent
    data class Error(val message: String) : ChatEvent
}
