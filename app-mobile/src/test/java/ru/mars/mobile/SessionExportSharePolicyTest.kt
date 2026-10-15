package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SessionItem

class SessionExportSharePolicyTest {

    @Test
    fun subjectFallsBackWhenSessionHasNoTitle() {
        assertEquals("Диалог Марса", SessionExportSharePolicy.subject(null))
        assertEquals("Диалог Марса", SessionExportSharePolicy.subject(SessionItem("s1", "   ")))
        assertEquals("Бюджет", SessionExportSharePolicy.subject(SessionItem("s1", "Бюджет")))
    }

    @Test
    fun authorityIsApplicationIdScoped() {
        // authority объявлен в манифесте как ${applicationId}.sessionexports —
        // FileProvider.getUriForFile обязан получить ровно его.
        assertEquals("ru.mars.mobile.sessionexports", SessionExportSharePolicy.authority("ru.mars.mobile"))
    }

    @Test
    fun exportLandsInItsOwnCacheSubdirectory() {
        // Отдельный подкаталог кэша: file_paths.xml отдаёт наружу только его,
        // остальной кэш приложения шарить нельзя.
        assertEquals("session-exports", SessionExportSharePolicy.EXPORT_DIR)
    }

    @Test
    fun mimeTypeIsMarkdown() {
        assertEquals("text/markdown", SessionExportSharePolicy.MIME_TYPE)
    }

    @Test
    fun failureMessageNamesTheReason() {
        assertTrue(SessionExportSharePolicy.failureMessage("HTTP 500").contains("HTTP 500"))
        assertTrue(SessionExportSharePolicy.failureMessage(null).startsWith("Не удалось"))
    }

    @Test
    fun successMessageTellsTheUserWhatHappensNext() {
        assertTrue(SessionExportSharePolicy.successMessage().isNotBlank())
    }
}
