package ru.mars.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SessionItem

class SessionRowActionsTest {
    @Test
    fun readOnlySessionIsNotManageable() {
        // Delegated subagent children arrive with the server read_only flag set.
        assertFalse(
            SessionRowActions.canManage(
                SessionItem(
                    "sub1",
                    "Subagent Session",
                    sourceTag = "subagent",
                    readOnly = true,
                    parentSessionId = "w1",
                )
            )
        )
    }

    @Test
    fun plainRowIsManageable() {
        // Ordinary top-level session with default flags keeps rename/delete.
        assertTrue(SessionRowActions.canManage(SessionItem("s1", "Работа")))
    }

    @Test
    fun explicitReadOnlyFalseIsManageable() {
        assertTrue(SessionRowActions.canManage(SessionItem("s2", "Заметки", readOnly = false)))
    }

    @Test
    fun subagentTagWithoutReadOnlyStaysManageable() {
        // sourceTag alone never disables actions — read_only is the only authority.
        assertTrue(
            SessionRowActions.canManage(
                SessionItem("s3", "Помеченная", sourceTag = "subagent", readOnly = false)
            )
        )
    }
}
