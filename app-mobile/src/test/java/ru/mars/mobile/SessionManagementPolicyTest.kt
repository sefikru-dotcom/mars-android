package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SessionItem

class SessionManagementPolicyTest {

    @Test
    fun menuOffersPinArchiveExportForPlainRow() {
        assertEquals(
            listOf(SessionActionKind.PIN, SessionActionKind.ARCHIVE, SessionActionKind.EXPORT),
            SessionManagementPolicy.menu(SessionItem("s1", "Работа")),
        )
    }

    @Test
    fun readOnlyRowGetsNoMenuEntries() {
        // read_only приходит от сервера для дочерних субагентов: сервер такие
        // мутации всё равно отклонит, поэтому меню их не предлагает.
        assertTrue(SessionManagementPolicy.menu(SessionItem("sub", "Субагент", readOnly = true)).isEmpty())
    }

    @Test
    fun pinLabelFlipsWithCurrentState() {
        assertEquals("Закрепить", SessionManagementPolicy.pinLabel(SessionItem("s1", "A")))
        assertEquals("Открепить", SessionManagementPolicy.pinLabel(SessionItem("s1", "A", pinned = true)))
    }

    @Test
    fun archiveLabelFlipsWithCurrentState() {
        assertEquals("В архив", SessionManagementPolicy.archiveLabel(SessionItem("s1", "A")))
        assertEquals(
            "Вернуть из архива",
            SessionManagementPolicy.archiveLabel(SessionItem("s1", "A", archived = true)),
        )
    }

    @Test
    fun togglesSendTheOppositeOfCurrentState() {
        // Пункт меню переключает состояние: тело запроса — не текущее значение.
        assertTrue(SessionManagementPolicy.nextPinned(SessionItem("s1", "A")))
        assertFalse(SessionManagementPolicy.nextPinned(SessionItem("s1", "A", pinned = true)))
        assertTrue(SessionManagementPolicy.nextArchived(SessionItem("s1", "A")))
        assertFalse(SessionManagementPolicy.nextArchived(SessionItem("s1", "A", archived = true)))
    }

    @Test
    fun archivedNoteExplainsArchivedRowOnly() {
        assertEquals("в архиве", SessionManagementPolicy.archivedNote(SessionItem("s1", "A", archived = true)))
        assertEquals(null, SessionManagementPolicy.archivedNote(SessionItem("s1", "A")))
    }

    @Test
    fun shareIsNeverOfferedAsAServerCall() {
        // POST /api/share/create на device-токене — 401 (серверный блокер), обходить
        // его нельзя; UI обязан сказать об этом, а не молча отказать.
        assertTrue(SessionManagementPolicy.SHARE_UNAVAILABLE_NOTE.contains("недоступна"))
        assertFalse(SessionManagementPolicy.menu(SessionItem("s1", "A")).any { it.name == "SHARE" })
    }

    @Test
    fun exportFileNameCarriesTitleSlugAndIdSuffix() {
        val name = SessionManagementPolicy.exportFileName(SessionItem("abcdef1234567890", "Бюджет на квартал"))
        assertTrue(name.endsWith(".md"))
        assertTrue(name.contains("abcdef12"))
    }
}
