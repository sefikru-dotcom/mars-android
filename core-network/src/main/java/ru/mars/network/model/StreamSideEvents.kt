package ru.mars.network.model

/**
 * Non-sealed side-channel carriers for SSE metadata frames that are delivered
 * ALONGSIDE (never instead of) the sealed [ChatEvent] flow, via the
 * `ChatRepository.onSideEvent` transport callback on `sendMessage`/`attachToStream`.
 *
 * Why not new [ChatEvent] variants: app-mobile `ChatUiState` and app-tv
 * `TvChatSessionFlow` hold exhaustive `when` expressions over [ChatEvent], so a
 * new sealed variant breaks the app modules' compilation. This mirrors the
 * established `todo_state` precedent ([TodoSnapshot] + `ChatRepository.onTodoState`).
 * The interface is deliberately NON-sealed so future server frames can be added
 * without breaking consumer `when` expressions; consumers match with `is`.
 *
 * Data-layer rule: these carriers only DELIVER what the server broadcast. Any
 * reaction (renaming a session header, rendering a goal card, sending the
 * continuation prompt as a new user message) is an explicit UI-layer decision —
 * the transport must never trigger a continuation on its own.
 */
interface StreamSideEvent

/**
 * SSE `title` frame (hermes-webui api/streaming.py
 * `put_event('title', {'session_id': …, 'title': effective_title})`, emitted after
 * auto-titling a session mid-stream). The frame ALSO still maps to
 * [ChatEvent.SessionBound] (session binding is unchanged); this carrier adds the
 * title TEXT that the sealed event drops, so the UI can refresh the session
 * header/sidebar without a `GET /api/session` round-trip.
 */
data class SessionTitleUpdate(
    val sessionId: String?,
    val title: String,
) : StreamSideEvent

/**
 * SSE `goal` frame (hermes-webui api/streaming.py, /goal parity — GoalManager
 * judge around a settled turn): `{session_id, state, message, message_key,
 * message_args?, decision?}`.
 *
 * [state] is preserved verbatim (`evaluating` | `continuing` | `idle` — mapping
 * states onto presentation is UI work, same rule as [TodoItem.status]).
 * [decisionJson] is the raw `decision` object serialized as compact JSON — the
 * judge verdict is an opaque server payload the data layer must not reshape.
 */
data class GoalUpdate(
    val sessionId: String?,
    val state: String,
    val message: String? = null,
    val messageKey: String? = null,
    val messageArgs: List<String> = emptyList(),
    val decisionJson: String? = null,
) : StreamSideEvent

/**
 * SSE `goal_continue` frame (hermes-webui api/streaming.py): the GoalManager
 * decided the turn did not finish the goal and queued a continuation —
 * `{session_id, continuation_prompt, text, message, message_key, message_args,
 * decision}` (`text` is a wire alias of `continuation_prompt`).
 *
 * The WebUI surfaces the status line and queues [continuationPrompt] as the NEXT
 * user message so /queue and user input keep priority. This client DELIVERS the
 * prompt only; auto-sending it is an explicit UI-layer decision that must never
 * happen inside the transport.
 */
data class GoalContinuation(
    val sessionId: String?,
    val continuationPrompt: String,
    val message: String? = null,
    val messageKey: String? = null,
    val messageArgs: List<String> = emptyList(),
    val decisionJson: String? = null,
) : StreamSideEvent
