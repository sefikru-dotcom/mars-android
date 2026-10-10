package ru.mars.mobile

/** Stable identity of a contextual smart suggestion (icon mapping lives in the Activity). */
enum class SmartSuggestionId { SERVER_STATUS, DAY_SUMMARY, PROJECTS, MUSIC, CONTINUE, EXPLAIN }

/**
 * One contextual suggestion chip: [label] is the short caption shown above the
 * composer / on the empty-chat placeholder, [message] is the prompt sent to Марс on tap.
 */
data class SmartSuggestion(val id: SmartSuggestionId, val label: String, val message: String)

/**
 * Smart suggestions (WebUI parity): a contextual ribbon of starter prompts on an
 * empty chat and follow-up prompts after a completed turn. Complements [QuickChips]
 * (which reacts to the turn outcome) with topic-aware prompts derived from the last
 * assistant answer. Pure data + visibility policy — JVM unit-testable.
 */
object SmartSuggestionsPolicy {
    val SERVER_STATUS = SmartSuggestion(
        SmartSuggestionId.SERVER_STATUS, "Статус серверов",
        "Покажи текущий статус серверов: аптайм, нагрузка, диски",
    )
    val DAY_SUMMARY = SmartSuggestion(
        SmartSuggestionId.DAY_SUMMARY, "Сводка дня",
        "Сделай сводку дня: что сделано и какие события произошли",
    )
    val PROJECTS = SmartSuggestion(
        SmartSuggestionId.PROJECTS, "Проекты",
        "Покажи статус всех проектов: что живо, что требует внимания",
    )
    val MUSIC = SmartSuggestion(
        SmartSuggestionId.MUSIC, "Музыка",
        "Включи музыку на основе моих предпочтений",
    )
    val CONTINUE = SmartSuggestion(
        SmartSuggestionId.CONTINUE, "Продолжи",
        "Продолжи с того места, где остановился",
    )
    val EXPLAIN = SmartSuggestion(
        SmartSuggestionId.EXPLAIN, "Объясни подробнее",
        "Объясни подробнее свой последний ответ",
    )

    /** Empty chat: topic starters the user taps most often on a fresh session. */
    val STARTER: List<SmartSuggestion> = listOf(SERVER_STATUS, DAY_SUMMARY, PROJECTS, MUSIC)

    /** After a settled turn: always lead with the follow-ups of the last answer. */
    val FOLLOW_UP: List<SmartSuggestion> = listOf(CONTINUE, EXPLAIN)

    /** The ribbon never grows past this many chips (composer space is limited). */
    const val MAX_VISIBLE = 5

    /**
     * Visibility policy:
     * - while the stream is typing → hidden (the send button doubles as stop);
     * - after a failed turn → hidden ([QuickChips] owns the failure recovery row);
     * - empty chat → the [STARTER] ribbon;
     * - after a completed turn → [FOLLOW_UP] first, then topic matches found in
     *   [lastAssistantText], deduplicated and capped at [MAX_VISIBLE].
     */
    fun suggestionsFor(
        status: ChatStatus,
        hasMessages: Boolean,
        lastAssistantText: String? = null,
    ): List<SmartSuggestion> = when {
        status == ChatStatus.TYPING -> emptyList()
        status == ChatStatus.ERROR -> emptyList()
        !hasMessages -> STARTER
        else -> (FOLLOW_UP + contextualFor(lastAssistantText))
            .distinctBy { it.id }
            .take(MAX_VISIBLE)
    }

    /**
     * Topic suggestions matched against the last assistant answer (case-insensitive
     * keyword scan, RU + EN). Order is stable: servers → summary → projects → music.
     */
    fun contextualFor(text: String?): List<SmartSuggestion> {
        if (text.isNullOrBlank()) return emptyList()
        val lower = text.lowercase()
        return buildList {
            if (SERVER_KEYWORDS.any { it in lower }) add(SERVER_STATUS)
            if (SUMMARY_KEYWORDS.any { it in lower }) add(DAY_SUMMARY)
            if (PROJECT_KEYWORDS.any { it in lower }) add(PROJECTS)
            if (MUSIC_KEYWORDS.any { it in lower }) add(MUSIC)
        }
    }

    private val SERVER_KEYWORDS = listOf("сервер", "uptime", "нагрузк", "диск", "хост", "server", "load average")
    private val SUMMARY_KEYWORDS = listOf("сводк", "итоги дня", "за сегодня", "summary")
    private val PROJECT_KEYWORDS = listOf("проект", "репозитор", "деплой", "project", "deploy")
    private val MUSIC_KEYWORDS = listOf("музык", "трек", "плеер", "music", "track")
}
