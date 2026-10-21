package ru.mars.mobile

/**
 * Пороги подтверждения перебивания. Все значения — в миллисекундах речи (не стенных часов) там,
 * где это возможно: «сколько человек реально говорил» устойчивее, чем «сколько прошло времени».
 */
data class BargeInConfig(
    /**
     * Окно после старта озвучки, в котором перебивание не рассматривается вовсе: хвост эха от
     * предыдущей фразы и первая атака TTS попадают именно сюда.
     */
    val echoSettleMs: Long = 250L,

    /** На наушниках акустической обратной связи нет: хватает короткой речи. */
    val headphonesMinVoicedMs: Long = 200L,

    /** Есть partial ASR, которого нет в тексте ответа, — это доказательство речи человека. */
    val transcriptMinVoicedMs: Long = 250L,

    /**
     * Динамик без AEC: partial мог быть распознан из эха (ASR ошибается), поэтому просим больше
     * речи, прежде чем верить тексту.
     */
    val echoRiskNoAecTranscriptMinVoicedMs: Long = 400L,

    /** Каждое следующее перебивание в том же ответе требует больше речи (защита от «дребезга»). */
    val repeatPenaltyMs: Long = 300L,

    /** Предохранитель от бесконечного цикла «перебил — ответил — перебил». */
    val maxBargeInsPerAnswer: Int = 5,

    /** Доля слов partial, найденных в тексте ответа, при которой partial считается эхом. */
    val echoOverlapRatio: Double = 0.6,
)

/** Чем подтвердилось перебивание. */
enum class BargeInEvidence {
    /** Только длительностью речи (возможно лишь на наушниках). */
    DURATION,

    /** Только partial ASR, которого нет в озвучиваемом тексте. */
    TRANSCRIPT,

    /** И длительность, и текст. */
    DURATION_AND_TRANSCRIPT,
}

/** Почему перебивания пока нет — для логов хоста и тестов. */
enum class BargeInReason {
    /** Окно эхо-успокоения после старта озвучки. */
    ECHO_SETTLE,

    /** Речи слишком мало для выбранного порога. */
    TOO_SHORT,

    /** Динамик/неизвестный маршрут, а partial нет или он совпал с текстом ответа. */
    NO_TRANSCRIPT_PROOF,

    /** Предохранитель: в этом ответе уже было [BargeInConfig.maxBargeInsPerAnswer] перебиваний. */
    LIMIT_REACHED,

    /** Речь ещё не началась. */
    NO_SPEECH,
}

/** Решение политики перебивания. */
sealed class BargeInDecision {
    /** Цикл не в SPEAKING (или перебивание выключено): политика не применяется. */
    object Ignored : BargeInDecision()

    /** Перебивание не подтверждено — ждём ещё речи/текста. */
    class Pending(val reason: BargeInReason) : BargeInDecision()

    /** Подтверждено: озвучку можно останавливать немедленно. */
    class Trigger(
        val evidence: BargeInEvidence,
        val voicedMs: Long,
        val route: AudioRoute,
    ) : BargeInDecision()
}

/** Событие подтверждённого перебивания для хоста (upstream interrupt + диагностика). */
data class BargeInEvent(
    val evidence: BargeInEvidence,
    val voicedMs: Long,
    val route: AudioRoute,
    val aecActive: Boolean,
    val partial: String?,
)

/**
 * Политика «человек действительно перебил, а не мы услышали собственный динамик».
 *
 * Зачем отдельный класс: раньше barge-in срабатывал на первый же [VoiceSegmenter.Event.SpeechStarted],
 * то есть на собственное эхо TTS — цикл перебивал сам себя. Здесь собраны все признаки, которых
 * не хватало, и все они проверяются юнит-тестами без устройства.
 *
 * ## Почему длительность — не универсальное доказательство
 * Встроенный динамик даёт непрерывную громкость на всю длину ответа, поэтому «человек говорил
 * 900 мс подряд» на динамике ничего не доказывает: ровно это делает и TTS. Поэтому:
 *
 * - **[AudioRoute.HEADPHONES]** — акустической обратной связи нет, длительности достаточно
 *   ([BargeInConfig.headphonesMinVoicedMs]);
 * - **динамик / неизвестный маршрут** — нужен partial ASR, слов которого НЕТ в озвучиваемом
 *   тексте ([BargeInConfig.transcriptMinVoicedMs]). Длительность работает только как нижняя
 *   граница (щелчок и «э» не перебивают).
 *
 * `AcousticEchoCanceler` облегчает жизнь, но не решает задачу: он ослабляет эхо, а не убирает его,
 * и на части устройств включается «формально» ([AudioCaptureDiagnostics.echoCanceled]). Поэтому
 * AEC лишь снижает требование к длине речи при наличии текста
 * ([BargeInConfig.echoRiskNoAecTranscriptMinVoicedMs]), но не отменяет необходимости текста.
 *
 * ## Защита от самоперебивания
 * - [BargeInConfig.echoSettleMs] после старта озвучки;
 * - partial, слова которого есть в тексте ответа, — эхо, а не речь ([isEchoOfAssistant]);
 * - без известного текста ответа на динамике текстовое доказательство не принимается вовсе:
 *   проверить «это эхо или человек» нечем;
 * - каждое следующее перебивание в том же ответе требует больше речи;
 * - [BargeInConfig.maxBargeInsPerAnswer] ограничивает цикл сверху.
 *
 * Класс чистый (JVM, без Android) и однопоточный: его зовёт [VoiceDialogLoop] с главного потока.
 */
class BargeInGate(
    val config: BargeInConfig = BargeInConfig(),
) {

    /** Идёт ли ответ, во время которого можно перебить. */
    var isActive: Boolean = false
        private set

    /** Маршрут вывода на момент старта ответа. */
    var route: AudioRoute = AudioRoute.UNKNOWN
        private set

    /** Аппаратный AEC реально включён на текущей сессии захвата. */
    var aecActive: Boolean = false
        private set

    /** Сколько перебиваний уже подтверждено в этом ответе. */
    var acceptedCount: Int = 0
        private set

    /** Последний partial, принятый как речь человека (для события хосту). */
    var lastPartial: String? = null
        private set

    /** Есть ли незавершённая попытка речи: цикл использует это, чтобы не выбрасывать реплику. */
    val isCandidate: Boolean
        get() = isActive && speechStartedAtMs != null

    private var playbackStartedAtMs: Long = 0L
    private var speechStartedAtMs: Long? = null
    private var voicedMs: Long = 0L
    private var transcriptEvidence: Boolean = false
    private var assistantText: String? = null

    /** Ответ начал звучать. Сбрасывает всё, что относится к предыдущему ответу. */
    fun onAnswerStarted(
        nowMs: Long,
        route: AudioRoute,
        aecActive: Boolean,
        assistantText: String? = null,
    ) {
        this.route = route
        this.aecActive = aecActive
        this.assistantText = assistantText?.takeIf { it.isNotBlank() }
        playbackStartedAtMs = nowMs
        isActive = true
        acceptedCount = 0
        lastPartial = null
        clearAttempt()
    }

    /**
     * Ответ звучит дальше: эталон эха дополняется текстом ОЧЕРЕДНОЙ реально озвучиваемой фразы.
     *
     * Зачем отдельный метод. [onAnswerStarted] фиксирует текст один раз, на первом звуке. Ответ
     * приходит фразами, и вторая фраза в этом снимке отсутствует — её эхо (ASR слышит динамик)
     * выглядело бы как слова, которых в «ответе» нет, то есть как речь человека. На динамике без
     * AEC это подтверждало перебивание по длительности, и цикл глушил сам себя.
     *
     * Что НЕ делает: не сбрасывает попытку речи, не трогает счётчик перебиваний и не взводит
     * политику заново — иначе каждая новая фраза обнуляла бы накопленную длительность человека,
     * который начал говорить между фразами. Текст, который ещё не звучал, сюда не попадает:
     * вызывающий передаёт только ту фразу, которую очередь отдала в воспроизведение.
     */
    fun onAssistantTextUpdated(spokenText: String?) {
        if (!isActive) return
        val text = spokenText?.takeIf { it.isNotBlank() } ?: return
        // Окно последних произнесённых фраз, а не замена и не весь ответ: ASR-буфер часто
        // захватывает хвост предыдущей фразы, и при замене этот хвост выглядел бы «чужим».
        // Ограничение длины держит эталон рядом с тем, что реально звучит сейчас.
        val window = (assistantText.orEmpty() + " " + text).trim().takeLast(SPOKEN_TEXT_WINDOW)
        assistantText = window.substringAfter(' ', window).trim()
    }

    /**
     * Начало речи по сегментеру (200 мс подряд выше порога).
     *
     * @return true, если попытка принята к рассмотрению (то есть это не окно эхо-успокоения).
     */
    fun onSpeechStarted(nowMs: Long): Boolean {
        if (!isActive) return false
        if (inEchoSettle(nowMs)) return false
        if (speechStartedAtMs == null) {
            speechStartedAtMs = nowMs
            voicedMs = 0L
            transcriptEvidence = false
        }
        return true
    }

    /**
     * Накопленная длительность речи в текущей попытке. Зовётся по ходу речи (тикает сегментер),
     * поэтому перебивание срабатывает не «в конце фразы», а через порог.
     */
    fun onVoiced(voicedMs: Long, nowMs: Long): BargeInDecision {
        if (!isActive) return BargeInDecision.Ignored
        if (inEchoSettle(nowMs)) return BargeInDecision.Pending(BargeInReason.ECHO_SETTLE)
        if (speechStartedAtMs == null) speechStartedAtMs = nowMs
        this.voicedMs = maxOf(this.voicedMs, voicedMs)
        return evaluate(nowMs)
    }

    /**
     * Partial ASR. На динамике это основной признак речи человека: слова, которых нет в ответе,
     * движок не мог услышать из динамика.
     */
    fun onPartial(text: String, nowMs: Long): BargeInDecision {
        if (!isActive) return BargeInDecision.Ignored
        val clean = text.trim()
        if (clean.isEmpty()) return BargeInDecision.Pending(BargeInReason.NO_SPEECH)
        if (inEchoSettle(nowMs)) return BargeInDecision.Pending(BargeInReason.ECHO_SETTLE)
        if (speechStartedAtMs == null) speechStartedAtMs = nowMs
        if (isEchoOfAssistant(clean)) {
            // Слова из озвучки: это эхо (или человек повторяет за Марсом — тогда сработает
            // длительность на наушниках и следующий, уже свой, partial).
            return evaluate(nowMs)
        }
        if (!canUseTranscript()) {
            // Текст ответа неизвестен, а маршрут эхо-опасен: проверить «эхо или человек» нечем.
            return evaluate(nowMs)
        }
        transcriptEvidence = true
        lastPartial = clean
        return evaluate(nowMs)
    }

    /**
     * Готовый сегмент whisper-пути: текста ещё нет, есть только длительность. На наушниках этого
     * достаточно, на динамике — нет (см. KDoc класса).
     */
    fun onSegment(voicedMs: Long, nowMs: Long): BargeInDecision {
        if (!isActive) return BargeInDecision.Ignored
        if (inEchoSettle(nowMs)) return BargeInDecision.Pending(BargeInReason.ECHO_SETTLE)
        if (speechStartedAtMs == null) speechStartedAtMs = nowMs
        this.voicedMs = maxOf(this.voicedMs, voicedMs)
        return evaluate(nowMs)
    }

    /** Речь закончилась, не дойдя до порога: попытка сбрасывается, счётчик перебиваний — нет. */
    fun onSpeechEnded() {
        clearAttempt()
    }

    /** Перебивание подтверждено и обработано: следующая попытка начинается с чистого листа. */
    fun onAccepted() {
        acceptedCount++
        clearAttempt()
    }

    /** Ответ закончился: политика выключается до следующего [onAnswerStarted]. */
    fun onAnswerFinished() {
        isActive = false
        clearAttempt()
    }

    /** Полный сброс (выход из голосового режима). */
    fun reset() {
        isActive = false
        route = AudioRoute.UNKNOWN
        aecActive = false
        acceptedCount = 0
        lastPartial = null
        assistantText = null
        playbackStartedAtMs = 0L
        clearAttempt()
    }

    /**
     * Похож ли текст на эхо озвучки: доля слов [text], найденных в тексте ответа, выше
     * [BargeInConfig.echoOverlapRatio].
     *
     * Это эвристика, а не AEC: она ловит «движок распознал наши же слова», но не спасает от
     * галлюцинаций ASR на громкой музыке. Поэтому на динамике одного текста мало — нужна ещё
     * длительность.
     */
    fun isEchoOfAssistant(text: String): Boolean {
        val spoken = assistantText ?: return false
        val partialTokens = tokens(text)
        if (partialTokens.isEmpty()) return false
        val spokenTokens = tokens(spoken).toSet()
        if (spokenTokens.isEmpty()) return false
        val common = partialTokens.count { it in spokenTokens }
        return common.toDouble() / partialTokens.size >= config.echoOverlapRatio
    }

    // --- внутреннее ---------------------------------------------------------------------------

    private fun evaluate(nowMs: Long): BargeInDecision {
        if (!isActive) return BargeInDecision.Ignored
        if (inEchoSettle(nowMs)) return BargeInDecision.Pending(BargeInReason.ECHO_SETTLE)
        val startedAt = speechStartedAtMs
            ?: return BargeInDecision.Pending(BargeInReason.NO_SPEECH)
        if (acceptedCount >= config.maxBargeInsPerAnswer) {
            return BargeInDecision.Pending(BargeInReason.LIMIT_REACHED)
        }
        // Речь могла начаться до ответа (человек заговорил в конце прошлой фразы): если хост
        // сообщает накопленную длительность, верим ТОЛЬКО ей — иначе паузы между всплесками эха
        // копились бы как «человек говорит», и цикл перебивал бы сам себя. Стенное время —
        // резерв для путей, где длительность не отчитывается вовсе (whisper-сегмент).
        val voiced = if (voicedMs > 0L) voicedMs else nowMs - startedAt
        val penalty = acceptedCount * config.repeatPenaltyMs

        val durationFloor = if (route == AudioRoute.HEADPHONES) config.headphonesMinVoicedMs else null
        val durationOk = durationFloor != null && voiced >= durationFloor + penalty

        val transcriptFloor = when {
            !transcriptEvidence -> null
            route == AudioRoute.HEADPHONES || aecActive -> config.transcriptMinVoicedMs
            else -> config.echoRiskNoAecTranscriptMinVoicedMs
        }
        val transcriptOk = transcriptFloor != null && voiced >= transcriptFloor + penalty

        if (durationOk || transcriptOk) {
            val evidence = when {
                durationOk && transcriptOk -> BargeInEvidence.DURATION_AND_TRANSCRIPT
                transcriptOk -> BargeInEvidence.TRANSCRIPT
                else -> BargeInEvidence.DURATION
            }
            return BargeInDecision.Trigger(evidence, voiced, route)
        }
        val reason = when {
            route.isEchoRisk && !transcriptEvidence -> BargeInReason.NO_TRANSCRIPT_PROOF
            else -> BargeInReason.TOO_SHORT
        }
        return BargeInDecision.Pending(reason)
    }

    private fun inEchoSettle(nowMs: Long): Boolean =
        nowMs - playbackStartedAtMs < config.echoSettleMs

    /** На динамике без текста ответа проверить «эхо или человек» нечем — доказательство не годится. */
    private fun canUseTranscript(): Boolean = !route.isEchoRisk || assistantText != null

    private fun clearAttempt() {
        speechStartedAtMs = null
        voicedMs = 0L
        transcriptEvidence = false
    }

    private fun tokens(text: String): List<String> =
        text.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.length >= MIN_TOKEN_LENGTH }

    private companion object {
        /** Слова короче двух символов не считаем: «и», «в» совпадут в любом тексте. */
        const val MIN_TOKEN_LENGTH = 2

        /** Сколько последних произнесённых символов держим эталоном эха (~2–3 фразы). */
        const val SPOKEN_TEXT_WINDOW = 320
    }
}
