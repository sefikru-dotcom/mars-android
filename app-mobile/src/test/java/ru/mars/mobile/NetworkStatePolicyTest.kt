package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тесты [NetworkStatePolicy] — чистой машины состояний сети:
 * редукция событий Available/CapabilitiesChanged/Losing/Lost (детекция
 * NetworkCapabilities платформенным [NetworkConnectivityObserver]) и флаги
 * переходов restored/dropped, от которых зависят баннер и флаш офлайн-очереди.
 */
class NetworkStatePolicyTest {

    private val policy = NetworkStatePolicy()

    // --- sendAllowed -------------------------------------------------------------------------

    @Test
    fun `online and unvalidated allow sending, losing and offline buffer`() {
        assertTrue(NetworkState.ONLINE.sendAllowed)
        assertTrue(NetworkState.UNVALIDATED.sendAllowed)
        assertFalse(NetworkState.LOSING.sendAllowed)
        assertFalse(NetworkState.OFFLINE.sendAllowed)
    }

    // --- начальное состояние ------------------------------------------------------------------

    @Test
    fun `initial state is offline until first evidence`() {
        // «Не доказано обратное»: до первого снимка отправка буферизуется.
        assertEquals(NetworkState.OFFLINE, policy.state)
        assertFalse(policy.state.sendAllowed)
    }

    @Test
    fun `policy accepts explicit initial state`() {
        val warm = NetworkStatePolicy(initial = NetworkState.ONLINE)
        assertEquals(NetworkState.ONLINE, warm.state)
    }

    // --- редукция событий ----------------------------------------------------------------------

    @Test
    fun `available validated goes online and reports restored from offline`() {
        val transition = policy.onEvent(NetworkEvent.Available(validated = true))
        assertEquals(NetworkState.ONLINE, policy.state)
        assertEquals(NetworkState.OFFLINE, transition.previous)
        assertEquals(NetworkState.ONLINE, transition.current)
        assertTrue(transition.changed)
        assertTrue(transition.restored)
        assertFalse(transition.dropped)
    }

    @Test
    fun `available unvalidated goes to unvalidated but still allows sending`() {
        val transition = policy.onEvent(NetworkEvent.Available(validated = false))
        assertEquals(NetworkState.UNVALIDATED, policy.state)
        assertTrue(policy.state.sendAllowed)
        // OFFLINE → UNVALIDATED тоже считается восстановлением связи (sendAllowed вырос).
        assertTrue(transition.restored)
    }

    @Test
    fun `capabilities validated flag flips online and unvalidated both ways`() {
        policy.onEvent(NetworkEvent.Available(validated = false))
        policy.onEvent(NetworkEvent.CapabilitiesChanged(validated = true))
        assertEquals(NetworkState.ONLINE, policy.state)
        policy.onEvent(NetworkEvent.CapabilitiesChanged(validated = false))
        assertEquals(NetworkState.UNVALIDATED, policy.state)
    }

    @Test
    fun `losing buffers sending and reports dropped from online`() {
        policy.onEvent(NetworkEvent.Available(validated = true))
        val transition = policy.onEvent(NetworkEvent.Losing)
        assertEquals(NetworkState.LOSING, policy.state)
        assertFalse(policy.state.sendAllowed)
        assertTrue(transition.dropped)
        assertFalse(transition.restored)
    }

    @Test
    fun `lost goes offline`() {
        policy.onEvent(NetworkEvent.Available(validated = true))
        val transition = policy.onEvent(NetworkEvent.Lost)
        assertEquals(NetworkState.OFFLINE, policy.state)
        assertTrue(transition.dropped)
    }

    @Test
    fun `recovery from losing is restored transition`() {
        policy.onEvent(NetworkEvent.Available(validated = true))
        policy.onEvent(NetworkEvent.Losing)
        val transition = policy.onEvent(NetworkEvent.Available(validated = true))
        assertEquals(NetworkState.ONLINE, policy.state)
        // LOSING → ONLINE: связь вернулась — триггер флаша очереди.
        assertTrue(transition.restored)
        assertFalse(transition.dropped)
    }

    @Test
    fun `full outage cycle offline to losing to lost and back`() {
        policy.onEvent(NetworkEvent.Available(validated = true))
        policy.onEvent(NetworkEvent.Losing)
        policy.onEvent(NetworkEvent.Lost)
        assertEquals(NetworkState.OFFLINE, policy.state)
        policy.onEvent(NetworkEvent.Available(validated = false))
        policy.onEvent(NetworkEvent.CapabilitiesChanged(validated = true))
        assertEquals(NetworkState.ONLINE, policy.state)
    }

    @Test
    fun `repeated same-state events are not transitions`() {
        policy.onEvent(NetworkEvent.Available(validated = true))
        val transition = policy.onEvent(NetworkEvent.CapabilitiesChanged(validated = true))
        assertFalse(transition.changed)
        assertFalse(transition.restored)
        assertFalse(transition.dropped)
        assertEquals(NetworkState.ONLINE, policy.state)
    }

    @Test
    fun `lost while already offline is not a dropped transition`() {
        val transition = policy.onEvent(NetworkEvent.Lost)
        assertEquals(NetworkState.OFFLINE, transition.previous)
        assertEquals(NetworkState.OFFLINE, transition.current)
        assertFalse(transition.dropped)
    }

    // --- чистая функция reduce ------------------------------------------------------------------

    @Test
    fun `static reduce matches onEvent rules and does not mutate`() {
        assertEquals(
            NetworkState.ONLINE,
            NetworkStatePolicy.reduce(NetworkState.OFFLINE, NetworkEvent.Available(validated = true)),
        )
        assertEquals(
            NetworkState.UNVALIDATED,
            NetworkStatePolicy.reduce(NetworkState.ONLINE, NetworkEvent.CapabilitiesChanged(validated = false)),
        )
        assertEquals(
            NetworkState.LOSING,
            NetworkStatePolicy.reduce(NetworkState.ONLINE, NetworkEvent.Losing),
        )
        assertEquals(
            NetworkState.OFFLINE,
            NetworkStatePolicy.reduce(NetworkState.LOSING, NetworkEvent.Lost),
        )
        // reduce не трогает состояние экземпляра.
        val untouched = NetworkStatePolicy(initial = NetworkState.ONLINE)
        NetworkStatePolicy.reduce(untouched.state, NetworkEvent.Lost)
        assertEquals(NetworkState.ONLINE, untouched.state)
    }
}
