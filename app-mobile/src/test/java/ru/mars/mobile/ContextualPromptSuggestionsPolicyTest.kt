package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualPromptSuggestionsPolicyTest {

    private val P = ContextualPromptSuggestionsPolicy

    // --- catalog integrity ---

    @Test
    fun `every catalog chip has a non-blank label and prompt and a unique id`() {
        P.CATALOG.forEach {
            assertTrue(it.label.isNotBlank())
            assertTrue(it.promptText.isNotBlank())
        }
        assertEquals(P.CATALOG.size, P.CATALOG.map { it.id }.distinct().size)
    }

    @Test
    fun `rows keep the documented order and labels`() {
        assertEquals(
            listOf("Покажи логи", "Исправь ошибку", "Запусти тест повторно"),
            P.DEBUG_ROW.map { it.label },
        )
        assertEquals(
            listOf("git status", "Покажи git diff", "Сделай коммит"),
            P.GIT_ROW.map { it.label },
        )
        assertEquals(
            listOf("Проверь статус сервиса", "Покажи логи systemd"),
            P.SERVICE_ROW.map { it.label },
        )
        assertEquals(
            listOf("Статус проектов", "Сводка за день", "Проверь балансы и хост"),
            P.STARTER.map { it.label },
        )
    }

    @Test
    fun `all four categories are covered by the catalog plus the workspace chip`() {
        val categories = (P.CATALOG + P.workspaceChip("/root/workspace"))
            .map { it.category }
            .toSet()
        assertEquals(SuggestionCategory.values().toSet(), categories)
    }

    @Test
    fun `MAX_VISIBLE caps the ribbon at five chips`() {
        assertEquals(5, P.MAX_VISIBLE)
    }

    // --- stream / runner state ---

    @Test
    fun `hidden while a stream or background runner is active regardless of context`() {
        assertEquals(
            emptyList<PromptSuggestion>(),
            P.suggestionsFor(
                ChatStateSnapshot(
                    lastAssistantText = "Сборка упала: BUILD FAILED",
                    lastTurnFailed = true,
                    workspacePath = "/root/workspace/kvartal",
                    streamActive = true,
                ),
            ),
        )
        assertEquals(
            emptyList<PromptSuggestion>(),
            P.suggestionsFor(ChatStateSnapshot(streamActive = true)),
        )
    }

    // --- clean chat / start ---

    @Test
    fun `empty chat shows the starter ribbon`() {
        assertEquals(P.STARTER, P.suggestionsFor(ChatStateSnapshot()))
        assertEquals(
            P.STARTER,
            P.suggestionsFor(ChatStateSnapshot(lastUserText = "  ", lastAssistantText = "")),
        )
        assertEquals(
            listOf("Статус проектов", "Сводка за день", "Проверь балансы и хост"),
            P.suggestionsFor(ChatStateSnapshot()).map { it.label },
        )
    }

    // --- failure detection ---

    @Test
    fun `failed-turn flag leads with the debug row in order`() {
        val chips = P.suggestionsFor(
            ChatStateSnapshot(
                lastAssistantText = "Готово, изменения внесены",
                lastTurnFailed = true,
            ),
        )
        assertEquals(
            listOf(PromptSuggestionId.SHOW_LOGS, PromptSuggestionId.FIX_ERROR, PromptSuggestionId.RERUN_TESTS),
            chips.map { it.id },
        )
        assertEquals(
            listOf(SuggestionCategory.DEBUG, SuggestionCategory.ACTION, SuggestionCategory.ACTION),
            chips.map { it.category },
        )
    }

    @Test
    fun `failure markers in the assistant text trigger the debug row without the flag`() {
        for (text in listOf(
            "Команда завершилась с exit_code=2",
            "Gradle: BUILD FAILED in 41s",
            "Ошибка компиляции: unresolved reference",
            "ssh: connect to host port 22: connection refused",
            "Тесты упали: 2 failed, 14 passed",
            "Запрос не проходит, сервер недоступен",
        )) {
            val chips = P.suggestionsFor(ChatStateSnapshot(lastAssistantText = text))
            assertTrue(
                "no debug row for: $text",
                chips.take(3).map { it.id } == P.DEBUG_ROW.map { it.id },
            )
        }
    }

    @Test
    fun `failure scan is case-insensitive`() {
        assertTrue(P.hasFailureMarkers("EXIT_CODE: 1"))
        assertTrue(P.hasFailureMarkers("Build Failed"))
        assertTrue(P.hasFailureMarkers("Connection REFUSED"))
    }

    @Test
    fun `clean assistant text produces no debug chips`() {
        assertFalse(P.hasFailureMarkers(null))
        assertFalse(P.hasFailureMarkers(""))
        assertFalse(P.hasFailureMarkers("   "))
        assertFalse(P.hasFailureMarkers("Всё хорошо, работа продолжается"))

        val chips = P.suggestionsFor(
            ChatStateSnapshot(lastAssistantText = "Всё хорошо, работа продолжается"),
        )
        assertTrue(chips.none { it.category == SuggestionCategory.DEBUG || it.id == PromptSuggestionId.FIX_ERROR })
    }

    @Test
    fun `failure markers are scanned in the assistant text only`() {
        val chips = P.suggestionsFor(
            ChatStateSnapshot(
                lastUserText = "у меня там ошибка и сбой были раньше",
                lastAssistantText = "Понял, посмотрю позже",
            ),
        )
        // No debug row: user text does not count as a failure signal -> STARTER fallback.
        assertEquals(P.STARTER, chips)
    }

    @Test
    fun `flag and markers together never duplicate the debug row`() {
        val chips = P.suggestionsFor(
            ChatStateSnapshot(
                lastAssistantText = "Процесс завершился, exit_code != 0",
                lastTurnFailed = true,
            ),
        )
        assertEquals(P.DEBUG_ROW, chips)
        assertEquals(chips.size, chips.map { it.id }.distinct().size)
    }

    // --- git topic ---

    @Test
    fun `git mention in the assistant text appends the git row`() {
        val chips = P.suggestionsFor(
            ChatStateSnapshot(lastAssistantText = "Правки готовы, можно делать коммит"),
        )
        assertEquals(
            listOf(PromptSuggestionId.GIT_STATUS, PromptSuggestionId.GIT_DIFF, PromptSuggestionId.GIT_COMMIT),
            chips.map { it.id },
        )
        assertEquals(
            listOf(SuggestionCategory.INSPECT, SuggestionCategory.INSPECT, SuggestionCategory.ACTION),
            chips.map { it.category },
        )
    }

    @Test
    fun `git mention in the user message also triggers the git row`() {
        val chips = P.suggestionsFor(
            ChatStateSnapshot(
                lastUserText = "Запушь ветку, пожалуйста",
                lastAssistantText = "Хорошо, приступаю",
            ),
        )
        assertEquals(P.GIT_ROW.map { it.id }, chips.map { it.id })
    }

    // --- service topic ---

    @Test
    fun `service or port mention appends the service row`() {
        val chips = P.suggestionsFor(
            ChatStateSnapshot(lastAssistantText = "Проверил systemd: юнит активен"),
        )
        assertEquals(
            listOf(PromptSuggestionId.SERVICE_STATUS, PromptSuggestionId.SYSTEMD_LOGS),
            chips.map { it.id },
        )

        val portChips = P.suggestionsFor(
            ChatStateSnapshot(lastAssistantText = "Слушатель на порту 8787 поднялся"),
        )
        assertEquals(P.SERVICE_ROW.map { it.id }, portChips.map { it.id })
    }

    // --- ranking, dedupe, cap ---

    @Test
    fun `failure outranks git which outranks service which outranks workspace, capped at five`() {
        val chips = P.suggestionsFor(
            ChatStateSnapshot(
                lastAssistantText = "Сборка упала: BUILD FAILED. Правки в git не закоммичены, " +
                    "caddy на порту 8787 не отвечает.",
                lastTurnFailed = true,
                workspacePath = "/root/workspace/kvartal",
            ),
        )
        assertEquals(P.MAX_VISIBLE, chips.size)
        assertEquals(chips.size, chips.map { it.id }.distinct().size)
        assertEquals(
            listOf(
                PromptSuggestionId.SHOW_LOGS,
                PromptSuggestionId.FIX_ERROR,
                PromptSuggestionId.RERUN_TESTS,
                PromptSuggestionId.GIT_STATUS,
                PromptSuggestionId.GIT_DIFF,
            ),
            chips.map { it.id },
        )
    }

    @Test
    fun `git and service rows together fill exactly five chips without duplicates`() {
        val chips = P.suggestionsFor(
            ChatStateSnapshot(
                lastAssistantText = "Юнит перезапущен, порт слушает, правки лежат в git",
            ),
        )
        assertEquals(5, chips.size)
        assertEquals(chips.size, chips.map { it.id }.distinct().size)
        assertEquals(
            (P.GIT_ROW + P.SERVICE_ROW).map { it.id },
            chips.map { it.id },
        )
    }

    @Test
    fun `settled chat without any signal falls back to the starter ribbon`() {
        val chips = P.suggestionsFor(
            ChatStateSnapshot(
                lastUserText = "Расскажи про погоду",
                lastAssistantText = "Сегодня солнечно и тепло",
            ),
        )
        assertEquals(P.STARTER, chips)
    }

    // --- workspace awareness ---

    @Test
    fun `known workspace paths map to friendly project names`() {
        assertEquals("Квартал", P.workspaceName("/root/workspace/kvartal"))
        assertEquals("Альпен", P.workspaceName("/opt/Alpen/"))
        assertEquals("Mars Android", P.workspaceName("/root/workspace/mars-android"))
        assertEquals("Квиз-Дуэль", P.workspaceName("/root/workspace/quiz-duel"))
        assertEquals("WebUI", P.workspaceName("/opt/hermes-webui"))
        assertEquals("Музыка", P.workspaceName("/root/workspace/mars-music"))
    }

    @Test
    fun `unknown workspace falls back to the path tail`() {
        assertEquals("workspace", P.workspaceName("/root/workspace"))
        assertEquals("foo", P.workspaceName("/root/workspace/foo/"))
        assertEquals("my-project", P.workspaceName("my-project"))
    }

    @Test
    fun `workspace chip is a NAV chip carrying the full path in the prompt`() {
        val chip = P.workspaceChip("/root/workspace/kvartal")
        assertEquals(PromptSuggestionId.WORKSPACE_SUMMARY, chip.id)
        assertEquals("Сводка: Квартал", chip.label)
        assertEquals(SuggestionCategory.NAV, chip.category)
        assertTrue(chip.promptText.contains("/root/workspace/kvartal"))
    }

    @Test
    fun `resolved workspace appends exactly its nav chip to an otherwise quiet chat`() {
        val chips = P.suggestionsFor(
            ChatStateSnapshot(
                lastAssistantText = "Готово",
                workspacePath = "/root/workspace/alpen",
            ),
        )
        assertEquals(listOf(PromptSuggestionId.WORKSPACE_SUMMARY), chips.map { it.id })
        assertEquals("Сводка: Альпен", chips.single().label)
    }

    @Test
    fun `blank workspace path never produces the nav chip`() {
        for (path in listOf(null, "", "   ")) {
            val chips = P.suggestionsFor(
                ChatStateSnapshot(lastAssistantText = "Готово", workspacePath = path),
            )
            assertTrue(chips.none { it.id == PromptSuggestionId.WORKSPACE_SUMMARY })
            assertEquals(P.STARTER, chips)
        }
    }

    // --- snapshot semantics ---

    @Test
    fun `isEmptyChat is true only when both last texts are null or blank`() {
        assertTrue(ChatStateSnapshot().isEmptyChat)
        assertTrue(ChatStateSnapshot(lastUserText = "", lastAssistantText = "  ").isEmptyChat)
        assertFalse(ChatStateSnapshot(lastUserText = "привет").isEmptyChat)
        assertFalse(ChatStateSnapshot(lastAssistantText = "привет").isEmptyChat)
    }

    @Test
    fun `output never exceeds MAX_VISIBLE for any trigger combination`() {
        val everything = ChatStateSnapshot(
            lastUserText = "сделай коммит и проверь сервис",
            lastAssistantText = "Терминал упал, exit_code=1, systemd-юнит недоступен, git в расходящемся состоянии",
            lastTurnFailed = true,
            workspacePath = "/root/workspace/mars-android",
        )
        assertTrue(P.suggestionsFor(everything).size <= P.MAX_VISIBLE)
        assertTrue(P.suggestionsFor(everything.copy(streamActive = true)).isEmpty())
    }
}
