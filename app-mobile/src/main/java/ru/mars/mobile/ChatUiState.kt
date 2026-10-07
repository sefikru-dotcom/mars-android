package ru.mars.mobile

enum class ChatStatus { IDLE, TYPING, RECEIVED, ERROR }

/** Small, platform-independent state machine used by the chat screen. */
class ChatUiState {
    var status: ChatStatus = ChatStatus.IDLE
        private set

    fun begin(message: String): Boolean {
        if (message.isBlank() || status == ChatStatus.TYPING) return false
        status = ChatStatus.TYPING
        return true
    }

    fun complete() { status = ChatStatus.RECEIVED }
    fun fail() { status = ChatStatus.ERROR }
    fun cancel() { status = ChatStatus.IDLE }
}
