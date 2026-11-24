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
    fun explicitPickWinsOverServerSessionModelForDisplay() {
        val selection = ModelSelection(FakeStorage())
        selection.select(ModelInfo("gemini-3.8-flash", "Gemini", "Vertex", "custom:vertex-gemini"))
        selection.pickerEntries(realCatalog())
        // Сервер сообщил модель сессии — но человек выбрал сам, и чип показывает его выбор:
        // ровно эта модель и уйдёт в chat/start.
        selection.setSessionModel("@custom:plusvibe-api:gpt-6.1-sol", "custom:plusvibe-api")
        assertEquals("gemini-3.8-flash", selection.buttonLabel())
        assertEquals("gemini-3.8-flash", selection.displayedChoice()?.modelId)
        assertTrue(selection.displayedChoice()?.explicitPick == true)
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
    fun wireChoiceUsesServerSessionModelWhenNoExplicitPick() {
        // Принятый серверный дефолт — НЕ пользовательский выбор: модель сессии важнее.
        val selection = ModelSelection(FakeStorage())
        selection.resolveEffective(realCatalog())
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

    // --- явный выбор пользователя переживает серверные ответы --------------------------------
    //
    // Владельческий баг: «в чате с Марсом не меняется модель при ручной смене, отвечает
    // всегда haiku 5.5». Сервер `_resolve_chat_model` уважает чужое семейство только при
    // `explicit_model_pick=true`, иначе откатывает на дефолт профиля. Флаг терялся, потому
    // что `setSessionModel()` (деталь сессии, первый ход нового чата, reload) перезаписывал
    // единственный источник выбора серверным значением с explicitPick=false.

    private fun gptSol() = ModelInfo(
        "@custom:plusvibe-api:gpt-6.1-sol", "GPT 6.1 SOL", "PlusVibe API", "custom:plusvibe-api"
    )

    private fun haiku() = ModelInfo("claude-haiku-5.5", "Claude Haiku 5.5", "Anthropic", "custom:anthropic")

    @Test
    fun explicitPickSurvivesServerSessionModelAndKeepsTheFlag() {
        val selection = ModelSelection(FakeStorage())
        selection.select(gptSol())
        // Сервер сообщил СВОЮ модель сессии (в том числе откатанную) — выбор не затирается.
        selection.setSessionModel("claude-haiku-5.5", "custom:anthropic")
        assertEquals(
            ModelChoice("@custom:plusvibe-api:gpt-6.1-sol", "custom:plusvibe-api", explicitPick = true),
            selection.wireChoice()
        )
        // Именно этот признак превращается в explicit_model_pick=true в теле chat/start.
        assertTrue(selection.wireChoice()?.explicitPick == true)
    }

    @Test
    fun repeatedServerAnswersNeverClearTheExplicitPick() {
        // setSessionModel зовут и первый ход нового чата, и каждая загрузка детали сессии.
        val selection = ModelSelection(FakeStorage())
        selection.select(gptSol())
        repeat(3) { selection.setSessionModel("claude-haiku-5.5", "custom:anthropic") }
        // Сервер вообще не назвал модель — это тоже не повод сбрасывать выбор.
        selection.setSessionModel(null, null)
        assertEquals(
            ModelChoice("@custom:plusvibe-api:gpt-6.1-sol", "custom:plusvibe-api", explicitPick = true),
            selection.wireChoice()
        )
    }

    @Test
    fun clearExplicitFallsBackToServerSessionModelWithoutFlag() {
        val storage = FakeStorage()
        val selection = ModelSelection(storage)
        selection.select(gptSol())
        selection.setSessionModel("claude-haiku-5.5", "custom:anthropic")
        selection.clearExplicit()
        assertEquals(
            ModelChoice("claude-haiku-5.5", "custom:anthropic", explicitPick = false),
            selection.wireChoice()
        )
        // Снятый выбор не возвращается и после перезапуска.
        assertNull(storage.saved)
    }

    @Test
    fun clearExplicitWithoutSessionKeepsTheModelButDropsTheFlag() {
        val selection = ModelSelection(FakeStorage())
        selection.select(gptSol())
        selection.clearExplicit()
        val wire = selection.wireChoice()
        assertEquals("@custom:plusvibe-api:gpt-6.1-sol", wire?.modelId)
        assertFalse(wire?.explicitPick == true)
    }

    @Test
    fun restoredPickIsExplicitAndSurvivesSessionBind() {
        val storage = FakeStorage()
        ModelSelection(storage).select(gptSol())

        // Перезапуск приложения: выбор восстановлен из prefs и снова явный.
        val restored = ModelSelection(storage)
        restored.setSessionModel("claude-haiku-5.5", "custom:anthropic")
        assertEquals(
            ModelChoice("@custom:plusvibe-api:gpt-6.1-sol", "custom:plusvibe-api", explicitPick = true),
            restored.wireChoice()
        )
    }

    @Test
    fun newChatKeepsTheExplicitPick() {
        val selection = ModelSelection(FakeStorage())
        selection.select(gptSol())
        selection.setSessionModel("claude-haiku-5.5", "custom:anthropic")
        selection.clearSessionModel() // «Новый чат»: серверной модели сессии больше нет.
        assertEquals(
            ModelChoice("@custom:plusvibe-api:gpt-6.1-sol", "custom:plusvibe-api", explicitPick = true),
            selection.wireChoice()
        )
    }

    @Test
    fun selectingAnotherModelReplacesTheExplicitPick() {
        val selection = ModelSelection(FakeStorage())
        selection.select(gptSol())
        selection.setSessionModel("claude-haiku-5.5", "custom:anthropic")
        selection.select(haiku())
        assertEquals(
            ModelChoice("claude-haiku-5.5", "custom:anthropic", explicitPick = true),
            selection.wireChoice()
        )
    }

    // --- Открытие существующего диалога и сопоставление моделей WebUI ------------------------
    //
    // Владельческий баг: WebUI работал в чате на `claude-sonnet-5.5`, а телефон при открытии
    // этого же чата показывал старый выбор из SharedPreferences, потому что
    // `setSessionModel()` не снимал `userPick`, а `buttonLabel()`/пикер искали строку
    // каталога строгим `id ==` (в каталоге модель записана как
    // `@custom:plusvibe-api:claude-sonnet-5.5`).

    /** Каталог как в GET /api/models: id custom-модели в форме `@provider:model`. */
    private fun webUiCatalog() = ModelsResponse(
        activeProvider = "custom:plusvibe-api",
        defaultModel = "@custom:plusvibe-api:gpt-6.1-sol",
        models = listOf(
            ModelInfo("@custom:plusvibe-api:gpt-6.1-sol", "GPT 6.1 SOL", "PlusVibe API", "custom:plusvibe-api"),
            ModelInfo("@custom:plusvibe-api:claude-sonnet-5.5", "Claude Sonnet 5.5", "PlusVibe API", "custom:plusvibe-api"),
            ModelInfo("claude-haiku-5.5", "Claude Haiku 5.5", "Anthropic", "custom:anthropic"),
        ),
    )

    @Test
    fun bindOpenedSessionModelDropsStaleGlobalPickAndShowsSessionModel() {
        val storage = FakeStorage()
        val selection = ModelSelection(storage)
        // Залипший глобальный выбор (например, восстановленный из prefs трёхдневной давности).
        selection.select(gptSol())
        selection.pickerEntries(webUiCatalog())
        assertEquals("GPT 6.1 SOL", selection.buttonLabel())

        // Открыли чат, в котором в WebUI работали на claude-sonnet-5.5 (сервер отдал bare id).
        selection.bindOpenedSessionModel("claude-sonnet-5.5", "custom:plusvibe-api")

        // 1) Показывается модель открытого чата — человеческим именем из каталога.
        assertEquals("Claude Sonnet 5.5", selection.buttonLabel())
        // 2) В chat/start уходит канонический id каталога и серверный провайдер, без флага.
        assertEquals(
            ModelChoice("@custom:plusvibe-api:claude-sonnet-5.5", "custom:plusvibe-api", explicitPick = false),
            selection.wireChoice()
        )
        // 3) Старый глобальный выбор снят и из персистентности.
        assertNull(storage.saved)
        assertNull(storage.load())
    }

    @Test
    fun bindOpenedSessionModelKeepsServerIdWhenCatalogIsUnknown() {
        val selection = ModelSelection(FakeStorage())
        selection.bindOpenedSessionModel("anthropic/claude-sonnet-5.5", "custom:plusvibe-api")
        // Без каталога канонизировать нечем — уходит ровно серверный id (никаких догадок).
        assertEquals(
            ModelChoice("anthropic/claude-sonnet-5.5", "custom:plusvibe-api", explicitPick = false),
            selection.wireChoice()
        )
        assertEquals("anthropic/claude-sonnet-5.5", selection.buttonLabel())
    }

    @Test
    fun bindOpenedSessionModelWithoutModelKeepsPreviousBehaviour() {
        // Новый/пустой чат: серверной модели нет — прежняя семантика setSessionModel,
        // выбор пользователя до первого сообщения обязан выжить.
        val selection = ModelSelection(FakeStorage())
        selection.select(gptSol())
        selection.bindOpenedSessionModel(null, null)
        assertEquals(
            ModelChoice("@custom:plusvibe-api:gpt-6.1-sol", "custom:plusvibe-api", explicitPick = true),
            selection.wireChoice()
        )
        selection.bindOpenedSessionModel("   ", "custom:plusvibe-api")
        assertTrue(selection.wireChoice()?.explicitPick == true)
    }

    @Test
    fun bindOpenedSessionModelLetsTheNextUserPickWin() {
        val selection = ModelSelection(FakeStorage())
        selection.pickerEntries(webUiCatalog())
        selection.bindOpenedSessionModel("claude-sonnet-5.5", "custom:plusvibe-api")
        // Человек открыл чат и сразу выбрал другую модель в пикере — она и уйдёт дальше.
        selection.select(haiku())
        assertEquals(
            ModelChoice("claude-haiku-5.5", "custom:anthropic", explicitPick = true),
            selection.wireChoice()
        )
        assertEquals("Claude Haiku 5.5", selection.buttonLabel())
    }

    @Test
    fun findCatalogModelMatchesBareVendorAndPrefixedIds() {
        val selection = ModelSelection(FakeStorage())
        selection.pickerEntries(webUiCatalog())
        // Все три живые формы одного и того же id из WebUI/session detail.
        val forms = listOf(
            "claude-sonnet-5.5",
            "anthropic/claude-sonnet-5.5",
            "@custom:plusvibe-api:claude-sonnet-5.5",
            "claude.sonnet.5.5",
        )
        forms.forEach { form ->
            val row = selection.findCatalogModel(form, "custom:plusvibe-api")
            assertEquals("форма $form должна находить строку каталога", "@custom:plusvibe-api:claude-sonnet-5.5", row?.id)
            assertEquals("Claude Sonnet 5.5", row?.name)
        }
        // Без подсказки провайдера нормализованное совпадение тоже работает.
        assertEquals("@custom:plusvibe-api:claude-sonnet-5.5", selection.findCatalogModel("claude-sonnet-5.5")?.id)
        // Точное совпадение не подменяется: id из каталога возвращается как есть.
        assertEquals("claude-haiku-5.5", selection.findCatalogModel("claude-haiku-5.5", "custom:anthropic")?.id)
    }

    @Test
    fun findCatalogModelIsNullWithoutCatalogOrUnknownModel() {
        assertNull(ModelSelection(FakeStorage()).findCatalogModel("claude-sonnet-5.5", "custom:plusvibe-api"))
        val selection = ModelSelection(FakeStorage())
        selection.pickerEntries(webUiCatalog())
        assertNull(selection.findCatalogModel("", "custom:plusvibe-api"))
        assertNull(selection.findCatalogModel("no-such-model", "custom:plusvibe-api"))
    }

    @Test
    fun matchesCatalogModelHighlightsTheSessionRowInThePicker() {
        val selection = ModelSelection(FakeStorage())
        val entries = selection.pickerEntries(webUiCatalog())
        // Сессия отдала bare id, каталог — `@provider:model`: пункт всё равно отмечен.
        selection.bindOpenedSessionModel("claude-sonnet-5.5", "custom:plusvibe-api")
        val checked = entries.indexOfFirst { selection.matchesCatalogModel(it.model, selection.displayedChoice()) }
        assertEquals("@custom:plusvibe-api:claude-sonnet-5.5", entries[checked].model.id)
        assertEquals(1, entries.count { selection.matchesCatalogModel(it.model, selection.displayedChoice()) })
        // Совпадение по нормализованному id работает и без записи в каталоге под этим именем.
        assertTrue(selection.matchesCatalogModel(entries[1].model, ModelChoice("anthropic/claude-sonnet-5.5", null, false)))
        assertFalse(selection.matchesCatalogModel(entries[2].model, selection.displayedChoice()))
    }
}
