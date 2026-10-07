package ru.mars.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingFlowStateTest {
    @Test fun preventsDuplicateCreateAndExchange() {
        val state = PairingFlowState()
        assertTrue(state.beginCreate())
        assertFalse(state.beginCreate())
        assertTrue(state.created())
        assertTrue(state.beginExchange())
        assertFalse(state.beginExchange())
    }

    @Test fun revokeReturnsToIdleOnlyAfterSuccess() {
        val state = PairingFlowState()
        state.beginCreate(); state.created(); state.beginExchange(); state.connected()
        assertTrue(state.beginRevoke())
        assertFalse(state.beginRevoke())
        assertTrue(state.revoked())
        assertTrue(state.beginCreate())
    }
}
