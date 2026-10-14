package ru.mars.mobile

/**
 * Чистая политика переноса фокуса доступности на «Повторить» (retry-focus) при
 * появлении error-состояния секционного экрана.
 *
 * Зачем: пользователь TalkBack должен получить действие повтора сразу после
 * озвучивания ошибки (live-region), а не искать кнопку линейной навигацией.
 * Фокус переносится ТОЛЬКО при активно работающем screen reader с touch
 * exploration — иначе перехват фокуса вреден (рамка фокуса, скачки скролла у
 * пользователей без screen reader).
 *
 * Триггер — появление контрола на экране: состояние вкладки перешло в
 * canRetry, когда «Повторить» ещё не было видно ([retryWasVisible] == false),
 * либо пользователь переключился на вкладку, где «Повторить» уже показано
 * ([tabSwitched]). Пока тот же retry остаётся видимым, повторные рендеры
 * фокус не перехватывают.
 *
 * Слои: решение — здесь (чистый JVM, покрыт тестами); проводку
 * (поиск контрола по tag, requestFocus + TYPE_VIEW_FOCUSED, чтение
 * AccessibilityManager) делает UI-слой экрана.
 */
object RetryFocusPolicy {
    fun shouldMoveFocus(
        accessibilityEnabled: Boolean,
        touchExplorationEnabled: Boolean,
        canRetry: Boolean,
        retryWasVisible: Boolean,
        tabSwitched: Boolean,
    ): Boolean =
        accessibilityEnabled &&
            touchExplorationEnabled &&
            canRetry &&
            (tabSwitched || !retryWasVisible)
}
