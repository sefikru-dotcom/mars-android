package ru.mars.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Поведение retry-focus ([RetryFocusPolicy]): фокус доступности переносится на
 * «Повторить» только когда screen reader с touch exploration активен, состояние
 * вкладки допускает повтор и контрол только что появился на экране (новая
 * ошибка или переключение на вкладку с уже показанным «Повторить»).
 */
class RetryFocusPolicyTest {

    private fun should(
        canRetry: Boolean,
        retryWasVisible: Boolean = false,
        tabSwitched: Boolean = false,
        accessibilityEnabled: Boolean = true,
        touchExplorationEnabled: Boolean = true,
    ): Boolean = RetryFocusPolicy.shouldMoveFocus(
        accessibilityEnabled = accessibilityEnabled,
        touchExplorationEnabled = touchExplorationEnabled,
        canRetry = canRetry,
        retryWasVisible = retryWasVisible,
        tabSwitched = tabSwitched,
    )

    @Test
    fun `focus moves when retry appears with screen reader active`() {
        assertTrue(should(canRetry = true))
    }

    @Test
    fun `focus moves when switching to a tab that already shows retry`() {
        assertTrue(should(canRetry = true, retryWasVisible = true, tabSwitched = true))
    }

    @Test
    fun `steady re-render with retry already visible does not steal focus`() {
        assertFalse(should(canRetry = true, retryWasVisible = true, tabSwitched = false))
    }

    @Test
    fun `no retry - no focus`() {
        assertFalse(should(canRetry = false))
        assertFalse(should(canRetry = false, tabSwitched = true))
        assertFalse(should(canRetry = false, retryWasVisible = true, tabSwitched = true))
    }

    @Test
    fun `without screen reader focus is never stolen`() {
        assertFalse(should(canRetry = true, accessibilityEnabled = false))
        assertFalse(should(canRetry = true, touchExplorationEnabled = false))
        assertFalse(should(canRetry = true, tabSwitched = true, accessibilityEnabled = false))
        assertFalse(should(canRetry = true, tabSwitched = true, touchExplorationEnabled = false))
    }

    @Test
    fun `retry reappearing after a loading pass is focused again`() {
        // Сценарий экрана: ошибка -> «Повторить» видно; клик -> LOADING
        // (canRetry=false, видимость сброшена); снова ошибка -> фокус опять
        // переносится (retryWasVisible пересчитан экраном в false).
        assertTrue(should(canRetry = true))
        assertFalse(should(canRetry = false))
        assertTrue(should(canRetry = true, retryWasVisible = false))
    }
}
