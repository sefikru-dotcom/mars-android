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

        /**
         * Применить темп и высоту к последующим репликам.
         *
         * Метод с телом по умолчанию НАРОЧНО: движков три ([AndroidSpeechEngine],
         * [ServerTtsSpeechEngine], [OfflineTtsSpeechEngine]) и плюс фейки в тестах; без
         * дефолта добавление метода сломало бы все реализации разом, включая те, где
         * темпа нет вовсе (серверный синтез принимает его в запросе, а не вызовом).
         *
         * Реализация вправе проигнорировать вызов: отказ от темпа — не ошибка, а
         * особенность движка. Молчание здесь честнее исключения: голос важнее темпа.
         *
         * Вызывать можно в любой момент, в том числе во время речи: движки применяют
         * значение к СЛЕДУЮЩЕЙ реплике (у `TextToSpeech` параметры глобальные, у
         * серверного синтеза и piper — на запрос), поэтому обрыв текущей фразы ради
         * нового темпа был бы нежелателен.
         */
        fun setProsody(prosody: VoiceProsody) = Unit

        /**
         * Предсинтез (lookahead): подготовить [text] к озвучке ЗАРАНЕЕ, пока звучит
         * предыдущий кусок.
         *
         * Зачем: движок синтезирует одну реплику за раз, и раньше кусок N+1 уходил в синтез
         * только после того, как N отзвучал целиком (`onEngineSilent`) — то есть с нуля, в
         * полной тишине. Для сетевого синтеза это 5–10 секунд паузы ровно после первой
         * запятой (см. `SpeechChunker`), для Piper — сотни миллисекунд прогрева.
         *
         * Метод с телом по умолчанию НАРОЧНО, по той же причине, что и [setProsody]: движков
         * четыре и плюс фейки в тестах, а предсинтез поддерживают не все. Молчаливый `Unit`
         * честнее исключения: отсутствие предсинтеза — не ошибка, а потеря скорости.
         *
         * Реализация обязана быть безопасной для повторных вызовов и не обязана ничего
         * гарантировать: если к моменту [speak] текста в кэше нет, движок синтезирует его
         * обычным путём.
         */
        fun prefetch(text: String) = Unit
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

    /**
     * Контроллер освобождён ([onDestroy]): движок уже отключён, и любое позднее обращение к
     * нему — обращение к мёртвому объекту.
     *
     * Зачем флаг, а не расчёт на аккуратность хоста: колбэки UI (нажатие «Озвучить»,
     * завершение хода, ответ сети) приходят и после уничтожения экрана — особенно в голосовом
     * режиме, где ход живёт в `lifecycleScope`. Без флага такой вызов дёргал бы `stop()`/
     * `speak()` у освобождённого `TextToSpeech`/Piper, а `stopSpeaking()` ещё и выстреливал бы
     * в хост колбэком «звук кончился», по которому тот снова открывает микрофон.
     */
    private var destroyed: Boolean = false

    /** One-shot flag: the NEXT completed answer is spoken even with [autoSpeakEnabled] off. */
    private var speakNextAnswer = false

    /**
     * Ответ этого хода будет озвучен: включён авторежим или взведён одноразовый
     * [armSpeakNextAnswer]. Хост спрашивает это в НАЧАЛЕ хода, чтобы решить, отдавать ли
     * дельты стрима потоковой озвучке ([StreamingSpeechController]) — ждать терминального
     * Done значило бы потерять всю экономию задержки.
     */
    val wantsSpeech: Boolean
        get() = engineAvailable && foreground && (autoSpeakEnabled || speakNextAnswer)

    /**
     * Ход озвучен потоковым путём: одноразовое взведение израсходовано, иначе следующий
     * обычный ход зазвучал бы без просьбы пользователя.
     */
    fun consumeSpeakNextAnswer() {
        speakNextAnswer = false
    }

    /**
     * Хост узнаёт, что озвучка реально закончилась (или оборвалась). Нужен только
     * hands-free-циклу: пока TTS говорит, микрофон не должен приниматься за ответ пользователя,
     * а как только звук стих — цикл снова слушает. Движок сообщает об этом сам (Android TTS —
     * через `UtteranceProgressListener`, серверный — по концу воспроизведения); если движок
     * молчит, цикл остаётся в SPEAKING и в слушание не вернётся — это видимое, а не тихое
     * поведение (см. предупреждение в [VoiceDialogLoop]).
     */
    var onSpeakingFinished: (() -> Unit)? = null

    /** Озвучка сейчас звучит: ставится при принятом [speakInternal], снимается любым стопом. */
    var speaking: Boolean = false
        private set

    /** Хост сообщил, что звук идёт из ДРУГОГО пути (потоковая озвучка фраз). */
    fun setExternalSpeaking(value: Boolean) {
        speaking = value
    }

    /** Platform engine init result (async). `false` disables all output gracefully. */
    /**
     * Применить темп и высоту к движку: единственный вход для хоста.
     *
     * Зачем прокси, а не прямой вызов движка: у хоста на руках только контроллер, а
     * движков внутри цепочки три (Piper → сервер → системный). Если бы экран выбирал
     * движок сам, он повторил бы ту же логику выбора и однажды разошёлся бы с ней.
     *
     * Неудача молчит: движок, не поддерживающий темп, не должен ломать озвучку.
     */
    fun setProsody(prosody: VoiceProsody) {
        if (destroyed) return
        runCatching { engine.setProsody(prosody) }
    }

    fun onEngineReady(available: Boolean) {
        if (destroyed) return
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
        if (destroyed) return
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
        if (destroyed) return
        if (!engineAvailable || !foreground) return
        if (!autoSpeakEnabled && !speakNextAnswer) return
        speakNextAnswer = false
        speakInternal(finalAnswer)
    }

    /** Manual request (volume button / message speaker) — works with auto mode off. */
    fun speakNow(text: String?): SpeakOutcome {
        if (destroyed) return SpeakOutcome.ENGINE_UNAVAILABLE
        if (!engineAvailable) return SpeakOutcome.ENGINE_UNAVAILABLE
        return speakInternal(text)
    }

    /**
     * Предсинтез следующего куска, пока звучит текущий.
     *
     * Вызывающий ([LocalVoiceTts.prefetchNext]) уже держит очищенный текст, но фильтр здесь
     * всё равно применяется: движку нельзя отдавать markdown/код ни на синтез, ни на
     * предсинтез, иначе в кэш попадёт не то, что потом попросят произнести.
     *
     * Правила те же, что у озвучки: без движка и в фоне ([foreground]) предсинтез не нужен —
     * звука всё равно не будет. Ошибки глотаются: предсинтез — оптимизация, а не гарантия.
     */
    fun prefetch(text: String?) {
        if (destroyed) return
        if (!engineAvailable || !foreground) return
        val speakable = filter.filterForSpeech(text.orEmpty())
        if (speakable.isBlank()) return
        runCatching { engine.prefetch(speakable) }
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
        // Флаг — ПОСЛЕ стопа и освобождения: остановка обязана доехать до живого движка.
        destroyed = true
    }

    /** Immediate silence (e.g. explicit «stop»). Never throws into the host. */
    fun stopSpeaking() {
        if (destroyed) return
        val wasSpeaking = speaking
        speaking = false
        runCatching { engine.stop() }
        // Стоп — тоже «звук закончился»: hands-free-цикл обязан вернуться в слушание, иначе
        // после первого же перебивания он навсегда остался бы в SPEAKING.
        if (wasSpeaking) notifySpeakingFinished()
    }

    /** Движок сообщил, что дочитал (или упал): хост переводит цикл обратно в слушание. */
    fun notifySpeakingFinished() {
        // Поздний колбэк освобождённого движка не должен открывать микрофон на мёртвом экране.
        if (destroyed) return
        speaking = false
        runCatching { onSpeakingFinished?.invoke() }
    }

    private fun speakInternal(text: String?): SpeakOutcome {
        val speakable = filter.filterForSpeech(text.orEmpty())
        if (speakable.isBlank()) return SpeakOutcome.NOTHING_TO_SPEAK
        val accepted = runCatching { engine.speak(speakable) }.getOrDefault(false)
        if (!accepted) {
            // Engine is dead/unbound: disable output instead of failing every future turn.
            engineAvailable = false
            speaking = false
            return SpeakOutcome.ENGINE_UNAVAILABLE
        }
        speaking = true
        return SpeakOutcome.SPOKEN
    }
}
