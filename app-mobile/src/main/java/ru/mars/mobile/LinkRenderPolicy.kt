package ru.mars.mobile

/**
 * Правило отрисовки кликабельных ссылок в транскрипте чата.
 *
 * Чистая логика без Android-классов: цвет НЕ хардкодится, а приходит из темы
 * ([MarsTheme.ACCENT_TEXT]) — так дизайн-система остаётся единственным
 * источником цвета, а правило покрывается обычным JVM-тестом.
 */
object LinkRenderPolicy {

    /**
     * Прозрачный фон: базовый [android.text.style.ClickableSpan] красит текст в
     * `highlightColor` виджета и заливает фон — в тёмной теме это выглядит как
     * случайная плашка поверх пузыря, поэтому фон снимаем явно.
     */
    const val TRANSPARENT = 0

    /** Готовое состояние отрисовки ссылки: цвет, подчёркивание и фон. */
    data class DrawState(val color: Int, val underline: Boolean, val background: Int)

    /**
     * Ссылка визуально отличается от обычного текста: цвет акцентного текста
     * темы + подчёркивание. Акцент передаётся аргументом (а не читается из
     * [MarsTheme] внутри), чтобы правило оставалось чистым и тестируемым.
     */
    fun drawState(accent: Int): DrawState =
        DrawState(color = accent, underline = true, background = TRANSPARENT)
}
