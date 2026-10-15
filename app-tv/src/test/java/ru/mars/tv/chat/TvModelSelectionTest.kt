package ru.mars.tv.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.mars.network.model.ModelInfo
import ru.mars.network.model.ModelsResponse

/**
 * Выбор модели на ТВ (чип «Модель»): payload для `POST /api/chat/start` из ЖИВОГО
 * каталога `GET /api/models` — никаких захардкоженных списков. Паритет мобильного
 * `ModelSelectionTest`.
 */
class TvModelSelectionTest {

    private class FakeStorage(initial: TvModelChoice? = null) : TvModelChoiceStorage {
        var saved: TvModelChoice? = initial
        var cleared = false
        override fun load(): TvModelChoice? = saved
        override fun save(choice: TvModelChoice) {
            saved = choice
            cleared = false
        }
        override fun clear() {
            saved = null
            cleared = true
        }
    }

    private val catalog = ModelsResponse(
        models = listOf(
            ModelInfo(id = "hermes-4", provider = "Nous Research", name = "Hermes 4", providerId = "nousresearch"),
            ModelInfo(id = "custom:openai:gpt-5.2", provider = "OpenAI", name = "GPT 5.2", providerId = "custom"),
        ),
        activeProvider = "nousresearch",
        defaultModel = "hermes-4",
    )

    private fun modelOf(id: String, providerId: String?, name: String = id) =
        ModelInfo(id = id, provider = name, name = name, providerId = providerId)

    // --- payload, который уходит в chat/start при выборе модели ------------------------------

    @Test
    fun `explicit picker choice is persisted and wired with provider and explicitPick`() {
        val storage = FakeStorage()
        val selection = TvModelSelection(storage)

        val picked = selection.select(catalog.models[1])

        assertEquals(TvModelChoice("custom:openai:gpt-5.2", "custom", explicitPick = true), picked)
        // persist: переживает пересоздание экрана
        assertEquals(picked, storage.saved)
        // payload chat/start: model + model_provider + explicit_model_pick=true
        val wire = selection.wireChoice()
        assertEquals("custom:openai:gpt-5.2", wire?.modelId)
        assertEquals("custom", wire?.providerId)
        assertTrue(wire?.explicitPick == true)
    }

    @Test
    fun `explicit choice immediately becomes the session model for the next turn`() {
        val selection = TvModelSelection(FakeStorage())
        selection.setSessionModel("hermes-4", "nousresearch")

        // Явный выбор перекрывает серверную модель сессии: следующий chat/start уходит с ним.
        selection.select(catalog.models[1])

        val wire = selection.wireChoice()
        assertEquals("custom:openai:gpt-5.2", wire?.modelId)
        assertTrue(wire?.explicitPick == true)
    }

    @Test
    fun `server session model outranks the stored local pick on the wire`() {
        val storage = FakeStorage(TvModelChoice("custom:openai:gpt-5.2", "custom", explicitPick = true))
        val selection = TvModelSelection(storage)

        selection.setSessionModel("hermes-4", "nousresearch")
        val wire = selection.wireChoice()

        assertEquals("hermes-4", wire?.modelId)
        assertEquals("nousresearch", wire?.providerId)
        assertFalse(wire?.explicitPick == true)
        // Локальный выбор не затёрт: после снятия серверной привязки он снова в силе.
        selection.clearSessionModel()
        assertEquals("custom:openai:gpt-5.2", selection.wireChoice()?.modelId)
        assertFalse(storage.cleared)
    }

    @Test
    fun `restored pick from prefs is explicit and wins over server default`() {
        val selection = TvModelSelection(FakeStorage(TvModelChoice("custom:openai:gpt-5.2", "custom", explicitPick = true)))

        val resolved = selection.resolveEffective(catalog)

        assertEquals(TvModelChoice("custom:openai:gpt-5.2", "custom", explicitPick = true), resolved)
    }

    // --- разрешение эффективной модели из каталога -------------------------------------------

    @Test
    fun `server default model is adopted with its catalog provider id and is not explicit`() {
        val selection = TvModelSelection(FakeStorage())

        val resolved = selection.resolveEffective(catalog)

        assertEquals(TvModelChoice("hermes-4", "nousresearch", explicitPick = false), resolved)
        assertEquals(resolved, selection.wireChoice())
    }

    @Test
    fun `default without a catalog row falls back to the active provider`() {
        val selection = TvModelSelection(FakeStorage())
        val unknownDefault = ModelsResponse(
            models = emptyList(),
            activeProvider = "nousresearch",
            defaultModel = "hermes-4",
        )

        val resolved = selection.resolveEffective(unknownDefault)

        assertEquals(TvModelChoice("hermes-4", "nousresearch", explicitPick = false), resolved)
    }

    @Test
    fun `no persisted pick and empty catalog - nothing is invented, chat start goes model-less`() {
        val selection = TvModelSelection(FakeStorage())

        assertNull(selection.resolveEffective(ModelsResponse()))
        // Null-выбор: chat/start уходит БЕЗ модельных полей, сервер маршрутизирует дефолтом.
        assertNull(selection.wireChoice())
        assertNull(selection.displayedChoice())
        assertNull(selection.buttonLabel())
    }

    @Test
    fun `session model with blank id is ignored`() {
        val selection = TvModelSelection(FakeStorage())

        selection.setSessionModel("   ", "nousresearch")

        assertNull(selection.wireChoice())
    }

    // --- строки пикера и подписи ---------------------------------------------------------------

    @Test
    fun `picker entries keep server order and label with name and provider`() {
        val selection = TvModelSelection(FakeStorage())

        val entries = selection.pickerEntries(catalog)

        assertEquals(listOf("hermes-4", "custom:openai:gpt-5.2"), entries.map { it.model.id })
        assertEquals(listOf("Hermes 4 · Nous Research", "GPT 5.2 · OpenAI"), entries.map { it.label })
    }

    @Test
    fun `picker label hides a duplicated provider and falls back to the id`() {
        val selection = TvModelSelection(FakeStorage())
        val response = ModelsResponse(
            models = listOf(
                modelOf("custom:openai:gpt-5.2", "custom", name = "OpenAI"),
                modelOf("custom:x", null, name = "  "),
            ),
        )

        val entries = selection.pickerEntries(response)

        assertEquals("OpenAI", entries[0].label)
        assertEquals("custom:x", entries[1].label)
    }

    @Test
    fun `chip label uses the catalog name, else the real id`() {
        val selection = TvModelSelection(FakeStorage())
        selection.setSessionModel("hermes-4", "nousresearch")
        selection.resolveEffective(catalog) // каталог известен -> имя из него
        assertEquals("Hermes 4", selection.buttonLabel())

        // Неизвестная серверная модель: подпись — её честный id, не «Неизвестная модель».
        selection.setSessionModel("custom:openai:gpt-5.2-mini", "custom")
        assertEquals("custom:openai:gpt-5.2-mini", selection.buttonLabel())
    }

    @Test
    fun `blank model ids are rejected by select`() {
        val storage = FakeStorage()
        val selection = TvModelSelection(storage)

        assertNull(selection.select(modelOf("  ", "custom")))
        assertNull(storage.saved)
        assertNull(selection.wireChoice())
    }
}
