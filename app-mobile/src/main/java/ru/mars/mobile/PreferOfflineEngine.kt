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

    /**
     * Темп и голос — в ОБА движка, а не только в офлайн.
     *
     * Без этого метода обёртка молча съедала настройку: [SpeechEngine.setProsody] имеет
     * тело по умолчанию, поэтому вызов от хоста доходил до обёртки и останавливался —
     * ни Piper, ни резерв не меняли темп. Симптом на устройстве выглядел бы как
     * «ползунок ни на что не влияет».
     *
     * Порядок и отсутствие проверок: движок, который темп не поддерживает, просто
     * проигнорирует вызов (у каждого свой `runCatching`). Узнавать, кто из двух сейчас
     * в работе, не нужно и вредно — при следующей реплике выбор может смениться, и
     * второй движок должен быть уже настроен.
     */
    override fun setProsody(prosody: VoiceProsody) {
        runCatching { offline.setProsody(prosody) }
        runCatching { fallback.setProsody(prosody) }
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
