package ru.mars.mobile

/**
 * Чистая политика доступности чата: подписи кнопки композера
 * (отправка/остановка стрима) и раскрывающихся заголовков карточек
 * (thinking/tool/todo). Без Android-импортов — JVM-тесты; view-слой
 * (ChatActivity/MarsDialog) лишь мапит значения на узлы: contentDescription,
 * stateDescription, button-role. Тексты решает только эта политика, чтобы
 * TalkBack озвучивал действие, а не устаревшее состояние иконки.
 */
object ChatA11yPolicy {
    /** Кнопка композера в простое: обычная отправка сообщения. */
    const val SEND = "Отправить"

    /** Кнопка композера во время стрима: та же кнопка останавливает генерацию. */
    const val STOP = "Остановить"

    /** Состояние раскрывающегося заголовка (sentence case — для stateDescription). */
    const val STATE_EXPANDED = "Развёрнуто"
    const val STATE_COLLAPSED = "Свёрнуто"

    /**
     * Подпись кнопки композера обязана соответствовать ДЕЙСТВИЮ клика:
     * во время набора (TYPING) клик останавливает стрим, в покое — отправляет.
     */
    fun sendButtonDescription(typing: Boolean): String = if (typing) STOP else SEND

    /** Доступное состояние раскрывающегося заголовка. */
    fun stateDescription(expanded: Boolean): String = if (expanded) STATE_EXPANDED else STATE_COLLAPSED

    /**
     * Единая подпись раскрывающегося заголовка: непустые части метки через запятую
     * плюс состояние в конце («Мысли, 3 строки, свёрнуто»). Пустые части отбрасываются;
     * если метки нет вовсе — остаётся только состояние.
     */
    fun expandableHeaderDescription(expanded: Boolean, vararg parts: String?): String {
        val state = if (expanded) "развёрнуто" else "свёрнуто"
        val label = parts
            .filterNotNull()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
        return if (label.isEmpty()) state else "$label, $state"
    }
}
