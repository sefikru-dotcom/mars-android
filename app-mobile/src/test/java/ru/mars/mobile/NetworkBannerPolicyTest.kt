package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тесты [NetworkBannerPolicy] — чистой политики баннера сетевого статуса:
 * видимость/текст/тон для каждого состояния [NetworkState] и размера офлайн-очереди.
 */
class NetworkBannerPolicyTest {

    private val policy = NetworkBannerPolicy()

    // --- OFFLINE --------------------------------------------------------------------------------

    @Test
    fun `offline without queue shows error banner promising auto-send`() {
        val ui = policy.bannerFor(NetworkState.OFFLINE, pendingCount = 0)
        assertTrue(ui.visible)
        assertEquals(NetworkBannerUi.Tone.ERROR, ui.tone)
        assertTrue(ui.text.contains("Нет связи"))
        assertTrue(ui.text.contains("автоматически"))
    }

    @Test
    fun `offline with queue shows counter`() {
        val ui = policy.bannerFor(NetworkState.OFFLINE, pendingCount = 3)
        assertTrue(ui.visible)
        assertEquals(NetworkBannerUi.Tone.ERROR, ui.tone)
        assertTrue(ui.text.contains("3 сообщения"))
        assertTrue(ui.text.contains("в очереди"))
    }

    // --- LOSING / UNVALIDATED --------------------------------------------------------------------

    @Test
    fun `losing shows warning banner`() {
        val ui = policy.bannerFor(NetworkState.LOSING, pendingCount = 0)
        assertTrue(ui.visible)
        assertEquals(NetworkBannerUi.Tone.WARNING, ui.tone)
        assertTrue(ui.text.contains("нестабильно"))
    }

    @Test
    fun `losing with queue mentions counter`() {
        val ui = policy.bannerFor(NetworkState.LOSING, pendingCount = 2)
        assertTrue(ui.text.contains("2 сообщения"))
    }

    @Test
    fun `unvalidated shows info banner while checking connectivity`() {
        val ui = policy.bannerFor(NetworkState.UNVALIDATED, pendingCount = 0)
        assertTrue(ui.visible)
        assertEquals(NetworkBannerUi.Tone.INFO, ui.tone)
        assertTrue(ui.text.contains("проверяется"))
    }

    // --- ONLINE ----------------------------------------------------------------------------------

    @Test
    fun `online without queue hides banner`() {
        val ui = policy.bannerFor(NetworkState.ONLINE, pendingCount = 0)
        assertFalse(ui.visible)
        assertEquals(NetworkBannerUi.Hidden, ui)
    }

    @Test
    fun `online with pending queue shows recovery banner until flushed`() {
        val ui = policy.bannerFor(NetworkState.ONLINE, pendingCount = 5)
        assertTrue(ui.visible)
        assertEquals(NetworkBannerUi.Tone.OK, ui.tone)
        assertTrue(ui.text.contains("Связь восстановлена"))
        assertTrue(ui.text.contains("5 сообщений"))
    }

    // --- плюрализация -----------------------------------------------------------------------------

    @Test
    fun `russian plural forms are correct`() {
        assertEquals("1 сообщение", policy.messagesLabel(1))
        assertEquals("2 сообщения", policy.messagesLabel(2))
        assertEquals("4 сообщения", policy.messagesLabel(4))
        assertEquals("5 сообщений", policy.messagesLabel(5))
        assertEquals("11 сообщений", policy.messagesLabel(11))
        assertEquals("12 сообщений", policy.messagesLabel(12))
        assertEquals("14 сообщений", policy.messagesLabel(14))
        assertEquals("21 сообщение", policy.messagesLabel(21))
        assertEquals("22 сообщения", policy.messagesLabel(22))
        assertEquals("25 сообщений", policy.messagesLabel(25))
        assertEquals("100 сообщений", policy.messagesLabel(100))
        assertEquals("101 сообщение", policy.messagesLabel(101))
        assertEquals("111 сообщений", policy.messagesLabel(111))
    }
}
