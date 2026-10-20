package ru.mars.mobile

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Android adapter for [VoiceOutputController.SpeechEngine]: owns the platform
 * [TextToSpeech] instance and isolates every Android dependency of voice output here.
 *
 * - init is asynchronous and fully guarded: a device without any TTS engine (constructor
 *   throws / ERROR status / missing language data) reports `onInitFinished(false)` instead
 *   of crashing, and later [speak] calls return false so the controller disables output;
 * - a speak requested BEFORE init completes is buffered (single slot, QUEUE_FLUSH
 *   semantics — only the freshest text survives) and flushed once the engine is ready;
 * - language: the device default, falling back to Russian when unsupported (Mars answers
 *   in Russian); a failed fallback still speaks with whatever the engine provides;
 * - all calls happen on the main thread (Activity lifecycle / lifecycleScope), matching
 *   TextToSpeech's own threading contract — no locking, no UI blocking: synthesis itself
 *   is asynchronous inside the platform engine.
 */
class AndroidSpeechEngine(
    context: Context,
    private val onInitFinished: (Boolean) -> Unit,
    /** Озвучка закончилась (дочитала или упала): хост переводит hands-free-цикл в слушание. */
    private val onSpeechFinished: () -> Unit = {},
) : VoiceOutputController.SpeechEngine {

    private var ready = false
    private var dead = false
    private var pendingText: String? = null

    /** Считаем «озвучка идёт», чтобы не дёргать хост дважды (onDone + onError, стопы). */
    private var speaking = false

    private val initListener = TextToSpeech.OnInitListener { status ->
        if (status != TextToSpeech.SUCCESS) {
            dead = true
            pendingText = null
            onInitFinished(false)
            return@OnInitListener
        }
        tts?.let { engine ->
            val result = engine.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.forLanguageTag("ru-RU"))
            }
            // Речь ассистента, а не «медиа»: на этом маршруте платформа связывает вывод с
            // микрофонным AEC, и озвучка не попадает в захват как голос человека. Часть
            // платформенных движков атрибуты игнорирует — отказ не фатален, озвучка важнее.
            runCatching {
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
            }
            // Единственный способ узнать, что синтез реально дочитал: без этого слушателя
            // hands-free-цикл не смог бы отличить свой голос от ответа пользователя.
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = finishSpeech()

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finishSpeech()

                override fun onError(utteranceId: String?, errorCode: Int) = finishSpeech()
            })
        }
        ready = true
        pendingText?.let { text ->
            pendingText = null
            speaking = true
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }
        onInitFinished(true)
    }

    private var tts: TextToSpeech? = runCatching {
        TextToSpeech(context.applicationContext, initListener)
    }.getOrElse {
        // No TTS service on the device (or the binder died on construction).
        dead = true
        onInitFinished(false)
        null
    }

    override fun speak(text: String): Boolean {
        if (dead) return false
        val engine = tts ?: return false
        if (!ready) {
            // Init still in flight: remember the freshest request, drop older ones.
            pendingText = text
            return true
        }
        val accepted = runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID) == TextToSpeech.SUCCESS
        }.getOrDefault(false)
        if (!accepted) dead = true else speaking = true
        return accepted
    }

    override fun stop() {
        pendingText = null
        runCatching { tts?.stop() }
        // Платформа не всегда присылает onStop после stop(): «звук кончился» объявляем сами.
        finishSpeech()
    }

    override fun shutdown() {
        pendingText = null
        ready = false
        dead = true
        speaking = false
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }

    private fun finishSpeech() {
        if (!speaking) return
        speaking = false
        runCatching { onSpeechFinished() }
    }

    companion object {
        private const val UTTERANCE_ID = "mars-voice-output"
    }
}
