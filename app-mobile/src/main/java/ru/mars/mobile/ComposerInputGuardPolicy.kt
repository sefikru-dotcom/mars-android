package ru.mars.mobile

/**
 * Защита композера от программных перезаписей — чистая JVM-политика без Android-импортов.
 * Хост (ChatActivity) владеет EditText/фокусом/IME; этот класс отвечает на два вопроса:
 *
 * 1. [resolveTextApply] — можно ли источнику события программно применить текст
 *    (setText/черновик/очистка)? Ключевой инвариант: пока пользователь набирал текст
 *    ([onUserEdited]), никакие входящие SSE-кадры и сетевые переключения его не перезапишут —
 *    поток ответов рендерится в транскрипт и композера не касается вообще.
 *
 * 2. [resolveFocusRestore] — можно ли источнику события трогать фокус/клавиатуру?
 *    SSE-кадры фокус не перемещают никогда ([ComposerFocusDecision.Suppress]); после обрыва
 *    связи фокус возвращается композеру одноразово и только если он был у него до обрыва,
 *    причём во время «флаппинга» (серия переходов сети за короткое окно) восстановление
 *    откладывается ([ComposerFocusDecision.DeferUntilQuiet]) — иначе клавиатура мигает на
 *    каждый drop/restore.
 *
 * Дополнительно [allowComposerRender] прореживает SSE-драйвенные перерисовки композера
 * (коалесинг частых кадров), уменьшая шанс, что ре-рендер вообще полезет в текст/фокус.
 *
 * Все решения детерминированы и покрыты JVM-тестами; время инжектируется через [clock].
 */

/** Источник запроса на программное применение текста к композеру. */
enum class TextApplyOrigin {
    /** Кадр SSE-потока (дельта ответа, фаза, тул-карта) — триггер полного ре-рендера экрана. */
    STREAM_UPDATE,

    /** Смена сетевого состояния / показ-скрытие баннера. */
    NETWORK_CHANGE,

    /** Восстановление черновика (холодный старт, возврат на экран чата). */
    DRAFT_RESTORE,

    /** Смена диалога: композитор принимает черновик новой сессии. */
    SESSION_SWITCH,

    /** Очистка композера после успешной отправки/буферизации. */
    SEND_RESET,

    /** Голосовой ввод: текст надиктован пользователем (приравнивается к набору руками). */
    VOICE_INPUT,
}

sealed interface ComposerTextDecision {
    /** Программный setText разрешён. */
    data object Apply : ComposerTextDecision

    /** Текст принадлежит пользователю/потоку — композер трогать нельзя. */
    data object Preserve : ComposerTextDecision
}

/** Источник запроса на перемещение фокуса/клавиатуры. */
enum class FocusRequestOrigin {
    /** Пришёл SSE-кадр — классический виновник «клавиатура дёрнулась во время потока». */
    STREAM_CHUNK,

    /** Сеть снова разрешает отправку ([NetworkTransition.restored]). */
    NETWORK_SETTLED,

    /** Сетевой баннер скрылся и хост хочет вернуть фокус композеру. */
    BANNER_HIDDEN,
}

sealed interface ComposerFocusDecision {
    /** Фокус не трогаем (восстанавливать нечего). */
    data object Keep : ComposerFocusDecision

    /** Одноразово вернуть фокус композеру и поднять клавиатуру. */
    data object RestoreWithKeyboard : ComposerFocusDecision

    /** Идёт флаппинг сети: повторить запрос после [ComposerInputGuardPolicy.quietMillisRemaining]. */
    data object DeferUntilQuiet : ComposerFocusDecision

    /** Этому источнику фокус не принадлежит никогда (SSE-кадры). */
    data object Suppress : ComposerFocusDecision
}

/**
 * Состояние и решения защиты композера (контракт — в шапке файла).
 *
 * @param flapWindowMillis окно, в котором ≥ [FLAP_TRANSITION_THRESHOLD] переходов сети
 *   считаются флаппингом (фокус не переставляем, ждём тишины).
 * @param renderMinIntervalMillis минимальный интервал между SSE-драйвенными перерисовками
 *   композера ([allowComposerRender]).
 */
class ComposerInputGuardPolicy(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val flapWindowMillis: Long = DEFAULT_FLAP_WINDOW_MILLIS,
    private val renderMinIntervalMillis: Long = DEFAULT_RENDER_MIN_INTERVAL_MILLIS,
) {

    /** True с момента [onUserEdited] до программной очистки/смены диалога: текст принадлежит пользователю. */
    var userOwnsText: Boolean = false
        private set

    /** Композер сейчас в фокусе (хост транслирует onFocusChange). */
    var composerFocused: Boolean = false
        private set

    /** Фокус был у композера до обрыва связи — ожидается одноразовое восстановление. */
    var pendingFocusRestore: Boolean = false
        private set

    /** Моменты «изменившихся» сетевых переходов внутри окна флаппинга. */
    private val transitionTimestamps = ArrayDeque<Long>()
    private var lastRenderAtEpochMillis: Long? = null

    /**
     * Пользователь коснулся текста (набор, вставка, ручная очистка поля). Даже намеренно
     * пустое поле — решение пользователя: последующий [TextApplyOrigin.DRAFT_RESTORE] его не перезапишет.
     */
    fun onUserEdited() {
        userOwnsText = true
    }

    /** Хост программно очистил композер после отправки — текст снова ничей. */
    fun onSendReset() {
        userOwnsText = false
    }

    /** Хост программно применил текст к композеру (черновик после Apply-решения). */
    fun onTextAppliedProgrammatically() {
        userOwnsText = false
    }

    /**
     * Смена диалога/новый чат: полный сброс защиты — новый контекст вправе применить свой
     * черновик, фокус и сетевая история не переносятся.
     */
    fun onSessionSwitch() {
        userOwnsText = false
        pendingFocusRestore = false
        transitionTimestamps.clear()
    }

    /** Реальный фокус композера (слушатель onFocusChange хоста). */
    fun onComposerFocusChanged(focused: Boolean) {
        composerFocused = focused
    }

    /**
     * Сетевой переход из [NetworkStatePolicy.onEvent]: «изменившиеся» переходы копятся в окне
     * флаппинга; потеря связи при фокусе на композере запоминает одноразовое восстановление.
     */
    fun onNetworkTransition(transition: NetworkTransition) {
        val now = clock()
        if (transition.changed) {
            transitionTimestamps.addLast(now)
            pruneTransitions(now)
        }
        if (!transition.current.sendAllowed && composerFocused) {
            pendingFocusRestore = true
        }
    }

    /**
     * Решение по программному тексту. Правила:
     * - [TextApplyOrigin.STREAM_UPDATE] и [TextApplyOrigin.NETWORK_CHANGE] — всегда
     *   [ComposerTextDecision.Preserve]: SSE-кадры и сеть не владельцы композера;
     * - [TextApplyOrigin.DRAFT_RESTORE] — [ComposerTextDecision.Apply], только пока текст
     *   ничей ([userOwnsText] == false);
     * - [TextApplyOrigin.SESSION_SWITCH], [TextApplyOrigin.SEND_RESET],
     *   [TextApplyOrigin.VOICE_INPUT] — действия пользователя/явный контекст:
     *   [ComposerTextDecision.Apply] всегда.
     */
    fun resolveTextApply(origin: TextApplyOrigin): ComposerTextDecision = when (origin) {
        TextApplyOrigin.STREAM_UPDATE, TextApplyOrigin.NETWORK_CHANGE -> ComposerTextDecision.Preserve
        TextApplyOrigin.DRAFT_RESTORE ->
            if (userOwnsText) ComposerTextDecision.Preserve else ComposerTextDecision.Apply
        TextApplyOrigin.SESSION_SWITCH, TextApplyOrigin.SEND_RESET, TextApplyOrigin.VOICE_INPUT ->
            ComposerTextDecision.Apply
    }

    /**
     * Решение по фокусу/клавиатуре. Правила:
     * - [FocusRequestOrigin.STREAM_CHUNK] — всегда [ComposerFocusDecision.Suppress]: поток
     *   ответа не имеет права перемещать фокус (иначе клавиатура «прыгает» на каждом кадре);
     * - [FocusRequestOrigin.NETWORK_SETTLED]/[FocusRequestOrigin.BANNER_HIDDEN] —
     *   [ComposerFocusDecision.Keep], если восстанавливать нечего (фокус не терялся);
     *   [ComposerFocusDecision.RestoreWithKeyboard] одноразово, когда сеть успокоилась;
     *   [ComposerFocusDecision.DeferUntilQuiet] во время флаппинга — повторить через
     *   [quietMillisRemaining].
     *
     * Grant восстановления сбрасывает [pendingFocusRestore] (single-shot), поэтому повторный
     * запрос до нового обрыва вернёт [ComposerFocusDecision.Keep].
     */
    fun resolveFocusRestore(origin: FocusRequestOrigin): ComposerFocusDecision {
        if (origin == FocusRequestOrigin.STREAM_CHUNK) return ComposerFocusDecision.Suppress
        if (!pendingFocusRestore) return ComposerFocusDecision.Keep
        if (composerFocused) {
            // Фокус уже вернулся сам (пользователь тапнул в поле) — восстанавливать нечего.
            pendingFocusRestore = false
            return ComposerFocusDecision.Keep
        }
        if (isFlapping()) return ComposerFocusDecision.DeferUntilQuiet
        pendingFocusRestore = false
        return ComposerFocusDecision.RestoreWithKeyboard
    }

    /**
     * Флаппинг: ≥ [FLAP_TRANSITION_THRESHOLD] переходов сети за последние [flapWindowMillis].
     * Одиночный обрыв с долгим офлайном к моменту восстановления уже «остыл» и не флагается.
     */
    fun isFlapping(): Boolean {
        pruneTransitions(clock())
        return transitionTimestamps.size >= FLAP_TRANSITION_THRESHOLD
    }

    /**
     * Сколько миллисекунд осталось до «тишины» (0, если флаппинга нет): момент, когда
     * предпоследний переход покинет окно и [resolveFocusRestore] снова сможет грантить фокус.
     */
    fun quietMillisRemaining(): Long {
        pruneTransitions(clock())
        if (transitionTimestamps.size < FLAP_TRANSITION_THRESHOLD) return 0
        val readyAt = transitionTimestamps[transitionTimestamps.size - FLAP_TRANSITION_THRESHOLD] + flapWindowMillis
        return (readyAt - clock()).coerceAtLeast(0)
    }

    /**
     * Прореживание SSE-драйвенных перерисовок композера: true не чаще раза в
     * [renderMinIntervalMillis] (первый вызов всегда проходит). Хост вызывает перед
     * ре-рендером композера на кадре потока; кадровые данные транскрипта это не ограничивает.
     */
    fun allowComposerRender(): Boolean {
        val now = clock()
        val last = lastRenderAtEpochMillis
        if (last != null && now - last < renderMinIntervalMillis) return false
        lastRenderAtEpochMillis = now
        return true
    }

    /** Полный сброс (logout/пересоздание экрана без смены диалога). */
    fun reset() {
        userOwnsText = false
        composerFocused = false
        pendingFocusRestore = false
        transitionTimestamps.clear()
        lastRenderAtEpochMillis = null
    }

    private fun pruneTransitions(now: Long) {
        while (transitionTimestamps.isNotEmpty() && now - transitionTimestamps.first() >= flapWindowMillis) {
            transitionTimestamps.removeFirst()
        }
    }

    companion object {
        /** Окно детекции флаппинга: переходы сети плотнее этого считаются «мигающей» связью. */
        const val DEFAULT_FLAP_WINDOW_MILLIS = 2_000L

        /** Переходов внутри окна, начиная с которых сеть считается флапающей. */
        const val FLAP_TRANSITION_THRESHOLD = 2

        /** Минимальный интервал SSE-драйвенных перерисовок композера. */
        const val DEFAULT_RENDER_MIN_INTERVAL_MILLIS = 100L
    }
}
