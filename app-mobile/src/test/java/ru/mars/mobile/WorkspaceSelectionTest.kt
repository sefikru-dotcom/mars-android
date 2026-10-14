package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.mars.network.model.WorkspaceItem
import ru.mars.network.model.WorkspacesResponse

class WorkspaceSelectionTest {
    private class FakeStorage : WorkspaceChoiceStorage {
        var saved: WorkspaceItem? = null
        var saveCount = 0
        var clearCount = 0
        override fun load(): WorkspaceItem? = saved
        override fun save(item: WorkspaceItem) { saved = item; saveCount++ }
        override fun clear() { saved = null; clearCount++ }
    }

    /** Mirrors the captured GET /api/workspaces fixture: entries + active `last`. */
    private fun realCatalog() = WorkspacesResponse(
        workspaces = listOf(
            WorkspaceItem("Home", "/root/workspace"),
            WorkspaceItem("Квартал", "/opt/kvartal-git", isDefault = true),
            WorkspaceItem("hermes-webui", "/opt/hermes-webui"),
        ),
        last = "/opt/kvartal-git",
    )

    @Test
    fun noChoiceWithoutCatalogAndNoInventedPrefix() {
        val selection = WorkspaceSelection(FakeStorage())
        assertNull(selection.current)
        assertNull(selection.buttonLabel())
        // "server default" state: the message goes out untouched, never with a guessed path.
        assertEquals("привет", selection.outgoingMessage("привет"))
    }

    @Test
    fun adoptsServerDefaultFromCatalogWithoutPersisting() {
        val storage = FakeStorage()
        val selection = WorkspaceSelection(storage)
        assertEquals(WorkspaceItem("Квартал", "/opt/kvartal-git", isDefault = true), selection.resolveEffective(realCatalog()))
        assertEquals("Квартал", selection.buttonLabel())
        assertEquals(0, storage.saveCount) // adoption is ephemeral — `last` may change server-side
        assertEquals(
            "[Workspace::v1: /opt/kvartal-git]\nпривет",
            selection.outgoingMessage("привет"),
        )
    }

    @Test
    fun explicitPickWinsAndPersists() {
        val storage = FakeStorage()
        val selection = WorkspaceSelection(storage)
        val entries = selection.pickerEntries(realCatalog())
        assertEquals(3, entries.size)

        val picked = selection.select(entries[2])
        assertEquals(WorkspaceItem("hermes-webui", "/opt/hermes-webui"), picked)
        assertEquals(picked, storage.saved)
        assertEquals(
            "[Workspace::v1: /opt/hermes-webui]\nmake build",
            selection.outgoingMessage("make build"),
        )

        // Restored from prefs on the next launch — the pick survives a restart.
        val restored = WorkspaceSelection(storage)
        assertEquals(picked, restored.current)
        // A catalog reload re-bases the pick instead of overriding it with `last`.
        restored.resolveEffective(realCatalog())
        assertEquals("/opt/hermes-webui", restored.current?.path)
    }

    @Test
    fun autoRowFallsBackToServerDefaultAndKeepsStorageClean() {
        val storage = FakeStorage()
        val selection = WorkspaceSelection(storage)
        selection.select(WorkspaceItem("Home", "/root/workspace"))
        assertEquals(1, storage.saveCount)

        selection.pickerEntries(realCatalog())
        selection.select(null) // the "Авто" row
        assertEquals("/opt/kvartal-git", selection.current?.path)
        assertEquals(2, storage.saveCount) // the fallback default is persisted for the chip
        assertEquals(0, storage.clearCount)
    }

    @Test
    fun autoWithoutCatalogClearsPersistedPick() {
        val storage = FakeStorage().apply { saved = WorkspaceItem("Home", "/root/workspace") }
        val selection = WorkspaceSelection(storage)
        selection.select(null)
        assertNull(selection.current)
        assertNull(storage.saved)
        assertEquals(1, storage.clearCount)
        assertEquals("привет", selection.outgoingMessage("привет"))
    }

    @Test
    fun sentinelEscapesPathLikeServerRegexExpects() {
        val selection = WorkspaceSelection(FakeStorage())
        selection.select(WorkspaceItem("odd", "/opt/a]b\\c"))
        // Server strips `^\s*\[Workspace::v1:\s*(?:\\.|[^\]\\])+\]` — `\` → `\\`, `]` → `\]`.
        assertEquals("[Workspace::v1: /opt/a\\]b\\\\c]\nhi", selection.outgoingMessage("hi"))
    }

    @Test
    fun stalePickOutsideCatalogIsKeptForSending() {
        val storage = FakeStorage().apply { saved = WorkspaceItem("Gone", "/opt/removed") }
        val selection = WorkspaceSelection(storage)
        selection.resolveEffective(realCatalog())
        // The path is still valid server-side even when absent from the picker list.
        assertEquals("/opt/removed", selection.current?.path)
        assertEquals("[Workspace::v1: /opt/removed]\nx", selection.outgoingMessage("x"))
    }

    @Test
    fun reloadFromStorageAdoptsPickMadeOnAnotherScreen() {
        val storage = FakeStorage()
        val selection = WorkspaceSelection(storage)
        assertNull(selection.current)
        // Another screen (shared `mars-workspace-choice` storage) persists a pick
        // while ChatActivity's instance is already constructed.
        storage.saved = WorkspaceItem("hermes-webui", "/opt/hermes-webui")
        assertEquals(WorkspaceItem("hermes-webui", "/opt/hermes-webui"), selection.reloadFromStorage())
        assertEquals("hermes-webui", selection.buttonLabel())
        assertEquals(
            "[Workspace::v1: /opt/hermes-webui]\nhi",
            selection.outgoingMessage("hi"),
        )
    }

    @Test
    fun reloadFromStorageFallsBackToServerDefaultOfLastCatalog() {
        val storage = FakeStorage()
        val selection = WorkspaceSelection(storage)
        selection.resolveEffective(realCatalog()) // adopts server default ephemerally
        storage.saved = null // "Авто" on the Spaces screen cleared the explicit pick
        assertEquals("/opt/kvartal-git", selection.reloadFromStorage()?.path)
        assertEquals("Квартал", selection.buttonLabel())
    }

    @Test
    fun reloadFromStorageWithoutPickOrCatalogStaysNull() {
        val selection = WorkspaceSelection(FakeStorage())
        selection.resolveEffective(realCatalog())
        // No persisted pick and no catalog known — nothing to invent.
        val empty = WorkspaceSelection(FakeStorage())
        assertNull(empty.reloadFromStorage())
        assertEquals("привет", empty.outgoingMessage("привет"))
    }

    @Test
    fun tokenCountsFormatLikeWebUi() {
        assertEquals("0", formatTokenCount(0))
        assertEquals("0", formatTokenCount(-5))
        assertEquals("999", formatTokenCount(999))
        assertEquals("1.0k", formatTokenCount(1000))
        assertEquals("12.3k", formatTokenCount(12345))
        assertEquals("200.0k", formatTokenCount(200000))
        assertEquals("1.5M", formatTokenCount(1500000))
    }
}
