package ru.mars.mobile

/**
 * Что нажимает основная кнопка полноэкранного голосового режима.
 *
 * Отдельное перечисление, а не `Boolean`: у экрана четыре состояния, и два из них
 * («слушаю» и «говорю») означают РАЗНЫЕ действия при одинаковой форме нажатия —
 * закончить реплику против заглушить ответ. Один флаг «нажатие что-то делает»
 * заставил бы Activity угадывать по фазе, то есть держать вторую копию правил
 * рядом с [VoiceModePresenter] и разъезжаться с ней при каждой правке.
 *
 * [NONE] — кнопка нарисована, но мертва намеренно: в фазе «думаю» останавливать
 * нечего (озвучки ещё нет), а отмену хода полноэкранный режим не умеет — это дело
 * композера. Мёртвое нажатие честнее выключенной кнопки только тогда, когда оно
 * подписано; поэтому подпись в этой фазе — «Думаю…», а не «Стоп».
 */
enum class VoiceModeAction {
    /** Начать реплику: открыть микрофон и слушать. */
    START,

    /** Закончить реплику и отдать её в чат. */
    FINISH,

    /** Заглушить озвучку ответа. */
    STOP,

    /** Действия нет: кнопка выключена (фаза ожидания). */
    NONE,
}

/**
 * Всё, что экран рисует по одному снимку [VoiceModeState], уже посчитанное.
 *
 * Зачем отдельный тип, если есть [VoiceModeState]: там состояние канала (фаза, две
 * строки, причина отказа), здесь — ТЕКСТЫ интерфейса. Смешивать их значило бы
 * положить русские подписи в данные, которые считает движок голоса, и потерять
 * возможность проверить формулировки юнит-тестом без Android.
 *
 * @param phaseLabel строка фазы под орбом («Слушаю…», «Нажмите, чтобы говорить»);
 * @param hint подсказка под фазой: причина отказа или предложение повтора; `null` — молчим;
 * @param primaryLabel подпись основной кнопки;
 * @param tone роль цвета фазы (цвет выбирает экран: палитра живёт в `MarsTheme`);
 * @param action что основная кнопка делает при нажатии;
 * @param primaryEnabled нажимается ли она вообще;
 * @param showRetry показывать ли вторую кнопку «Повторить»;
 * @param userLine последняя реплика человека; `null` — строки нет (транскрипт выключен
 *   или реплика пустая). Пустая строка здесь запрещена намеренно: TextView с пустым
 *   текстом всё равно занимает высоту и рвёт вертикальный ритм экрана;
 * @param assistantLine начало ответа; те же правила, что у [userLine];
 * @param contentDescription полное описание для скринридера — одним куском, потому что
 *   орб и подпись фазы озвучиваются вместе.
 */
data class VoiceModeScreen(
    val phaseLabel: String,
    val hint: String?,
    val primaryLabel: String,
    val tone: VoiceModeTone,
    val action: VoiceModeAction,
    val primaryEnabled: Boolean,
    val showRetry: Boolean,
    val userLine: String?,
    val assistantLine: String?,
    val contentDescription: String,
)

/**
 * Чистая логика отображения полноэкранного голосового режима: снимок -> тексты.
 *
 * Ни одного `android.*`: только строки и перечисления, поэтому подписи фаз и правила
 * кнопки проверяются обычным JUnit. Экран ([VoiceModeActivity]) не формулирует
 * ни слова сам — он подставляет [VoiceModeScreen] в свои View. Иначе одна и та же
 * фраза жила бы в двух местах (Activity и тест) и расходилась бы при правке.
 *
 * Почему подписи здесь, а не в `LocalVoiceState.label`: у композера и у полного
 * экрана разные роли текста. У кнопки композера подпись — одно слово в
 * `contentDescription` (там нет места), у полного экрана — крупная строка под орбом
 * и отдельная подпись кнопки. Один общий набор строк заставил бы один из двух
 * экранов показывать чужой текст.
 */
object VoiceModePresenter {

    /**
     * Покой: микрофон закрыт. Это не название фазы, а ПРИГЛАШЕНИЕ — единственное,
     * что человеку нужно знать на пустом экране. «Готов слушать» описывало бы
     * состояние, но не отвечало бы на вопрос «что мне делать».
     */
    const val PHASE_IDLE: String = "Нажмите, чтобы говорить"

    /** Микрофон открыт. Многоточие — процесс идёт, а не закончился. */
    const val PHASE_LISTENING: String = "Слушаю…"

    /** Реплика ушла, ответа ещё нет. */
    const val PHASE_THINKING: String = "Думаю…"

    /** Ответ озвучивается. */
    const val PHASE_SPEAKING: String = "Говорю…"

    /** Основная кнопка в покое: начать реплику. */
    const val PRIMARY_START: String = "Говорить"

    /** Основная кнопка во время слушания: закончить и отправить. */
    const val PRIMARY_FINISH: String = "Готово"

    /** Основная кнопка в ожидании: нажимать нечего, и подпись это признаёт. */
    const val PRIMARY_BUSY: String = "Думаю…"

    /** Основная кнопка во время озвучки: немедленная тишина. */
    const val PRIMARY_STOP: String = "Стоп"

    /** Вторая кнопка после неудачного хода. */
    const val RETRY_LABEL: String = "Повторить"

    /**
     * Подсказка при [VoiceModeState.canRetry].
     *
     * Говорим, что именно случилось («не получилось») и что можно сделать, вместо
     * сухого «Ошибка»: человек на голосовом экране не смотрит в лог, ему нужен
     * следующий шаг.
     */
    const val RETRY_HINT: String = "Не получилось. Можно повторить."

    /**
     * Единственная точка перевода состояния в экран.
     *
     * @param state снимок канала;
     * @param transcript показывать ли текст реплик. Выключенный транскрипт — это не
     *   «пустые строки», а ОТСУТСТВИЕ строк: иначе экран резервировал бы под них
     *   высоту и орб уезжал бы вверх у людей, которым текст не нужен.
     */
    fun render(state: VoiceModeState, transcript: Boolean): VoiceModeScreen {
        val action = actionFor(state.phase)
        val primaryLabel = primaryLabelFor(state.phase)
        val enabled = action != VoiceModeAction.NONE
        return VoiceModeScreen(
            phaseLabel = phaseLabelFor(state.phase),
            hint = hintFor(state),
            primaryLabel = primaryLabel,
            tone = state.tone,
            action = action,
            primaryEnabled = enabled,
            showRetry = state.canRetry,
            userLine = if (transcript) blankToNull(state.userLine) else null,
            assistantLine = if (transcript) blankToNull(state.assistantLine) else null,
            contentDescription = describe(state, primaryLabel, enabled),
        )
    }

    /** Строка фазы под орбом. Одна фаза — одна строка, без вариантов «по случаю». */
    fun phaseLabelFor(phase: LocalVoicePhase): String = when (phase) {
        LocalVoicePhase.IDLE -> PHASE_IDLE
        LocalVoicePhase.LISTENING -> PHASE_LISTENING
        LocalVoicePhase.THINKING -> PHASE_THINKING
        LocalVoicePhase.SPEAKING -> PHASE_SPEAKING
    }

    /** Что делает основная кнопка в этой фазе. */
    fun actionFor(phase: LocalVoicePhase): VoiceModeAction = when (phase) {
        LocalVoicePhase.IDLE -> VoiceModeAction.START
        LocalVoicePhase.LISTENING -> VoiceModeAction.FINISH
        LocalVoicePhase.THINKING -> VoiceModeAction.NONE
        LocalVoicePhase.SPEAKING -> VoiceModeAction.STOP
    }

    /** Подпись основной кнопки: глагол действия, а не название фазы. */
    fun primaryLabelFor(phase: LocalVoicePhase): String = when (phase) {
        LocalVoicePhase.IDLE -> PRIMARY_START
        LocalVoicePhase.LISTENING -> PRIMARY_FINISH
        LocalVoicePhase.THINKING -> PRIMARY_BUSY
        LocalVoicePhase.SPEAKING -> PRIMARY_STOP
    }

    /**
     * Подсказка под фазой. Причина отказа важнее предложения повтора: если человек
     * не расслышал «нет разрешения на микрофон», кнопка «Повторить» выглядит
     * издевательством, потому что повторить он не может.
     */
    fun hintFor(state: VoiceModeState): String? {
        val notice = state.notice?.trim().orEmpty()
        if (notice.isNotEmpty()) return notice
        if (state.canRetry) return RETRY_HINT
        return null
    }

    /**
     * Описание для скринридера: фаза, причина, действие.
     *
     * Орб — чистая графика без текста, поэтому всё, что он значит, должно быть
     * произнесено словами. Подпись кнопки добавляется только когда кнопка живая:
     * иначе скринридер обещал бы действие, которого нет.
     */
    private fun describe(state: VoiceModeState, primaryLabel: String, enabled: Boolean): String {
        val builder = StringBuilder(phaseLabelFor(state.phase))
        val notice = state.notice?.trim().orEmpty()
        if (notice.isNotEmpty()) builder.append(". ").append(notice)
        if (enabled) builder.append(". Кнопка: ").append(primaryLabel)
        return builder.toString()
    }

    /** Пустая/пробельная строка для экрана — отсутствие строки, а не «пустая плашка». */
    private fun blankToNull(text: String): String? = if (text.isBlank()) null else text
}