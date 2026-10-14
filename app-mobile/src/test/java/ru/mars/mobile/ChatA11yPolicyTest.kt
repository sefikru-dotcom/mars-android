package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Юнит-тесты [ChatA11yPolicy] — чистой политики доступных подписей чата:
 * кнопка композера обязана описывать ДЕЙСТВИЕ (idle -> «Отправить»,
 * typing -> «Остановить»), а раскрывающиеся заголовки — единой строкой
 * давать метку и состояние для TalkBack.
 */
class ChatA11yPolicyTest {

    // --- Кнопка композера -------------------------------------------------------------------------

    @Test
    fun `idle composer button is described as send`() {
        assertEquals("Отправить", ChatA11yPolicy.sendButtonDescription(typing = false))
    }

    @Test
    fun `typing composer button is described as stop`() {
        // Во время стрима та же кнопка останавливает генерацию — подпись обязана
        // соответствовать действию клика, а не иконке отправки.
        assertEquals("Остановить", ChatA11yPolicy.sendButtonDescription(typing = true))
    }

    // --- Состояние раскрытия ------------------------------------------------------------------------

    @Test
    fun `expanded state description`() {
        assertEquals("Развёрнуто", ChatA11yPolicy.stateDescription(expanded = true))
    }

    @Test
    fun `collapsed state description`() {
        assertEquals("Свёрнуто", ChatA11yPolicy.stateDescription(expanded = false))
    }

    // --- Заголовок раскрывающейся карточки -----------------------------------------------------------

    @Test
    fun `header description joins label parts and appends state`() {
        assertEquals(
            "Мысли, 3 строки, свёрнуто",
            ChatA11yPolicy.expandableHeaderDescription(expanded = false, "Мысли", "3 строки")
        )
        assertEquals(
            "Мысли, 3 строки, развёрнуто",
            ChatA11yPolicy.expandableHeaderDescription(expanded = true, "Мысли", "3 строки")
        )
    }

    @Test
    fun `header description drops blank and null parts`() {
        assertEquals(
            "terminal, свёрнуто",
            ChatA11yPolicy.expandableHeaderDescription(expanded = false, null, " terminal ", "", null)
        )
    }

    @Test
    fun `header description falls back to state only when no label`() {
        assertEquals("развёрнуто", ChatA11yPolicy.expandableHeaderDescription(expanded = true))
        assertEquals(
            "свёрнуто",
            ChatA11yPolicy.expandableHeaderDescription(expanded = false, null, "   ")
        )
    }
}
