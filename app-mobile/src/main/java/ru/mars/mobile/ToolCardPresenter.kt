package ru.mars.mobile

/** Semantic tool family — drives the human label and the header icon on the render layer. */
enum class ToolKind(val label: String) {
    TERMINAL("Терминал"),
    FILESYSTEM("Файловая система"),
    WEB("Интернет и поиск"),
    MCP("MCP интеграция"),
    GENERIC("Инструмент"),
}

/** Lifecycle of one tool call plate. */
enum class ToolState(val label: String) {
    RUNNING("Выполняется…"),
    DONE("Выполнено"),
}

/** One monospaced section of the tool card («Аргументы» / «Результат»). */
data class ToolSection(
    val label: String,
    /** Full section text — the copy action always uses this. */
    val fullText: String,
    /** Text rendered right now (truncated to the threshold when collapsed). */
    val visibleText: String,
    /** Lines hidden in the collapsed state (0 when the section fits). */
    val hiddenLines: Int,
    /** Expand/collapse row label; null when the section needs no toggle. */
    val toggleLabel: String?,
)

/** Presentation model of one tool call card. Pure data — no Android imports. */
data class ToolCardView(
    val kind: ToolKind,
    /** Human family label («Терминал», «Файловая система»…). */
    val kindLabel: String,
    /** Raw tool name — rendered monospaced as the card title. */
    val toolName: String,
    val state: ToolState,
    /** «Выполняется…» / «Выполнено». */
    val stateLabel: String,
    val expanded: Boolean,
    /** True → ic_chevron_up, false → ic_chevron_down. */
    val chevronUp: Boolean,
    /** One-line argument hint for the collapsed plate («ls -la», path, query…); null when absent. */
    val detailLabel: String?,
    /** Arguments section; null when the call carries no arguments. */
    val argsSection: ToolSection?,
    /** Result section; null while the tool is still running. */
    val resultSection: ToolSection?,
    /** Structured clipboard payload for the copy button. */
    val copySource: String,
    // Palette tokens (MarsTheme constants, inlined at compile time).
    val titleColor: Int,
    val stateColor: Int,
    val bodyColor: Int,
    val cardColor: Int,
    val borderColor: Int,
)

/**
 * Pure presentation logic for tool_call / tool_result plates (WebUI parity):
 * family classification, RUNNING/DONE state, separated argument/result sections
 * with monospaced collapsing, and a structured copy dump.
 * No Android imports — JVM unit-testable.
 */
object ToolCardPresenter {
    /** Sections longer than this are collapsed by default. */
    const val COLLAPSE_THRESHOLD_LINES = 8
    /** Max length of the collapsed one-line detail hint. */
    const val DETAIL_MAX_LENGTH = 60

    const val ARGS_LABEL = "Аргументы"
    const val RESULT_LABEL = "Результат"

    private val TERMINAL_NAMES = setOf("bash", "terminal", "shell", "sh", "console", "execute_code")
    private val FILESYSTEM_NAMES = setOf("search_files", "read_file", "write_file", "patch")
    private val WEB_NAMES = setOf("web_search", "web_extract", "browser", "browser_exec")
    private val MCP_NAMES = setOf("mcp", "tools", "tool_call", "tool_search", "tool_describe")

    /** Keys whose string value makes a good one-line hint of the call. */
    private val DETAIL_KEYS = listOf(
        "command", "path", "file_path", "url", "query", "pattern", "name", "text",
    )
    private val DETAIL_REGEX = Regex("\"(${DETAIL_KEYS.joinToString("|")})\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /**
     * Family of a tool by name: exact alias first, then prefix heuristics
     * (mcp__… / tool_… → MCP, web… / browser… → WEB, file-ish → FILESYSTEM).
     */
    fun classify(name: String): ToolKind {
        val n = name.trim().lowercase()
        if (n.isEmpty()) return ToolKind.GENERIC
        return when {
            n in TERMINAL_NAMES -> ToolKind.TERMINAL
            n in FILESYSTEM_NAMES -> ToolKind.FILESYSTEM
            n in WEB_NAMES -> ToolKind.WEB
            n in MCP_NAMES -> ToolKind.MCP
            n.startsWith("mcp") || n.startsWith("tool") -> ToolKind.MCP
            n.startsWith("web") || n.startsWith("browser") -> ToolKind.WEB
            n.startsWith("terminal") || n.startsWith("bash") || n.startsWith("shell") -> ToolKind.TERMINAL
            n.contains("file") || n.startsWith("patch") -> ToolKind.FILESYSTEM
            else -> ToolKind.GENERIC
        }
    }

    fun lineCount(text: String): Int = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

    fun isCollapsible(text: String): Boolean = lineCount(text) > COLLAPSE_THRESHOLD_LINES

    /** Rendered text: first [COLLAPSE_THRESHOLD_LINES] lines of a long dump when collapsed. */
    fun visibleText(text: String, expanded: Boolean): String {
        if (expanded || !isCollapsible(text)) return text
        return text.split('\n').take(COLLAPSE_THRESHOLD_LINES).joinToString("\n")
    }

    fun hiddenLineCount(text: String): Int =
        (lineCount(text) - COLLAPSE_THRESHOLD_LINES).coerceAtLeast(0)

    /** Expand/collapse row label; null when the section fits without collapsing. */
    fun toggleLabel(text: String, expanded: Boolean): String? {
        if (!isCollapsible(text)) return null
        if (expanded) return "Свернуть"
        val hidden = hiddenLineCount(text)
        return "Показать ещё $hidden ${linesWord(hidden)}"
    }

    private fun linesWord(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod10 == 1 && mod100 != 11 -> "строку"
            mod10 in 2..4 && mod100 !in 12..14 -> "строки"
            else -> "строк"
        }
    }

    /**
     * One-line hint of the call for the collapsed plate: the first meaningful
     * string argument (command, path, url, query…), unescaped and truncated.
     */
    fun detailLabel(arguments: String): String? {
        val match = DETAIL_REGEX.find(arguments) ?: return null
        val value = unescape(match.groupValues[2]).trim()
        if (value.isEmpty()) return null
        return if (value.length > DETAIL_MAX_LENGTH) {
            value.take(DETAIL_MAX_LENGTH - 1).trimEnd() + "…"
        } else {
            value
        }
    }

    private fun unescape(raw: String): String = raw
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    /** Structured copy dump — replaces the legacy raw string dump. */
    fun copySource(tool: ToolExecution): String = buildString {
        append("Инструмент: ").append(tool.name).append(" (").append(classify(tool.name).label).append(')')
        if (tool.arguments.isNotBlank()) {
            append("\n\n").append(ARGS_LABEL).append(":\n").append(tool.arguments.trim())
        }
        append("\n\n").append(RESULT_LABEL).append(":\n")
        append(tool.result?.trim() ?: ToolState.RUNNING.label)
    }

    private fun section(label: String, text: String, expanded: Boolean): ToolSection = ToolSection(
        label = label,
        fullText = text,
        visibleText = visibleText(text, expanded),
        hiddenLines = if (isCollapsible(text)) hiddenLineCount(text) else 0,
        toggleLabel = toggleLabel(text, expanded),
    )

    /** Full presentation of one tool call for the render layer. */
    fun present(tool: ToolExecution): ToolCardView {
        val kind = classify(tool.name)
        val state = if (tool.result != null) ToolState.DONE else ToolState.RUNNING
        val args = tool.arguments.trim()
        return ToolCardView(
            kind = kind,
            kindLabel = kind.label,
            toolName = tool.name,
            state = state,
            stateLabel = state.label,
            expanded = tool.expanded,
            chevronUp = tool.expanded,
            detailLabel = detailLabel(tool.arguments),
            argsSection = if (args.isEmpty()) null else section(ARGS_LABEL, args, tool.expanded),
            resultSection = tool.result?.let { section(RESULT_LABEL, it.trim(), tool.expanded) },
            copySource = copySource(tool),
            titleColor = MarsTheme.ACCENT_TEXT,
            stateColor = if (state == ToolState.DONE) MarsTheme.STATUS_ONLINE else MarsTheme.STATUS_INFO,
            bodyColor = MarsTheme.TEXT,
            cardColor = MarsTheme.CODE_BG,
            borderColor = MarsTheme.CODE_BORDER,
        )
    }
}
