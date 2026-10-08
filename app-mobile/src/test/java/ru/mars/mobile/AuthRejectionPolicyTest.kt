package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRejectionPolicyTest {
    @Test
    fun detectsHttp401FromChatStreamError() {
        // ChatRepository surfaces a non-2xx /api/chat/start as ChatEvent.Error("HTTP 401").
        assertTrue(AuthRejectionPolicy.isCredentialRejected("HTTP 401"))
    }

    @Test
    fun detectsServerUnauthorizedMessageFromSessionCreation() {
        // SessionRepository.createSession prefers the server `error` body over the status text.
        assertTrue(AuthRejectionPolicy.isCredentialRejected("unauthorized"))
        assertTrue(AuthRejectionPolicy.isCredentialRejected("Unauthorized"))
        assertTrue(AuthRejectionPolicy.isCredentialRejected("401: unauthorized"))
    }

    @Test
    fun detectsInvalidOrExpiredTokenMessages() {
        assertTrue(AuthRejectionPolicy.isCredentialRejected("invalid token"))
        assertTrue(AuthRejectionPolicy.isCredentialRejected("Token expired"))
    }

    @Test
    fun transportFailuresAreNotRejections() {
        // A flaky network must never trigger a forced logout.
        assertFalse(AuthRejectionPolicy.isCredentialRejected("Превышено время ожидания ответа сервера"))
        assertFalse(AuthRejectionPolicy.isCredentialRejected("Сервер недоступен: не удалось определить адрес"))
        assertFalse(AuthRejectionPolicy.isCredentialRejected("Соединение прервано (сеть переключилась или сервер закрыл сокет)"))
    }

    @Test
    fun otherHttpStatusesAreNotRejections() {
        assertFalse(AuthRejectionPolicy.isCredentialRejected("HTTP 404"))
        assertFalse(AuthRejectionPolicy.isCredentialRejected("HTTP 500"))
        assertFalse(AuthRejectionPolicy.isCredentialRejected("HTTP 403"))
        assertFalse(AuthRejectionPolicy.isCredentialRejected("Сессия не создана"))
    }

    @Test
    fun nullAndBlankMessagesAreNotRejections() {
        assertFalse(AuthRejectionPolicy.isCredentialRejected(null))
        assertFalse(AuthRejectionPolicy.isCredentialRejected(""))
        assertFalse(AuthRejectionPolicy.isCredentialRejected("   "))
    }

    @Test
    fun reauthNoticeIsUserFacingRussianText() {
        assertEquals("Авторизация отклонена сервером. Войдите снова.", AuthRejectionPolicy.REAUTH_NOTICE)
    }
}
