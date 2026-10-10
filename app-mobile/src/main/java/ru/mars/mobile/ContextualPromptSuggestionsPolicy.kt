package ru.mars.mobile

/** Chip icon category; the drawable mapping lives in the Activity. */
enum class SuggestionCategory { ACTION, DEBUG, INSPECT, NAV }

/** Stable identity of a contextual prompt suggestion (icon mapping lives in the Activity). */
enum class PromptSuggestionId {
    // failure recovery row
    SHOW_LOGS, FIX_ERROR, RERUN_TESTS,

    // git row
    GIT_STATUS, GIT_DIFF, GIT_COMMIT,

    // service row
    SERVICE_STATUS, SYSTEMD_LOGS,

    // workspace-aware navigation
    WORKSPACE_SUMMARY,

    // clean-chat starters
    PROJECTS_STATUS, DAY_SUMMARY, HOST_BALANCES,
}

/**
 * One predictive prompt chip: [label] is the short caption, [promptText] is the
 * prompt sent to Марс on tap, [category] drives the chip icon.
 */
data class PromptSuggestion(
    val id: PromptSuggestionId,
    val label: String,
    val promptText: String,
    val category: SuggestionCategory,
)

/**
 * Immutable snapshot of the chat state the policy ranks against:
 * - [lastUserText] / [lastAssistantText]: the two most recent transcript messages;
 * - [lastTurnFailed]: the caller's verdict on the last assistant turn (terminal
 *   crashed, non-zero exit_code, compilation or network failure) — the policy
 *   additionally scans [lastAssistantText] for failure markers itself;
 * - [workspacePath]: the currently selected workspace (`/root/workspace`,
 *   `kvartal`, `alpen`, …), null when nothing is resolved;
 * - [streamActive]: a background runner / SSE stream is still typing.
 */
data class ChatStateSnapshot(
    val lastUserText: String? = null,
    val lastAssistantText: String? = null,
    val lastTurnFailed: Boolean = false,
    val workspacePath: String? = null,
    val streamActive: Boolean = false,
) {
    /** A fresh session: nothing to react to yet. */
    val isEmptyChat: Boolean
        get() = lastUserText.isNullOrBlank() && lastAssistantText.isNullOrBlank()
}

/**
 * Contextual prompt suggestions: a ranked ribbon of one-tap prompts derived from
 * the current chat context (last messages, failure state, workspace, runner).
 * Complements [QuickChips] (turn-outcome reactions) and [SmartSuggestionsPolicy]
 * (topic starters/follow-ups) with failure-, git-, service- and workspace-aware
 * prompts. Pure data + ranking policy — JVM unit-testable, no Android types.
 */
object ContextualPromptSuggestionsPolicy {

    /** The ribbon never grows past this many chips (composer space is limited). */
    const val MAX_VISIBLE = 5

    // --- failure recovery row (leads the ribbon after a broken turn) ---
    val SHOW_LOGS = PromptSuggestion(
        PromptSuggestionId.SHOW_LOGS, "Покажи логи",
        "Покажи логи последнего неудачного запуска целиком",
        SuggestionCategory.DEBUG,
    )
    val FIX_ERROR = PromptSuggestion(
        PromptSuggestionId.FIX_ERROR, "Исправь ошибку",
        "Найди причину ошибки и исправь её, затем проверь фикс",
        SuggestionCategory.ACTION,
    )
    val RERUN_TESTS = PromptSuggestion(
        PromptSuggestionId.RERUN_TESTS, "Запусти тест повторно",
        "Запусти упавший тест или сборку повторно и покажи результат",
        SuggestionCategory.ACTION,
    )
    val DEBUG_ROW: List<PromptSuggestion> = listOf(SHOW_LOGS, FIX_ERROR, RERUN_TESTS)

    // --- git row ---
    val GIT_STATUS = PromptSuggestion(
        PromptSuggestionId.GIT_STATUS, "git status",
        "Выполни git status и покажи результат",
        SuggestionCategory.INSPECT,
    )
    val GIT_DIFF = PromptSuggestion(
        PromptSuggestionId.GIT_DIFF, "Покажи git diff",
        "Покажи git diff текущих изменений",
        SuggestionCategory.INSPECT,
    )
    val GIT_COMMIT = PromptSuggestion(
        PromptSuggestionId.GIT_COMMIT, "Сделай коммит",
        "Сделай коммит текущих изменений с понятным сообщением",
        SuggestionCategory.ACTION,
    )
    val GIT_ROW: List<PromptSuggestion> = listOf(GIT_STATUS, GIT_DIFF, GIT_COMMIT)

    // --- service row ---
    val SERVICE_STATUS = PromptSuggestion(
        PromptSuggestionId.SERVICE_STATUS, "Проверь статус сервиса",
        "Проверь статус упомянутого сервиса: жив ли процесс и слушает ли порт",
        SuggestionCategory.INSPECT,
    )
    val SYSTEMD_LOGS = PromptSuggestion(
        PromptSuggestionId.SYSTEMD_LOGS, "Покажи логи systemd",
        "Покажи свежие логи systemd по этому сервису через journalctl",
        SuggestionCategory.DEBUG,
    )
    val SERVICE_ROW: List<PromptSuggestion> = listOf(SERVICE_STATUS, SYSTEMD_LOGS)

    // --- clean-chat starters ---
    val PROJECTS_STATUS = PromptSuggestion(
        PromptSuggestionId.PROJECTS_STATUS, "Статус проектов",
        "Покажи статус всех проектов: что живо, что требует внимания",
        SuggestionCategory.INSPECT,
    )
    val DAY_SUMMARY = PromptSuggestion(
        PromptSuggestionId.DAY_SUMMARY, "Сводка за день",
        "Сделай сводку за день: что сделано и какие события произошли",
        SuggestionCategory.INSPECT,
    )
    val HOST_BALANCES = PromptSuggestion(
        PromptSuggestionId.HOST_BALANCES, "Проверь балансы и хост",
        "Проверь балансы сервисов и состояние хоста: диски, память, нагрузка",
        SuggestionCategory.INSPECT,
    )
    val STARTER: List<PromptSuggestion> = listOf(PROJECTS_STATUS, DAY_SUMMARY, HOST_BALANCES)

    /** Full catalog (constant chips; the workspace chip is built per path). */
    val CATALOG: List<PromptSuggestion> = DEBUG_ROW + GIT_ROW + SERVICE_ROW + STARTER

    /**
     * Ranking policy:
     * - a live stream / background runner → hidden (the send button doubles as stop);
     * - empty chat → the [STARTER] ribbon;
     * - failed last turn (caller flag OR failure markers in the assistant text)
     *   → [DEBUG_ROW] leads;
     * - git / commit mentions → [GIT_ROW];
     * - service / port mentions → [SERVICE_ROW];
     * - a resolved workspace appends its [workspaceChip] (NAV);
     * - nothing matched → [STARTER] as the generic tail.
     * Result is deduplicated by id and capped at [MAX_VISIBLE], most relevant first.
     */
    fun suggestionsFor(snapshot: ChatStateSnapshot): List<PromptSuggestion> {
        if (snapshot.streamActive) return emptyList()
        if (snapshot.isEmptyChat) return STARTER

        val assistantText = snapshot.lastAssistantText.orEmpty()
        val combined = (assistantText + "\n" + snapshot.lastUserText.orEmpty()).lowercase()
        val failed = snapshot.lastTurnFailed || hasFailureMarkers(assistantText)

        val ranked = buildList {
            if (failed) addAll(DEBUG_ROW)
            if (GIT_KEYWORDS.any { it in combined }) addAll(GIT_ROW)
            if (SERVICE_KEYWORDS.any { it in combined }) addAll(SERVICE_ROW)
            snapshot.workspacePath?.takeIf { it.isNotBlank() }?.let { add(workspaceChip(it)) }
        }.distinctBy { it.id }

        return (if (ranked.isEmpty()) STARTER else ranked).take(MAX_VISIBLE)
    }

    /**
     * Heuristic failure scan of the last assistant answer (case-insensitive):
     * terminal crashes, non-zero exit codes, build/test failures, network errors.
     * Deliberately broad — a false positive only surfaces the recovery row.
     */
    fun hasFailureMarkers(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lower = text.lowercase()
        return FAILURE_MARKERS.any { it in lower }
    }

    /** Workspace-aware NAV chip: known projects get a friendly name, others the path tail. */
    fun workspaceChip(path: String): PromptSuggestion = PromptSuggestion(
        id = PromptSuggestionId.WORKSPACE_SUMMARY,
        label = "Сводка: " + workspaceName(path),
        promptText = "Покажи состояние workspace $path: незакоммиченные правки, последние коммиты, что в работе",
        category = SuggestionCategory.NAV,
    )

    /** Friendly project name for known workspace paths; path tail otherwise. */
    fun workspaceName(path: String): String {
        val lower = path.lowercase()
        return when {
            "kvartal" in lower -> "Квартал"
            "alpen" in lower -> "Альпен"
            "mars-android" in lower -> "Mars Android"
            "quiz" in lower -> "Квиз-Дуэль"
            "hermes-webui" in lower -> "WebUI"
            "music" in lower -> "Музыка"
            else -> path.trimEnd('/').substringAfterLast('/').ifBlank { path }
        }
    }

    private val FAILURE_MARKERS = listOf(
        // terminal / exit codes
        "exit_code", "exit code", "код возврата", "терминал упал",
        // build / compilation
        "build failed", "compilation failed", "compile error", "compiler error",
        "ошибка компиляции", "не компилируется", "ошибка сборки",
        // tests
        "тесты упали", "тест упал", "test failed", "tests failed", "test failure",
        // network
        "connection refused", "connection reset", "connection timed out",
        "network error", "ошибка сети", "не отвечает", "недоступен", "unreachable",
        // runtime failures
        "traceback", "exception", "stacktrace", "timeout", "таймаут",
        // generic (RU): «ошибка», «сбой», «упал»
        "ошибк", "сбой", "упал",
    )

    private val GIT_KEYWORDS = listOf(
        "git", "коммит", "commit", "ветк", "branch", "push",
        "rebase", "merge", "cherry-pick", "дифф",
    )

    private val SERVICE_KEYWORDS = listOf(
        "сервис", "service", "systemd", "порт", "port",
        "caddy", "nginx", "демон", "daemon", "health",
    )
}
