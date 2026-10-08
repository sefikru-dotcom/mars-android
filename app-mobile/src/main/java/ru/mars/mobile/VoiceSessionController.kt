package ru.mars.mobile

/**
 * Voice-input session state machine, decoupled from any Activity: the host (e.g. ChatActivity
 * with its ACTION_RECOGNIZE_SPEECH intent flow, or a future SpeechRecognizer wrapper) feeds
 * lifecycle events here, and the controller guards transitions and fans state out to a
 * [Listener]. Pure JVM — no Android imports, fully unit-testable.
 *
 * States and the only legal transitions:
 * ```
 * IDLE ──start()──▶ LISTENING ──onResult(text)──▶ PROCESSING ──onProcessingComplete()──▶ IDLE
 *  ▲                    │                             │
 *  │                    ├──onError(msg)──▶ ERROR ◀────┘
 *  └──────cancel()/reset()───────────────────┘
 * ```
 * - [start] is rejected (returns false) while LISTENING or PROCESSING — no double sessions;
 * - [onResult] is accepted ONLY from LISTENING; a blank recognition is reported as an error,
 *   never as an empty message to the host;
 * - [onError] moves LISTENING/PROCESSING to ERROR and records [lastError];
 * - [cancel] returns to IDLE from any active state without delivering a result;
 * - [reset] clears ERROR back to IDLE (also clears [lastError]);
 * - every event fired against the wrong state is ignored (late recognizer callbacks after a
 *   cancel must not resurrect a dead session).
 */
class VoiceSessionController(private val listener: Listener? = null) {

    enum class State { IDLE, LISTENING, PROCESSING, ERROR }

    interface Listener {
        /** Fired on every accepted transition, with the NEW state. */
        fun onStateChanged(state: State) {}
        /** Mic session opened — host starts recording (and gives haptic feedback). */
        fun onListeningStarted() {}
        /** Recognized, non-blank text handed to the host for processing (trimmed). */
        fun onResult(text: String) {}
        /** Recognition or processing failed; [message] is user-facing. */
        fun onError(message: String) {}
    }

    var state: State = State.IDLE
        private set

    /** Last error message while [state] is ERROR; cleared by [reset]/[cancel]/new [start]. */
    var lastError: String? = null
        private set

    /**
     * Open a listening session. Returns false (no-op) when already LISTENING or PROCESSING —
     * an active session is never restarted underneath the user.
     */
    fun start(): Boolean {
        if (state == State.LISTENING || state == State.PROCESSING) return false
        lastError = null
        transition(State.LISTENING)
        listener?.onListeningStarted()
        return true
    }

    /**
     * Deliver recognized speech. Accepted only from LISTENING. Blank/whitespace-only input is
     * treated as a recognition failure ([EMPTY_RESULT]) — the host must never receive an empty
     * message. On success the controller moves to PROCESSING and forwards the trimmed text.
     */
    fun onResult(rawText: String): Boolean {
        if (state != State.LISTENING) return false
        val text = rawText.trim()
        if (text.isEmpty()) {
            fail(EMPTY_RESULT)
            return false
        }
        transition(State.PROCESSING)
        listener?.onResult(text)
        return true
    }

    /** Host finished handling the recognized text (e.g. message sent): PROCESSING -> IDLE. */
    fun onProcessingComplete(): Boolean {
        if (state != State.PROCESSING) return false
        transition(State.IDLE)
        return true
    }

    /**
     * Recognition/processing failure from the host. Ignored while IDLE (a late error callback
     * for an already-cancelled session must not flip a fresh state to ERROR).
     */
    fun onError(message: String) {
        if (state == State.IDLE) return
        fail(message.ifBlank { GENERIC_ERROR })
    }

    /** Abort from any active state back to IDLE without delivering a result. */
    fun cancel(): Boolean {
        if (state == State.IDLE) return false
        lastError = null
        transition(State.IDLE)
        return true
    }

    /** Clear an ERROR state (user retry / dismissed notice): ERROR -> IDLE. */
    fun reset(): Boolean {
        if (state != State.ERROR) return false
        lastError = null
        transition(State.IDLE)
        return true
    }

    private fun fail(message: String) {
        lastError = message
        transition(State.ERROR)
        listener?.onError(message)
    }

    private fun transition(next: State) {
        state = next
        listener?.onStateChanged(next)
    }

    companion object {
        const val EMPTY_RESULT = "Речь не распознана — попробуйте ещё раз."
        const val GENERIC_ERROR = "Голосовой ввод недоступен — попробуйте ещё раз."
    }
}
