package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.SessionItem

class SessionManagementPolicyTest {

    @Test
    fun menuOffersPinArchiveExportShareForPlainRow() {
        assertEquals(
            listOf(
                SessionActionKind.PIN,
                SessionActionKind.ARCHIVE,
                SessionActionKind.EXPORT,
                SessionActionKind.SHARE,
            ),
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
    fun shareIsOfferedThroughServerAllowlistedRoute() {
        // Блокер снят: /api/share/create и /api/share/revoke добавлены в
        // NATIVE_BEARER_EXACT_PATHS (hermes-webui/api/auth.py), device-токен их
        // принимает. Значит публичная ссылка — штатная функция меню, а не отказ.
        assertTrue(SessionManagementPolicy.menu(SessionItem("s1", "A")).contains(SessionActionKind.SHARE))
        assertEquals("Поделиться ссылкой", SessionManagementPolicy.shareLabel())
        assertFalse(SessionManagementPolicy.shareLabel().contains("недоступ"))
    }

    @Test
    fun shareMenuEntryStaysOutOfReadOnlyRows() {
        // read_only (дочерние субагенты) не получает ни одной мутации — включая share.
        assertFalse(
            SessionManagementPolicy.menu(SessionItem("sub", "Субагент", readOnly = true))
                .contains(SessionActionKind.SHARE)
        )
    }

    @Test
    fun exportFileNameCarriesTitleSlugAndIdSuffix() {
        val name = SessionManagementPolicy.exportFileName(SessionItem("abcdef1234567890", "Бюджет на квартал"))
        assertTrue(name.endsWith(".md"))
        assertTrue(name.contains("abcdef12"))
    }
}
