package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.repository.SessionShareLink

/**
 * Публичная share-ссылка (долг O4): адрес для буфера обмена и тексты состояний.
 * Серверные ручки уже разрешены device-токену, поэтому «блокер 401» здесь не
 * проверяется — политика покрывает только представление.
 */
class SessionSharePolicyTest {

    private val link = SessionShareLink(
        token = "abc123",
        path = "/share/abc123",
        title = "Бюджет",
        messageCount = 12,
    )

    @Test
    fun relativeSharePathBecomesAbsoluteForClipboard() {
        assertEquals(
            "https://mars.example:8443/share/abc123",
            SessionSharePolicy.clipboardText("https://mars.example:8443/", link),
        )
    }

    @Test
    fun absolutePathFromServerIsKeptAsIs() {
        assertEquals(
            "https://other.example/share/abc123",
            SessionSharePolicy.absoluteUrl("https://mars.example", "https://other.example/share/abc123"),
        )
    }

    @Test
    fun pathWithoutLeadingSlashStillJoins() {
        assertEquals(
            "https://mars.example/share/abc123",
            SessionSharePolicy.absoluteUrl("https://mars.example", "share/abc123"),
        )
    }

    @Test
    fun dialogMessageNamesSnapshotAndMessageCount() {
        val message = SessionSharePolicy.dialogMessage(link)
        assertTrue(message.contains("12"))
        assertTrue(message.contains("снимок"))
        // Ссылка не синхронна с диалогом — это должно быть сказано прямо.
        assertTrue(message.contains("на момент создания"))
    }

    @Test
    fun dialogMessageWithoutMessagesOmitsCount() {
        val message = SessionSharePolicy.dialogMessage(link.copy(messageCount = 0))
        assertFalse(message.contains("снимке сообщений"))
        assertTrue(message.contains("снимок"))
    }

    @Test
    fun failureMessageCarriesServerReason() {
        assertEquals(
            "Не удалось создать публичную ссылку: too many shares",
            SessionSharePolicy.failureMessage("too many shares"),
        )
        assertEquals(
            "Не удалось создать публичную ссылку",
            SessionSharePolicy.failureMessage(null),
        )
    }

    @Test
    fun revokeWithoutLinkReadsAsNoLinkNotAsServerError() {
        // Сервер отвечает 404 на диалог без ссылки: пользователю нужен не
        // «Session not found», а понятное «ссылки нет».
        assertEquals(
            "Публичной ссылки у диалога нет",
            SessionSharePolicy.revokeFailureMessage("404 Session not found"),
        )
        assertEquals(
            "Публичной ссылки у диалога нет",
            SessionSharePolicy.revokeFailureMessage(null),
        )
        assertTrue(
            SessionSharePolicy.revokeFailureMessage("500 boom").contains("500 boom"),
        )
    }
}
