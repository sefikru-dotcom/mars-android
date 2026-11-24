package ru.mars.mobile.voice

import ru.mars.mobile.AudioRoute

/**
 * Фаза голосовой сессии в фоне.
 *
 * Три фазы, а не пять: [ARMED] и [DIALOGUE] различаются только тем, кто держит микрофон
 * (wake-word детектор или распознавание реплики), а всё, что важно снаружи — «микрофон открыт
 * или нет» — это `phase != IDLE`.
 */
enum class VoiceSessionPhase {
    /** Микрофон закрыт, сессия не идёт. */
    IDLE,

    /** Микрофон открыт, ищется ключевая фраза. */
    ARMED,

    /** Фраза найдена: микрофон передан распознаванию реплики и озвучке ответа. */
    DIALOGUE,
}

/**
 * Почему микрофон можно держать открытым именно так.
 *
 * [FOREGROUND] — экран активен, человек видит приложение; расход батареи объясним.
 * [BACKGROUND] — экран погашен или устройство заблокировано: микрофон живёт только за счёт
 * foreground-сервиса с типом `microphone`, и пороги в этом режиме строже (см.
 * [VoiceSessionConfig.backgroundMinBatteryPercent]).
 */
enum class VoiceSessionMode {
    FOREGROUND,
    BACKGROUND,
}

/**
 * Причина отказа держать микрофон открытым.
 *
 * @param notice короткий текст для человека (уведомление сервиса или строка статуса).
 *   Живёт здесь, а не в UI: причина и её объяснение — одно решение, разъезжаться им нельзя.
 * @param transient `true` — отказ временный, состояние изменится само (батарея, «не беспокоить»,
 *   экран); `false` — без действия человека не пройдёт (нет разрешения, нет модели).
 */
enum class VoiceSessionRejection(val notice: String, val transient: Boolean) {
    /** Сессия ещё не начиналась: спрашивать «держать ли микрофон» нечего. */
    NOT_STARTED("Голосовая сессия не запущена", true),

    /** Нет разрешения `RECORD_AUDIO`: микрофон не откроется вообще. */
    MIC_PERMISSION_MISSING("Нет разрешения на микрофон", false),

    /** Модель распознавания не скачана: wake word искать нечем. */
    MODEL_MISSING("Модель распознавания не скачана", false),

    /** Идёт телефонный звонок: микрофон принадлежит звонилке. */
    CALL_ACTIVE("Идёт звонок — голосовой режим приостановлен", true),

    /** Микрофон занят другим захватом приложения (голосовой диалог в чате). */
    MIC_BUSY("Микрофон занят другим голосовым режимом", true),

    /** Микрофон не открылся или движок не поднялся (нет ABI, битые файлы, занято системой). */
    MIC_UNAVAILABLE("Микрофон недоступен", true),

    /** Включён режим «не беспокоить»: фоновое прослушивание противоречит ему. */
    DO_NOT_DISTURB("Режим «не беспокоить» — фоновое прослушивание выключено", true),

    /** Экран погашен, а foreground-сервиса с типом `microphone` нет: Android не даст микрофон. */
    NO_FOREGROUND_SERVICE("Экран погашен — фоновый режим недоступен", true),

    /** Фоновый режим выключен настройкой приложения. */
    BACKGROUND_DISABLED("Фоновое прослушивание выключено в настройках", false),

    /** Заряд ниже порога (в фоне порог выше). */
    BATTERY_TOO_LOW("Мало заряда — фоновое прослушивание приостановлено", true),

    /** Включён режим энергосбережения: фоновое прослушивание не начинаем. */
    POWER_SAVE("Режим энергосбережения — фоновое прослушивание приостановлено", true),

    /** После закрытия сессии выдерживается пауза, чтобы не будить устройство сразу. */
    COOLDOWN("Голосовой режим остывает после остановки", true),

    /** В диалоге слишком долго нет речи: сессия реплики закрывается, wake word остаётся. */
    IDLE_TIMEOUT("Не расслышал — жду ключевую фразу", true),

    /** Одна реплика звучит дольше лимита: обрываем её, микрофон остаётся под wake word. */
    UTTERANCE_LIMIT("Слишком длинная реплика — остановил", true),

    /** Исчерпан лимит длительности сессии: микрофон закрывается на паузу. */
    SESSION_LIMIT_REACHED("Лимит длительности сессии — пауза", true),
}

/**
 * Снимок окружения, от которого зависит решение «держать ли микрофон открытым».
 *
 * Только значения: ни `Context`, ни системных сервисов. Снимок собирает Android-сторона
 * ([AndroidVoiceSessionEnvironment] в `VoiceSessionController.kt`), а решение принимает
 * [VoiceSessionPolicy] — поэтому политика проверяется на JVM без телефона.
 *
 * @param batteryPercent заряд 0..100 (`-1` — неизвестен, считается достаточным);
 * @param charging устройство заряжается (или подключено к питанию);
 * @param screenOn экран включён (`PowerManager.isInteractive`);
 * @param keyguardLocked устройство заблокировано (`KeyguardManager.isKeyguardLocked`);
 * @param audioRoute маршрут вывода: на динамике микрофон слышит собственную озвучку;
 * @param doNotDisturb включён режим «не беспокоить»;
 * @param powerSaveMode включён режим энергосбережения;
 * @param micPermissionGranted выдано ли `RECORD_AUDIO` прямо сейчас;
 * @param modelInstalled лежит ли модель распознавания на диске;
 * @param callActive идёт звонок (микрофон занят звонилкой);
 * @param otherCaptureActive микрофон занят ДРУГИМ захватом приложения — не нашей сессией
 *   (голосовой диалог в чате). Свою сессию сюда передавать нельзя: политика закрыла бы её сама;
 * @param foregroundServiceRunning работает ли foreground-сервис с типом `microphone`.
 */
data class VoiceSessionEnvironment(
    val batteryPercent: Int = 100,
    val charging: Boolean = false,
    val screenOn: Boolean = true,
    val keyguardLocked: Boolean = false,
    val audioRoute: AudioRoute = AudioRoute.UNKNOWN,
    val doNotDisturb: Boolean = false,
    val powerSaveMode: Boolean = false,
    val micPermissionGranted: Boolean = true,
    val modelInstalled: Boolean = true,
    val callActive: Boolean = false,
    val otherCaptureActive: Boolean = false,
    val foregroundServiceRunning: Boolean = true,
) {
    /** Экран пригоден для работы: включён и не заблокирован. */
    val screenUsable: Boolean get() = screenOn && !keyguardLocked

    /** Сессия была бы фоновой: экран погашен или устройство заблокировано. */
    val background: Boolean get() = !screenUsable

    /** Заряд известен (некоторые прошивки отдают `-1` вместо процентов). */
    val batteryKnown: Boolean get() = batteryPercent in 0..100
}

/**
 * Пороги и таймауты сессии.
 *
 * Значения подобраны под критерий приёмки «30 минут фонового ожидания — не больше нескольких
 * процентов батареи»: в фоне порог заряда выше, лимит сессии ограничен, а между сессиями есть
 * пауза. Все они — параметры, потому что на реальном устройстве их придётся подстраивать.
 *
 * @param minBatteryPercent ниже этого заряда микрофон не открывается вообще (без питания);
 * @param backgroundMinBatteryPercent ниже этого заряда микрофон не открывается в фоне;
 * @param idleTimeoutMs сколько тишины в диалоге терпим до закрытия реплики;
 * @param maxUtteranceMs лимит длительности одной реплики;
 * @param maxSessionMs лимит непрерывного ожидания ключевой фразы — страховка от забытого
 *   включения, а не рабочий таймаут: два часа, после чего сессия уходит на паузу и хост
 *   обязан вернуть её в прослушивание сам (см. [VoiceSessionDecision.Deny.retryAfterMs]);
 * @param maxDialogueMs лимит одного диалога (вопрос-ответ) целиком;
 * @param cooldownMs пауза после закрытия сессии перед новым открытием;
 * @param lowBatteryCooldownMs пауза после отказа по батарее/энергосбережению: чаще проверять
 *   бессмысленно, а будить устройство дорого;
 * @param allowInBackground разрешён ли фоновый режим (настройка приложения);
 * @param silenceRouteMatters нужен ли учёт маршрута (оставлено для совместимости с host-логикой).
 */
data class VoiceSessionConfig(
    val minBatteryPercent: Int = 15,
    val backgroundMinBatteryPercent: Int = 40,
    val idleTimeoutMs: Long = 8_000L,
    val maxUtteranceMs: Long = 30_000L,
    /**
     * Два часа, а не пять минут.
     *
     * Пять минут — это не «экономия батареи», а выключение режима, который владелец включил
     * сам: в логе с устройства сервис закрывал микрофон через 5 минут после старта
     * (`MarsVoice: phase=IDLE (Лимит длительности сессии — пауза)`) и больше не слушал.
     * Лимит остаётся только страховкой от забытого включения; после него хост обязан
     * вернуться в прослушивание сам, а не остаться в IDLE навсегда.
     */
    val maxSessionMs: Long = 2 * 60 * 60_000L,
    val maxDialogueMs: Long = 3 * 60_000L,
    val cooldownMs: Long = 2_000L,
    val lowBatteryCooldownMs: Long = 60_000L,
    val allowInBackground: Boolean = true,
    val silenceRouteMatters: Boolean = true,
) {
    init {
        require(minBatteryPercent in 0..100) { "minBatteryPercent вне 0..100: $minBatteryPercent" }
        require(backgroundMinBatteryPercent >= minBatteryPercent) {
            "фоновый порог ($backgroundMinBatteryPercent) не может быть ниже общего ($minBatteryPercent)"
        }
        require(idleTimeoutMs > 0 && maxUtteranceMs > 0 && maxSessionMs > 0 && maxDialogueMs > 0) {
            "таймауты обязаны быть положительными"
        }
    }
}

/** Решение политики: микрофон можно держать открытым или обязан быть закрыт. */
sealed class VoiceSessionDecision {

    /** Микрофон можно держать открытым; [mode] — в каком режиме это происходит. */
    data class Allow(val mode: VoiceSessionMode) : VoiceSessionDecision()

    /**
     * Микрофон держать нельзя (или он обязан быть закрыт прямо сейчас).
     *
     * @param retryAfterMs через сколько имеет смысл спросить снова; `0` — только по событию
     *   (экран, «не беспокоить», разрешение), таймером спрашивать бессмысленно;
     * @param keepsMicOpen `true` — закрывается только реплика/диалог, а микрофон остаётся под
     *   wake word (таймаут простоя, длинная реплика). `false` — закрывать микрофон целиком.
     */
    data class Deny(
        val reason: VoiceSessionRejection,
        val retryAfterMs: Long = 0L,
        val keepsMicOpen: Boolean = false,
    ) : VoiceSessionDecision()
}

/**
 * Чистая политика голосовой сессии: можно ли держать микрофон открытым и когда его закрыть.
 *
 * Зачем отдельный класс. Решение «держать микрофон в фоне» — это батарея, экран, маршрут аудио,
 * «не беспокоить» и лимиты длительности. Ошибка в нём не видна на глаз: телефон просто тихо
 * садится за сутки. Поэтому решение вынесено из Android целиком и проверяется на JVM: файл не
 * импортирует `android.*` (маршрут берётся из чистого [AudioRoute]), время приходит параметром.
 *
 * ## Правила принятия решения
 *
 * [gate] — ворота «микрофон вообще можно открыть», первое совпавшее правило выигрывает:
 *
 * | # | Условие | Решение | Повтор |
 * |---|---|---|---|
 * | 1 | нет `RECORD_AUDIO` | `MIC_PERMISSION_MISSING` | по событию |
 * | 2 | модель не установлена | `MODEL_MISSING` | по событию |
 * | 3 | идёт звонок | `CALL_ACTIVE` | 3 с |
 * | 4 | микрофон занят чужим захватом | `MIC_BUSY` | 1 с |
 * | 5 | «не беспокоить» | `DO_NOT_DISTURB` | по событию |
 * | 6 | экран погашен и нет foreground-сервиса | `NO_FOREGROUND_SERVICE` | по событию |
 * | 7 | экран погашен, фоновый режим выключен | `BACKGROUND_DISABLED` | по событию |
 * | 8 | энергосбережение в фоне | `POWER_SAVE` | 30 с |
 * | 9 | заряд < [VoiceSessionConfig.minBatteryPercent] без питания | `BATTERY_TOO_LOW` | 60 с |
 * | 10 | в фоне заряд < [VoiceSessionConfig.backgroundMinBatteryPercent] без питания | `BATTERY_TOO_LOW` | 60 с |
 * | 11 | пауза после закрытия сессии не вышла | `COOLDOWN` | остаток паузы |
 * | 12 | иначе | `Allow(FOREGROUND/BACKGROUND)` | — |
 *
 * Правила 1–2 стоят первыми намеренно: это не «сейчас нельзя», а «нельзя, пока человек не
 * поправит» — и сообщение должно быть именно о них, а не о батарее.
 *
 * ## Лимиты во время сессии ([tick])
 *
 * | Условие | Решение | Микрофон |
 * |---|---|---|
 * | окружение стало плохим (правила 1–10) | причина из таблицы выше | закрыть |
 * | [VoiceSessionPhase.ARMED] дольше [VoiceSessionConfig.maxSessionMs] | `SESSION_LIMIT_REACHED` | закрыть, пауза 30 с, затем снова слушать |
 * | [VoiceSessionPhase.DIALOGUE] дольше [VoiceSessionConfig.maxDialogueMs] | `SESSION_LIMIT_REACHED` | закрыть, пауза 30 с |
 * | в диалоге тишина дольше [VoiceSessionConfig.idleTimeoutMs] | `IDLE_TIMEOUT` | оставить под wake word |
 * | реплика звучит дольше [VoiceSessionConfig.maxUtteranceMs] | `UTTERANCE_LIMIT` | оставить под wake word |
 *
 * ## Маршрут аудио
 *
 * Маршрут не запрещает сессию: на динамике микрофон слышит собственную озвучку, но этим
 * управляет barge-in ([ru.mars.mobile.BargeInGate]), а не политика микрофона. Здесь маршрут
 * только виден хосту через [VoiceSessionEnvironment.audioRoute] — чтобы он мог выбрать, чем
 * отвечать (наушники → короткая озвучка, динамик → без перебивания).
 *
 * ## Потокобезопасность
 *
 * Класс не синхронизирован: его зовёт [VoiceSessionController] с главного потока. Держать
 * внутри `synchronized` значило бы обещать потокобезопасность, которой нет у вызывающего кода.
 *
 * @param config пороги и таймауты.
 */
class VoiceSessionPolicy(
    val config: VoiceSessionConfig = VoiceSessionConfig(),
) {

    /** Текущая фаза сессии. */
    var phase: VoiceSessionPhase = VoiceSessionPhase.IDLE
        private set

    /** Когда микрофон открыли (монотонные мс); `null` — сессия не идёт. */
    var openedAtMs: Long? = null
        private set

    /** Последняя причина отказа (в том числе та, которой закрылась сессия); `null` — отказов не было. */
    var lastRejection: VoiceSessionRejection? = null
        private set

    /** Когда закрыли сессию; `null` — сессия ещё не закрывалась. */
    private var closedAtMs: Long? = null

    /** Пауза, назначенная закрытием: нужна, чтобы не открывать микрофон сразу. */
    private var cooldownUntilMs: Long = 0L

    private var dialogueStartedAtMs: Long? = null

    /** Начало текущей реплики (первый звук после тишины); `null` — реплика не начата. */
    private var utteranceStartedAtMs: Long? = null

    /** Последняя активность: звук речи или начало диалога. */
    private var lastActivityAtMs: Long = 0L

    /** Режим, в котором сессия открыта сейчас (или была бы открыта) — по последнему снимку. */
    val mode: VoiceSessionMode get() = modeFor(lastEnvironment)

    /** Снимок окружения последнего вызова: нужен для [mode] и диагностики. */
    private var lastEnvironment: VoiceSessionEnvironment = VoiceSessionEnvironment()

    /**
     * Ворота: можно ли открыть микрофон прямо сейчас.
     *
     * Не меняет фазу и не назначает паузу — это чистый вопрос. Годится и для UI («почему кнопка
     * неактивна»), и для [start].
     */
    fun gate(env: VoiceSessionEnvironment, nowMs: Long): VoiceSessionDecision {
        lastEnvironment = env
        return gateLocked(env, nowMs)
    }

    /**
     * Открыть сессию: ворота + переход в [VoiceSessionPhase.ARMED].
     *
     * Повторный вызов при уже открытой сессии возвращает текущее решение без побочных эффектов
     * (идемпотентно): контроллер может звать [start] на каждом resume.
     */
    fun start(env: VoiceSessionEnvironment, nowMs: Long): VoiceSessionDecision {
        lastEnvironment = env
        if (phase != VoiceSessionPhase.IDLE) return VoiceSessionDecision.Allow(modeFor(env))
        val decision = gateLocked(env, nowMs)
        when (decision) {
            is VoiceSessionDecision.Deny -> {
                lastRejection = decision.reason
                return decision
            }
            is VoiceSessionDecision.Allow -> {
                phase = VoiceSessionPhase.ARMED
                openedAtMs = nowMs
                dialogueStartedAtMs = null
                utteranceStartedAtMs = null
                lastActivityAtMs = nowMs
                closedAtMs = null
                cooldownUntilMs = 0L
                lastRejection = null
                return decision
            }
        }
    }

    /**
     * Найдена ключевая фраза: [VoiceSessionPhase.ARMED] → [VoiceSessionPhase.DIALOGUE].
     *
     * @return `false`, если сессия не открыта или исчерпан лимит длительности — в обоих случаях
     *   диалог начинать нельзя (микрофон уже закрыт политикой).
     */
    fun onWakeWord(nowMs: Long): Boolean {
        if (phase != VoiceSessionPhase.ARMED) return false
        val opened = openedAtMs
        if (opened != null && nowMs - opened >= config.maxSessionMs) {
            close(nowMs, VoiceSessionRejection.SESSION_LIMIT_REACHED)
            return false
        }
        phase = VoiceSessionPhase.DIALOGUE
        dialogueStartedAtMs = nowMs
        utteranceStartedAtMs = null
        lastActivityAtMs = nowMs
        return true
    }

    /**
     * В диалоге появился звук речи: сбрасывает таймер простоя и заводит таймер реплики.
     *
     * Вне диалога игнорируется: звук в [VoiceSessionPhase.ARMED] — это и есть поиск фразы, и
     * продлевать им лимиты нельзя.
     */
    fun onSpeech(nowMs: Long) {
        if (phase != VoiceSessionPhase.DIALOGUE) return
        lastActivityAtMs = nowMs
        if (utteranceStartedAtMs == null) utteranceStartedAtMs = nowMs
    }

    /**
     * Хост занят: ответ ещё готовится или уже звучит. Продлевает таймер простоя, но НЕ таймер
     * реплики — иначе озвучка ответа обрывала бы диалог по «тишине», хотя человек ждёт ответа.
     */
    fun onHostActivity(nowMs: Long) {
        if (phase != VoiceSessionPhase.DIALOGUE) return
        lastActivityAtMs = nowMs
    }

    /** Реплика распознана или отменена: реплика закрыта, диалог продолжается. */
    fun onUtteranceEnd(nowMs: Long) {
        if (phase != VoiceSessionPhase.DIALOGUE) return
        utteranceStartedAtMs = null
        lastActivityAtMs = nowMs
    }

    /** Диалог закончился (ответ озвучен или человек отказался): назад к ожиданию фразы. */
    fun onDialogueEnd(nowMs: Long) {
        if (phase != VoiceSessionPhase.DIALOGUE) return
        phase = VoiceSessionPhase.ARMED
        dialogueStartedAtMs = null
        utteranceStartedAtMs = null
        lastActivityAtMs = nowMs
    }

    /**
     * Периодическая проверка открытой сессии: окружение и лимиты длительности.
     *
     * Зовётся по таймеру, пока `phase != IDLE`. При отказе сессия закрывается ВНУТРИ метода
     * (фаза и пауза уже обновлены) — вызывающему остаётся только погасить микрофон.
     */
    fun tick(env: VoiceSessionEnvironment, nowMs: Long): VoiceSessionDecision {
        lastEnvironment = env
        if (phase == VoiceSessionPhase.IDLE) return gateLocked(env, nowMs)

        val gate = gateLocked(env, nowMs, skipCooldown = true)
        if (gate is VoiceSessionDecision.Deny) {
            close(nowMs, gate.reason)
            return gate
        }

        val opened = openedAtMs
        if (phase == VoiceSessionPhase.ARMED && opened != null && nowMs - opened >= config.maxSessionMs) {
            return close(nowMs, VoiceSessionRejection.SESSION_LIMIT_REACHED)
        }

        val dialogue = dialogueStartedAtMs
        if (phase == VoiceSessionPhase.DIALOGUE && dialogue != null && nowMs - dialogue >= config.maxDialogueMs) {
            return close(nowMs, VoiceSessionRejection.SESSION_LIMIT_REACHED)
        }

        val utterance = utteranceStartedAtMs
        if (phase == VoiceSessionPhase.DIALOGUE && utterance != null && nowMs - utterance >= config.maxUtteranceMs) {
            // Микрофон остаётся: обрывается реплика, а не сессия — человек может сказать короче.
            utteranceStartedAtMs = null
            lastActivityAtMs = nowMs
            lastRejection = VoiceSessionRejection.UTTERANCE_LIMIT
            return VoiceSessionDecision.Deny(
                reason = VoiceSessionRejection.UTTERANCE_LIMIT,
                retryAfterMs = 0L,
                keepsMicOpen = true,
            )
        }

        if (phase == VoiceSessionPhase.DIALOGUE && nowMs - lastActivityAtMs >= config.idleTimeoutMs) {
            utteranceStartedAtMs = null
            phase = VoiceSessionPhase.ARMED
            dialogueStartedAtMs = null
            lastActivityAtMs = nowMs
            lastRejection = VoiceSessionRejection.IDLE_TIMEOUT
            return VoiceSessionDecision.Deny(
                reason = VoiceSessionRejection.IDLE_TIMEOUT,
                retryAfterMs = 0L,
                keepsMicOpen = true,
            )
        }

        return VoiceSessionDecision.Allow(modeFor(env))
    }

    /**
     * Закрыть сессию.
     *
     * @param reason причина закрытия; `null` — человек выключил режим сам. После явной остановки
     *   паузы нет: запрещать человеку включить режим обратно нельзя.
     */
    fun stop(nowMs: Long, reason: VoiceSessionRejection? = null) {
        if (phase == VoiceSessionPhase.IDLE) {
            lastRejection = reason ?: lastRejection
            return
        }
        close(nowMs, reason, explicit = true)
    }

    /** Сколько ещё терпеть тишину в диалоге (0 — таймер не идёт). */
    fun idleLeftMs(nowMs: Long): Long {
        if (phase != VoiceSessionPhase.DIALOGUE) return 0L
        return (config.idleTimeoutMs - (nowMs - lastActivityAtMs)).coerceAtLeast(0L)
    }

    /** Сколько ещё можно держать микрофон открытым в текущей фазе (0 — лимит исчерпан). */
    fun sessionLeftMs(nowMs: Long): Long {
        val since = when (phase) {
            VoiceSessionPhase.IDLE -> return 0L
            VoiceSessionPhase.ARMED -> openedAtMs ?: return 0L
            VoiceSessionPhase.DIALOGUE -> dialogueStartedAtMs ?: return 0L
        }
        val limit = if (phase == VoiceSessionPhase.ARMED) config.maxSessionMs else config.maxDialogueMs
        return (limit - (nowMs - since)).coerceAtLeast(0L)
    }

    /** Сколько ещё длится пауза после закрытия сессии (0 — паузы нет). */
    fun cooldownLeftMs(nowMs: Long): Long = (cooldownUntilMs - nowMs).coerceAtLeast(0L)

    /** Время закрытия последней сессии; `null` — сессия ещё не закрывалась. */
    fun lastClosedAtMs(): Long? = closedAtMs

    // --- внутреннее ---------------------------------------------------------------------------

    /**
     * Ворота без записи окружения.
     *
     * @param skipCooldown `true` для [tick]: пауза — просьба не открывать микрофон заново, а не
     *   причина закрыть уже открытую сессию (иначе сессия закрывалась бы собственной паузой).
     */
    private fun gateLocked(
        env: VoiceSessionEnvironment,
        nowMs: Long,
        skipCooldown: Boolean = false,
    ): VoiceSessionDecision {
        val mode = modeFor(env)

        if (!env.micPermissionGranted) return deny(VoiceSessionRejection.MIC_PERMISSION_MISSING)
        if (!env.modelInstalled) return deny(VoiceSessionRejection.MODEL_MISSING)
        if (env.callActive) {
            return VoiceSessionDecision.Deny(VoiceSessionRejection.CALL_ACTIVE, CALL_RETRY_MS)
        }
        if (env.otherCaptureActive) {
            return VoiceSessionDecision.Deny(VoiceSessionRejection.MIC_BUSY, MIC_RETRY_MS)
        }
        if (env.doNotDisturb) return deny(VoiceSessionRejection.DO_NOT_DISTURB)

        if (env.background) {
            if (!env.foregroundServiceRunning) return deny(VoiceSessionRejection.NO_FOREGROUND_SERVICE)
            if (!config.allowInBackground) return deny(VoiceSessionRejection.BACKGROUND_DISABLED)
            if (env.powerSaveMode) {
                return VoiceSessionDecision.Deny(
                    VoiceSessionRejection.POWER_SAVE,
                    config.lowBatteryCooldownMs,
                )
            }
        }

        if (!env.charging && env.batteryKnown) {
            if (env.batteryPercent < config.minBatteryPercent) {
                return VoiceSessionDecision.Deny(
                    VoiceSessionRejection.BATTERY_TOO_LOW,
                    config.lowBatteryCooldownMs,
                )
            }
            // В фоне порог выше: экран не показывает, что микрофон работает, и человек не
            // заметит разряд, пока телефон не выключится.
            if (env.background && env.batteryPercent < config.backgroundMinBatteryPercent) {
                return VoiceSessionDecision.Deny(
                    VoiceSessionRejection.BATTERY_TOO_LOW,
                    config.lowBatteryCooldownMs,
                )
            }
        }

        if (!skipCooldown) {
            val left = cooldownLeftMs(nowMs)
            if (left > 0L) return VoiceSessionDecision.Deny(VoiceSessionRejection.COOLDOWN, left)
        }

        return VoiceSessionDecision.Allow(mode)
    }

    private fun deny(reason: VoiceSessionRejection): VoiceSessionDecision =
        VoiceSessionDecision.Deny(reason, retryAfterMs = 0L)

    /**
     * Закрытие сессии с назначением паузы.
     *
     * Пауза зависит от причины: после разряда и лимита длительности открывать микрофон сразу
     * бессмысленно (телефон только что показал, что ему тяжело), после явной остановки — нельзя.
     */
    private fun close(
        nowMs: Long,
        reason: VoiceSessionRejection?,
        explicit: Boolean = false,
    ): VoiceSessionDecision {
        val retryAfter = if (explicit) 0L else cooldownFor(reason)
        phase = VoiceSessionPhase.IDLE
        openedAtMs = null
        dialogueStartedAtMs = null
        utteranceStartedAtMs = null
        closedAtMs = nowMs
        cooldownUntilMs = nowMs + retryAfter
        lastRejection = reason ?: lastRejection
        val rejection = reason ?: VoiceSessionRejection.NOT_STARTED
        return VoiceSessionDecision.Deny(rejection, retryAfter)
    }

    private fun cooldownFor(reason: VoiceSessionRejection?): Long = when (reason) {
        VoiceSessionRejection.BATTERY_TOO_LOW,
        VoiceSessionRejection.POWER_SAVE,
        -> config.lowBatteryCooldownMs

        VoiceSessionRejection.SESSION_LIMIT_REACHED -> maxOf(config.cooldownMs, SESSION_COOLDOWN_MS)

        VoiceSessionRejection.NOT_STARTED -> 0L

        else -> config.cooldownMs
    }

    private fun modeFor(env: VoiceSessionEnvironment): VoiceSessionMode =
        if (env.screenUsable) VoiceSessionMode.FOREGROUND else VoiceSessionMode.BACKGROUND

    private companion object {
        /** Повторный вопрос при звонке: звонки длятся дольше, чем стоит дёргать политику. */
        const val CALL_RETRY_MS = 3_000L

        /** Микрофон освобождается почти сразу, но не мгновенно (закрытие AudioRecord). */
        const val MIC_RETRY_MS = 1_000L

        /** Пауза после исчерпания лимита сессии: полминуты тишины вместо непрерывной работы. */
        const val SESSION_COOLDOWN_MS = 30_000L
    }
}
