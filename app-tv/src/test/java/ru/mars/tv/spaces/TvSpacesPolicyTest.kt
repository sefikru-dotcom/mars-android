package ru.mars.tv.spaces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.WorkspaceItem
import ru.mars.network.model.WorkspacesResponse

/**
 * Unit-тесты [TvSpacesPolicy] без Android-фреймворка: read-only каталог
 * пространств (mobile `SpacesPolicy` parity в части просмотра) — порядок
 * сервера, бейдж «По умолчанию» из response-level `last`, честное пустое
 * состояние и восстановление фокуса.
 */
class TvSpacesPolicyTest {

    private val response = WorkspacesResponse(
        workspaces = listOf(
            WorkspaceItem(name = "Home", path = "/root/workspace"),
            WorkspaceItem(name = "Office", path = "/root/office"),
        ),
        last = "/root/office",
    )

    @Test
    fun `rows keep server order and badge the entry matching last`() {
        val rows = TvSpacesPolicy.rows(response)
        assertEquals(listOf("Home", "Office"), rows.map { it.name })
        assertEquals(listOf("/root/workspace", "/root/office"), rows.map { it.path })
        assertFalse(rows[0].defaultBadge)
        assertTrue(rows[1].defaultBadge)
    }

    @Test
    fun `no last means no default badge anywhere`() {
        val rows = TvSpacesPolicy.rows(response.copy(last = null))
        assertTrue(rows.none { it.defaultBadge })
    }

    @Test
    fun `blank name falls back to the path, blank path entries are dropped`() {
        val rows = TvSpacesPolicy.rows(
            WorkspacesResponse(
                workspaces = listOf(
                    WorkspaceItem(name = "", path = "/srv/data"),
                    WorkspaceItem(name = "Мусор", path = "  "),
                ),
            ),
        )
        assertEquals(1, rows.size)
        assertEquals("/srv/data", rows.single().name)
        assertEquals("/srv/data", rows.single().key)
    }

    @Test
    fun `summary counts only functional rows`() {
        assertEquals("Пространств: 2", TvSpacesPolicy.summaryLine(response))
        assertEquals(
            "Пространств: 0",
            TvSpacesPolicy.summaryLine(WorkspacesResponse(workspaces = listOf(WorkspaceItem("x", " ")))),
        )
    }

    @Test
    fun `empty catalog is data with its own line, not an error`() {
        assertEquals(TvSpacesPolicy.EMPTY_LIST, TvSpacesPolicy.emptyLine(WorkspacesResponse()))
        assertNull(TvSpacesPolicy.emptyLine(response))
    }

    @Test
    fun `focus restoration clamps and never wraps`() {
        assertEquals(TvSpacesPolicy.NO_SELECTION, TvSpacesPolicy.restoreSelection(2, 0))
        assertEquals(1, TvSpacesPolicy.restoreSelection(7, 2))
        assertEquals(0, TvSpacesPolicy.restoreSelection(-1, 2))
    }
}
