package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ModelGroup
import ru.mars.network.model.ModelInfo
import ru.mars.network.model.ModelsResponse

class ModelSelectionTest {
    private class FakeStorage : ModelChoiceStorage {
        var saved: ModelChoice? = null
        var saveCount = 0
        override fun load(): ModelChoice? = saved
        override fun save(choice: ModelChoice) { saved = choice; saveCount++ }
        override fun clear() { saved = null }
    }

    /** Mirrors the real captured GET /api/models fixture (fixtures/api_models.json). */
    private fun realCatalog() = ModelsResponse(
        activeProvider = "custom:vertex-gemini",
        defaultModel = "gemini-3.8-flash",
        groups = listOf(
            ModelGroup(
                provider = "Vertex Gemini · Талант",
                providerId = "custom:vertex-gemini",
                models = listOf(ModelInfo("gemini-3.8-flash", "gemini-3.8-flash", "Vertex Gemini · Талант", "custom:vertex-gemini")),
                extraModels = listOf(ModelInfo("gemini-3-flash-preview", null, "Vertex Gemini · Талант", "custom:vertex-gemini"))
            ),
            ModelGroup(
                provider = "PlusVibe API",
                providerId = "custom:plusvibe-api",
                models = listOf(ModelInfo("@custom:plusvibe-api:gpt-6.1-sol", "GPT 6.1 SOL", "PlusVibe API", "custom:plusvibe-api"))
            )
        ),
        models = listOf(
            ModelInfo("gemini-3.8-flash", "gemini-3.8-flash", "Vertex Gemini · Талант", "custom:vertex-gemini"),
            ModelInfo("gemini-3-flash-preview", null, "Vertex Gemini · Талант", "custom:vertex-gemini"),
            ModelInfo("@custom:plusvibe-api:gpt-6.1-sol", "GPT 6.1 SOL", "PlusVibe API", "custom:plusvibe-api")
        )
    )

    @Test
    fun noChoiceWithoutCatalogAndNoInventedFallback() {
        val selection = ModelSelection(FakeStorage())
        assertNull(selection.current)
        assertNull(selection.resolveEffective(ModelsResponse()))
        assertNull(selection.current)
        assertNull(selection.buttonLabel())
        assertTrue(selection.pickerEntries(ModelsResponse()).isEmpty())
    }

    @Test
    fun adoptsServerDefaultModelWithCatalogProvider() {
        val selection = ModelSelection(FakeStorage())
        val choice = selection.resolveEffective(realCatalog())
        assertEquals(ModelChoice("gemini-3.8-flash", "custom:vertex-gemini", explicitPick = false), choice)
        assertEquals(choice, selection.current)
        assertEquals("gemini-3.8-flash", selection.buttonLabel())
    }

    @Test
    fun defaultWithoutCatalogRowFallsBackToActiveProviderOnly() {
        val catalog = ModelsResponse(
            activeProvider = "custom:vertex-gemini",
            defaultModel = "gemini-3.8-flash",
            models = emptyList()
        )
        val selection = ModelSelection(FakeStorage())
        assertEquals(ModelChoice("gemini-3.8-flash", "custom:vertex-gemini", explicitPick = false), selection.resolveEffective(catalog))
    }

    @Test
    fun selectReturnsRealIdAndProviderAndPersists() {
        val storage = FakeStorage()
        val selection = ModelSelection(storage)
        val picked = selection.select(ModelInfo("@custom:plusvibe-api:gpt-6.1-sol", "GPT 6.1 SOL", "PlusVibe API", "custom:plusvibe-api"))
        assertEquals(ModelChoice("@custom:plusvibe-api:gpt-6.1-sol", "custom:plusvibe-api", explicitPick = true), picked)
        assertEquals(picked, storage.saved)
        assertEquals(1, storage.saveCount)
        // Without a loaded catalog the label falls back to the real model id …
        assertEquals("@custom:plusvibe-api:gpt-6.1-sol", selection.buttonLabel())
        // … and once the catalog is known it shows the server display name.
        selection.pickerEntries(realCatalog())
        assertEquals("GPT 6.1 SOL", selection.buttonLabel())
    }

    @Test
    fun serverSessionModelOverridesStaleLocalChoiceForDisplay() {
        val selection = ModelSelection(FakeStorage())
        selection.select(ModelInfo("gemini-3.8-flash", "Gemini", "Vertex", "custom:vertex"))
        selection.pickerEntries(realCatalog())
        selection.setSessionModel("@custom:plusvibe-api:gpt-6.1-sol", "custom:plusvibe-api")
        assertEquals("GPT 6.1 SOL", selection.buttonLabel())
        assertEquals("@custom:plusvibe-api:gpt-6.1-sol", selection.displayedChoice()?.modelId)
    }
    @Test
    fun selectRejectsBlankModelId() {
        val storage = FakeStorage()
        val selection = ModelSelection(storage)
        assertNull(selection.select(ModelInfo("  ", null, null, null)))
        assertNull(selection.current)
        assertEquals(0, storage.saveCount)
    }

    @Test
    fun persistedPickSurvivesReloadAndWinsOverServerDefault() {
        val storage = FakeStorage()
        ModelSelection(storage).select(ModelInfo("@custom:plusvibe-api:gpt-6.1-sol", "GPT 6.1 SOL", "PlusVibe API", "custom:plusvibe-api"))

        val restored = ModelSelection(storage)
        assertEquals(ModelChoice("@custom:plusvibe-api:gpt-6.1-sol", "custom:plusvibe-api", explicitPick = true), restored.current)
        // A persisted user pick is never overwritten by the server default.
        assertEquals(restored.current, restored.resolveEffective(realCatalog()))
        assertEquals("GPT 6.1 SOL", restored.buttonLabel())
    }

    @Test
    fun pickerEntriesKeepServerOrderAndLabelWithProvider() {
        val selection = ModelSelection(FakeStorage())
        val entries = selection.pickerEntries(realCatalog())
        assertEquals(
            listOf("gemini-3.8-flash", "gemini-3-flash-preview", "@custom:plusvibe-api:gpt-6.1-sol"),
            entries.map { it.model.id }
        )
        assertEquals("gemini-3.8-flash · Vertex Gemini · Талант", entries[0].label)
        // Rows without a display label fall back to the real model id.
        assertEquals("gemini-3-flash-preview · Vertex Gemini · Талант", entries[1].label)
        assertEquals("GPT 6.1 SOL · PlusVibe API", entries[2].label)
    }

    @Test
    fun buttonLabelFallsBackToModelIdWithoutCatalogName() {
        val storage = FakeStorage()
        storage.saved = ModelChoice("some-model", null, explicitPick = true)
        val selection = ModelSelection(storage)
        assertEquals("some-model", selection.buttonLabel())
        assertFalse(selection.buttonLabel()!!.isEmpty())
    }

    @Test
    fun wireChoiceUsesServerSessionModelWhenPresent() {
        val selection = ModelSelection(FakeStorage())
        selection.select(ModelInfo("gemini-3.8-flash", "Gemini", "Vertex", "custom:vertex-gemini"))
        selection.setSessionModel("@custom:plusvibe-api:gpt-6.1-sol", "custom:plusvibe-api")
        assertEquals(
            ModelChoice("@custom:plusvibe-api:gpt-6.1-sol", "custom:plusvibe-api", explicitPick = false),
            selection.wireChoice()
        )
        // Display and wire resolve through the SAME rule — no diverging copies.
        assertEquals(selection.wireChoice(), selection.displayedChoice())
    }

    @Test
    fun wireChoiceFallsBackToCurrentWithoutSessionModel() {
        val selection = ModelSelection(FakeStorage())
        val picked = selection.select(ModelInfo("gemini-3.8-flash", "Gemini", "Vertex", "custom:vertex-gemini"))
        assertEquals(picked, selection.wireChoice())
    }

    @Test
    fun wireChoiceNullWithoutAnyChoice() {
        // No catalog, no persisted pick, no session model → null: chat/start is
        // sent WITHOUT model fields so the server routes with its own default.
        assertNull(ModelSelection(FakeStorage()).wireChoice())
    }

    @Test
    fun clearSessionModelRestoresCurrentForWire() {
        val selection = ModelSelection(FakeStorage())
        val picked = selection.select(ModelInfo("gemini-3.8-flash", "Gemini", "Vertex", "custom:vertex-gemini"))
        selection.setSessionModel("@custom:plusvibe-api:gpt-6.1-sol", "custom:plusvibe-api")
        selection.clearSessionModel()
        assertEquals(picked, selection.wireChoice())
    }

    @Test
    fun blankSessionModelIsIgnoredForWire() {
        val selection = ModelSelection(FakeStorage())
        val picked = selection.select(ModelInfo("gemini-3.8-flash", "Gemini", "Vertex", "custom:vertex-gemini"))
        selection.setSessionModel("   ", "custom:vertex-gemini")
        assertEquals(picked, selection.wireChoice())
    }
}
