package ru.mars.mobile

/**
 * Фаза голосового канала локального чата.
 *
 * Ровно четыре состояния, потому что больше не нужно ни кнопке, ни хосту: кнопка красит
 * точку и подпись, экран показывает «идёт ход» по своей модели (`LocalChatUiState.streaming`).
 * Отдельного `ERROR` нет намеренно: отказ — это [LocalVoiceState.notice] при прежней фазе,
 * иначе кнопка залипала бы в красном состоянии после разового сбоя микрофона.
 */
enum class LocalVoicePhase {
    /** Микрофон закрыт, озвучки нет: можно начинать. */
    IDLE,

    /** Микрофон открыт, реплика распознаётся. */
    LISTENING,

    /** Реплика отдана в композер, ответ ещё не пришёл. */
    THINKING,

    /** Ответ озвучивается; нажатие останавливает звук. */
    SPEAKING,
}

/**
 * Чистое состояние голосового ввода локального чата: без `android.*`, без ссылок на View,
 * без движков. Всё, что видит экран и кнопка, приходит одним неизменяемым снимком, поэтому
 * рендер — чистая функция от него.
 *
 * Почему флаги доступности отдельно от фазы: «микрофон есть, разрешения нет» и «разрешение
 * есть, распознавателя нет» — разные причины, и человеку нужен разный текст. Фаза отвечает
 * только на вопрос «что происходит сейчас».
 *
 * Цветов здесь нет: палитра живёт в [MarsTheme] (Android-зависимый), а это файл обязан
 * оставаться JVM-чистым — иначе его нельзя проверить юнит-тестом и он потащил бы Android в
 * состояние. Кнопка ([LocalVoiceButton]) сама переводит [phase] в цвет.
 *
 * @param phase текущая фаза канала;
 * @param partial живой транскрипт последней реплики (может меняться и укорачиваться);
 * @param micPermissionGranted выдан ли `RECORD_AUDIO` прямо сейчас;
 * @param asrAvailable есть ли на устройстве движок распознавания (порт создан);
 * @param ttsAvailable есть ли чем озвучивать (системный TTS или серверный канал);
 * @param notice последнее сообщение для человека (нет разрешения, не расслышал, нет движка);
 *   `null` — показывать нечего. Хост волен вывести его в своей строке статуса.
 */
data class LocalVoiceState(
    val phase: LocalVoicePhase = LocalVoicePhase.IDLE,
    val partial: String = "",
    val micPermissionGranted: Boolean = false,
    val asrAvailable: Boolean = false,
    val ttsAvailable: Boolean = false,
    val notice: String? = null,
) {

    val isListening: Boolean get() = phase == LocalVoicePhase.LISTENING

    val isThinking: Boolean get() = phase == LocalVoicePhase.THINKING

    val isSpeaking: Boolean get() = phase == LocalVoicePhase.SPEAKING

    /** Можно ли начать реплику прямо сейчас: есть разрешение и есть чем распознавать. */
    val canListen: Boolean get() = micPermissionGranted && asrAvailable

    /**
     * Стоит ли вообще рисовать блок голоса.
     *
     * Если на устройстве нет ни распознавания, ни озвучки, кнопка была бы мёртвой — а мёртвая
     * кнопка хуже отсутствующей. Голосовой ввод — основная причина существования блока,
     * поэтому озвучка одна его не показывает.
     */
    val visible: Boolean get() = asrAvailable

    /** Подпись на кнопке: одно слово, чтобы кнопка оставалась компактной. */
    val label: String
        get() = when (phase) {
            LocalVoicePhase.IDLE -> "Голос"
            LocalVoicePhase.LISTENING -> "Слушаю"
            LocalVoicePhase.THINKING -> "Думаю"
            LocalVoicePhase.SPEAKING -> "Говорю"
        }

    /** Текст для скринридера: действие, а не состояние иконки. */
    val contentDescription: String
        get() {
            if (phase == LocalVoicePhase.IDLE && !micPermissionGranted) {
                return "Голосовой ввод. Нет разрешения на микрофон"
            }
            if (phase == LocalVoicePhase.IDLE && !asrAvailable) {
                return "Голосовой ввод недоступен: распознавание речи не установлено"
            }
            return when (phase) {
                LocalVoicePhase.IDLE -> "Голосовой ввод. Нажмите и говорите"
                LocalVoicePhase.LISTENING -> "Слушаю. Нажмите, чтобы закончить и отправить"
                LocalVoicePhase.THINKING -> "Думаю над ответом"
                LocalVoicePhase.SPEAKING -> "Говорю ответ. Нажмите, чтобы остановить озвучку"
            }
        }

    /** Копия с другой фазой: остальные флаги (доступность, разрешение) не меняются. */
    fun asPhase(next: LocalVoicePhase): LocalVoiceState = copy(phase = next)
}
