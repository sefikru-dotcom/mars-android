package ru.mars.mobile

import ru.mars.mobile.VoiceOutputController.SpeechEngine
import ru.mars.network.repository.ServerTtsVoices

/**
 * Озвучка с приоритетом того голоса, который человек ВЫБРАЛ, и офлайн-моделью (Piper irina)
 * как резервом.
 *
 * Владелец указал на «роботизированный до ужаса» голос — это был Google TTS, потому
 * что автоозвучка в голосовом режиме шла только через него, хотя модель Piper уже
 * лежала на диске и работала в ручном «Озвучить». После этого офлайн-модель стала
 * безусловно первой — и появилась обратная проблема: выбрав в шторке сетевой голос
 * («Светлана» или «Дмитрий»), человек продолжал слышать Ирину, потому что обёртка
 * отдавала реплику офлайну, не глядя на выбор.
 *
 * Правила выбора движка (по порядку):
 * 1. Выбран локальный профиль ([ServerTtsVoices.LOCAL_IRINA]) — говорим офлайном. Это
 *    единственный случай, когда Piper имеет право звучать первым: человек выбрал его
 *    явно, и сеть тут ни при чём.
 * 2. Выбран сетевой профиль («как в вебе» — `voiceId == null`, либо Светлана, либо
 *    Дмитрий) и запасной движок умеет серверный синтез ([ServerTtsSpeechEngine]) —
 *    реплика уходит ему. Он сам внутри перебирает профили и деградирует на системный
 *    TTS, поэтому «серверный голос» не превращается в тишину без сети.
 * 3. Иначе (голос неизвестен, запасной движок не серверный) — прежний порядок: офлайн,
 *    затем запасной.
 * 4. Если офлайн отказал (модель не скачана, движок не поднялся) — реплика всё равно
 *    уходит запасному движку: молчание хуже робота.
 * 5. Если отказали оба — возвращаем `false`: хост покажет «озвучка недоступна», а не
 *    сделает вид, что говорит.
 *
 * `stop`/`shutdown` идут в оба движка: остановить надо всё, что может звучать, — при
 * переключении между движками старая реплика не должна остаться в фоне.
 */
class PreferOfflineEngine(
    private val offline: SpeechEngine,
    private val fallback: SpeechEngine,
) : SpeechEngine {

    /**
     * Последний переданный темп и голос.
     *
     * Хранится, а не выбрасывается, потому что [speak] обязан знать ВЫБРАННЫЙ голос: без
     * него правило «сетевой голос — сначала на сервер» не к чему привязать.
     *
     * `@Volatile`: ставится с главного потока из шторки, читается с потока озвучки.
     */
    @Volatile
    private var prosody: VoiceProsody = VoiceProsody()

    /** Умеет ли запасной движок серверный синтез (единственный «живой сетевой канал»). */
    private val serverChannel: Boolean get() = fallback is ServerTtsSpeechEngine

    override fun speak(text: String): Boolean {
        val body = text.trim()
        if (body.isEmpty()) return false
        if (prefersServerVoice()) {
            // Сетевой голос выбран явно: он и должен звучать. Внутри движка уже есть
            // перебор профилей и деградация на системный TTS, поэтому `false` здесь
            // означает «звука не будет вовсе», а не «сервер не ответил».
            if (runCatching { fallback.speak(body) }.getOrDefault(false)) return true
            return runCatching { offline.speak(body) }.getOrDefault(false)
        }
        // Локальный голос выбран (или сетевого канала нет): офлайн первым.
        if (runCatching { offline.speak(body) }.getOrDefault(false)) return true
        // Модель не готова — молчание хуже робота, поэтому говорим запасным движком.
        return runCatching { fallback.speak(body) }.getOrDefault(false)
    }

    /**
     * Нужно ли отдать реплику серверному каналу.
     *
     * `voiceId == null` — это «как в вебе», то есть сетевой голос по умолчанию, а НЕ
     * «голоса нет»: человек ничего не выбирал, и подменять голос веба локальной моделью
     * значило бы решать за него.
     *
     * Неизвестный `voiceId` (голос убрали из сборки, настройка приехала из старой версии)
     * сюда не попадает: обещать сетевой канал для голоса, которого нет в каталоге, значит
     * отправить запрос, заведомо обречённый на отказ. Для такого выбора остаётся прежний
     * порядок — офлайн первым.
     */
    private fun prefersServerVoice(): Boolean {
        if (!serverChannel) return false
        val voiceId = prosody.voiceId
        if (voiceId == ServerTtsVoices.LOCAL_IRINA.id) return false
        if (voiceId == null) return true
        val profile = ServerTtsVoices.byId(voiceId) ?: return false
        return !profile.isLocal
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
        this.prosody = prosody
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
