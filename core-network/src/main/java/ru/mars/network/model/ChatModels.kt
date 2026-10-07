package ru.mars.network.model

sealed interface ChatEvent {
    data class Chunk(val text: String) : ChatEvent
    data object Done : ChatEvent
    data class Error(val message: String) : ChatEvent
}
