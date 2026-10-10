package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartSuggestionsPolicyTest {

    // --- catalog integrity ---

    @Test
    fun `starter ribbon exposes the four topic starters in order`() {
        assertEquals(
            listOf("Статус серверов", "Сводка дня", "Проекты", "Музыка"),
            SmartSuggestionsPolicy.STARTER.map { it.label },
        )
    }

    @Test
    fun `follow-up ribbon leads with continue and explain`() {
        assertEquals(
            listOf(SmartSuggestionId.CONTINUE, SmartSuggestionId.EXPLAIN),
            SmartSuggestionsPolicy.FOLLOW_UP.map { it.id },
        )
        assertEquals("Продолжи", SmartSuggestionsPolicy.CONTINUE.label)
        assertEquals("Объясни подробнее", SmartSuggestionsPolicy.EXPLAIN.label)
    }

    @Test
    fun `every suggestion has a non-blank label and prompt and a unique id`() {
        val all = listOf(
            SmartSuggestionsPolicy.SERVER_STATUS,
            SmartSuggestionsPolicy.DAY_SUMMARY,
            SmartSuggestionsPolicy.PROJECTS,
            SmartSuggestionsPolicy.MUSIC,
            SmartSuggestionsPolicy.CONTINUE,
            SmartSuggestionsPolicy.EXPLAIN,
        )
        all.forEach {
            assertTrue(it.label.isNotBlank())
            assertTrue(it.message.isNotBlank())
        }
        assertEquals(all.size, all.map { it.id }.distinct().size)
    }

    // --- visibility policy ---

    @Test
    fun `hidden while the stream is typing regardless of transcript`() {
        assertEquals(
            emptyList<SmartSuggestion>(),
            SmartSuggestionsPolicy.suggestionsFor(ChatStatus.TYPING, hasMessages = false),
        )
        assertEquals(
            emptyList<SmartSuggestion>(),
            SmartSuggestionsPolicy.suggestionsFor(ChatStatus.TYPING, hasMessages = true, lastAssistantText = "сервер"),
        )
    }

    @Test
    fun `hidden after a failed turn - quick chips own the failure row`() {
        assertEquals(
            emptyList<SmartSuggestion>(),
            SmartSuggestionsPolicy.suggestionsFor(ChatStatus.ERROR, hasMessages = true),
        )
        assertEquals(
            emptyList<SmartSuggestion>(),
            SmartSuggestionsPolicy.suggestionsFor(ChatStatus.ERROR, hasMessages = false),
        )
    }

    @Test
    fun `empty chat shows the starter ribbon in idle and received`() {
        for (status in listOf(ChatStatus.IDLE, ChatStatus.RECEIVED)) {
            assertEquals(
                SmartSuggestionsPolicy.STARTER,
                SmartSuggestionsPolicy.suggestionsFor(status, hasMessages = false),
            )
        }
    }

    @Test
    fun `settled turn without context shows only the follow-ups`() {
        val chips = SmartSuggestionsPolicy.suggestionsFor(ChatStatus.RECEIVED, hasMessages = true)
        assertEquals(SmartSuggestionsPolicy.FOLLOW_UP, chips)

        val blankContext = SmartSuggestionsPolicy.suggestionsFor(
            ChatStatus.IDLE, hasMessages = true, lastAssistantText = "   ",
        )
        assertEquals(SmartSuggestionsPolicy.FOLLOW_UP, blankContext)
    }

    @Test
    fun `server keywords append the status starter after the follow-ups`() {
        val chips = SmartSuggestionsPolicy.suggestionsFor(
            ChatStatus.RECEIVED, hasMessages = true,
            lastAssistantText = "Проверил сервер: uptime 40 дней, нагрузка низкая.",
        )
        assertEquals(
            listOf(SmartSuggestionId.CONTINUE, SmartSuggestionId.EXPLAIN, SmartSuggestionId.SERVER_STATUS),
            chips.map { it.id },
        )
    }

    @Test
    fun `each topic keyword maps to its suggestion`() {
        assertEquals(
            listOf(SmartSuggestionId.SERVER_STATUS),
            SmartSuggestionsPolicy.contextualFor("Хост доступен").map { it.id },
        )
        assertEquals(
            listOf(SmartSuggestionId.DAY_SUMMARY),
            SmartSuggestionsPolicy.contextualFor("Вот сводка за день").map { it.id },
        )
        assertEquals(
            listOf(SmartSuggestionId.PROJECTS),
            SmartSuggestionsPolicy.contextualFor("Все проекты живы, деплой прошёл").map { it.id },
        )
        assertEquals(
            listOf(SmartSuggestionId.MUSIC),
            SmartSuggestionsPolicy.contextualFor("Включил трек в плеере").map { it.id },
        )
        assertEquals(
            listOf(SmartSuggestionId.SERVER_STATUS),
            SmartSuggestionsPolicy.contextualFor("Server load average is 0.2").map { it.id },
        )
    }

    @Test
    fun `context matches keep the stable servers-summary-projects-music order`() {
        val chips = SmartSuggestionsPolicy.contextualFor(
            "Музыка играет, проект задеплоен, сервер в норме, вот и сводка",
        )
        assertEquals(
            listOf(
                SmartSuggestionId.SERVER_STATUS,
                SmartSuggestionId.DAY_SUMMARY,
                SmartSuggestionId.PROJECTS,
                SmartSuggestionId.MUSIC,
            ),
            chips.map { it.id },
        )
    }

    @Test
    fun `ribbon is deduplicated and capped at MAX_VISIBLE`() {
        // CONTINUE/EXPLAIN + all four topics = 6 candidates -> capped to 5.
        val chips = SmartSuggestionsPolicy.suggestionsFor(
            ChatStatus.RECEIVED, hasMessages = true,
            lastAssistantText = "Сервер, сводка, проект, музыка — всё проверил",
        )
        assertEquals(SmartSuggestionsPolicy.MAX_VISIBLE, chips.size)
        assertEquals(chips.size, chips.map { it.id }.distinct().size)
        assertEquals(
            listOf(SmartSuggestionId.CONTINUE, SmartSuggestionId.EXPLAIN, SmartSuggestionId.SERVER_STATUS,
                SmartSuggestionId.DAY_SUMMARY, SmartSuggestionId.PROJECTS),
            chips.map { it.id },
        )
    }

    @Test
    fun `null or blank context never produces topic chips`() {
        assertEquals(emptyList<SmartSuggestion>(), SmartSuggestionsPolicy.contextualFor(null))
        assertEquals(emptyList<SmartSuggestion>(), SmartSuggestionsPolicy.contextualFor(""))
        assertEquals(emptyList<SmartSuggestion>(), SmartSuggestionsPolicy.contextualFor("   "))
        assertEquals(emptyList<SmartSuggestion>(), SmartSuggestionsPolicy.contextualFor("просто текст без тем"))
    }
}
