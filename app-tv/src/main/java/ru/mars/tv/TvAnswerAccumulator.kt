package ru.mars.tv

/**
 * Pure accumulator + terminal-state machine for a streaming TV answer bubble.
 *
 * STREAMING: the answer bubble starts life as a "Марс: " placeholder; the streaming chunks
 * are plain answer text. The placeholder is dropped exactly ONCE — at the first chunk — and
 * every chunk after that is appended verbatim. The previous inline implementation re-applied
 * `removePrefix("Марс: ")` to the accumulated text on EVERY chunk, so an answer that itself
 * began with «Марс: » lost that prefix.
 *
 * TERMINAL (first event wins): the SSE read loop in ChatRepository does NOT stop at terminal
 * frames — after `error/apperror` -> ChatEvent.Error and `done/stream_end/cancel` ->
 * ChatEvent.Done it keeps delivering whatever the server (or the parser's buffered
 * `parser.end()` flush, or a connection drop during the tail read) sends next. So late
 * ChatEvent.Chunk frames after Error/Done and a trailing Error after Done DO reach the UI.
 * Once this accumulator is terminal, late events are rejected (null/false return) and the
 * locked outcome survives: a late chunk can no longer clobber the error text, mutate or
 * extend the finished answer, and a tail network error cannot overwrite a completed answer.
 *
 * The state is PER-INSTANCE: every turn constructs a fresh accumulator, so a new answer
 * streams normally right after a terminal one. Keeping this machine out of the Activity
 * makes both the once-only reset and the terminal lock testable on the JVM.
 */
class TvAnswerAccumulator(placeholder: String) {

    /** Answer lifecycle: live chunks, or the locked outcome (first terminal event wins). */
    enum class State { STREAMING, DONE, ERROR }

    var state: State = State.STREAMING
        private set

    private val builder = StringBuilder(placeholder)
    private var placeholderRemoved = false
    private var errorText: String? = null

    /** Authoritative bubble text right now: the locked error, else the accumulated answer/placeholder. */
    fun text(): String = errorText ?: builder.toString()

    /**
     * Consumes one stream chunk.
     *
     * @return the full accumulated answer text, or null when the answer is already terminal
     * (Done/Error) — the renderer must then leave the bubble untouched.
     */
    fun onChunk(chunk: String): String? {
        if (state != State.STREAMING) return null
        if (!placeholderRemoved) {
            builder.setLength(0)
            placeholderRemoved = true
        }
        builder.append(chunk)
        return builder.toString()
    }

    /**
     * Terminal success (Done event): locks the accumulated answer against late frames.
     *
     * @return true when THIS call locked the answer; false when it was already terminal.
     */
    fun onDone(): Boolean {
        if (state != State.STREAMING) return false
        state = State.DONE
        return true
    }

    /**
     * Terminal failure (Error event): replaces the bubble text with the error rendering
     * (same visible format as before, now owned here so it is JVM-testable) and locks it.
     *
     * @return the error text to render, or null when the answer is already terminal — the
     * first terminal event wins, so a tail error never clobbers a completed answer and a
     * second error never rewrites the first.
     */
    fun onError(message: String): String? {
        if (state != State.STREAMING) return null
        state = State.ERROR
        errorText = "$ERROR_PREFIX$message"
        return errorText
    }

    companion object {
        /** Bubble prefix of a terminal stream error (status-strip text lives in TvChatSessionFlow). */
        const val ERROR_PREFIX = "Ошибка: "
    }
}
