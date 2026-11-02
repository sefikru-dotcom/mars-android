package ru.mars.mobile

/**
 * Что рисует полноэкранный голосовой режим.
 *
 * Зачем отдельное состояние, если есть [LocalVoiceState]: у композера и у полноэкранного
 * экрана разные задачи. Композеру нужны флаги доступности (есть ли микрофон, выдан ли
 * `RECORD_AUDIO`) — он решает, можно ли вообще начать. Полному экрану они не нужны вовсе:
 * на него попадают только когда голос уже работает, и там важны фаза, две строки текста и
 * одноместная кнопка. Переносить одно в другое значило бы тащить в экран шесть полей,
 * из которых он читает два.
 *
 * Фазы НЕ дублируются: используется уже существующий [LocalVoicePhase], иначе два
 * перечисления разъедутся на первом же добавлении фазы (например «прерываю»).
 *
 * Цветов здесь нет: палитра живёт в [MarsTheme] (Android-зависимый), а файл обязан
 * оставаться JVM-чистым. Экран переводит [tone] в цвет сам, как это делает
 * [LocalVoiceButton] с фазой.
 *
 * @param phase текущая фаза канала;
 * @param userLine последняя реплика человека (живой партиал или финальный текст);
 * @param assistantLine начало ответа — для полноэкранного вида его достаточно: целиком
 *   ответ читается на слух, а экран не должен превращаться в ленту;
 * @param notice сообщение для человека (не расслышал, нет разрешения); null — нечего сказать;
 * @param canRetry предложить повтор последней реплики (ход упал с ошибкой).
 */
data class VoiceModeState(
    val phase: LocalVoicePhase = LocalVoicePhase.IDLE,
    val userLine: String = "",
    val assistantLine: String = "",
    val notice: String? = null,
    val canRetry: Boolean = false,
) {

    /** Роль цвета фазы: экран сам решает, каким цветом её красить. */
    val tone: VoiceModeTone
        get() = when (phase) {
            LocalVoicePhase.IDLE -> VoiceModeTone.IDLE
            LocalVoicePhase.LISTENING -> VoiceModeTone.LISTENING
            LocalVoicePhase.THINKING -> VoiceModeTone.THINKING
            LocalVoicePhase.SPEAKING -> VoiceModeTone.SPEAKING
        }

    /**
     * Орб «дышит» только там, где есть звук или ожидание.
     *
     * В [LocalVoicePhase.IDLE] анимация выключена: экран открыт, микрофон закрыт —
     * пульсирующий орб читался бы как «уже слушаю», и человек начинал бы говорить
     * в закрытый микрофон.
     */
    val orbPulses: Boolean
        get() = phase != LocalVoicePhase.IDLE

    /** Есть ли что показать в строке человека. */
    val hasUserLine: Boolean
        get() = userLine.isNotBlank()

    companion object {

        /**
         * Снимок из состояния композера.
         *
         * `userLine` берётся из живого партиала: на полноэкранном режиме человек должен
         * видеть, что его услышали, ДО того как ответ начнётся. Ответ и подсказка
         * приходят отдельно — композер о них знает, но это разные источники правды.
         */
        fun from(state: LocalVoiceState): VoiceModeState = VoiceModeState(
            phase = state.phase,
            userLine = state.partial,
            notice = state.notice,
        )
    }
}

/** Четыре роли цвета для орба и подписи фазы. */
enum class VoiceModeTone { IDLE, LISTENING, THINKING, SPEAKING }
