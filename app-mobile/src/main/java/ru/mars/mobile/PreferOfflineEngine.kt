package ru.mars.mobile

import ru.mars.mobile.VoiceOutputController.SpeechEngine

/**
 * Озвучка с приоритетом офлайн-модели (Piper irina) над системным TTS.
 *
 * Владелец указал на «роботизированный до ужаса» голос — это был Google TTS, потому
 * что автоозвучка в голосовом режиме шла только через него, хотя модель Piper уже
 * лежала на диске и работала в ручном «Озвучить».
 *
 * Правила выбора движка:
 * 1. Офлайн-модель приняла реплику — говорим ею. Это основной путь.
 * 2. Офлайн недоступен (модель не скачана, движок отказал) — отдаём реплику запасному
 *    движку, чтобы человек не остался в тишине.
 * 3. Если и запасной отказал — возвращаем `false`: хост покажет «озвучка недоступна»,
 *    а не сделает вид, что говорит.
 *
 * `stop`/`shutdown` идут в оба движка: остановить надо всё, что может звучать, — при
 * переключении между движками старая реплика не должна остаться в фоне.
 */
class PreferOfflineEngine(
    private val offline: SpeechEngine,
    private val fallback: SpeechEngine,
) : SpeechEngine {

    override fun speak(text: String): Boolean {
        val body = text.trim()
        if (body.isEmpty()) return false
        // Офлайн первым: он и есть тот голос, который человек хочет слышать.
        if (runCatching { offline.speak(body) }.getOrDefault(false)) return true
        // Модель не готова — молчание хуже робота, поэтому говорим запасным движком.
        return runCatching { fallback.speak(body) }.getOrDefault(false)
    }

    override fun stop() {
        runCatching { offline.stop() }
        runCatching { fallback.stop() }
    }

    override fun shutdown() {
        runCatching { offline.shutdown() }
        runCatching { fallback.shutdown() }
    }
}
