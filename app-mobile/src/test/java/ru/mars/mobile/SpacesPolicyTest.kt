package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.WorkspaceItem
import ru.mars.network.model.WorkspacesResponse

/**
 * Чистая политика экрана «Пространства»: строки каталога, метки выбора,
 * offline-safe строка и тексты состояний. Без Android и без сети — проверяется
 * только разбор данных.
 */
class SpacesPolicyTest {

    /** Фикстура формата GET /api/workspaces (как в [WorkspaceSelectionTest]). */
    private fun catalog() = WorkspacesResponse(
        workspaces = listOf(
            WorkspaceItem("Home", "/root/workspace"),
            WorkspaceItem("Квартал", "/opt/kvartal-git"),
            WorkspaceItem("hermes-webui", "/opt/hermes-webui"),
        ),
        last = "/opt/kvartal-git",
    )

    @Test
    fun `path label trims trailing slash except root`() {
        assertEquals("/opt/kvartal-git", SpacesPolicy.pathLabel("/opt/kvartal-git/"))
        assertEquals("/root/workspace", SpacesPolicy.pathLabel("/root/workspace"))
        assertEquals("/", SpacesPolicy.pathLabel("/"))
        assertEquals("", SpacesPolicy.pathLabel(""))
        assertEquals("/opt//a", SpacesPolicy.pathLabel("/opt//a"))
    }

    @Test
    fun `auto row is active only without an explicit pick`() {
        assertTrue(SpacesPolicy.autoRow(explicitPickPath = null).isActive)
        assertTrue(SpacesPolicy.autoRow(explicitPickPath = "").isActive)
        assertFalse(SpacesPolicy.autoRow(explicitPickPath = "/opt/hermes-webui").isActive)
        assertEquals(SpacesPolicy.AUTO_KEY, SpacesPolicy.autoRow(null).key)
        assertTrue(SpacesPolicy.autoRow(null).isAuto)
        // «Авто» — режим, а не пространство: бейджа «По умолчанию» у неё нет.
        assertFalse(SpacesPolicy.autoRow(null).isServerDefault)
    }

    @Test
    fun `rows keep server order and mark pick and server default independently`() {
        val rows = SpacesPolicy.rows(catalog(), explicitPickPath = "/opt/hermes-webui")
        assertEquals(
            listOf("/root/workspace", "/opt/kvartal-git", "/opt/hermes-webui"),
            rows.map { it.key },
        )
        assertEquals(listOf(false, false, true), rows.map { it.isActive })
        // Серверный `last` помечен независимо от пользовательского пика.
        assertEquals(listOf(false, true, false), rows.map { it.isServerDefault })
        assertEquals("Квартал", rows[1].title)
        assertEquals("/opt/kvartal-git", rows[1].subtitle)
        assertFalse(rows.any { it.isAuto })
    }

    @Test
    fun `rows without pick have no active item`() {
        val rows = SpacesPolicy.rows(catalog(), explicitPickPath = null)
        assertFalse(rows.any { it.isActive })
        assertTrue(rows[1].isServerDefault)
    }

    @Test
    fun `rows drop blank paths and duplicate paths`() {
        // Строка без пути нефункциональна (выбрать нечего), а дубль пути ломает
        // уникальность ключа клика: второй такой записи на экране быть не должно.
        val response = WorkspacesResponse(
            workspaces = listOf(
                WorkspaceItem("Пусто", "   "),
                WorkspaceItem("Home", "/root/workspace"),
                WorkspaceItem("Home ещё раз", "/root/workspace"),
            ),
            last = "/root/workspace",
        )
        val rows = SpacesPolicy.rows(response, null)
        assertEquals(listOf("/root/workspace"), rows.map { it.key })
        assertEquals("Home", rows.single().title)
        assertTrue(rows.single().isServerDefault)
    }

    @Test
    fun `blank name falls back to the path label`() {
        val response = WorkspacesResponse(
            workspaces = listOf(WorkspaceItem(name = "", path = "/srv/data/")),
            last = null,
        )
        val row = SpacesPolicy.rows(response, null).single()
        assertEquals("/srv/data", row.title)
        assertEquals("/srv/data", row.subtitle)
        assertFalse(row.isServerDefault)
    }

    @Test
    fun `local row is always the active offline fallback`() {
        val row = SpacesPolicy.localRow(WorkspaceItem("Home", "/root/workspace", isDefault = true))
        assertTrue(row.isActive)
        assertEquals("/root/workspace", row.key)
        assertEquals("Home", row.title)
        assertFalse(row.isAuto)
        assertTrue(row.isServerDefault)
    }

    @Test
    fun `badges follow the active and default flags`() {
        val both = SpaceRow("k", "t", "s", isActive = true, isServerDefault = true, isAuto = false)
        assertEquals(listOf(SpacesPolicy.ACTIVE_BADGE, SpacesPolicy.DEFAULT_BADGE), both.badges)

        val activeOnly = both.copy(isServerDefault = false)
        assertEquals(listOf(SpacesPolicy.ACTIVE_BADGE), activeOnly.badges)

        val defaultOnly = both.copy(isActive = false)
        assertEquals(listOf(SpacesPolicy.DEFAULT_BADGE), defaultOnly.badges)

        assertEquals(emptyList<String>(), both.copy(isActive = false, isServerDefault = false).badges)
    }

    @Test
    fun `summary line counts rendered catalog rows`() {
        assertEquals("Пространств: 3", SpacesPolicy.summaryLine(catalog()))
        assertEquals(
            "Пространств: 0",
            SpacesPolicy.summaryLine(WorkspacesResponse(workspaces = emptyList(), last = null)),
        )
    }

    @Test
    fun `empty line is null only when there is something to show`() {
        assertNull(SpacesPolicy.emptyLine(catalog()))
        assertEquals(
            SpacesPolicy.MSG_EMPTY,
            SpacesPolicy.emptyLine(WorkspacesResponse(workspaces = emptyList(), last = null)),
        )
        // Каталог из одних пустых путей — тоже пустое состояние, а не список.
        assertEquals(
            SpacesPolicy.MSG_EMPTY,
            SpacesPolicy.emptyLine(
                WorkspacesResponse(workspaces = listOf(WorkspaceItem("x", "")), last = null)
            ),
        )
    }

    @Test
    fun `cache is fresh up to the 24 hour limit`() {
        assertTrue(SpacesPolicy.isCacheFresh(0))
        assertTrue(SpacesPolicy.isCacheFresh(SpacesPolicy.CACHE_MAX_AGE_MS))
        assertFalse(SpacesPolicy.isCacheFresh(SpacesPolicy.CACHE_MAX_AGE_MS + 1))
        // Сбитые назад часы — не устаревание: кэш не хуже свежего.
        assertTrue(SpacesPolicy.isCacheFresh(-60_000))
    }
}
