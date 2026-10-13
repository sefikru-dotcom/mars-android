package ru.mars.tv.chat

/**
 * Чистая политика кнопки отправки ТВ-чата: во время живого хода одна кнопка несёт ДВЕ
 * роли (мобильный паритет — в app-mobile `sendMessage` на TYPING уходит в `steerMessage`):
 *
 * - ввод НЕ пуст -> STEER: уточнение поверх активного хода (POST /api/chat/steer),
 *   ход НЕ отменяется — агент дочитает правку между инструментами;
 * - ввод пуст -> STOP: отмена серверного хода (прежнее поведение «Остановить»);
 * - хода нет -> SEND: обычная отправка сообщения.
 *
 * Признак успеха steer — поле `accepted` ответа, а не HTTP-код (контракт
 * `_handle_chat_steer`: HTTP 200 + `{"accepted": false, "fallback": ...}` означает
 * ОТКАЗ); транспорт это уже учитывает — `ChatRepository.executeControl` возвращает
 * false при `accepted:false`, и шлюз [ru.mars.tv.TvChatControlGateway.steer] отдаёт
 * готовый Boolean. При отказе текст НЕ очищается — правка остаётся в поле ввода.
 */
object TvSteerPolicy {

    /** Действие основной кнопки. */
    enum class SendAction { SEND, STEER, STOP }

    /** Тексты статус-полосы для исходов steer. */
    const val STATUS_ACCEPTED = "Уточнение отправлено — агент дочитает его в этом ходу"
    const val STATUS_REJECTED = "Уточнение не принято — текст сохранён в поле ввода"
    const val STATUS_NO_SESSION = "Уточнение не отправлено — сессия ещё не создана"

    /** Текст статус-полосы при недоставленном steer (SSE `pending_steer_leftover`). */
    const val LEFTOVER_STATUS = "Правка не доставлена — текст возвращён в поле ввода"

    /** Какое действие выполняет кнопка при текущем состоянии хода и ввода. */
    fun actionFor(turnLive: Boolean, inputBlank: Boolean): SendAction = when {
        !turnLive -> SendAction.SEND
        inputBlank -> SendAction.STOP
        else -> SendAction.STEER
    }

    /** Подпись кнопки для действия. */
    fun buttonLabel(action: SendAction): String = when (action) {
        SendAction.SEND -> "Отправить"
        SendAction.STEER -> "Уточнить"
        SendAction.STOP -> "Остановить"
    }

    /** Иконка «стоп» нужна только для отмены хода; steer/send показывают отправку. */
    fun buttonShowsStopIcon(action: SendAction): Boolean = action == SendAction.STOP

    /**
     * Возврат недоставленного steer в поле ввода (WebUI/мобильный паритет): подставляем
     * текст ТОЛЬКО в пустое поле — набранное пользователем важнее опоздавшей правки.
     */
    fun canRestoreLeftover(leftoverText: String, currentInput: String): Boolean =
        leftoverText.isNotBlank() && currentInput.isBlank()
}
