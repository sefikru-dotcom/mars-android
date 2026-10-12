package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.WorkspaceItem
import ru.mars.network.model.WorkspacesResponse

class SpacesPolicyTest {

    /** Фикстура формата GET /api/workspaces (как в WorkspaceSelectionTest). */
    private fun catalog() = WorkspacesResponse(
        workspaces = listOf(
            WorkspaceItem("Home", "/root/workspace"),
            WorkspaceItem("Квартал", "/opt/kvartal-git"),
            WorkspaceItem("hermes-webui", "/opt/hermes-webui"),
        ),
        last = "/opt/kvartal-git",
    )

    @Test
    fun pathLabelTrimsTrailingSlashExceptRoot() {
        assertEquals("/opt/kvartal-git", SpacesPolicy.pathLabel("/opt/kvartal-git/"))
        assertEquals("/root/workspace", SpacesPolicy.pathLabel("/root/workspace"))
        assertEquals("/", SpacesPolicy.pathLabel("/"))
        assertEquals("", SpacesPolicy.pathLabel(""))
    }

    @Test
    fun autoRowActiveOnlyWithoutExplicitPick() {
        assertTrue(SpacesPolicy.autoRow(explicitPickPath = null).isActive)
        assertTrue(SpacesPolicy.autoRow(explicitPickPath = "").isActive)
        assertFalse(SpacesPolicy.autoRow(explicitPickPath = "/opt/hermes-webui").isActive)
        assertEquals(SpacesPolicy.AUTO_KEY, SpacesPolicy.autoRow(null).key)
        assertTrue(SpacesPolicy.autoRow(null).isAuto)
    }

    @Test
    fun rowsMarkExplicitPickActiveAndServerDefaultFlagged() {
        val rows = SpacesPolicy.rows(catalog(), explicitPickPath = "/opt/hermes-webui")
        assertEquals(listOf("/root/workspace", "/opt/kvartal-git", "/opt/hermes-webui"), rows.map { it.key })
        assertEquals(listOf(false, false, true), rows.map { it.isActive })
        // Серверный `last` помечен независимо от пользовательского пика.
        assertEquals(listOf(false, true, false), rows.map { it.isServerDefault })
        assertEquals("Квартал", rows[1].title)
        assertEquals("/opt/kvartal-git", rows[1].subtitle)
        assertFalse(rows.any { it.isAuto })
    }

    @Test
    fun rowsWithoutPickHaveNoActiveItem() {
        val rows = SpacesPolicy.rows(catalog(), explicitPickPath = null)
        assertFalse(rows.any { it.isActive })
        assertTrue(rows[1].isServerDefault)
    }

    @Test
    fun blankNameFallsBackToPathLabel() {
        val response = WorkspacesResponse(
            workspaces = listOf(WorkspaceItem(name = "", path = "/srv/data/")),
            last = null,
        )
        val row = SpacesPolicy.rows(response, null).single()
        assertEquals("/srv/data", row.title)
        assertEquals("/srv/data", row.subtitle)
    }

    @Test
    fun localRowIsAlwaysActiveOfflineFallback() {
        val row = SpacesPolicy.localRow(WorkspaceItem("Home", "/root/workspace"))
        assertTrue(row.isActive)
        assertEquals("/root/workspace", row.key)
        assertEquals("Home", row.title)
        assertFalse(row.isAuto)
    }
}
