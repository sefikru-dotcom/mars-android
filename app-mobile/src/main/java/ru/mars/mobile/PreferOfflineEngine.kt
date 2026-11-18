package ru.mars.mobile

import ru.mars.mobile.VoiceOutputController.SpeechEngine
import ru.mars.network.repository.ServerTtsVoices

/**
 * Каким каналом реально прозвучала реплика.
 *
 * Нужен не для отрисовки, а для ЧЕСТНОСТИ: человек выбрал голос в шторке, и он вправе знать,
 * что услышал не его. Прежняя цепочка молча уходила в платформенный `TextToSpeech`, и
 * единственным следом был «роботизированный» тембр на приёмке.
 */
enum class VoiceChannel {
    /** Облачный (сетевой) синтез — голос, который человек выбрал. */
    CLOUD,

    /** Локальная модель Piper — единственный разрешённый резерв. */
    OFFLINE,

    /** Платформенный TTS прошивки: чужой голос, о котором хост обязан узнать. */
    SYSTEM,
}

/** Почему прозвучал не тот голос, что выбран. */
enum class VoiceSubstitutionReason {
    /** Выбран сетевой голос, но облако не дало звука. */
    CLOUD_FAILED,

    /** Выбран локальный голос, но модель Piper не поднялась. */
    OFFLINE_FAILED,

    /** Говорит системный TTS: ни облака, ни локальной модели нет. */
    SYSTEM_RESERVE,

    /**
     * Выбранного голоса нет в каталоге этой сборки.
     *
     * Так бывает после обновления: настройка приехала из старой версии, а профиль из
     * [ServerTtsVoices.VOICE_DIALOG] убрали или переименовали. Раньше такой выбор молча
     * приводил к локальной модели: шторка показывала выбранный голос, звучала Ирина, и
     * человек не понимал, почему его выбор не работает. Отдельная причина нужна, чтобы этот
     * случай не путали с сетевым сбоем: чинится он ПОВТОРНЫМ выбором голоса, а не сетью.
     */
    VOICE_UNKNOWN,
}

/**
 * Факт подмены голоса: реплику произнёс канал [spoken], а не выбранный человеком.
 *
 * Это событие, а не ошибка: резерв — разрешённое поведение. Запрещено только молчание об
 * этом, поэтому каждый такой случай уходит хосту одним колбэком
 * ([PreferOfflineEngine.onVoiceSubstituted], [ServerTtsSpeechEngine.onVoiceSubstituted]).
 */
data class VoiceSubstitution(
    val spoken: VoiceChannel,
    val reason: VoiceSubstitutionReason,
)

/**
 * Политика озвучки: облако основное, локальная модель Piper — единственный резерв, системный
 * TTS — только видимый последний резерв.
 *
 * Владелец указал на «роботизированный до ужаса» голос — это был Google TTS, потому что
 * автоозвучка в голосовом режиме шла только через него, хотя модель Piper уже лежала на диске
 * и работала в ручном «Озвучить». После этого офлайн-модель стала безусловно первой — и
 * появилась обратная проблема: выбрав в шторке сетевой голос («Светлана» или «Дмитрий»),
 * человек продолжал слышать Ирину.
 *
 * Правила выбора движка (по порядку):
 * 1. Выбран локальный профиль ([ServerTtsVoices.LOCAL_IRINA]) — говорим Piper. Это единственный
 *    случай, когда офлайн-модель имеет право звучать первой: человек выбрал её явно.
 * 2. Выбран сетевой профиль («как в вебе» — `voiceId == null`, Светлана, Дмитрий, Ава, Оникс) и
 *    запасной движок умеет серверный синтез ([ServerTtsSpeechEngine]) — реплика уходит ему.
 * 3. Если облако отказало — реплика идёт ЛОКАЛЬНОЙ МОДЕЛИ, а не системному TTS. Раньше здесь
 *    стоял платформенный `TextToSpeech`, и «нейро»-голос подменялся роботом Android при любом
 *    `429`/`401`/таймауте, хотя живая модель лежала на устройстве (подтверждённый дефект
 *    «сетевой fallback уходит в системный вместо единственной офлайн-модели»).
 * 4. Системный TTS звучит только тогда, когда сетевого канала нет ВООБЩЕ и локальная модель
 *    отказала, и этот случай ОБЪЯВЛЯЕТСЯ через [onVoiceSubstituted] — молчаливой подмены
 *    больше нет.
 * 5. Если не может ни один канал — возвращаем `false`: хост покажет «озвучка недоступна», а не
 *    сделает вид, что говорит.
 *
 * `stop`/`shutdown` идут в оба движка: остановить надо всё, что может звучать, — при
 * переключении между движками старая реплика не должна остаться в фоне.
 *
 * @param offline локальная модель (Piper) — единственный резерв;
 * @param fallback сетевой канал ([ServerTtsSpeechEngine]) или, в старых сборках без сети,
 *   платформенный движок;
 * @param onVoiceSubstituted реплику произнёс не выбранный голос — хост обязан сказать об этом
 *   человеку (строка статуса, уведомление), иначе подмена снова станет незаметной.
 */
class PreferOfflineEngine(
    private val offline: SpeechEngine,
    private val fallback: SpeechEngine,
    private val onVoiceSubstituted: (VoiceSubstitution) -> Unit = {},
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
        return if (prefersServerVoice()) speakCloudFirst(body) else speakLocalFirst(body)
    }

    /**
     * Выбран сетевой голос: облако основное, Piper — резерв при отказе.
     *
     * `false` наружу означает «звука не будет вовсе», а не «сервер не ответил»: внутренняя
     * деградация облачного движка (перебор профилей, `429` с повтором и уход на Piper)
     * остаётся его заботой, и сюда доходит только полный отказ.
     */
    private fun speakCloudFirst(body: String): Boolean {
        if (trySpeak(fallback, body)) return true
        // Облако не дало звука — говорим ЛОКАЛЬНОЙ моделью. Сеть тут уже ни при чём, и
        // системный робот был бы подменой выбранного голоса, а не резервом.
        if (trySpeak(offline, body)) {
            announce(VoiceChannel.OFFLINE, VoiceSubstitutionReason.CLOUD_FAILED)
            return true
        }
        return false
    }

    /**
     * Выбран локальный голос (или сетевого канала нет): сначала Piper.
     *
     * Порядок после отказа модели разный и это важно: при живом сетевом канале говорим им
     * (другой нейро-голос лучше робота), а при его отсутствии — платформой, объявив подмену.
     */
    private fun speakLocalFirst(body: String): Boolean {
        if (trySpeak(offline, body)) {
            announceUnknownChoice()
            return true
        }
        if (serverChannel) {
            if (trySpeak(fallback, body)) {
                announce(VoiceChannel.CLOUD, VoiceSubstitutionReason.OFFLINE_FAILED)
                return true
            }
            return false
        }
        // Сетевого канала нет: единственный оставшийся голос — платформенный. Он звучит, но
        // НЕ молча: иначе человек, выбравший Ирину, услышал бы робота без объяснения.
        if (trySpeak(fallback, body)) {
            announce(VoiceChannel.SYSTEM, VoiceSubstitutionReason.SYSTEM_RESERVE)
            return true
        }
        return false
    }

    /**
     * Объявить, что выбранного голоса нет в каталоге сборки.
     *
     * [prefersServerVoice] отправляет такой выбор на локальную модель (обещать сетевой канал
     * для несуществующего профиля значило бы гарантированный отказ), и раньше это
     * происходило БЕЗ единого сигнала: человек видел в шторке «Катя», слышал Ирину и считал,
     * что выбор не работает. Проверка дешёвая и идемпотентная, поэтому выполняется на каждой
     * локальной реплике, а не один раз при смене настройки: настройка могла приехать из
     * хранилища до того, как шторка вообще открывалась.
     */
    private fun announceUnknownChoice() {
        val voiceId = prosody.voiceId ?: return
        if (ServerTtsVoices.byId(voiceId) != null) return
        announce(VoiceChannel.OFFLINE, VoiceSubstitutionReason.VOICE_UNKNOWN)
    }

    /**
     * Один вызов движка, который не может уронить озвучку.
     *
     * `runCatching` обязателен: движок — чужой код (нативный синтез, плеер, платформа), и его
     * исключение не должно отменять резерв, который ещё может дать звук.
     */
    private fun trySpeak(engine: SpeechEngine, body: String): Boolean =
        runCatching { engine.speak(body) }.getOrDefault(false)

    private fun announce(channel: VoiceChannel, reason: VoiceSubstitutionReason) {
        runCatching { onVoiceSubstituted(VoiceSubstitution(channel, reason)) }
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

    /**
     * Предсинтез следующего куска — в ТОТ движок, который будет говорить.
     *
     * Правило то же, что у [speak]: выбран сетевой голос — предсинтез делает серверный канал
     * (сетевой запрос стоит секунды, и греть его заранее важнее всего). Локальный голос —
     * предсинтез не нужен: Piper синтезирует на месте, а кэш на диске у него свой.
     *
     * `runCatching` обязателен: предсинтез — оптимизация, его падение не должно ломать озвучку
     * следующего куска (движок всё равно синтезирует текст обычным путём).
     */
    override fun prefetch(text: String) {
        if (!prefersServerVoice()) return
        val body = text.trim()
        if (body.isEmpty()) return
        runCatching { fallback.prefetch(body) }
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
