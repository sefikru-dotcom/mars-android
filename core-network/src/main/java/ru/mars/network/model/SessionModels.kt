package ru.mars.network.model

/** A session shown in the WebUI session list. */
data class SessionItem(
    val id: String,
    val title: String? = null,
    val preview: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val messageCount: Int? = null,
)

/** A message persisted in a chat session. */
data class SessionMessage(
    val id: String? = null,
    val role: String,
    val content: String,
    val createdAt: String? = null,
    val eventId: String? = null,
)

/** Full session returned by the session detail endpoint. */
data class SessionDetail(
    val id: String,
    val title: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val messages: List<SessionMessage> = emptyList(),
)
