package ru.mars.mobile

import ru.mars.mobile.domain.util.SpeechContentFilter

/**
 * Voice-OUTPUT policy for completed answers, decoupled from any Activity: the host
 * (ChatActivity) feeds lifecycle/turn events here, and the controller decides WHAT is
 * spoken and WHEN, delegating the actual synthesis to a [SpeechEngine]. Pure JVM — no
 * Android imports, fully unit-testable. Mirror of [VoiceSessionController] (voice INPUT):
 * recognition stays an explicit user action, this class never opens the microphone.
 *
 * Rules:
 * - only a COMPLETED answer is spoken: the host calls [onTurnComplete] exactly once per
 *   finished turn (terminal Done / stream-end fallback / recovery finish). There is no API
 *   for intermediate SSE chunks, so partial stream frames can never reach the engine;
 * - text is cleaned through [SpeechContentFilter] (markdown, code fences, URLs, emoji and
 *   service blocks are never read verbatim); a blank/code-only result degrades gracefully;
 * - auto-reading happens only when the user opted in: the sticky [autoSpeakEnabled] toggle
 *   or the one-shot [armSpeakNextAnswer] armed by an explicit voice-input message. With
 *   both off, only manual [speakNow] requests (volume button / message speaker) speak;
 * - speech stops on every context change: [onNewTurn] (a new turn starts),
 *   [onSessionSwitch] (dialog switched / stopped / new chat — also disarms the one-shot),
 *   [onPause] (screen hidden) and [onDestroy]; while paused, completed answers are NOT
 *   spoken in the background;
 * - a missing or failing TTS engine disables output instead of crashing: [onEngineReady]
 *   with `false`, or any rejected [SpeechEngine.speak], flips [engineAvailable] off and
 *   every later request reports [SpeakOutcome.ENGINE_UNAVAILABLE];
 * - nothing here blocks: the engine contract is fire-and-forget (Android TextToSpeech
 *   synthesizes asynchronously), and all methods are cheap main-thread string operations.
 */
class VoiceOutputController(
    private val engine: SpeechEngine,
    private val filter: SpeechContentFilter = SpeechContentFilter(),
    autoSpeakInitially: Boolean = false,
    private val onAutoSpeakChanged: ((Boolean) -> Unit)? = null,
) {

    /** Minimal contract over the platform synthesizer; the Android adapter implements it. */
    interface SpeechEngine {
        /** Speak [text] (flushing whatever is currently spoken). False when the engine rejected it. */
        fun speak(text: String): Boolean
        /** Stop current speech immediately. Safe to call when idle. */
        fun stop()
        /** Release the engine permanently (host destroyed). */
        fun shutdown()
    }

    /** Result of a speak request — the host maps it to a status line. */
    enum class SpeakOutcome { SPOKEN, NOTHING_TO_SPEAK, ENGINE_UNAVAILABLE }

    /** Sticky «read answers aloud» mode (user toggle, persisted by the host). */
    var autoSpeakEnabled: Boolean = autoSpeakInitially
        private set

    /** False once the engine failed init or rejected a speak — output stays off, no retries. */
    var engineAvailable: Boolean = true
        private set

    /** False between [onPause] and [onResume]: completed answers are never spoken in background. */
    var foreground: Boolean = true
        private set

    /** One-shot flag: the NEXT completed answer is spoken even with [autoSpeakEnabled] off. */
    private var speakNextAnswer = false

    /** Platform engine init result (async). `false` disables all output gracefully. */
    fun onEngineReady(available: Boolean) {
        engineAvailable = available
        if (!available) speakNextAnswer = false
    }

    /** User toggled «озвучивать ответы». Disabling also stops speech in progress. */
    fun setAutoSpeak(enabled: Boolean) {
        autoSpeakEnabled = enabled
        onAutoSpeakChanged?.invoke(enabled)
        if (!enabled) stopSpeaking()
    }

    /**
     * Explicit voice input arms reading of the next completed answer: the user spoke to
     * Mars, Mars speaks back. Consumed by the first [onTurnComplete] (or cleared by
     * [onSessionSwitch]/engine death) — it never leaks across dialogs.
     */
    fun armSpeakNextAnswer() {
        if (engineAvailable) speakNextAnswer = true
    }

    /**
     * The turn is COMPLETE (terminal Done / stream-end fallback / recovery finish): speak
     * the final answer when auto mode or the voice-input one-shot allows it. Must never be
     * called for intermediate SSE chunks — the host wires it only to turn completion.
     * While paused ([onPause]) nothing is spoken and the one-shot arm is preserved for the
     * next completed turn in foreground.
     */
    fun onTurnComplete(finalAnswer: String?) {
        if (!engineAvailable || !foreground) return
        if (!autoSpeakEnabled && !speakNextAnswer) return
        speakNextAnswer = false
        speakInternal(finalAnswer)
    }

    /** Manual request (volume button / message speaker) — works with auto mode off. */
    fun speakNow(text: String?): SpeakOutcome {
        if (!engineAvailable) return SpeakOutcome.ENGINE_UNAVAILABLE
        return speakInternal(text)
    }

    /** A new turn starts: previous speech must not bleed into it. */
    fun onNewTurn() = stopSpeaking()

    /** Dialog switched / stopped / new chat: stop speech and disarm the one-shot. */
    fun onSessionSwitch() {
        speakNextAnswer = false
        stopSpeaking()
    }

    fun onPause() {
        foreground = false
        stopSpeaking()
    }

    fun onResume() {
        foreground = true
    }

    /** Host destroyed: stop and release the engine. */
    fun onDestroy() {
        speakNextAnswer = false
        stopSpeaking()
        runCatching { engine.shutdown() }
    }

    /** Immediate silence (e.g. explicit «stop»). Never throws into the host. */
    fun stopSpeaking() {
        runCatching { engine.stop() }
    }

    private fun speakInternal(text: String?): SpeakOutcome {
        val speakable = filter.filterForSpeech(text.orEmpty())
        if (speakable.isBlank()) return SpeakOutcome.NOTHING_TO_SPEAK
        val accepted = runCatching { engine.speak(speakable) }.getOrDefault(false)
        if (!accepted) {
            // Engine is dead/unbound: disable output instead of failing every future turn.
            engineAvailable = false
            return SpeakOutcome.ENGINE_UNAVAILABLE
        }
        return SpeakOutcome.SPOKEN
    }
}
