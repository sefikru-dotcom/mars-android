package ru.mars.mobile

/** Presentation data for one code block, consumed by the (thin) Android render layer. */
data class CodeBlockView(
    /** Canonical language title for the block header («Kotlin», «JSON», «Текст»…). */
    val languageLabel: String,
    /** Code actually rendered right now (truncated to the threshold when collapsed). */
    val visibleCode: String,
    /** Full snippet — the copy button always copies this, collapsed or not. */
    val fullCode: String,
    /** True when the listing is longer than [CodeBlockPresenter.COLLAPSE_THRESHOLD_LINES]. */
    val collapsible: Boolean,
    val expanded: Boolean,
    /** Lines hidden in the collapsed state (0 when not collapsible). */
    val hiddenLines: Int,
    /** Label of the expand/collapse row; null when the block needs no toggle. */
    val toggleLabel: String?,
) {
    /** What the copy button puts on the clipboard: always the complete snippet. */
    val copySource: String get() = fullCode
}

/**
 * Pure presentation logic for [ContentBlock.Code] (WebUI parity):
 * language header, clipboard-copy source and collapsing of long listings
 * so «простыни» of code do not flood the chat transcript.
 * No Android imports — JVM unit-testable.
 */
object CodeBlockPresenter {
    /** Listings longer than this are collapsed by default. */
    const val COLLAPSE_THRESHOLD_LINES = 18

    private val LANGUAGE_LABELS = mapOf(
        "kotlin" to "Kotlin", "kt" to "Kotlin", "kts" to "Kotlin",
        "java" to "Java",
        "python" to "Python", "py" to "Python", "python3" to "Python",
        "bash" to "Bash", "sh" to "Bash", "shell" to "Bash", "zsh" to "Bash", "console" to "Bash",
        "json" to "JSON", "json5" to "JSON",
        "sql" to "SQL", "psql" to "SQL", "postgresql" to "SQL", "mysql" to "SQL",
        "js" to "JavaScript", "javascript" to "JavaScript", "jsx" to "JavaScript", "node" to "JavaScript",
        "ts" to "TypeScript", "typescript" to "TypeScript", "tsx" to "TypeScript",
        "html" to "HTML", "xml" to "XML", "css" to "CSS", "scss" to "SCSS",
        "yaml" to "YAML", "yml" to "YAML", "toml" to "TOML",
        "md" to "Markdown", "markdown" to "Markdown",
        "diff" to "Diff", "patch" to "Diff",
        "go" to "Go", "golang" to "Go",
        "rust" to "Rust", "rs" to "Rust",
        "c" to "C",
        "cpp" to "C++", "c++" to "C++", "hpp" to "C++", "cxx" to "C++",
        "csharp" to "C#", "cs" to "C#", "c#" to "C#",
        "php" to "PHP", "ruby" to "Ruby", "rb" to "Ruby",
        "swift" to "Swift", "dart" to "Dart", "lua" to "Lua", "r" to "R",
        "dockerfile" to "Docker", "docker" to "Docker",
        "groovy" to "Groovy", "gradle" to "Groovy",
        "ini" to "INI", "properties" to "Properties", "env" to "Env",
        "makefile" to "Makefile", "cmake" to "CMake",
        "text" to "Текст", "txt" to "Текст", "plaintext" to "Текст", "plain" to "Текст",
    )

    /**
     * Header title for a fence tag: known alias → canonical name («py» → «Python»),
     * unknown tag → capitalized as-is, empty tag → «Текст».
     */
    fun languageLabel(language: String): String {
        val tag = language.trim().lowercase()
        if (tag.isEmpty()) return "Текст"
        LANGUAGE_LABELS[tag]?.let { return it }
        return tag.replaceFirstChar { it.uppercaseChar() }
    }

    fun lineCount(code: String): Int = if (code.isEmpty()) 0 else code.count { it == '\n' } + 1

    fun isCollapsible(code: String): Boolean = lineCount(code) > COLLAPSE_THRESHOLD_LINES

    /** Rendered code: first [COLLAPSE_THRESHOLD_LINES] lines of a long listing when collapsed. */
    fun visibleCode(code: String, expanded: Boolean): String {
        if (expanded || !isCollapsible(code)) return code
        return code.split('\n').take(COLLAPSE_THRESHOLD_LINES).joinToString("\n")
    }

    fun hiddenLineCount(code: String): Int =
        (lineCount(code) - COLLAPSE_THRESHOLD_LINES).coerceAtLeast(0)

    /** Expand/collapse row label; null when the block fits without collapsing. */
    fun toggleLabel(code: String, expanded: Boolean): String? {
        if (!isCollapsible(code)) return null
        return if (expanded) {
            "Свернуть"
        } else {
            val hidden = hiddenLineCount(code)
            "Показать ещё $hidden ${linesWord(hidden)}"
        }
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

    /** Full presentation of one block for the render layer. */
    fun present(block: ContentBlock.Code, expanded: Boolean): CodeBlockView = CodeBlockView(
        languageLabel = languageLabel(block.language),
        visibleCode = visibleCode(block.code, expanded),
        fullCode = block.code,
        collapsible = isCollapsible(block.code),
        expanded = expanded,
        hiddenLines = if (isCollapsible(block.code)) hiddenLineCount(block.code) else 0,
        toggleLabel = toggleLabel(block.code, expanded),
    )
}
